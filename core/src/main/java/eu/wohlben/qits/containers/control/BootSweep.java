package eu.wohlben.qits.containers.control;

import eu.wohlben.qits.containers.entity.CtContainer;
import eu.wohlben.qits.containers.entity.DesiredState;
import eu.wohlben.qits.containers.entity.ObservedState;
import eu.wohlben.qits.containers.persistence.CtContainerRepository;
import eu.wohlben.qits.containers.spec.LifecyclePolicy;
import eu.wohlben.qits.db.DbRetry;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * What a restart does about the containers that were already running.
 *
 * <p><b>THE INVARIANT, verbatim: no code path in this service removes a container that no registry
 * row names.</b> Every container this sweep touches is addressed by the {@code container_name} on
 * its own row, and the row was written before the container was started. A container carrying no row
 * — a compose original, a bootstrap seed, a container from before this service existed, another
 * instance's workload — is somebody else's, and unclaimed means left alone. There is no listing by
 * label here, no listing by name pattern, and none may be added: sweeping the host rather than the
 * rows is the regression this repository was built to remove.
 *
 * <p><b>Adopt, do not reap.</b> A restart of this service must be invisible to a container that is
 * still running — that is the headline requirement of the whole campaign. A row found in flight
 * whose container is up is simply marked {@code RUNNING} with a line of detail; nothing is stopped
 * and nothing is removed. qits-platform-deployments' startup sweep takes the same stance for the
 * same reason, and it is one to keep rather than "complete" with a reap.
 *
 * <p>Three questions, in this order:
 *
 * <ol>
 *   <li><b>Rows left in flight</b> ({@code PENDING}/{@code STARTING}): a {@code docker run} the
 *       process did not survive. Inspect the row's own container. Running is an adoption; a
 *       container that exited is settled per policy — an {@code EPHEMERAL} one has done its work, so
 *       it is removed and its row settles {@code ABSENT}/{@code GONE}, while any other policy keeps
 *       its container and records {@code EXITED}; absent is {@code MISSING}, and for
 *       {@code EPHEMERAL} also {@code ABSENT}, because a run-once workload whose container never
 *       appeared is not something to try again.
 *   <li><b>Deletes that never settled</b> ({@code ABSENT} but not {@code GONE}): replay the remove,
 *       which is idempotent, and settle. This is what makes a delete survive a crash between its row
 *       write and its docker call.
 *   <li><b>Rows already {@code RUNNING}</b>: untouched. The observer confirms them on its own
 *       schedule, and doing it twice at boot would only make a slow start look like a failure.
 * </ol>
 *
 * <p><b>Nothing here ever throws out of the observer.</b> A docker daemon that is not up yet is the
 * ordinary case on a host that just rebooted: each failure is a WARN, the row is left exactly as it
 * was for the next boot or the next observation pass, and the application finishes booting. An
 * orchestrator that refused to start because it could not reach docker would be an orchestrator that
 * could not be deployed to fix docker.
 */
@ApplicationScoped
public class BootSweep {

  private static final Logger LOG = Logger.getLogger(BootSweep.class);

  @Inject CtContainerRepository containers;
  @Inject ContainersDriver driver;
  @Inject ContainerRegistry registry;
  @Inject java.time.Clock clock;

  /**
   * The order the three startup steps run in, as CDI priorities. Lower runs first, and
   * {@value #STARTUP_PRIORITY} is the platform default spelled out so the two numbers around it are
   * readable as "before" and "after" rather than as magic.
   *
   * <p>{@code SharedResources} ({@value #SHARED_RESOURCES_PRIORITY}) makes sure the shared volumes
   * exist first, this sweep adopts second, and {@code ContainerObserver}'s ticker
   * ({@value #OBSERVER_PRIORITY}) starts last. <b>The last one is the one that matters.</b> An
   * observation pass landing before the sweep would see every in-flight row as a container it knows
   * nothing about and could spend a strike on a workload the sweep is about to adopt. Two observers
   * of one event have no order without these numbers — qits-platform-deployments avoids the
   * question by doing both in one method, which is not open to us with a sweep and a ticker in
   * different classes.
   */
  public static final int SHARED_RESOURCES_PRIORITY = 2400;

  /**
   * Between the shared resources and the sweep: the platform builder needs the network answer the
   * shared-resources pass just warned about, and nothing about the sweep or the observer should
   * wait on an image pull.
   */
  public static final int PLATFORM_BUILDER_PRIORITY = 2450;

  public static final int STARTUP_PRIORITY = 2500;

  public static final int OBSERVER_PRIORITY = 2600;

  /**
   * Skipped in test mode, exactly as qits-platform-deployments' is: a {@code @QuarkusTest} would
   * otherwise run a sweep against whatever rows the previous class left, before the test that owns
   * them has arranged anything. The suite drives {@link #sweepOnce()} itself.
   */
  void onStart(@Observes @Priority(STARTUP_PRIORITY) StartupEvent event) {
    if (LaunchMode.current() == LaunchMode.TEST) {
      return;
    }
    try {
      sweepOnce();
    } catch (RuntimeException e) {
      LOG.warnf(e, "The startup sweep could not finish; the observation passes carry on from here");
    }
  }

