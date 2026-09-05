package eu.wohlben.qits.containers.control;

import eu.wohlben.qits.containers.control.ContainersDriver.CacheResult;
import eu.wohlben.qits.containers.spec.ContainersIdentifiers;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The BuildKit caches on this host: the daemon's own, and one per builder container.
 *
 * <h4>Why this lives here and not in qits-ci</h4>
 *
 * <p>The cache is qits-ci's by every other measure — its steps fill it and its policy decides how
 * much of it is worth keeping. What qits-ci does not have is a docker socket: its step containers
 * get one bind-mounted <em>by this service</em>, and it holds none itself. So the policy value
 * arrives on the wire as {@code keepStorageBytes} and the mechanics are here, which is the same
 * split every route of this service is under — the caller decides, this service performs.
 *
 * <p><b>Two caches, two commands, and neither is a prune of everything.</b> The host's default
 * builder answers {@code docker builder prune --keep-storage}; a bootstrap builder is a container
 * running buildkitd, whose cache is reachable only from inside it, so it gets a {@code buildctl
 * prune} through {@code docker exec} — the only exec in this service, with a container name a
 * filtered {@code ps} produced and a command that is two constants. Both are bounded by
 * {@code keepStorageBytes}: neither ever empties a cache.
 *
 * <p><b>A builder that fails costs itself and never the call.</b> Its error lands in its own row of
 * the answer and the run carries on — a host with a wedged builder container must still be able to
 * prune the cache that is actually full, and an exception out of one exec would otherwise take the
 * host's own prune with it when the host prune ran first.
 *
 * <p><b>The two caches get two numbers.</b> The host's is the platform's build cache and is worth
 * keeping tens of gigabytes of; a {@code buildx_buildkit_*} container is a bootstrap builder, alive
 * between bootstraps and useful only during one, so the orchestrator sends it a much smaller
 * keep-storage. It is one call rather than two because the two caches are one question — how much
 * disk the build plane may hold — and a caller that had to make two would be a caller that can
 * forget the second.
 *
 * <p><b>A dry run reports zero reclaimed, and that is honest rather than unhelpful.</b> A
 * {@code du} says what a cache holds and what of it is reclaimable; it cannot say what a prune down
 * to a particular keep-storage would free, because that depends on which records the daemon picks
 * once it starts deleting. So the number stays 0 — nothing was reclaimed — and the {@code du}'s own
 * summary lines are the answer, in {@code detail}.
 */
@ApplicationScoped
public class BuildCacheGc {

  private static final Logger LOG = Logger.getLogger(BuildCacheGc.class);

  /** What one cache did. {@code error} is null for the ones that worked. */
  public record Cache(long reclaimedBytes, String detail, String error) {}

  /** One builder container's cache. */
  public record Builder(String container, long reclaimedBytes, String detail, String error) {}

  /** The whole run: the host's cache, then a row per builder container. */
  public record Result(boolean dryRun, Cache host, List<Builder> builders) {}

  @Inject ContainersDriver driver;

  /**
   * Whether the platform's own buildkitd is part of the build plane — and so of this sweep. Its
   * cache gets the HOST's keep-storage number, not the builder one: since the migration off the
   * host docker it <em>is</em> the platform's build cache, and the small bootstrap-builder budget
   * would empty the cache every committed Dockerfile warms.
   */
  @ConfigProperty(name = "qits.containers.buildkit.enabled")
  boolean platformBuilderEnabled;

  /**
   * One pass.
   *
   * @param dryRun read every cache, prune none
   * @param keepStorageBytes how much the host's cache may keep
   * @param builderKeepStorageBytes how much a builder container's own cache may keep — usually far
   *     less, because a bootstrap builder is only needed while a bootstrap is running
   */
  public Result sweep(boolean dryRun, long keepStorageBytes, long builderKeepStorageBytes) {
    Cache host = hostCache(dryRun, keepStorageBytes);
    List<Builder> builders = new ArrayList<>();
    if (platformBuilderEnabled) {
      // The platform builder is not in the buildx listing — it is this service's own, named by a
      // constant rather than a prefix — and its failure stays its own row like any builder's, so a
      // host with the builder down can still prune the stores that answer.
      builders.add(
          builderCache(ContainersIdentifiers.PLATFORM_BUILDER, dryRun, keepStorageBytes));
    }
    for (String container : driver.listBuildxBuilders(ContainersTimeouts.GC_LIST)) {
      builders.add(builderCache(container, dryRun, builderKeepStorageBytes));
    }
    LOG.infof(
        "Build cache collection%s: host reclaimed %d bytes, %d builder(s)",
        dryRun ? " (dry run)" : "", host.reclaimedBytes(), builders.size());
    return new Result(dryRun, host, List.copyOf(builders));
  }

  /** The daemon's own cache. A failure here is reported and does not stop the builders. */
  private Cache hostCache(boolean dryRun, long keepStorageBytes) {
    try {
      CacheResult result =
          dryRun
              ? driver.describeBuildCache(ContainersTimeouts.PRUNE)
              : driver.pruneBuildCache(keepStorageBytes, ContainersTimeouts.PRUNE);
      if (!result.ok()) {
        return new Cache(0, null, Details.brief(result.detail()));
      }
      return new Cache(dryRun ? 0 : result.bytes(), result.detail(), null);
    } catch (RuntimeException e) {
      return new Cache(0, null, Details.brief(String.valueOf(e.getMessage())));
    }
  }

  /** One builder container's cache — see the class javadoc on why its failure stays its own. */
  private Builder builderCache(String container, boolean dryRun, long keepStorageBytes) {
    try {
      CacheResult result =
          dryRun
              ? driver.describeBuilderCache(container, ContainersTimeouts.PRUNE)
              : driver.pruneBuilderCache(container, keepStorageBytes, ContainersTimeouts.PRUNE);
      if (!result.ok()) {
        return new Builder(container, 0, null, Details.brief(result.detail()));
      }
      return new Builder(container, dryRun ? 0 : result.bytes(), result.detail(), null);
    } catch (RuntimeException e) {
      return new Builder(container, 0, null, Details.brief(String.valueOf(e.getMessage())));
    }
  }
}
