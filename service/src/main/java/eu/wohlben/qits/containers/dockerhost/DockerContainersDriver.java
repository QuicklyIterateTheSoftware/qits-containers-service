package eu.wohlben.qits.containers.dockerhost;

import eu.wohlben.qits.containers.control.ContainersDriver;
import eu.wohlben.qits.containers.control.ContainersDriver.CacheResult;
import eu.wohlben.qits.containers.control.ContainersDriver.DiskUsage;
import eu.wohlben.qits.containers.control.ContainersDriver.ImageSummary;
import eu.wohlben.qits.containers.control.ContainersDriver.VolumeDetail;
import eu.wohlben.qits.containers.control.ContainersTimeouts;
import eu.wohlben.qits.containers.docker.ContainerProcess;
import eu.wohlben.qits.containers.docker.DockerArgv;
import eu.wohlben.qits.containers.spec.ContainerSpec;
import eu.wohlben.qits.containers.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.spec.VolumeSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The sole production implementation of {@code core}'s docker seam: {@link DockerArgv} assembles
 * the command line, {@link ContainerProcess} runs it, and this class is only the reading of what
 * comes back.
 *
 * <p><b>It carries no argv and no timeout of its own.</b> Every command line is a pure function in
 * {@code core/docker}, so the sandbox is asserted element for element in a suite with no daemon;
 * every deadline arrives as a parameter, because the caller is what knows how long it can afford to
 * wait. What is left here is the parsing, and the parsing is where the belts are.
 *
 * <p><b>It replaces {@code UnwiredContainersDriver} by ordinary CDI precedence.</b> That one is a
 * {@code @DefaultBean}, which means "unless something else implements this", so this class needs no
 * alternative, no priority and no profile — exactly the arrangement qits-platform-deployments'
 * {@code DockerDeploymentDriver} has against its own seam.
 *
 * <h4>Docker not answering is not the same statement as docker having no such container</h4>
 *
 * <p>The two are told apart rather than merged, and which one a call reports decides what the
 * registry does next:
 *
 * <ul>
 *   <li><b>{@link #inspect} throws</b> when the daemon did not answer — the binary is missing, the
 *       call timed out, or docker refused for a reason that is not "no such container". Its empty
 *       answer is a <em>positive</em> statement ("docker has no container by this name") that the
 *       boot sweep settles rows on and that {@code delete} reads as "it is really gone", so
 *       spending it on "we could not find out" would abandon live containers on a host whose docker
 *       is briefly down. Every caller already handles the throw by leaving the row exactly as it
 *       was: the observer keeps its strike count, the boot sweep keeps the row for the next boot.
 *   <li><b>The listings degrade</b> to an empty list with a warning, because an empty listing is a
 *       statement about no particular container. Nothing removes anything on the strength of one.
 *   <li><b>The operations</b> — run, stop, remove, pull, volume — report {@code ok=false} with
 *       docker's own text, which is the true statement either way and is what lands on the row.
 * </ul>
 *
 * <p><b>Nothing here fails a boot.</b> A host that has just rebooted has an orchestrator up before
 * its docker, and an orchestrator that refused to start because it could not reach docker would be
 * one that could not be deployed to fix docker.
 */
@ApplicationScoped
public class DockerContainersDriver implements ContainersDriver {

  private static final Logger LOG = Logger.getLogger(DockerContainersDriver.class);

  /** What {@link ContainerProcess} reports when the child never ran or was killed on its deadline. */
  private static final int NO_ANSWER = -1;

  /**
   * How docker says a name is not on this host. Matched case-insensitively over the combined
   * output, and it is the ONLY refusal read as an absence — anything else is a daemon that did not
   * answer. Erring that way is deliberate: an unrecognised refusal left as "unknown" costs a row
   * one more observation pass, while an unrecognised refusal read as "gone" costs a live container
   * its row.
   */
  private static final List<String> ABSENT_MARKERS = List.of("no such container", "no such object");

  /** Where a container reads its own id. Docker writes the short id as the hostname. */
  private static final Path HOSTNAME_FILE = Path.of("/etc/hostname");

  /** How much of a docker message is echoed into a log line. */
  private static final int BRIEF_CHARS = 400;

  /** Which binary is shelled out to. A property of the seam, so it ships from {@code core}. */
  @ConfigProperty(name = "qits.containers.container-runtime")
  String runtime;

  /**
   * Which group owns the host's docker socket, for the workloads that declared the bind. Injected
   * rather than read here because it is a fact about the host this process stands on and not a
   * property of the seam — and it reaches the argv only inside {@code hostDockerSocket}'s own arm.
   */
  @Inject DockerSocketGroup socketGroup;

  @Override
  public Started run(
      ContainerSpec spec,
      String name,
      Map<String, String> labels,
      LifecyclePolicy policy,
      Duration timeout) {
    ContainerProcess.Result result =
        ContainerProcess.run(
            null,
            DockerArgv.run(runtime, name, spec, labels, policy, socketGroup.value()),
            timeout,
            ContainersTimeouts.RUN_MAX_CHARS);
    if (!succeeded(result)) {
      LOG.warnf("docker refused to run %s: %s", name, brief(result.output()));
      return new Started(false, "", result.output());
    }
    // A detached run prints the container id and nothing else; the last non-blank line is it even
    // when docker printed a pull's progress above it.
    return new Started(true, lastLine(result.output()), null);
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
    ContainerProcess.Result result =
        ContainerProcess.run(
            null,
            DockerArgv.runBuildkitd(runtime, image, network, stateVolume, toml, pidsLimit, oomScoreAdj),
            timeout,
            ContainersTimeouts.RUN_MAX_CHARS);
    if (!succeeded(result)) {
      LOG.warnf("docker refused to run the platform builder: %s", brief(result.output()));
      return new Started(false, "", result.output());
    }
    return new Started(true, lastLine(result.output()), null);
  }

  /** The image reference behind a name — same absence/no-answer split as {@link #inspect}. */
  @Override
  public Optional<String> imageOf(String name, Duration timeout) {
    ContainerProcess.Result result =
        ContainerProcess.run(
            null, DockerArgv.inspectImage(runtime, name), timeout, ContainersTimeouts.SHORT_MAX_CHARS);
    if (succeeded(result)) {
      return Optional.of(result.output() == null ? "" : result.output().strip());
    }
    if (result.exitCode() != NO_ANSWER && absent(result.output())) {
      return Optional.empty();
    }
    throw new IllegalStateException(
        "docker did not answer an image inspect of " + name + ": " + brief(result.output()));
  }

  /**
   * One {@code docker inspect}, in one call: the id, the {@code <status>/<health>} pair and when
   * this run began. See the class javadoc for why an unrecognised refusal throws rather than
   * answering empty.
   */
  @Override
  public Optional<Observed> inspect(String name, Duration timeout) {
    ContainerProcess.Result result =
        ContainerProcess.run(
            null,
            DockerArgv.inspectObservation(runtime, name),
            timeout,
            ContainersTimeouts.SHORT_MAX_CHARS);
    if (succeeded(result)) {
      return Optional.of(parseObservation(name, result.output()));
    }
    if (result.exitCode() != NO_ANSWER && absent(result.output())) {
      return Optional.empty();
    }
    LOG.warnf("Could not inspect %s: %s", name, brief(result.output()));
    throw new IllegalStateException(
        "docker did not answer an inspect of " + name + ": " + brief(result.output()));
  }

  /**
   * The three fields of {@link DockerArgv#OBSERVATION_FORMAT}.
   *
   * <p><b>{@code <no value>} is answered as an absence in every field</b>, which is the belt
   * qits-platform-deployments' driver learned by measurement: Go's template prints it for a field
   * the object does not carry, and read back it would be a health state no container has or a start
   * time no clock produced. The state format's own {@code else none} arm already covers the health
   * case; this covers the day a format loses it.
   */
  static Observed parseObservation(String name, String output) {
    String[] fields = (output == null ? "" : output.strip()).split("\\|", 3);
    String id = field(fields, 0);
    // The status and the health share one field, so the belt runs on each half rather than on the
    // pair: `exited/<no value>` carries a real status beside an absent health.
    String state = field(fields, 1);
    int slash = state.indexOf('/');
    String status = present(slash < 0 ? state : state.substring(0, slash));
    String health = present(slash < 0 ? "" : state.substring(slash + 1));
    return new Observed(
        id,
        status.toLowerCase(Locale.ROOT),
        health.isEmpty() ? "none" : health.toLowerCase(Locale.ROOT),
        startedAt(name, field(fields, 2)));
  }

  /**
   * When this run began, or null. Docker prints RFC 3339 with nanoseconds, and
   * {@code 0001-01-01T00:00:00Z} for a container that has never been started — which parses
   * perfectly and means nothing, so it is answered as null like any other absence.
   */
  private static Instant startedAt(String name, String value) {
    if (value.isEmpty() || value.startsWith("0001-01-01")) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (RuntimeException e) {
      LOG.debugf("Could not read the start time of %s from '%s'", name, value);
      return null;
    }
  }

  private static String field(String[] fields, int index) {
    return index >= fields.length ? "" : present(fields[index]);
  }

  /** A value docker really printed, or the empty string for one it did not have. */
  private static String present(String value) {
    String stripped = value == null ? "" : value.strip();
    return "<no value>".equals(stripped) ? "" : stripped;
  }

  @Override
  public OpResult start(String name, Duration timeout) {
    return op("start " + name, DockerArgv.start(runtime, name), timeout);
  }

  @Override
  public OpResult stop(String name, Duration timeout) {
    return op("stop " + name, DockerArgv.stop(runtime, name), timeout);
  }

  @Override
  public OpResult remove(String name, Duration timeout) {
    return op("remove " + name, DockerArgv.rm(runtime, name), timeout);
  }

  /**
   * The tail of a container's output, bounded while it is read.
   *
   * <p>A failed call still answers with whatever docker said, rather than with an empty tail: this
   * is the diagnosis path, and "no such container" is itself the diagnosis of a workload nothing
   * ever started. A timeout is reported as truncated, because it is: the stream was cut.
   */
  @Override
  public LogTail logsTail(String name, int lines, Duration timeout, int maxChars) {
    ContainerProcess.Result result =
        ContainerProcess.run(null, DockerArgv.logsTail(runtime, name, lines), timeout, maxChars);
    if (!succeeded(result)) {
      LOG.debugf("Could not read the logs of %s: %s", name, brief(result.output()));
    }
    return new LogTail(
        result.output() == null ? "" : result.output(), result.truncated() || result.timedOut());
  }

  @Override
  public List<String> listByLabels(Map<String, String> filters, Duration timeout) {
    return lines("the container listing", DockerArgv.psByLabels(runtime, filters), timeout);
  }

  @Override
  public OpResult ensureVolume(VolumeSpec spec, Map<String, String> labels, Duration timeout) {
    // docker's own create is idempotent for a name that exists with the same driver, so there is
    // no "does it exist" round trip to make first.
    return op(
        "create the volume " + spec.name(),
        DockerArgv.volumeCreate(runtime, spec, labels),
        timeout);
  }

  @Override
  public OpResult removeVolume(String name, Duration timeout) {
    return op("remove the volume " + name, DockerArgv.volumeRm(runtime, name), timeout);
  }

  @Override
  public List<String> listVolumesByLabels(Map<String, String> filters, Duration timeout) {
    return lines("the volume listing", DockerArgv.volumeLs(runtime, filters), timeout);
  }

  @Override
  public OpResult pull(String imageRef, Duration timeout, int maxChars) {
    ContainerProcess.Result result =
        ContainerProcess.run(null, DockerArgv.pull(runtime, imageRef), timeout, maxChars);
    if (succeeded(result)) {
      return new OpResult(true, null);
    }
    LOG.warnf("Could not pull %s: %s", imageRef, brief(result.output()));
    return new OpResult(false, result.output());
  }

  /**
   * Whether the network exists. There is no create anywhere in this service — a bridge cannot be
   * created on a swarm-initialized host at all, and a network this service invented would be one no
   * other module's containers are on.
   */
  @Override
  public boolean networkPresent(String network, Duration timeout) {
    ContainerProcess.Result result =
        ContainerProcess.run(
            null,
            DockerArgv.networkInspect(runtime, network),
            timeout,
            ContainersTimeouts.SHORT_MAX_CHARS);
    if (succeeded(result)) {
      return true;
    }
    LOG.debugf("The network %s does not answer an inspect: %s", network, brief(result.output()));
    return false;
  }

  /**
   * This process's own container id, blank when it is not in one. It reads {@code /etc/hostname}
   * rather than asking the daemon — the same shape qits-platform-deployments uses, and the reason
   * the seam gives it no timeout.
   */
  @Override
  public String selfContainerId() {
    try {
      return Files.readString(HOSTNAME_FILE).strip();
    } catch (Exception e) {
      return "";
    }
  }

  // --- the host's own stores ---------------------------------------------------------------
  //
  // Images, dangling volumes and build cache. The seam's javadoc states the rule these implement:
  // a listing that PROTECTS throws when it could not be made, a listing that only produces
  // CANDIDATES degrades to empty. Truncation counts as "could not be made" for every one of them —
  // the output bound keeps the TAIL, so a listing read short has silently lost its front, and a
  // listing missing entries is exactly how an in-use image stops being protected.

  @Override
  public DiskUsage diskUsage(Duration timeout) {
    ContainerProcess.Result result =
        ContainerProcess.run(
            null, DockerArgv.systemDf(runtime), timeout, ContainersTimeouts.SHORT_MAX_CHARS);
    if (!whole(result)) {
      LOG.warnf("Could not read the host's disk usage: %s", brief(result.output()));
      throw new IllegalStateException(
          "docker did not answer a system df: " + brief(result.output()));
    }
    return DockerGcReads.diskUsage(result.output());
  }

  @Override
  public List<ImageSummary> listImages(Duration timeout) {
    ContainerProcess.Result result =
        ContainerProcess.run(
            null, DockerArgv.imageLs(runtime), timeout, ContainersTimeouts.LISTING_MAX_CHARS);
    if (!whole(result)) {
      LOG.warnf(
          "Could not read the image listing, so it is read as empty: %s", brief(result.output()));
      return List.of();
    }
    return DockerGcReads.images(result.output());
  }

  /**
   * What every container was created from. <b>It throws</b> — see the seam's javadoc: this listing
   * is what keeps an image a container holds out of a collection's candidate set, so an empty
   * answer has to mean "no container references anything".
   */
  @Override
  public List<String> listImageReferencesInUse(Duration timeout) {
    ContainerProcess.Result result =
        ContainerProcess.run(
            null,
            DockerArgv.psImageReferences(runtime),
            timeout,
            ContainersTimeouts.LISTING_MAX_CHARS);
    if (!whole(result)) {
      LOG.warnf("Could not read what containers are using: %s", brief(result.output()));
      throw new IllegalStateException(
          "docker did not answer a container listing: " + brief(result.output()));
    }
    return DockerGcReads.linesOf(result.output());
  }

  @Override
  public OpResult removeImage(String id, Duration timeout) {
    return op("remove the image " + id, DockerArgv.imageRm(runtime, id), timeout);
  }

  @Override
  public OpResult removeImageReferences(List<String> references, Duration timeout) {
    return op(
        "remove the image " + String.join(" ", references),
        DockerArgv.imageRmRefs(runtime, references),
        timeout);
  }

  @Override
  public List<String> listDanglingVolumes(Duration timeout) {
    return lines("the dangling volume listing", DockerArgv.volumeLsDangling(runtime), timeout);
  }

  /**
   * One volume's labels and creation time. Empty for a volume docker does not have, and a throw for
   * everything else — {@link #inspect}'s rule, for {@link #inspect}'s reason.
   */
  @Override
  public Optional<VolumeDetail> inspectVolume(String name, Duration timeout) {
    ContainerProcess.Result result =
        ContainerProcess.run(
            null,
            DockerArgv.volumeInspectDetail(runtime, name),
            timeout,
            ContainersTimeouts.SHORT_MAX_CHARS);
    if (succeeded(result)) {
      return Optional.of(DockerGcReads.volumeDetail(name, result.output()));
    }
    if (result.exitCode() != NO_ANSWER && absent(result.output())) {
      return Optional.empty();
    }
    LOG.warnf("Could not inspect the volume %s: %s", name, brief(result.output()));
    throw new IllegalStateException(
        "docker did not answer an inspect of the volume " + name + ": " + brief(result.output()));
  }

  /** The containers holding a volume. <b>It throws</b>, for {@link #listImageReferencesInUse}'s reason. */
  @Override
  public List<String> listContainersUsingVolume(String volumeName, Duration timeout) {
    ContainerProcess.Result result =
        ContainerProcess.run(
            null,
            DockerArgv.psByVolume(runtime, volumeName),
            timeout,
            ContainersTimeouts.LISTING_MAX_CHARS);
    if (!whole(result)) {
      LOG.warnf(
          "Could not read what is using the volume %s: %s", volumeName, brief(result.output()));
      throw new IllegalStateException(
          "docker did not answer a container listing for the volume "
              + volumeName
              + ": "
              + brief(result.output()));
    }
    return DockerGcReads.linesOf(result.output());
  }

  @Override
  public List<String> listBuildxBuilders(Duration timeout) {
    return lines("the builder listing", DockerArgv.psBuildxBuilders(runtime), timeout);
  }

  /**
   * The host builder's cache. It is the buildx plugin on CLI 29, so it is made with
   * {@link DockerArgv#buildxEnvironment()} — the plugin writes state under {@code $DOCKER_CONFIG}
   * and this service's is read-only.
   */
  @Override
  public CacheResult pruneBuildCache(long keepStorageBytes, Duration timeout) {
    return cache(
        "prune the host build cache",
        DockerArgv.builderPrune(runtime, keepStorageBytes),
        DockerArgv.buildxEnvironment(),
        timeout,
        true);
  }

  @Override
  public CacheResult describeBuildCache(Duration timeout) {
    return cache(
        "read the host build cache",
        DockerArgv.buildxDu(runtime),
        DockerArgv.buildxEnvironment(),
        timeout,
        false);
  }

  /**
   * One builder container's own cache. <b>No buildx environment</b>: this runs {@code buildctl}
   * inside the builder, where the host's plugin state is nothing at all.
   */
  @Override
  public CacheResult pruneBuilderCache(String container, long keepStorageBytes, Duration timeout) {
    return cache(
        "prune the build cache of " + container,
        DockerArgv.buildctlPrune(runtime, container, keepStorageBytes),
        Map.of(),
        timeout,
        true);
  }

  @Override
  public CacheResult describeBuilderCache(String container, Duration timeout) {
    return cache(
        "read the build cache of " + container,
        DockerArgv.buildctlDu(runtime, container),
        Map.of(),
        timeout,
        false);
  }

  /**
   * One build-cache call, pruning or reading.
   *
   * <p>A failure carries docker's own text rather than throwing, because a build cache is a place
   * where one wedged builder must not take the run with it — the caller reports it per cache. The
   * summary lines are kept and the per-record lines are dropped; on this host a {@code du} prints
   * about two thousand of the latter.
   *
   * <p><b>The exit status decides, and nothing that was merely printed does.</b> These are the
   * calls that talk on stderr while working perfectly: CLI 29 answers every {@code --keep-storage}
   * with {@code Flag --keep-storage has been deprecated}, and this runner merges stderr into
   * stdout. Reading a line for a failure would report a working prune as broken, and the deprecated
   * spelling is the one every docker the platform runs still accepts — so the warning is tolerated
   * and lands in {@code detail} when there is nothing better to put there.
   */
  private CacheResult cache(
      String what,
      List<String> argv,
      Map<String, String> env,
      Duration timeout,
      boolean pruning) {
    ContainerProcess.Result result =
        ContainerProcess.run(null, argv, env, timeout, ContainersTimeouts.PRUNE_MAX_CHARS);
    if (!succeeded(result)) {
      LOG.warnf("Could not %s: %s", what, brief(result.output()));
      return new CacheResult(false, 0, result.output());
    }
    long bytes =
        pruning
            ? DockerGcReads.reclaimedBytes(result.output())
            : DockerGcReads.reclaimableBytes(result.output());
    String summary = DockerGcReads.cacheSummary(result.output());
    return new CacheResult(true, bytes, summary.isEmpty() ? brief(result.output()) : summary);
  }

  /**
   * A call that answered, in full. <b>Truncation is a failure here and nowhere else</b>: everything
   * above this section reads one short line or a diagnosis, where a dropped front costs detail,
   * while a listing with its front dropped is a listing that quietly lost entries.
   */
  private static boolean whole(ContainerProcess.Result result) {
    return succeeded(result) && !result.truncated();
  }

  // --- the shapes every call above is one of ------------------------------------------------

  /** A call whose whole answer is "did it work". */
  private OpResult op(String what, List<String> argv, Duration timeout) {
    ContainerProcess.Result result =
        ContainerProcess.run(null, argv, timeout, ContainersTimeouts.SHORT_MAX_CHARS);
    if (succeeded(result)) {
      return new OpResult(true, null);
    }
    LOG.warnf("Could not %s: %s", what, brief(result.output()));
    return new OpResult(false, result.output());
  }

  /** A listing. Degrades to nothing with a warning — see the class javadoc. */
  private List<String> lines(String what, List<String> argv, Duration timeout) {
    ContainerProcess.Result result =
        ContainerProcess.run(null, argv, timeout, ContainersTimeouts.SHORT_MAX_CHARS);
    if (!succeeded(result)) {
      LOG.warnf("Could not read %s, so it is read as empty: %s", what, brief(result.output()));
      return List.of();
    }
    return (result.output() == null ? "" : result.output())
        .lines()
        .map(String::strip)
        .filter(line -> !line.isEmpty())
        .toList();
  }

  private static boolean succeeded(ContainerProcess.Result result) {
    return result.exitCode() == 0 && !result.timedOut();
  }

  private static boolean absent(String output) {
    String text = (output == null ? "" : output).toLowerCase(Locale.ROOT);
    return ABSENT_MARKERS.stream().anyMatch(text::contains);
  }

  private static String lastLine(String output) {
    return (output == null ? "" : output)
        .lines()
        .map(String::strip)
        .filter(line -> !line.isEmpty())
        .reduce((first, second) -> second)
        .orElse("");
  }

  /**
   * One docker message, rendered for a log line: control characters replaced and the length capped.
   * Docker's output is not this service's — an image an owner chose printed some of it — so a value
   * that could carry a newline could forge a second log entry.
   */
  private static String brief(String message) {
    if (message == null || message.isBlank()) {
      return "no detail";
    }
    StringBuilder out = new StringBuilder(Math.min(message.length(), BRIEF_CHARS));
    message
        .strip()
        .codePoints()
        .limit(BRIEF_CHARS)
        .forEach(cp -> out.appendCodePoint(Character.isISOControl(cp) ? ' ' : cp));
    return out.toString();
  }
}
