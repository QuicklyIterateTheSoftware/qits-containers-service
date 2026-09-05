package eu.wohlben.qits.containers.control;

import eu.wohlben.qits.containers.spec.ContainerSpec;
import eu.wohlben.qits.containers.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.spec.VolumeSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The seam between this service's orchestration and the host's docker daemon — the {@code
 * DeploymentDriver} / {@code CiStepRunner} arrangement: {@code core} owns the interface and
 * everything that calls it, {@code service/dockerhost} owns the sole production implementation
 * (shelling the docker CLI through {@link eu.wohlben.qits.containers.docker.ContainerProcess}), and
 * the suites install a scripted fake so a clone's {@code ./mvnw verify} needs no docker.
 *
 * <p>Everything crossing this seam is names, specs and references — never entities. The driver knows
 * nothing about registry rows, owners or sweeps; it starts, watches and removes containers, and it
 * makes and lists volumes.
 *
 * <p><b>Every container-touching method takes an explicit {@link Duration} timeout, and that is the
 * interface making a deployment rule unavoidable rather than documenting one.</b> Patience is not
 * tuning here: a docker call with no deadline is a worker held forever by a daemon that stopped
 * answering, and this service has exactly one worker. A default would let a caller be untimed by
 * omission, and a timeout stored on the implementation would put the deadline out of reach of the
 * caller that knows what it can afford to wait for. So the parameter is there on every one of them,
 * including the ones that "cannot" block — {@link #selfContainerId()} is the sole exception and it
 * reads a file rather than a daemon.
 *
 * <p><b>The reads that can be unbounded take a bound too</b>, for the same reason and stated the
 * same way: {@link #logsTail} and {@link #pull} capture output an owner influences.
 *
 * <p><b>Nothing here removes a container by label.</b> {@link #listByLabels} narrows a listing; what
 * may be removed is what a registry row names, and the decision is the caller's.
 */
public interface ContainersDriver {

  /** Whether the container started, its docker id, and what docker said if it did not. */
  record Started(boolean started, String containerId, String detail) {}

  /**
   * One observation: the docker id, the {@code running}/{@code exited}/… status, the health
   * ({@code none} when the image declares no check), and when this run began.
   *
   * <p>An <b>absent</b> {@link Optional} from {@link #inspect} is docker having no such container.
   * That is a different statement from an unhealthy one and the two must never be merged: absent is
   * what a row's own container being gone looks like, and unhealthy is a container that is there.
   */
  record Observed(String id, String status, String health, Instant startedAt) {}

  /** Whether the call did what was asked, and what docker said if it did not. */
  record OpResult(boolean ok, String detail) {}

  /** A bounded tail of a container's own output, and whether the front of it was dropped. */
  record LogTail(String text, boolean truncated) {}

  /**
   * Start the container, detached, under this exact name. The row that names it was written first,
   * so a crash between this call and its answer leaves a container the registry can still find.
   */
  Started run(
      ContainerSpec spec,
      String name,
      Map<String, String> labels,
      LifecyclePolicy policy,
      Duration timeout);

  /**
   * Start the container that is already there, under this name, keeping its identity.
   *
   * <p><b>It is not a {@link #run} of the same spec, and the difference is the whole reason this
   * method exists.</b> A stopped container still holds its name, so a run against it is refused by
   * the daemon; and even if it were not, a run would make a <em>second</em> container — a new docker
   * id, a new start time, and none of the state the stopped one was carrying. A workload that was
   * stopped and is asked for again is asked for the container it had.
   */
  OpResult start(String name, Duration timeout);

  /** One inspect. Empty when docker has no such container — see {@link Observed}. */
  Optional<Observed> inspect(String name, Duration timeout);

  /**
   * Which image reference the named container was created from. Empty when docker has no such
   * container, and it <b>throws</b> when docker did not answer, on {@link #inspect}'s reasoning:
   * the one caller compares it against a configured pin to decide whether to replace the platform's
   * builder, and a guess read as "some other image" would recreate a healthy container.
   */
  Optional<String> imageOf(String name, Duration timeout);

  /**
   * Start the platform's own buildkitd — the one privileged container this service ever runs, and
   * the only run that goes around {@link ContainerSpec}. It does so <b>because</b> of what it needs:
   * {@code --privileged} must stay something no spec can express, so the whole argv is a constant
   * shape in {@code DockerArgv} and this method carries only the values this service's own
   * configuration decides. See {@code DockerArgv.runBuildkitd}.
   */
  Started runBuildkitd(
      String image, String network, String stateVolume, String toml, long pidsLimit,
      int oomScoreAdj, Duration timeout);

  /** Stop it, leaving it restartable. */
  OpResult stop(String name, Duration timeout);

  /** Remove it, running or not. Only ever for a container a row names. */
  OpResult remove(String name, Duration timeout);

  /** The tail of what the container printed — captured <b>before</b> any removal, or lost with it. */
  LogTail logsTail(String name, int lines, Duration timeout, int maxChars);

  /** Container ids carrying every one of these labels. A listing, never a licence to remove. */
  List<String> listByLabels(Map<String, String> filters, Duration timeout);

  /** Create the volume if it is absent, labelled. Idempotent — docker's own create is. */
  OpResult ensureVolume(VolumeSpec spec, Map<String, String> labels, Duration timeout);

  /** Remove the named volume. Always an explicit ask; nothing sweeps one. */
  OpResult removeVolume(String name, Duration timeout);

  /** Volume names carrying every one of these labels. */
  List<String> listVolumesByLabels(Map<String, String> filters, Duration timeout);

  /** Fetch the image, so a missing one is its own recorded outcome rather than a failed run. */
  OpResult pull(String imageRef, Duration timeout, int maxChars);

  /**
   * Whether the network exists. There is no create: a network this service invented would be one no
   * other module's containers are on, and a bridge cannot be created on a swarm host at all.
   */
  boolean networkPresent(String network, Duration timeout);

  // --- the host's own stores ------------------------------------------------------------------
  //
  // Images, dangling volumes and build cache are NOT rows, and this is the one part of the seam
  // that reaches things no registry row has ever named. Two rules keep it inside the repository's
  // first invariant, and they are stated here because they are properties of the SEAM rather than
  // of one caller:
  //
  //   1. Nothing here removes by label, by pattern or by prune. `removeImage` and `removeVolume`
  //      take one name, decided by a caller holding the rows and the pins.
  //   2. A listing that PROTECTS throws when it could not be made; a listing that only produces
  //      CANDIDATES degrades to empty. `listImageReferencesInUse` and `listContainersUsingVolume`
  //      are the protecting ones: an empty answer from either is a positive statement ("nothing
  //      references this"), so a daemon that did not answer must never be able to say it. An empty
  //      candidate listing costs a collection run that removes nothing, which is the harmless half.

  /** One store's line of {@code docker system df}: how many, how many are in use, how big. */
  record UsageLine(long count, long active, long sizeBytes, long reclaimableBytes) {

    /**
     * A store docker printed no line for. It is an all-zero line rather than a null because the
     * answer has four members and a caller drawing them needs four — and because a daemon that
     * answered and named three stores has said something true about the fourth.
     */
    public static final UsageLine EMPTY = new UsageLine(0, 0, 0, 0);
  }

  /** The four stores {@code docker system df} reports on. */
  record DiskUsage(
      UsageLine images, UsageLine containers, UsageLine volumes, UsageLine buildCache) {}

  /**
   * One local image, with every {@code repository:tag} that names it folded onto its id.
   *
   * <p><b>An image with no tags is a dangling one</b> — that is the whole of the definition, and it
   * is why "dangling" is not a field: it is {@code tags().isEmpty()}, so no reader can disagree with
   * a writer about it.
   */
  record ImageSummary(String id, List<String> tags, long sizeBytes, Instant createdAt) {}

  /** A volume's labels and when docker made it. */
  record VolumeDetail(String name, Map<String, String> labels, Instant createdAt) {}

  /**
   * What a build-cache call answered: whether it worked, the bytes it names, and its own summary
   * line. For a prune {@code bytes} is what it reclaimed; for a {@code du} it is what the cache says
   * is reclaimable. One record for both, because the caller reports them in the same field.
   */
  record CacheResult(boolean ok, long bytes, String detail) {}

  /** What the host's four stores hold. Throws when docker did not answer — a usage is not a guess. */
  DiskUsage diskUsage(Duration timeout);

  /** Every image on the host. Degrades to empty: no candidates is a collection that removes nothing. */
  List<ImageSummary> listImages(Duration timeout);

  /**
   * What every container on this host — running or not — was created from: a reference, or a bare
   * id when the reference no longer resolves.
   *
   * <p><b>It throws rather than degrading</b>, and that is the difference between a collection that
   * is safe and one that is green. This listing is what keeps an image a container is holding out
   * of the candidate set, so an empty answer has to mean "no container references anything" and
   * never "we could not find out".
   */
  List<String> listImageReferencesInUse(Duration timeout);

  /**
   * Remove one <b>untagged</b> image by id. Never forced, never a prune — see
   * {@code DockerArgv.imageRm}.
   */
  OpResult removeImage(String id, Duration timeout);

  /**
   * Remove a tagged image by every reference that names it.
   *
   * <p><b>Two methods rather than one, because docker has two answers.</b> An id is refused for an
   * image more than one reference names — including two tags of one repository — so a tagged image
   * is untagged instead, and the last untag is what removes it. Merging them would mean one belt
   * loose enough for both a name and an id, which is the belt that lets a tag reach a removal that
   * only meant to take an id.
   */
  OpResult removeImageReferences(List<String> references, Duration timeout);

  /** Volumes no container references. Degrades to empty, like every other candidate listing. */
  List<String> listDanglingVolumes(Duration timeout);

  /** One volume's labels and creation time. Empty when docker has no such volume; throws otherwise. */
  Optional<VolumeDetail> inspectVolume(String name, Duration timeout);

  /**
   * The containers referencing this volume, by name. <b>Throws rather than degrading</b>, for
   * {@link #listImageReferencesInUse}'s reason: an empty answer is what makes a builder's state
   * volume removable.
   */
  List<String> listContainersUsingVolume(String volumeName, Duration timeout);

  /** The buildx builder containers on this host. Degrades to empty — then no builder is pruned. */
  List<String> listBuildxBuilders(Duration timeout);

  /** Prune the host builder's cache down to {@code keepStorageBytes}. */
  CacheResult pruneBuildCache(long keepStorageBytes, Duration timeout);

  /** What the host builder's cache holds. Removes nothing. */
  CacheResult describeBuildCache(Duration timeout);

  /** Prune one builder container's own cache, from inside it. */
  CacheResult pruneBuilderCache(String container, long keepStorageBytes, Duration timeout);

  /** What one builder container's cache holds. Removes nothing. */
  CacheResult describeBuilderCache(String container, Duration timeout);

  /**
   * This process's own container id, blank when unknown. The one method with no timeout, because it
   * reads {@code /etc/hostname} rather than asking a daemon anything.
   */
  String selfContainerId();
}
