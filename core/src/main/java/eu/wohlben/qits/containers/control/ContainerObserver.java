package eu.wohlben.qits.containers.control;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.ContainersTimeouts;
import eu.wohlben.qits.containers.entity.CtContainer;
import eu.wohlben.qits.containers.entity.ObservedState;
import eu.wohlben.qits.containers.persistence.CtContainerRepository;
import eu.wohlben.qits.db.DbRetry;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The periodic pass that keeps the registry honest, and the one worker every background write of
 * this service runs on.
 *
 * <p><b>It writes rows and nothing else.</b> No container is started, stopped or removed here, and
 * no volume is touched. It is qits-platform-deployments' {@code DeploymentObserver} stance, and it
 * applies here for the same reason it does there: the boot sweep at least runs once, at a moment
 * nothing else is happening, while this runs beside a live platform forever. The sweeps that
 * <em>do</em> act — {@link IdleSweep}, {@link MaxAgeGc}, {@link VolumeReconcile} — are separate
 * classes with their own arguments, and they run on this worker rather than beside it.
 *
 * <p><b>One worker, one concurrency model.</b> A bare daemon ticker
 * ({@code ct-observation-ticker}) submits one pass every
 * {@code qits.containers.observe-interval-seconds} onto a single-threaded executor
 * ({@code ct-worker}); {@code 0} switches the whole thing off. Not quarkus-scheduler: the ticker's
 * only job is {@code submit}, and a scheduler extension would put a second thread pool and a second
 * ordering story beside a service whose registry writes must not race each other. A tick that fires
 * while a pass is already queued <b>collapses</b> into it — an observation is a statement about now,
 * so ten of them stacked behind a slow docker daemon would all answer the same question.
 *
 * <p><b>Two transitions, and each is deliberately narrow.</b>
 *
 * <ul>
 *   <li>{@code RUNNING} → {@code EXITED}/{@code MISSING} only when the container is absent or
 *       terminal on {@value #STRIKES_TO_DEMOTE} consecutive passes. One {@code inspect} that could
 *       not answer — a daemon reloading, a call that timed out — must never flip a workload that is
 *       serving, and a container that is restarting or paused is not dead.
 *   <li>{@code EXITED}/{@code MISSING} → {@code RUNNING} the moment the container answers again.
 *       The recovery <b>appends</b> to the detail and never erases it: the original text is the
 *       diagnosis of what went wrong, and it is what makes a bug findable.
 * </ul>
 *
 * <p>The strike count is in memory on purpose — it is a debounce, not a fact about the world, and a
 * restart that loses it simply spends two more passes agreeing. It is pruned to the candidates of
 * the latest pass, so it cannot grow with the history.
 *
 * <p>Every candidate costs one {@code inspect}, taken <b>outside every transaction</b>, with the
 * read before it and the write after it each in their own {@link DbRetry} bracket.
 */
@ApplicationScoped
public class ContainerObserver {

  private static final Logger LOG = Logger.getLogger(ContainerObserver.class);

  /**
   * How many consecutive passes must agree that a running workload's container is gone before the
   * row is demoted. Two, because one docker call that could not answer must not take a live
   * container's row with it.
   */
  static final int STRIKES_TO_DEMOTE = 2;

  @Inject CtContainerRepository containers;
  @Inject ContainersDriver driver;
  @Inject ContainerRegistry registry;
  @Inject IdleSweep idleSweep;
  @Inject VolumeReconcile volumeReconcile;
  @Inject MaxAgeGc maxAgeGc;
  @Inject RowPrune rowPrune;
  @Inject java.time.Clock clock;

  @ConfigProperty(name = "qits.containers.observe-interval-seconds")
  long observeIntervalSeconds;

  /** Consecutive dead observations per row id. In memory on purpose — see the class javadoc. */
  private final Map<UUID, Integer> strikes = new ConcurrentHashMap<>();

  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "ct-worker");
            t.setDaemon(true);
            return t;
          });

  /** At most one pass is pending behind the worker at a time. See the class javadoc. */
  private final AtomicBoolean passPending = new AtomicBoolean();

  private volatile Thread ticker;

  /**
   * Nothing ticks in test mode, for the reason the boot sweep is skipped: a pass landing mid-test
   * would settle rows the test is still arranging. The suite drives {@link #observeOnce()} and the
   * sweeps' own entry points directly, so the interval keeps its shipped value in the suite rather
   * than being overridden to disable something that never starts.
   *
   * <p><b>It starts after {@link BootSweep}</b>, by {@link BootSweep#OBSERVER_PRIORITY}. A pass
   * landing before the sweep would meet every in-flight row before the sweep had decided about it,
   * and could spend a strike on a workload that is about to be adopted.
   */
  void onStart(@Observes @jakarta.annotation.Priority(BootSweep.OBSERVER_PRIORITY) StartupEvent event) {
    if (LaunchMode.current() == LaunchMode.TEST) {
      return;
    }
    if (observeIntervalSeconds <= 0) {
      LOG.info(
          "Container observation is off (qits.containers.observe-interval-seconds=0): a row's state"
              + " will be whatever the operation that wrote it said");
      return;
    }
    Thread thread =
        new Thread(
            () -> {
              while (!Thread.currentThread().isInterrupted()) {
                try {
                  Thread.sleep(Duration.ofSeconds(observeIntervalSeconds).toMillis());
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                enqueuePass();
              }
            },
            "ct-observation-ticker");
    thread.setDaemon(true);
    ticker = thread;
    thread.start();
    LOG.infof("Observing container rows every %ds on ct-worker", observeIntervalSeconds);
  }

  @PreDestroy
  void shutdown() {
    Thread thread = ticker;
    if (thread != null) {
      thread.interrupt();
    }
    worker.shutdownNow();
  }

  /**
   * Queue one pass on {@code ct-worker}. Package-private so the suite can drive the collapse without
   * waiting for a tick.
   */
  void enqueuePass() {
    if (!passPending.compareAndSet(false, true)) {
      LOG.debug("A pass is already queued; this tick collapses into it");
      return;
    }
    worker.submit(
        () -> {
          // Cleared as the pass BEGINS, not when it ends: a tick arriving during a long pass may
          // queue the next one, so the queue holds at most one pending pass plus the running one.
          passPending.set(false);
          run("the observation pass", this::observeOnce);
          run("the idle sweep", idleSweep::sweepOnce);
          run("the volume reconcile", volumeReconcile::reconcileOnce);
          run("the max-age collection", maxAgeGc::sweepOnce);
          run("the row prune", rowPrune::pruneOnce);
        });
  }

  private static void run(String what, Runnable body) {
    try {
      body.run();
    } catch (RuntimeException e) {
      LOG.warnf(e, "%s failed; the next tick tries again", what);
    }
  }

  /** One observation pass. Package-private so the suite drives it without the tick. */
  void observeOnce() {
    List<Candidate> candidates =
        registry.read("The observation pass's candidate read", this::candidates);
    Set<UUID> seen = new HashSet<>();
    for (Candidate candidate : candidates) {
      seen.add(candidate.rowId());
      Optional<ContainersDriver.Observed> observed;
      try {
        // Outside every transaction: a docker call is a child process, and no bracket of this
        // component's own may span one.
        observed = driver.inspect(candidate.containerName(), ContainersTimeouts.INSPECT);
      } catch (RuntimeException e) {
        // A daemon that could not answer has said nothing. The row keeps whatever it said, and the
        // strike count keeps whatever it held: an unanswerable call is not evidence either way.
        LOG.warnf(
            "Could not observe %s, so its row is left as it is: %s",
            candidate.containerName(), e.getMessage());
        continue;
      }
      settle(candidate, observed);
    }
    strikes.keySet().retainAll(seen);
  }

  /** What one answer means for one row. */
  private void settle(Candidate candidate, Optional<ContainersDriver.Observed> observed) {
    boolean up = observed.isPresent() && ContainerRegistry.running(observed.get());
    boolean dead = observed.isEmpty() || ContainerRegistry.terminal(observed.get());

    if (up) {
      strikes.remove(candidate.rowId());
      if (candidate.observed() == ObservedState.EXITED
          || candidate.observed() == ObservedState.MISSING) {
        stamp(
            candidate.rowId(),
            ObservedState.RUNNING,
            "[recovered by observation: "
                + candidate.containerName()
                + " is running again, so the row that said "
                + candidate.observed()
                + " was wrong]");
        LOG.infof("Recovered %s by observation: it is running again", candidate.containerName());
        return;
      }
      stamp(candidate.rowId(), ObservedState.RUNNING, null);
      return;
    }

    if (!dead) {
      // Created, restarting, paused: docker still has a state for it. Anything that answered at all
      // clears whatever the last pass thought.
      strikes.remove(candidate.rowId());
      stamp(candidate.rowId(), null, null);
      return;
    }

    if (candidate.observed() != ObservedState.RUNNING) {
      // Already recorded as not running. Nothing to demote; just record that we looked.
      stamp(candidate.rowId(), null, null);
      return;
    }

    int strike = strikes.merge(candidate.rowId(), 1, Integer::sum);
    if (strike < STRIKES_TO_DEMOTE) {
      LOG.debugf(
          "%s looks gone, and one pass is not a verdict — waiting for a second",
          candidate.containerName());
      stamp(candidate.rowId(), null, null);
      return;
    }
    strikes.remove(candidate.rowId());
    ObservedState demoted = observed.isEmpty() ? ObservedState.MISSING : ObservedState.EXITED;
    stamp(
        candidate.rowId(),
        demoted,
        "[recorded "
            + demoted
            + " by observation: "
            + candidate.containerName()
            + " was gone on "
            + STRIKES_TO_DEMOTE
            + " consecutive passes. No container was touched.]");
    LOG.warnf(
        "%s was RUNNING, but its container is gone on %d consecutive observations — recorded %s",
        candidate.containerName(), STRIKES_TO_DEMOTE, demoted);
  }

  /**
   * One row's write: the observed state when it changed, the detail when there is one, and
   * {@code last_observed_at} always. The stamp is unconditional because it is the answer to "when
   * did anything last look at this", which a pass that changed nothing still answers.
   */
  private void stamp(UUID rowId, ObservedState observed, String detail) {
    DbRetry.runInNewTx(
        "The observation of container row " + rowId,
        () -> {
          CtContainer row = containers.findById(rowId);
          if (row == null) {
            return;
          }
          Instant now = clock.instant();
          row.lastObservedAt = now;
          if (observed != null && observed != row.observedState) {
            row.observedState = observed;
            row.updatedAt = now;
          }
          if (detail != null) {
            row.detail = Details.append(row.detail, detail);
            row.updatedAt = now;
          }
          containers.flush(); // statement phase, so a lost connection is retriable
        },
        ContainerRegistry.CUTOVER_BUDGET);
  }

  /** Every live row — one the owner has not deleted — as plain values. */
  private List<Candidate> candidates() {
    List<Candidate> out = new ArrayList<>();
    for (CtContainer row : containers.listLive()) {
      if (row.containerName == null || row.containerName.isBlank()) {
        continue;
      }
      out.add(new Candidate(row.id, row.containerName, row.observedState));
    }
    return List.copyOf(out);
  }

  /** How many rows the strike map is holding. Package-private: the suite asserts it is pruned. */
  int trackedStrikes() {
    return strikes.size();
  }

  private record Candidate(UUID rowId, String containerName, ObservedState observed) {}
}
