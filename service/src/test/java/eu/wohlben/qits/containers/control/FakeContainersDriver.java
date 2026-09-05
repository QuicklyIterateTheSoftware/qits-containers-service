package eu.wohlben.qits.containers.control;

import eu.wohlben.qits.containers.control.ContainersDriver.CacheResult;
import eu.wohlben.qits.containers.control.ContainersDriver.ImageSummary;
import eu.wohlben.qits.containers.control.ContainersDriver.VolumeDetail;
import eu.wohlben.qits.containers.spec.ContainerSpec;
import eu.wohlben.qits.containers.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.spec.VolumeSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The suite's stand-in for the docker seam — a scripted fake, not an honest one: it performs
 * nothing, records every call in arrival order, and answers what a test told it to. That is what
 * keeps a clone's {@code ./mvnw verify} docker-free, which matters more in this repository than
 * anywhere: docker is its subject, and a suite that needed a daemon to say what the orchestrator
 * does could never say it.
 *
 * <p><b>It is DUPLICATED per module rather than shared, and that is the house pattern rather than an
 * oversight.</b> Maven has no {@code testFixtures} scope, and the alternatives are a test-jar
 * dependency between modules that otherwise have none. qits-workspaces carries two copies of its
 * {@code FakeContainerRuntime} for exactly this reason, and qits-ci two of {@code FakeCiStepRunner}.
 * This copy is {@code core}'s; a module that needs one copies it, and the copies are free to diverge
 * to what each suite actually scripts.
 *
 * <p><b>Three calls do keep state, and that is not scripting but the daemon's own arithmetic on
 * names.</b> {@code run} refuses a name a container already carries, {@code stop} leaves an exited
 * container behind that name, and {@code start} flips one back to running with its id unchanged.
 * They are here because a fake whose {@code run} quietly overwrote its map entry could not tell a
 * plan that RAN from a plan that STARTED — measured 2026-08-13, on a restart path that was green
 * here and refused by every real daemon.
 *
 * <p><b>The call log is the point.</b> Half of what this service has to get right is ORDER — a row
 * before a run, logs before a removal, an adopt before anything else at boot — and order is not
 * visible in return values. Every method appends one {@code kind:target} line and {@link #calls()}
 * is what a test asserts against.
 *
 * <p><b>This copy is an {@code @Alternative} with no priority, and that is the one line that
 * differs from {@code core}'s.</b> This module ships {@code DockerContainersDriver} — an ordinary
 * bean — so an ordinary bean here would be an ambiguous resolution rather than an override, and a
 * globally enabled alternative would take the real driver away from the one test that needs it:
 * {@code ContainersRestartAdoptionIT} proves the adoption against a real daemon. An alternative
 * with no priority is disabled until a {@code QuarkusTestProfile} names it in {@code
 * getEnabledAlternatives()}, so each suite says which driver it is talking to. Same arrangement as
 * qits-workspaces' {@code FakeWorkspaceServiceDriver}.
 *
 * <p>Read its state through its METHODS in a {@code @QuarkusTest}: the injected reference is a CDI
 * client proxy, and a field read on a proxy sees the proxy's fields rather than the bean's.
 *
 * <p><b>Two hooks exist for claims that cannot be made any other way.</b> {@link #duringRun} runs
 * something at the instant {@code run} is entered, which is how "the row already existed, and said
 * PENDING, before docker was asked for anything" becomes an assertion rather than an inference from
 * ordering. {@link #scriptDown} makes every container-touching call throw, which is a docker daemon
 * that is not there — the state a boot sweep has to survive without failing a boot.
 */
@jakarta.enterprise.inject.Alternative
@jakarta.enterprise.context.ApplicationScoped
public class FakeContainersDriver implements ContainersDriver {

  private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
  private final List<ContainerSpec> ranSpecs = Collections.synchronizedList(new ArrayList<>());
  private final List<VolumeSpec> ensuredVolumes = Collections.synchronizedList(new ArrayList<>());

  private final Map<String, Observed> containers = new ConcurrentHashMap<>();

  /** What each container was created from — what {@code imageOf} answers. */
  private final Map<String, String> containerImages = new ConcurrentHashMap<>();
  private final Map<String, String> logs = new ConcurrentHashMap<>();
  private final Map<String, List<String>> labelListings = new ConcurrentHashMap<>();
  private final Map<String, List<String>> volumeListings = new ConcurrentHashMap<>();

  private volatile Started nextRun = new Started(true, "fake-id", null);
  private volatile OpResult nextOp = new OpResult(true, null);
  private volatile OpResult nextPull = new OpResult(true, null);
  private volatile boolean networkPresent = true;
  private volatile String selfId = "";
  private volatile String down;
  private volatile java.util.function.Consumer<String> duringRun;

  public void reset() {
    calls.clear();
    ranSpecs.clear();
    ensuredVolumes.clear();
    containers.clear();
    logs.clear();
    labelListings.clear();
    volumeListings.clear();
    nextRun = new Started(true, "fake-id", null);
    nextOp = new OpResult(true, null);
    nextPull = new OpResult(true, null);
    networkPresent = true;
    selfId = "";
    down = null;
    duringRun = null;
    diskUsage =
        new ContainersDriver.DiskUsage(
            ContainersDriver.UsageLine.EMPTY,
            ContainersDriver.UsageLine.EMPTY,
            ContainersDriver.UsageLine.EMPTY,
            ContainersDriver.UsageLine.EMPTY);
    images.clear();
    imageReferencesInUse.clear();
    imageRemovals.clear();
    danglingVolumes.clear();
    volumeDetails.clear();
    volumeHolders.clear();
    volumeHoldersUnreadable.clear();
    builders.clear();
    builderCaches.clear();
    inUseUnreadable = null;
    hostCache = new CacheResult(true, 0, "Total: 0B");
  }

  /** Every driver call in arrival order, tagged {@code kind:target}. */
  public List<String> calls() {
    return List.copyOf(calls);
  }

  public List<ContainerSpec> ranSpecs() {
    return List.copyOf(ranSpecs);
  }

  public List<VolumeSpec> ensuredVolumes() {
    return List.copyOf(ensuredVolumes);
  }

  public void scriptRun(Started result) {
    nextRun = result;
  }

  public void scriptOp(OpResult result) {
    nextOp = result;
  }

  public void scriptPull(OpResult result) {
    nextPull = result;
  }

  /**
   * What {@link #inspect} answers for this name. A name nothing scripted is <b>absent</b>: this fake
   * performs nothing, so the only containers docker could have are the ones a test said exist.
   */
  public void scriptContainer(String name, String status, String health, Instant startedAt) {
    containers.put(name, new Observed(name + "-id", status, health, startedAt));
  }

  public void scriptGone(String name) {
    containers.remove(name);
  }

  public void scriptLogs(String name, String text) {
    logs.put(name, text);
  }

  public void scriptLabelListing(Map<String, String> filters, List<String> ids) {
    labelListings.put(key(filters), List.copyOf(ids));
  }

  public void scriptVolumeListing(Map<String, String> filters, List<String> names) {
    volumeListings.put(key(filters), List.copyOf(names));
  }

  public void scriptNetworkPresent(boolean present) {
    networkPresent = present;
  }

  public void scriptSelfId(String id) {
    selfId = id;
  }

  /**
   * Every container-touching call throws from now on — a docker daemon that is not there, which is
   * the ordinary state of a host that has just rebooted. {@code null} puts it back.
   */
  public void scriptDown(String message) {
    down = message;
  }

  /**
   * Run something at the instant {@code run} is entered, before anything is recorded. It is how a
   * test says "the registry row was already committed, and said PENDING, when docker was asked" —
   * a claim about ORDER that no return value carries.
   */
  public void duringRun(java.util.function.Consumer<String> hook) {
    duringRun = hook;
  }

  /** The daemon's refusal to answer, if a test scripted one. */
  private void refuseIfDown(String what) {
    String message = down;
    if (message != null) {
      throw new IllegalStateException("docker is not answering (" + what + "): " + message);
    }
  }

  @Override
  public Started run(
      ContainerSpec spec,
      String name,
      Map<String, String> labels,
      LifecyclePolicy policy,
      Duration timeout) {
    java.util.function.Consumer<String> hook = duringRun;
    if (hook != null) {
      hook.accept(name);
    }
    refuseIfDown("run " + name);
    calls.add("run:" + name);
    ranSpecs.add(spec);
    Observed taken = containers.get(name);
    if (taken != null) {
      // Docker refuses a name another container already carries, running or exited, and no script
      // can say otherwise here: the name is the state. This fake used to overwrite the entry, which
      // made a registry that RAN where it should have STARTED green in this suite and refused by
      // every real daemon.
      return new Started(
          false,
          "",
          "Conflict. The container name \"/" + name + "\" is already in use by " + taken.id());
    }
    if (nextRun.started()) {
      containers.put(name, new Observed(nextRun.containerId(), "running", "none", Instant.EPOCH));
    }
    return nextRun;
  }

  /**
   * A start of the container that is there. It keeps the id, which is the whole claim a restart
   * makes: the same container came back rather than a new one being made under the old name.
   */
  @Override
  public OpResult start(String name, Duration timeout) {
    refuseIfDown("start " + name);
    calls.add("start:" + name);
    Observed existing = containers.get(name);
    if (existing == null) {
      // A start cannot invent a container, whatever the ops were scripted with. This is docker's
      // own answer for a name it does not have, and it is the vanished-container case the registry
      // has to degrade on rather than crash.
      return new OpResult(false, "Error response from daemon: No such container: " + name);
    }
    if (nextOp.ok()) {
      containers.put(
          name, new Observed(existing.id(), "running", existing.health(), existing.startedAt()));
    }
    return nextOp;
  }

  @Override
  public Optional<Observed> inspect(String name, Duration timeout) {
    refuseIfDown("inspect " + name);
    calls.add("inspect:" + name);
    return Optional.ofNullable(containers.get(name));
  }

  @Override
  public Optional<String> imageOf(String name, Duration timeout) {
    refuseIfDown("inspect the image of " + name);
    calls.add("imageOf:" + name);
    if (!containers.containsKey(name)) {
      return Optional.empty();
    }
    return Optional.of(containerImages.getOrDefault(name, ""));
  }

  @Override
  public Started runBuildkitd(
      String image,
      String network,
      String stateVolume,
      String toml,
      long pidsLimit,
      int oomScoreAdj,
      Duration timeout) {
    refuseIfDown("run buildkitd");
    calls.add("runBuildkitd:" + image);
    String name = eu.wohlben.qits.containers.spec.ContainersIdentifiers.PLATFORM_BUILDER;
    Observed taken = containers.get(name);
    if (taken != null) {
      // Same refusal shape as run(): the name is the state, whatever the script says.
      return new Started(
          false,
          "",
          "Conflict. The container name \"/" + name + "\" is already in use by " + taken.id());
    }
    if (nextRun.started()) {
      containers.put(name, new Observed(nextRun.containerId(), "running", "none", Instant.EPOCH));
      containerImages.put(name, image);
    }
    return nextRun;
  }

  /** Seeds a container as if an earlier run had made it — for the platform-builder boot cases. */
  public void seedContainer(String name, Observed observed, String image) {
    containers.put(name, observed);
    containerImages.put(name, image);
  }

  @Override
  public OpResult stop(String name, Duration timeout) {
    refuseIfDown("stop " + name);
    calls.add("stop:" + name);
    Observed existing = containers.get(name);
    if (nextOp.ok() && existing != null) {
      // A stop that worked leaves the container EXITED and still on the host, holding its name and
      // its id. That is the state an ensure of the same spec has to be able to start again.
      containers.put(
          name, new Observed(existing.id(), "exited", existing.health(), existing.startedAt()));
    }
    return nextOp;
  }

  @Override
  public OpResult remove(String name, Duration timeout) {
    refuseIfDown("remove " + name);
    calls.add("remove:" + name);
    // A remove that reports failure did not remove: the container is still there afterwards, which
    // is what lets a test say what the registry does when docker cannot perform one.
    if (nextOp.ok()) {
      containers.remove(name);
      containerImages.remove(name);
    }
    return nextOp;
  }

  @Override
  public LogTail logsTail(String name, int lines, Duration timeout, int maxChars) {
    refuseIfDown("logs " + name);
    calls.add("logs:" + name);
    String text = logs.getOrDefault(name, "");
    if (text.length() <= maxChars) {
      return new LogTail(text, false);
    }
    return new LogTail(text.substring(text.length() - maxChars), true);
  }

  @Override
  public List<String> listByLabels(Map<String, String> filters, Duration timeout) {
    calls.add("listByLabels:" + key(filters));
    return labelListings.getOrDefault(key(filters), List.of());
  }

  @Override
  public OpResult ensureVolume(VolumeSpec spec, Map<String, String> labels, Duration timeout) {
    calls.add("ensureVolume:" + spec.name());
    ensuredVolumes.add(spec);
    return nextOp;
  }

  @Override
  public OpResult removeVolume(String name, Duration timeout) {
    calls.add("removeVolume:" + name);
    return nextOp;
  }

  @Override
  public List<String> listVolumesByLabels(Map<String, String> filters, Duration timeout) {
    calls.add("listVolumesByLabels:" + key(filters));
    return volumeListings.getOrDefault(key(filters), List.of());
  }

  @Override
  public OpResult pull(String imageRef, Duration timeout, int maxChars) {
    calls.add("pull:" + imageRef);
    return nextPull;
  }

  @Override
  public boolean networkPresent(String network, Duration timeout) {
    calls.add("networkPresent:" + network);
    return networkPresent;
  }

  @Override
  public String selfContainerId() {
    calls.add("selfContainerId");
    return selfId;
  }


  // --- the host's own stores -------------------------------------------------------------------
  //
  // Nothing scripted here is a container this fake keeps state for: images, dangling volumes and
  // build caches are the host's, and what a test says about them is what docker would have said.
  // The two listings that PROTECT — the in-use references and a volume's holders — have their own
  // "docker would not answer" hooks, because the whole safety of the two collections is that an
  // unanswerable daemon stops them rather than emptying them.

  private volatile ContainersDriver.DiskUsage diskUsage =
      new ContainersDriver.DiskUsage(
          ContainersDriver.UsageLine.EMPTY,
          ContainersDriver.UsageLine.EMPTY,
          ContainersDriver.UsageLine.EMPTY,
          ContainersDriver.UsageLine.EMPTY);

  private final List<ImageSummary> images = Collections.synchronizedList(new ArrayList<>());
  private final List<String> imageReferencesInUse = Collections.synchronizedList(new ArrayList<>());
  private final Map<String, OpResult> imageRemovals = new ConcurrentHashMap<>();
  private final List<String> danglingVolumes = Collections.synchronizedList(new ArrayList<>());
  private final Map<String, VolumeDetail> volumeDetails = new ConcurrentHashMap<>();
  private final Map<String, List<String>> volumeHolders = new ConcurrentHashMap<>();
  private final Map<String, String> volumeHoldersUnreadable = new ConcurrentHashMap<>();
  private final List<String> builders = Collections.synchronizedList(new ArrayList<>());
  private final Map<String, CacheResult> builderCaches = new ConcurrentHashMap<>();

  private volatile String inUseUnreadable;
  private volatile CacheResult hostCache = new CacheResult(true, 0, "Total: 0B");

  public void scriptDiskUsage(ContainersDriver.DiskUsage usage) {
    diskUsage = usage;
  }

  /** One image on the host. No tags is a dangling image, which is the whole of that definition. */
  public void scriptImage(String id, List<String> tags, long sizeBytes, Instant createdAt) {
    images.add(new ImageSummary(id, List.copyOf(tags), sizeBytes, createdAt));
  }

  /** What containers were created from — a reference, or a bare id docker could not resolve. */
  public void scriptImageReferencesInUse(List<String> references) {
    imageReferencesInUse.clear();
    imageReferencesInUse.addAll(references);
  }

  /** The listing that protects, refusing to answer. Nothing may be collected on the strength of it. */
  public void scriptInUseUnreadable(String message) {
    inUseUnreadable = message;
  }

  /** What docker says to one {@code image rm}. Unscripted removes work. */
  public void scriptImageRemoval(String id, OpResult result) {
    imageRemovals.put(id, result);
  }

  public void scriptDanglingVolumes(List<String> names) {
    danglingVolumes.clear();
    danglingVolumes.addAll(names);
  }

  /** A volume docker knows about. One nothing scripted is one docker does not have. */
  public void scriptVolumeDetail(String name, Map<String, String> labels, Instant createdAt) {
    volumeDetails.put(name, new VolumeDetail(name, Map.copyOf(labels), createdAt));
  }

  public void scriptVolumeHolders(String name, List<String> containers) {
    volumeHolders.put(name, List.copyOf(containers));
  }

  /** The other listing that protects, refusing to answer for one volume. */
  public void scriptVolumeHoldersUnreadable(String name, String message) {
    volumeHoldersUnreadable.put(name, message);
  }

  public void scriptBuilders(List<String> containers) {
    builders.clear();
    builders.addAll(containers);
  }

  public void scriptHostCache(CacheResult result) {
    hostCache = result;
  }

  public void scriptBuilderCache(String container, CacheResult result) {
    builderCaches.put(container, result);
  }

  @Override
  public ContainersDriver.DiskUsage diskUsage(Duration timeout) {
    refuseIfDown("system df");
    calls.add("diskUsage");
    return diskUsage;
  }

  @Override
  public List<ImageSummary> listImages(Duration timeout) {
    refuseIfDown("image ls");
    calls.add("listImages");
    return List.copyOf(images);
  }

  @Override
  public List<String> listImageReferencesInUse(Duration timeout) {
    refuseIfDown("ps");
    calls.add("listImageReferencesInUse");
    String message = inUseUnreadable;
    if (message != null) {
      throw new IllegalStateException("docker did not answer a container listing: " + message);
    }
    return List.copyOf(imageReferencesInUse);
  }

  @Override
  public OpResult removeImage(String id, Duration timeout) {
    refuseIfDown("image rm " + id);
    calls.add("removeImage:" + id);
    OpResult result = imageRemovals.getOrDefault(id, new OpResult(true, null));
    if (result.ok()) {
      images.removeIf(image -> image.id().equals(id));
    }
    return result;
  }

  /**
   * Remove a tagged image by its references. The scripted outcome is looked up by the IMAGE'S ID,
   * so a test scripts a refusal the same way whichever call the collection ends up making.
   */
  @Override
  public OpResult removeImageReferences(List<String> references, Duration timeout) {
    refuseIfDown("image rm " + String.join(" ", references));
    calls.add("removeImageReferences:" + String.join(",", references));
    ImageSummary named =
        images.stream()
            .filter(image -> image.tags().stream().anyMatch(references::contains))
            .findFirst()
            .orElse(null);
    if (named == null) {
      return new OpResult(false, "Error response from daemon: No such image: " + references);
    }
    OpResult result = imageRemovals.getOrDefault(named.id(), new OpResult(true, null));
    if (result.ok()) {
      // Docker's own arithmetic: the last untag takes the image. This fake is only asked for the
      // whole set at once, so the image goes when the call works.
      images.remove(named);
    }
    return result;
  }

  @Override
  public List<String> listDanglingVolumes(Duration timeout) {
    refuseIfDown("volume ls");
    calls.add("listDanglingVolumes");
    return List.copyOf(danglingVolumes);
  }

  @Override
  public Optional<VolumeDetail> inspectVolume(String name, Duration timeout) {
    refuseIfDown("volume inspect " + name);
    calls.add("inspectVolume:" + name);
    return Optional.ofNullable(volumeDetails.get(name));
  }

  @Override
  public List<String> listContainersUsingVolume(String volumeName, Duration timeout) {
    refuseIfDown("ps --filter volume=" + volumeName);
    calls.add("listContainersUsingVolume:" + volumeName);
    String message = volumeHoldersUnreadable.get(volumeName);
    if (message != null) {
      throw new IllegalStateException("docker did not answer a container listing: " + message);
    }
    return volumeHolders.getOrDefault(volumeName, List.of());
  }

  @Override
  public List<String> listBuildxBuilders(Duration timeout) {
    refuseIfDown("ps --filter name=buildx_buildkit_");
    calls.add("listBuildxBuilders");
    return List.copyOf(builders);
  }

  @Override
  public CacheResult pruneBuildCache(long keepStorageBytes, Duration timeout) {
    refuseIfDown("builder prune");
    calls.add("pruneBuildCache:" + keepStorageBytes);
    return hostCache;
  }

  @Override
  public CacheResult describeBuildCache(Duration timeout) {
    refuseIfDown("buildx du");
    calls.add("describeBuildCache");
    return hostCache;
  }

  @Override
  public CacheResult pruneBuilderCache(String container, long keepStorageBytes, Duration timeout) {
    refuseIfDown("buildctl prune in " + container);
    calls.add("pruneBuilderCache:" + container + ":" + keepStorageBytes);
    return builderCaches.getOrDefault(container, new CacheResult(true, 0, "Total: 0B"));
  }

  @Override
  public CacheResult describeBuilderCache(String container, Duration timeout) {
    refuseIfDown("buildctl du in " + container);
    calls.add("describeBuilderCache:" + container);
    return builderCaches.getOrDefault(container, new CacheResult(true, 0, "Total: 0B"));
  }

  /** Filters as one comparable string, sorted, so scripting and lookup cannot disagree on order. */
  private static String key(Map<String, String> filters) {
    return new java.util.TreeMap<>(filters)
        .entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .reduce((a, b) -> a + "," + b)
            .orElse("");
  }
}
