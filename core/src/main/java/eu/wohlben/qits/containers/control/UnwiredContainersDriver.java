package eu.wohlben.qits.containers.control;

import eu.wohlben.qits.containers.spec.ContainerSpec;
import eu.wohlben.qits.containers.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.spec.VolumeSpec;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The driver a build that wired none gets: one that refuses every call, loudly.
 *
 * <p><b>It exists because the registry is a bean and the seam is not optional.</b> {@code core}
 * holds the interface and everything that calls it, while the sole production implementation lives
 * in {@code service} and arrives in WP4 — so between those two commits there is an application with
 * five beans injecting a type nothing satisfies, and ArC fails the build rather than the deployment.
 * The choices were to make every injection point {@code Instance<ContainersDriver>} and check it at
 * every call, or to ship a default that says what is missing. This is the second.
 *
 * <p><b>Every method throws, and none of them is a no-op.</b> A driver that quietly answered "yes,
 * done" would let the registry write rows for containers that were never started — which is the
 * exact state the whole repository is built to make impossible, arrived at from the other side. A
 * refusal is recorded as a failed operation on the row, which is a true statement.
 *
 * <p>{@code @DefaultBean}, so the real driver outranks it with no alternative, no priority and no
 * profile — and so {@code core}'s own suite's {@code FakeContainersDriver}, an ordinary bean, wins
 * over it too.
 */
@ApplicationScoped
@DefaultBean
public class UnwiredContainersDriver implements ContainersDriver {

  private static final String WHY =
      "no container runtime is wired into this build: core owns the ContainersDriver seam and the"
          + " implementation ships in the service module";

  @Override
  public Started run(
      ContainerSpec spec,
      String name,
      Map<String, String> labels,
      LifecyclePolicy policy,
      Duration timeout) {
    throw refuse("run " + name);
  }

  @Override
  public Optional<Observed> inspect(String name, Duration timeout) {
    throw refuse("inspect " + name);
  }

  @Override
  public Optional<String> buildkitdStamp(String name, Duration timeout) {
    throw refuse("inspect the stamp of " + name);
  }

  @Override
  public Started runBuildkitd(
      String image,
      String network,
      String stateVolume,
      String toml,
      String configStamp,
      long pidsLimit,
      int oomScoreAdj,
      Duration timeout) {
    throw refuse("run the platform builder");
  }

  @Override
  public OpResult start(String name, Duration timeout) {
    throw refuse("start " + name);
  }

  @Override
  public OpResult stop(String name, Duration timeout) {
    throw refuse("stop " + name);
  }

  @Override
  public OpResult remove(String name, Duration timeout) {
    throw refuse("remove " + name);
  }

  @Override
  public LogTail logsTail(String name, int lines, Duration timeout, int maxChars) {
    throw refuse("read the logs of " + name);
  }

  @Override
  public List<String> listByLabels(Map<String, String> filters, Duration timeout) {
    throw refuse("list containers");
  }

  @Override
  public OpResult ensureVolume(VolumeSpec spec, Map<String, String> labels, Duration timeout) {
    throw refuse("create the volume " + spec.name());
  }

  @Override
  public OpResult removeVolume(String name, Duration timeout) {
    throw refuse("remove the volume " + name);
  }

  @Override
  public List<String> listVolumesByLabels(Map<String, String> filters, Duration timeout) {
    throw refuse("list volumes");
  }

  @Override
  public OpResult pull(String imageRef, Duration timeout, int maxChars) {
    throw refuse("pull " + imageRef);
  }

  @Override
  public boolean networkPresent(String network, Duration timeout) {
    throw refuse("ask about the network " + network);
  }

  @Override
  public DiskUsage diskUsage(Duration timeout) {
    throw refuse("read the host's disk usage");
  }

  @Override
  public List<ImageSummary> listImages(Duration timeout) {
    throw refuse("list images");
  }

  @Override
  public List<String> listImageReferencesInUse(Duration timeout) {
    throw refuse("list the images containers are using");
  }

  @Override
  public OpResult removeImage(String id, Duration timeout) {
    throw refuse("remove the image " + id);
  }

  @Override
  public OpResult removeImageReferences(List<String> references, Duration timeout) {
    throw refuse("remove the image references " + references);
  }

  @Override
  public List<String> listDanglingVolumes(Duration timeout) {
    throw refuse("list dangling volumes");
  }

  @Override
  public Optional<VolumeDetail> inspectVolume(String name, Duration timeout) {
    throw refuse("inspect the volume " + name);
  }

  @Override
  public List<String> listContainersUsingVolume(String volumeName, Duration timeout) {
    throw refuse("list the containers using the volume " + volumeName);
  }

  @Override
  public List<String> listBuildxBuilders(Duration timeout) {
    throw refuse("list the builder containers");
  }

  @Override
  public CacheResult pruneBuildCache(long keepStorageBytes, Duration timeout) {
    throw refuse("prune the host build cache");
  }

  @Override
  public CacheResult describeBuildCache(Duration timeout) {
    throw refuse("read the host build cache");
  }

  @Override
  public CacheResult pruneBuilderCache(String container, long keepStorageBytes, Duration timeout) {
    throw refuse("prune the build cache of " + container);
  }

  @Override
  public CacheResult describeBuilderCache(String container, Duration timeout) {
    throw refuse("read the build cache of " + container);
  }

  @Override
  public String selfContainerId() {
    return "";
  }

  private static IllegalStateException refuse(String what) {
    return new IllegalStateException("Cannot " + what + ": " + WHY);
  }
}
