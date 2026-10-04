package eu.wohlben.qits.containers.control;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.ContainersDriver.VolumeDetail;
import eu.wohlben.qits.containers.driver.ContainersTimeouts;
import eu.wohlben.qits.containers.driver.gc.VolumeClassifier;
import eu.wohlben.qits.containers.spec.ContainerLabels;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Dangling volumes, collected by class — and only the three classes this platform can name.
 *
 * <p><b>Only a dangling volume is even a candidate.</b> {@code docker volume ls -f dangling=true}
 * answers the volumes no container references, so nothing here can reach a store a running workload
 * has open. That listing is the whole candidate set; there is no pattern sweep and no label sweep
 * below it.
 *
 * <p>What is removed, and nothing else is:
 *
 * <ul>
 *   <li><b>{@code managed-no-row}</b> — carries {@code qits.containers.managed=volume}, so this
 *       service made it, and <em>no row claims it</em>. That is a volume a crash left behind between
 *       the create and the row write, or one whose row was pruned; either way it is ours and
 *       nothing will ever ask for it again. A managed volume that DOES have a row is kept
 *       {@code live-row} and handed back to {@link VolumeReconcile}, which is the only code allowed
 *       to act on what a row says.
 *   <li><b>{@code buildx-state}</b> — {@code buildx_buildkit_<builder>_state}, the store a bootstrap
 *       builder container keeps its cache in, with <em>no builder container referencing it</em>.
 *       The dangling listing already says no container mounts it; the second question is asked
 *       anyway, because a stopped builder still holds its state and a builder that is coming back
 *       must not lose its cache. That listing throws rather than degrading, so an unanswerable
 *       docker keeps the volume.
 *   <li><b>{@code anonymous}</b> — a bare 64-hex name, which is what docker calls a volume a
 *       container asked for and nobody named. Dangling means the container it belonged to is gone.
 * </ul>
 *
 * <p><b>Everything else dangling is kept, with the reason {@code unmanaged}</b> — a compose
 * original, a bootstrap seed, another module's store between two runs. That is this repository's
 * first invariant read for volumes: a thing this service cannot account for is somebody else's, and
 * somebody else's is untouchable however much space it takes.
 *
 * <p><b>{@code minAge} is a second belt on the two classes that have a clock.</b> A managed volume
 * and an anonymous one both carry docker's own {@code CreatedAt}, so a store made minutes ago —
 * by a container this service is in the middle of starting, or by a build that has not finished —
 * is kept {@code too-young}. A buildx state volume has no age rule: its builder container existing
 * or not is a better answer than any clock, and it is the answer that is asked for.
 *
 * <p><b>The classification is qits-containers-driver's {@link VolumeClassifier}</b>, decided under this
 * service's {@link ContainerLabels#NS}: a volume another embedder labelled under its own namespace
 * is {@code unmanaged} here and kept. This class inspects, asks the rows, and maps the verdicts onto
 * the reason strings below, which are the {@code /gc} answer's.
 *
 * <p><b>No row is written, updated or deleted here, ever.</b> {@link VolumeReconcile} owns the rows,
 * and this class is deliberately the one that cannot: a collection that dropped a row as it removed
 * a volume would be able to erase the record of a volume it removed in error.
 */
@ApplicationScoped
public class VolumeGc {

  private static final Logger LOG = Logger.getLogger(VolumeGc.class);

  /** Ours, and no row will ever ask for it again. */
  public static final String MANAGED_NO_ROW = "managed-no-row";

  /** A dead builder's cache store. */
  public static final String BUILDX_STATE = "buildx-state";

  /** A container asked for it, nobody named it, and the container is gone. */
  public static final String ANONYMOUS = "anonymous";

  /** Kept: nothing here can account for it, so it is somebody else's. */
  public static final String UNMANAGED = "unmanaged";

  /** Kept: a row claims it, and rows are {@link VolumeReconcile}'s. */
  public static final String LIVE_ROW = "live-row";

  /** Kept: younger than the caller's {@code minAge}. */
  public static final String TOO_YOUNG = "too-young";

  /** Kept: a builder container still references it. */
  public static final String BUILDX_LIVE = "buildx-live";

  /** Kept: docker no longer has it — it went between the listing and the inspect. */
  public static final String VANISHED = "vanished";

  /** One volume and what was decided about it. */
  public record Outcome(String name, String reason) {}