  /** One pass. Package-private so the suite drives it without a real {@code StartupEvent}. */
  void sweepOnce() {
    List<Candidate> inFlight =
        registry.read("The boot sweep's in-flight read", () -> candidates(containers.listInFlight()));
    for (Candidate candidate : inFlight) {
      try {
        settleInFlight(candidate);
      } catch (RuntimeException e) {
        LOG.warnf(
            "Could not settle %s at startup, so its row is left as it is: %s",
            candidate.containerName(), e.getMessage());
      }
    }

    List<Candidate> unsettled =
        registry.read(
            "The boot sweep's unsettled-delete read", () -> candidates(containers.listUnsettledDeletes()));
    for (Candidate candidate : unsettled) {
      try {
        replayDelete(candidate);
      } catch (RuntimeException e) {
        LOG.warnf(
            "Could not replay the delete of %s, so it is replayed again next boot: %s",
            candidate.containerName(), e.getMessage());
      }
    }

    if (!inFlight.isEmpty() || !unsettled.isEmpty()) {
      LOG.infof(
          "Startup sweep: %d row(s) in flight, %d unsettled delete(s). Rows already RUNNING were"
              + " left untouched, and no container this registry does not name was looked at.",
          inFlight.size(), unsettled.size());
    }
  }

  /** One in-flight row, decided by what its own container is doing. */
  private void settleInFlight(Candidate candidate) {
    Optional<ContainersDriver.Observed> observed =
        driver.inspect(candidate.containerName(), ContainersTimeouts.INSPECT);

    if (observed.isPresent() && ContainerRegistry.running(observed.get())) {
      registry.settle(
          candidate.rowId(), ObservedState.RUNNING, "[adopted at startup: it is still running]");
      LOG.infof("Adopted %s at startup: it survived the restart", candidate.containerName());
      return;
    }

    boolean ephemeral = candidate.policy() == LifecyclePolicy.Type.EPHEMERAL;
    if (observed.isEmpty()) {
      // Docker has no such container. For a run-once workload that is the end of it; for anything
      // else it is a failure the observer may still see recover.
      if (ephemeral) {
        abandon(
            candidate.rowId(),
            ObservedState.MISSING,
            "[startup: the container is gone and this workload runs once, so nothing replaces it]");
      } else {
        registry.settle(
            candidate.rowId(),
            ObservedState.MISSING,
            "[startup: docker has no container by this name]");
      }
      return;
    }

    if (ephemeral) {
      // It ran and stopped, which for EPHEMERAL is the success path. The row names it, so removing
      // it is within the invariant — and it is the one removal this sweep performs.
      ContainersDriver.OpResult removed =
          driver.remove(candidate.containerName(), ContainersTimeouts.REMOVE);
      abandon(
          candidate.rowId(),
          ObservedState.GONE,
          removed.ok()
              ? "[startup: the run-once workload had finished, so its container was removed]"
              : "[startup: the run-once workload had finished; docker could not remove it: "
                  + Details.brief(removed.detail())
                  + "]");
      return;
    }
    registry.settle(
        candidate.rowId(),
        ObservedState.EXITED,
        "[startup: it had stopped. The container is kept — only a delete removes one.]");
  }

  /** A delete the process did not survive. The remove is idempotent, so replaying it is safe. */
  private void replayDelete(Candidate candidate) {
    ContainersDriver.OpResult removed =
        driver.remove(candidate.containerName(), ContainersTimeouts.REMOVE);
    boolean gone =
        removed.ok()
            || driver.inspect(candidate.containerName(), ContainersTimeouts.INSPECT).isEmpty();
    if (gone) {
      registry.settle(
          candidate.rowId(), ObservedState.GONE, "[startup: the interrupted delete was replayed]");
      return;
    }
    LOG.warnf(
        "Could not remove %s while replaying its delete: %s",
        candidate.containerName(), Details.brief(removed.detail()));
  }

  /** Settle a row as no longer wanted: nothing will start under it again. */
  private void abandon(UUID rowId, ObservedState observed, String detail) {
    DbRetry.runInNewTx(
        "The boot sweep's abandonment of row " + rowId,
        () -> {
          CtContainer row = containers.findById(rowId);
          if (row == null) {
            return;
          }
          row.desiredState = DesiredState.ABSENT;
          row.observedState = observed;
          row.detail = Details.append(row.detail, detail);
          row.updatedAt = clock.instant();
          containers.flush();
        },
        ContainerRegistry.CUTOVER_BUDGET);
  }

  private static List<Candidate> candidates(List<CtContainer> rows) {
    List<Candidate> out = new ArrayList<>(rows.size());
    for (CtContainer row : rows) {
      out.add(new Candidate(row.id, row.containerName, row.policy));
    }
    return List.copyOf(out);
  }

  /** One row worth deciding about, as plain values: no entity crosses a docker call. */
  private record Candidate(UUID rowId, String containerName, LifecyclePolicy.Type policy) {}
}
