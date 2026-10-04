package eu.wohlben.qits.containers.control;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.ContainersTimeouts;
import eu.wohlben.qits.containers.entity.CtContainer;
import eu.wohlben.qits.containers.entity.DesiredState;
import eu.wohlben.qits.containers.entity.ObservedState;
import eu.wohlben.qits.containers.persistence.CtContainerRepository;
import eu.wohlben.qits.db.DbRetry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Stops {@code IDLE_STOP} workloads nobody has asked about for their whole window.
 *
 * <p><b>Stamp on sight, never stop on sight.</b> A row whose {@code last_touched_at} is null is one
 * this process has never heard from — a workload started before a restart, or one whose owner has
 * not touched it yet — and reading that null as "idle since the beginning of time" would stop every
 * container the first pass after a restart. So an unstamped row is stamped with the pass's own
 * instant and left alone; it ages out one window later. That is qits-projects'
 * {@code AgentIdleSweep} rule, in the same words its javadoc uses, and it is the one thing about
 * this sweep that is not obvious from what it does.
 *
 * <p>It writes {@code desired=STOPPED} before it calls docker, exactly as {@code ContainerRegistry}
 * does, so a crash between the two leaves a row that says what was asked for. The container is
 * <b>stopped, never removed</b>: that is what {@code IDLE_STOP} means, and a later {@code ensure}
 * brings the same workload back under the same name.
 *
 * <p>It runs on {@code ct-worker}, enqueued by {@link ContainerObserver}. One worker is the whole
 * concurrency model here: a sweep with a thread of its own could stop a container an {@code ensure}
 * is in the middle of starting.
 */
@ApplicationScoped
public class IdleSweep {

  private static final Logger LOG = Logger.getLogger(IdleSweep.class);

  @Inject CtContainerRepository containers;
  @Inject ContainersDriver driver;
  @Inject ContainerRegistry registry;
  @Inject Clock clock;

  /** One pass, as of now. */
  void sweepOnce() {
    sweepOnce(clock.instant());
  }

  /**
   * One pass as of a given instant, so a suite can travel past a window with no sleeping and no
   * second application to shorten it. The {@code AgentIdleSweep} shape.
   *
   * @return how many workloads it stopped
   */
  int sweepOnce(Instant now) {
    // It brackets itself — see stampAndSelect — so there is no read wrapper around it.
    List<Idle> due = stampAndSelect(now);
    for (Idle idle : due) {
      ContainersDriver.OpResult stopped =
          driver.stop(idle.containerName(), ContainersTimeouts.STOP);
      registry.settle(
          idle.rowId(),
          stopped.ok() ? ObservedState.EXITED : null,
          stopped.ok()
              ? "[stopped as idle: nothing touched it since " + idle.lastTouchedAt() + "]"
              : "[could not stop an idle workload: " + Details.brief(stopped.detail()) + "]");
      if (stopped.ok()) {
        LOG.infof(
            "Stopped the idle workload %s (last touched %s)",
            idle.containerName(), idle.lastTouchedAt());
      }
    }
    return due.size();
  }

  /**
   * The stamping and the selection in one transaction, because they are one decision: a row is
   * either stamped now (and therefore not due) or already stamped (and therefore judged against its
   * stamp). Splitting them would let a pass stamp a row and the same pass then read it as idle.
   */
  private List<Idle> stampAndSelect(Instant now) {
    return DbRetry.inNewTx(
        "The idle sweep's stamp-and-select",
        () -> {
          List<Idle> due = new ArrayList<>();
          for (CtContainer row : containers.listIdleCandidates()) {
            if (row.lastTouchedAt == null) {
              row.lastTouchedAt = now;
              continue; // stamped on sight; it ages out one window from here
            }
            if (row.lastTouchedAt.plusSeconds(row.idleAfterS).isAfter(now)) {
              continue;
            }
            row.desiredState = DesiredState.STOPPED;
            row.updatedAt = now;
            due.add(new Idle(row.id, row.containerName, row.lastTouchedAt));
          }
          containers.flush();
          return List.copyOf(due);
        },
        ContainerRegistry.CUTOVER_BUDGET);
  }

  private record Idle(UUID rowId, String containerName, Instant lastTouchedAt) {}
}