  /** One volume docker refused, or could not be asked about, with its own words. */
  public record Failure(String name, String error) {}

  /** The whole run. In a dry run {@code removed} is what the same call without it would remove. */
  public record Result(
      boolean dryRun, List<Outcome> removed, List<Outcome> kept, List<Failure> failed) {}

  @Inject ContainersDriver driver;
  @Inject VolumeReconcile reconcile;
  @Inject Clock clock;

  /**
   * One pass.
   *
   * @param dryRun decide and report, remove nothing
   * @param minAge how young a managed or anonymous volume is protected for; null or zero protects
   *     none
   */
  public Result sweep(boolean dryRun, Duration minAge) {
    List<String> candidates = driver.listDanglingVolumes(ContainersTimeouts.VOLUME);
    if (candidates.isEmpty()) {
      return new Result(dryRun, List.of(), List.of(), List.of());
    }
    // The rows, in one bracket, through the seam that owns the question. Never written to.
    Map<String, Boolean> claimed = new java.util.HashMap<>();
    for (VolumeReconcile.Claim claim : reconcile.claimsFor(candidates)) {
      claimed.put(claim.name(), claim.owner() != null);
    }
    Instant youngest =
        minAge == null || minAge.isZero() || minAge.isNegative()
            ? null
            : clock.instant().minus(minAge);

    List<Outcome> removed = new ArrayList<>();
    List<Outcome> kept = new ArrayList<>();
    List<Failure> failed = new ArrayList<>();

    for (String name : candidates) {
      String reason;
      try {
        reason = classify(name, claimed.getOrDefault(name, false), youngest);
      } catch (RuntimeException e) {
        // A docker that would not answer about ONE volume costs that volume and not the run.
        failed.add(new Failure(name, Details.brief(String.valueOf(e.getMessage()))));
        continue;
      }
      if (!isRemovable(reason)) {
        kept.add(new Outcome(name, reason));
        continue;
      }
      if (dryRun) {
        removed.add(new Outcome(name, reason));
        continue;
      }
      ContainersDriver.OpResult gone = driver.removeVolume(name, ContainersTimeouts.VOLUME);
      if (gone.ok()) {
        removed.add(new Outcome(name, reason));
      } else {
        failed.add(new Failure(name, Details.brief(gone.detail())));
      }
    }
    LOG.infof(
        "Volume collection%s: %d candidates, removed %d, kept %d, failed %d",
        dryRun ? " (dry run)" : "", candidates.size(), removed.size(), kept.size(), failed.size());
    return new Result(dryRun, List.copyOf(removed), List.copyOf(kept), List.copyOf(failed));
  }

  /** Whether a class is one of the three that may be removed. */
  private static boolean isRemovable(String reason) {
    return MANAGED_NO_ROW.equals(reason) || BUILDX_STATE.equals(reason) || ANONYMOUS.equals(reason);
  }

  /**
   * Which class this volume is in — the removable ones first, {@code unmanaged} as the fall-through.
   * The inspect, then the decision; the decision asks docker for the containers using the volume
   * only for a buildx state name, and that listing throws rather than degrading: a builder that
   * could not be asked about is a builder whose cache is kept.
   */
  private String classify(String name, boolean hasRow, Instant youngest) {
    Optional<VolumeDetail> detail = driver.inspectVolume(name, ContainersTimeouts.VOLUME);
    return reason(
        VolumeClassifier.classify(
            name,
            detail,
            hasRow,
            youngest,
            ContainerLabels.NS,
            n -> driver.listContainersUsingVolume(n, ContainersTimeouts.GC_LIST)));
  }

  /** A classifier's verdict, as the reason string the {@code /gc} answer carries. */
  private static String reason(VolumeClassifier.Verdict verdict) {
    return switch (verdict) {
      case VANISHED -> VolumeGc.VANISHED;
      case CLAIMED -> VolumeGc.LIVE_ROW;
      case TOO_YOUNG -> VolumeGc.TOO_YOUNG;
      case MANAGED_UNCLAIMED -> VolumeGc.MANAGED_NO_ROW;
      case BUILDX_LIVE -> VolumeGc.BUILDX_LIVE;
      case BUILDX_STATE -> VolumeGc.BUILDX_STATE;
      case ANONYMOUS -> VolumeGc.ANONYMOUS;
      case UNMANAGED -> VolumeGc.UNMANAGED;
    };
  }
}
