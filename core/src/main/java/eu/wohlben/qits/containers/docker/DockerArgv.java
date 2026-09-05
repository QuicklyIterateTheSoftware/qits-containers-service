package eu.wohlben.qits.containers.docker;

import eu.wohlben.qits.containers.spec.ContainerLabels;
import eu.wohlben.qits.containers.spec.ContainerSpec;
import eu.wohlben.qits.containers.spec.ContainersIdentifiers;
import eu.wohlben.qits.containers.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.spec.VolumeSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every docker command line this service will ever run, as pure functions.
 *
 * <p>No I/O, no {@link ProcessBuilder}, no config, no clock — a spec goes in and a {@code List
 * <String>} comes out. That is what lets the argvs be asserted <b>element for element</b> in a
 * docker-free suite, and it is why they are assembled here rather than inside the driver: the argv
 * <b>is</b> the sandbox, and a flag lost in a refactor is invisible everywhere else until it is
 * invisible in production.
 *
 * <p><b>The belts run here too.</b> Validation at the API layer is the first checkpoint and this is
 * the second, unconditionally, on every value that reaches an element — because the API layer is one
 * loosened check away from being no checkpoint at all, and the point of two is that neither has to
 * be trusted alone.
 *
 * <p><b>{@code --rm} appears in nothing here, ever.</b> A self-removing container races the {@code
 * docker logs} capture that is the only diagnosis a container which died on its first breath can
 * offer, and it would delete a container whose registry row still names it — which is the one state
 * the adoption rule exists to make impossible. Every teardown is an explicit {@link #rm}.
 */
public final class DockerArgv {

  /**
   * The host docker socket, on both sides of the bind. It is a constant rather than a parameter for
   * the reason {@link ContainerSpec#hostDockerSocket()} is a boolean: what gets mounted must not be
   * something a caller chooses.
   */
  public static final String DOCKER_SOCKET = "/var/run/docker.sock";

  /**
   * The container state, as one line: {@code <status>/<health>}. Copied from
   * {@code DockerDeploymentDriver.observe} unchanged, including the {@code if}/{@code else} —
   * a bare {@code {{.State.Health.Status}}} prints Go's {@code <no value>} for an image that
   * declares no healthcheck, and {@code <no value>} would read back as a health state no container
   * has. The {@code else none} arm answers the absence as an absence.
   */
  public static final String STATE_FORMAT =
      "{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}";

  /** When the current run of the container began — what an adoption records as its start. */
  public static final String STARTED_AT_FORMAT = "{{.State.StartedAt}}";

  /**
   * A volume's labels, one {@code k=v} per line. <b>Ranged rather than indexed</b>, the same
   * measured reason the state format carries its {@code if}: on docker 29.5.3 an {@code index} of an
   * empty label map prints {@code <no value>}, which reads back as a label nothing set. A range over
   * an empty map prints nothing, which is what an unlabelled volume means.
   */
  public static final String VOLUME_LABELS_FORMAT =
      "{{range $k, $v := .Labels}}{{$k}}={{$v}}{{\"\\n\"}}{{end}}";

  private DockerArgv() {}

  /**
   * The whole {@code docker run}. Detached, never self-removing, and in a fixed order so the list is
   * assertable.
   *
   * <p>{@code labels} is this service's own namespace ({@link ContainerLabels#forContainer}); the
   * spec's {@code extraLabels} are merged in and the union is rendered sorted. The two cannot
   * collide — an owner may not write inside {@value ContainerLabels#NAMESPACE} — so the merge needs
   * no precedence rule and has none.
   */
  public static List<String> run(
      String runtimeBinary,
      String name,
      ContainerSpec spec,
      Map<String, String> labels,
      LifecyclePolicy policy) {
    return run(runtimeBinary, name, spec, labels, policy, "");
  }

  /**
   * The same run, told which group owns the host's docker socket.
   *
   * <p>{@code socketGroup} is rendered as a {@code --group-add} <b>only beside the socket bind</b>,
   * and it is blank for every workload that did not ask for one. It exists because a bind a workload
   * cannot open is not a privilege, it is a puzzle: the socket is {@code srw-rw----} owned by the
   * host's docker group, so a container running as anybody but root can hold the mount and still be
   * refused by the kernel on {@code connect}. qits-ci never met this — its opted-in steps run as the
   * image's own root — and qits-workspaces' admin workspaces do, because a workspace container runs
   * as the host uid.
   *
   * <p><b>It is not a spec field, deliberately.</b> The value is the socket's own group, read off the
   * socket by the process that holds it, so no caller can name a group and nothing can join one
   * without the bind that makes it mean anything. A spec field would be exactly the assembled
   * privilege {@link ContainerSpec} exists to prevent.
   */
  public static List<String> run(
      String runtimeBinary,
      String name,
      ContainerSpec spec,
      Map<String, String> labels,
      LifecyclePolicy policy,
      String socketGroup) {
    ContainersIdentifiers.requireContainerName(name);
    ContainersIdentifiers.requireImage(spec.image());
    ContainersIdentifiers.requireNetwork(spec.network());

    List<String> argv = new ArrayList<>();
    argv.add(runtimeBinary);
    argv.add("run");
    argv.add("-d");
    // tini as PID 1, beside -d because both say what SHAPE of run this is rather than what it
    // contains. Only when the spec asked: PID 1 in a container inherits every orphan and reaps
    // none of them unless it is written to, so a container hosting a long-lived session that
    // spawns builds fills up with zombies — and a container that runs one process and exits pays
    // for a second process it never needed.
    if (spec.init()) {
      argv.add("--init");
    }
    argv.add("--name");
    argv.add(name);
    argv.add("--network");
    argv.add(spec.network());
    for (String alias : spec.aliases()) {
      argv.add("--network-alias");
      argv.add(ContainersIdentifiers.requireAlias(alias));
    }
    for (String addHost : spec.addHosts()) {
      argv.add("--add-host=" + ContainersIdentifiers.requireAddHost(addHost));
    }
    // Who the first process is, rendered only when the spec named somebody — an unset user means
    // the image's own default, and the two must stay different statements. It is decided HERE
    // because it cannot be decided later: a container run with --cap-drop=ALL has no CAP_SETUID and
    // no CAP_SETGID, so `su` inside its script fails whatever the script does. Measured 2026-08-12
    // on qits-ci's own step containers, where the adduser/chown/su pattern could never have worked.
    if (!spec.user().isEmpty()) {
      argv.add("--user");
      argv.add(ContainersIdentifiers.requireUser(spec.user()));
    }
    // Sorted, because the whole argv is asserted literally and a map's iteration order is not a
    // thing to assert against.
    for (Map.Entry<String, String> label : merged(labels, spec.extraLabels()).entrySet()) {
      argv.add("--label");
      argv.add(label.getKey() + "=" + label.getValue());
    }
    // The sandbox. Each flag is rendered only when the spec asked for it, so "unset" stays a
    // different statement from "off" — a spec that says nothing must not silently acquire a fence,
    // and a spec that dropped one must be readable as having dropped it.
    ContainerSpec.SecurityPosture security = spec.security();
    if (security.noNewPrivileges()) {
      argv.add("--security-opt=no-new-privileges");
    }
    if (security.capDropAll()) {
      argv.add("--cap-drop=ALL");
    }
    if (security.memory() != null) {
      argv.add("--memory");
      argv.add(security.memory());
    }
    if (security.memorySwap() != null) {
      argv.add("--memory-swap");
      argv.add(security.memorySwap());
    }
    if (security.pidsLimit() != null) {
      argv.add("--pids-limit");
      argv.add(String.valueOf(security.pidsLimit()));
    }
    if (security.cpus() != null) {
      argv.add("--cpus");
      argv.add(security.cpus());
    }
    // Not a container limit but a host-survival hint: a spawned workload is more expendable than the
    // platform, so under memory pressure the kernel reaps it first. A reaped workload retries; a
    // crashed host does not. Consumers set the value (ci highest, then agents, then workspaces).
    if (security.oomScoreAdj() != null) {
      argv.add("--oom-score-adj");
      argv.add(String.valueOf(security.oomScoreAdj()));
    }
    // EPHEMERAL renders nothing here — see LifecyclePolicy, where the reason is argued.
    if (policy.restartsUnlessStopped()) {
      argv.add("--restart");
      argv.add("unless-stopped");
    }
    for (ContainerSpec.VolumeMount mount : spec.volumeMounts()) {
      argv.add("-v");
      argv.add(mount.volumeName() + ":" + mount.containerPath());
    }
    for (ContainerSpec.SharedMount mount : spec.sharedMounts()) {
      argv.add("-v");
      argv.add(mount.sharedName() + ":" + mount.containerPath());
    }
    // The one bind, and the only privilege escalation this service can perform. A container holding
    // it is root-equivalent on the host, so it is here because the spec DECLARED it — nothing else
    // in this method can add a mount, and no path anywhere comes from a caller.
    if (spec.hostDockerSocket()) {
      argv.add("-v");
      argv.add(DOCKER_SOCKET + ":" + DOCKER_SOCKET);
      // …and the group that makes the bind usable, when the deployment could read one off the
      // socket. It is rendered here and nowhere else, so a workload that did not ask for the socket
      // joins no group whatever the deployment holds.
      if (socketGroup != null && !socketGroup.isBlank()) {
        argv.add("--group-add");
        argv.add(ContainersIdentifiers.requireGroup(socketGroup.trim()));
      }
    }
    for (Map.Entry<String, String> variable : new TreeMap<>(spec.env()).entrySet()) {
      argv.add("-e");
      argv.add(ContainersIdentifiers.requireEnvKey(variable.getKey()) + "=" + variable.getValue());
    }
    // Docker's --entrypoint takes one word; a longer list spends its tail as leading arguments after
    // the image, which is the CLI's own convention (`--entrypoint /bin/sh img -c '…'`).
    List<String> entrypoint = spec.entrypoint();
    if (!entrypoint.isEmpty()) {
      argv.add("--entrypoint");
      argv.add(entrypoint.getFirst());
    }
    argv.add(spec.image());
    if (entrypoint.size() > 1) {
      argv.addAll(entrypoint.subList(1, entrypoint.size()));
    }
    argv.addAll(spec.args());
    return List.copyOf(argv);
  }

  /**
   * How the platform's own buildkitd writes its configuration and comes up. The file arrives as an
   * environment value and becomes {@code /etc/buildkit/buildkitd.toml} inside the container —
   * qits-ci's {@code BOOTSTRAP} shape, and for the same reason: this service shares no volume with
   * the container and the wire between them is an argv, so a small file can only be a value the
   * container writes for itself. Zero interpolation: the value is a variable the shell reads, never
   * a word in this string.
   *
   * <p>Two listen addresses, each with its own reader. {@code tcp://0.0.0.0:1234} binds the
   * network the container is on — the platform network, where every step container dials the
   * {@code qits-buildkitd} alias; 1234 is buildkitd's own conventional port, spelled once here and
   * once in the injected address default. {@code unix:///run/buildkit/buildkitd.sock} is
   * buildkitd's DEFAULT socket, and it is what the build-cache sweep's {@code docker exec buildctl
   * prune}/{@code du} finds — an exec'd buildctl knows no tcp address, and without the socket the
   * sweep answered {@code dial unix …: no such file or directory} on the live platform (measured
   * 2026-09-05, the first gc dry-run against the owned builder).
   */
  public static final String BUILDKITD_BOOTSTRAP =
      """
      set -e
      mkdir -p /etc/buildkit
      printf '%s' "$BUILDKITD_TOML" > /etc/buildkit/buildkitd.toml
      exec buildkitd --addr unix:///run/buildkit/buildkitd.sock --addr tcp://0.0.0.0:1234
      """;

  /** Where buildkitd keeps its content store — what the state volume is mounted over. */
  public static final String BUILDKITD_STATE_PATH = "/var/lib/buildkit";

  /**
   * The label the platform builder's whole configuration is stamped into, as a hash. The boot pass
   * compares it to decide whether the running container IS the configured one — the image pin, the
   * rendered toml, the bounds and the network all live in the stamp, so changing any of them
   * replaces the container (the state volume rides across). A builder with no stamp — the
   * bootstrap's host-net one, or anything hand-made — reads as "not the configured one" and is
   * replaced too, which is exactly the cutover the bootstrap hands over with.
   */
  public static final String BUILDKITD_STAMP_LABEL = "qits.containers.buildkit.config";

  /**
   * The one privileged {@code docker run} this service makes, and the reason it is its own argv
   * rather than a {@link ContainerSpec}: {@code --privileged} must never be something a spec can
   * express — a caller-assembled privilege is exactly what {@link ContainerSpec}'s shape exists to
   * prevent — and buildkitd cannot mount overlayfs for its build sandboxes without it. The whole
   * argv is this method's, with nothing caller-shaped in it: the name is the
   * {@link ContainersIdentifiers#PLATFORM_BUILDER} constant, the image and the toml come from this
   * service's own configuration, and both bounds are rendered unconditionally.
   *
   * <p>{@code --restart unless-stopped} because the builder outlives this service and a dockerd
   * restart — the {@code EXPLICIT} lifecycle's rendering, without the row (the builder is platform
   * infrastructure in {@code SharedResources}' sense: ensured at boot, claimed by nobody).
   * {@code --oom-score-adj} positive for the same reason a step container's is: under memory
   * pressure the kernel should take the build plane before a platform service.
   */
  public static List<String> runBuildkitd(
      String runtimeBinary,
      String image,
      String network,
      String stateVolume,
      String toml,
      String configStamp,
      long pidsLimit,
      int oomScoreAdj) {
    ContainersIdentifiers.requireImage(image);
    ContainersIdentifiers.requireNetwork(network);
    ContainersIdentifiers.requireVolumeName(stateVolume);
    return List.of(
        runtimeBinary,
        "run",
        "-d",
        "--name",
        ContainersIdentifiers.PLATFORM_BUILDER,
        "--network",
        network,
        "--network-alias",
        ContainersIdentifiers.PLATFORM_BUILDER,
        "--label",
        BUILDKITD_STAMP_LABEL + "=" + (configStamp == null ? "" : configStamp),
        "--privileged",
        "--restart",
        "unless-stopped",
        "--pids-limit",
        String.valueOf(pidsLimit),
        "--oom-score-adj",
        String.valueOf(oomScoreAdj),
        "-v",
        stateVolume + ":" + BUILDKITD_STATE_PATH,
        "-e",
        "BUILDKITD_TOML=" + (toml == null ? "" : toml),
        "--entrypoint",
        "/bin/sh",
        image,
        "-c",
        BUILDKITD_BOOTSTRAP);
  }

  /**
   * The configuration stamp a container was started with — {@value #BUILDKITD_STAMP_LABEL}. The
   * boot pass asks "is the running builder the CONFIGURED builder", and the stamp is the whole
   * answer: image, toml, bounds and network in one hash, so any moved value replaces the container
   * rather than adopting one that no longer matches what a deployment says.
   */
  public static List<String> inspectBuildkitdStamp(String runtimeBinary, String name) {
    return List.of(
        runtimeBinary,
        "inspect",
        "--format",
        "{{index .Config.Labels \"" + BUILDKITD_STAMP_LABEL + "\"}}",
        ContainersIdentifiers.requireContainerName(name));
  }

  /** One observation of the container's state — see {@link #STATE_FORMAT}. */
  public static List<String> inspectState(String runtimeBinary, String name) {
    return List.of(
        runtimeBinary,
        "inspect",
        "--format",
        STATE_FORMAT,
        ContainersIdentifiers.requireContainerName(name));
  }

  /** When the container's current run started. */
  public static List<String> inspectStartedAt(String runtimeBinary, String name) {
    return List.of(
        runtimeBinary,
        "inspect",
        "--format",
        STARTED_AT_FORMAT,
        ContainersIdentifiers.requireContainerName(name));
  }

  /**
   * The whole of one observation, in one line: {@code <id>|<status>/<health>|<startedAt>}.
   *
   * <p>It is one call rather than three because the observation pass costs one {@code docker
   * inspect} <b>per row, per pass, forever</b>, and {@code ContainersDriver.Observed} carries all
   * three fields.
   *
   * <p><b>It is not {@code "{{.Id}}|" + STATE_FORMAT + "|" + STARTED_AT_FORMAT}, and the reason is
   * measured rather than stylistic.</b> On docker 29.7.2 that composition fails on any container
   * without a healthcheck, with {@code map has no entry for key "Health"} — while
   * {@link #STATE_FORMAT} on its own answers {@code running/none} perfectly. The difference is
   * {@code .Id}: the CLI renders a template against the typed inspect object first and falls back
   * to the <b>raw JSON map</b> when that fails, and the Go field is named {@code ID} while the JSON
   * key is {@code Id}. So asking for the id at all moves the whole template onto the map path,
   * where {@code {{if .State.Health}}} is an error rather than a false — a missing map key is not a
   * zero value in Go's templates, which is the same lesson the volume label format learned from
   * {@code index}.
   *
   * <p>{@code index} is what a map path has instead: {@code index .State "Health"} answers the zero
   * value for a container that declares no check, so the {@code else} arm renders and the health
   * reads {@code none}. Measured both ways on docker 29.7.2 — {@code running/none} for a container
   * without a check, {@code running/healthy} for one with.
   *
   * <p>The two single-field spellings beside it stay: they are the typed-path forms, they are what
   * a caller that needs one field should use, and this one is deliberately not built out of them.
   */
  public static final String OBSERVATION_FORMAT =
      "{{.Id}}|{{.State.Status}}/"
          + "{{if index .State \"Health\"}}{{(index .State \"Health\").Status}}{{else}}none{{end}}"
          + "|{{.State.StartedAt}}";

  /** One inspect answering everything an observation records — see {@link #OBSERVATION_FORMAT}. */
  public static List<String> inspectObservation(String runtimeBinary, String name) {
    return List.of(
        runtimeBinary,
        "inspect",
        "--format",
        OBSERVATION_FORMAT,
        ContainersIdentifiers.requireContainerName(name));
  }

  /**
   * Start the container that already carries this name. It takes no spec and no flag: everything a
   * container is was decided by the {@link #run} that created it, and a start that could re-state
   * any of it would be a second place for the sandbox to be written.
   */
  public static List<String> start(String runtimeBinary, String name) {
    return List.of(runtimeBinary, "start", ContainersIdentifiers.requireContainerName(name));
  }

  /** Stop it, leaving it restartable. */
  public static List<String> stop(String runtimeBinary, String name) {
    return List.of(runtimeBinary, "stop", ContainersIdentifiers.requireContainerName(name));
  }

  /** Remove it, running or not. Every teardown ends here, and never at a {@code --rm}. */
  public static List<String> rm(String runtimeBinary, String name) {
    return List.of(runtimeBinary, "rm", "-f", ContainersIdentifiers.requireContainerName(name));
  }

  /** A bounded tail of the container's own output — the diagnosis, captured before any removal. */
  public static List<String> logsTail(String runtimeBinary, String name, int lines) {
    if (lines <= 0) {
      throw new IllegalArgumentException("Invalid log tail: " + lines + " lines");
    }
    return List.of(
        runtimeBinary,
        "logs",
        "--tail",
        String.valueOf(lines),
        ContainersIdentifiers.requireContainerName(name));
  }

  /**
   * Container ids matching every one of the label filters.
   *
   * <p>This narrows a listing; it never decides a removal. Which containers may be touched is a
   * question the registry rows answer, and a host-wide label sweep is the regression this repository
   * exists to remove.
   */
  public static List<String> psByLabels(String runtimeBinary, Map<String, String> filters) {
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "ps", "-aq"));
    for (Map.Entry<String, String> filter : new TreeMap<>(filters).entrySet()) {
      argv.add("--filter");
      argv.add("label=" + filter.getKey() + "=" + filter.getValue());
    }
    return List.copyOf(argv);
  }

  /** Fetch the image, so "the registry has no such image" is its own outcome rather than a run. */
  public static List<String> pull(String runtimeBinary, String imageRef) {
    return List.of(runtimeBinary, "pull", ContainersIdentifiers.requireImage(imageRef));
  }

  /** Create the named volume, labelled. Creating one that exists is docker's own no-op. */
  public static List<String> volumeCreate(
      String runtimeBinary, VolumeSpec spec, Map<String, String> labels) {
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "volume", "create"));
    for (Map.Entry<String, String> label : merged(labels, spec.extraLabels()).entrySet()) {
      argv.add("--label");
      argv.add(label.getKey() + "=" + label.getValue());
    }
    argv.add(spec.name());
    return List.copyOf(argv);
  }

  /** Remove the named volume. Only ever an explicit ask — nothing sweeps a volume. */
  public static List<String> volumeRm(String runtimeBinary, String name) {
    return List.of(
        runtimeBinary, "volume", "rm", ContainersIdentifiers.requireVolumeName(name));
  }

  /** Volume names matching every one of the label filters. */
  public static List<String> volumeLs(String runtimeBinary, Map<String, String> filters) {
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "volume", "ls", "-q"));
    for (Map.Entry<String, String> filter : new TreeMap<>(filters).entrySet()) {
      argv.add("--filter");
      argv.add("label=" + filter.getKey() + "=" + filter.getValue());
    }
    return List.copyOf(argv);
  }

  /** A volume's labels, one per line — see {@link #VOLUME_LABELS_FORMAT}. */
  public static List<String> volumeInspectLabels(String runtimeBinary, String name) {
    return List.of(
        runtimeBinary,
        "volume",
        "inspect",
        "--format",
        VOLUME_LABELS_FORMAT,
        ContainersIdentifiers.requireVolumeName(name));
  }

  /**
   * Ask whether the network exists. <b>There is no {@code network create} here.</b> Creating a
   * bridge is refused on a swarm-initialized host, and a network this service invented would be one
   * no other module's containers are on — so a missing network is something a deployment answers,
   * not something an orchestrator papers over.
   */
  public static List<String> networkInspect(String runtimeBinary, String network) {
    return List.of(
        runtimeBinary, "network", "inspect", ContainersIdentifiers.requireNetwork(network));
  }

  // --- garbage collection -------------------------------------------------------------------
  //
  // The host's own stores, which are NOT rows. Everything above this line is addressed to a
  // container a registry row names; the argvs below read and remove images, dangling volumes and
  // build cache, none of which any row has ever named. What keeps them inside this repository's
  // first invariant is that the DECIDING is the caller's and is made of keep rules — see control's
  // ImageGc and VolumeGc — and that nothing here is a `prune -a`, a `rm -f` or a label sweep.

  /**
   * One line per store of {@code docker system df}:
   * {@code <type>|<count>|<active>|<size>|<reclaimable>}.
   *
   * <p><b>Not {@code {{json .}}}</b>, for the reason every other read here is a template: this
   * module binds no docker output to a record, so a JSON shape would be a second serialization
   * contract to register for reflection and to keep in step with a CLI nobody here versions.
   * Measured on docker 29.7.2 — the four types are {@code Images}, {@code Containers},
   * {@code Local Volumes} and {@code Build Cache}, and the sizes are human ({@code 308.3GB}), which
   * is what the reader parses.
   */
  public static final String DISK_USAGE_FORMAT =
      "{{.Type}}|{{.TotalCount}}|{{.Active}}|{{.Size}}|{{.Reclaimable}}";

  /** What the host's four stores hold — the before and after of a collection run. */
  public static List<String> systemDf(String runtimeBinary) {
    return List.of(runtimeBinary, "system", "df", "--format", DISK_USAGE_FORMAT);
  }

  /**
   * One line per {@code repository:tag}: {@code <id>|<repository>|<tag>|<createdAt>|<size>}.
   *
   * <p>An image with two tags prints twice under one id, and an untagged one prints once with
   * {@code <none>} in both fields — which is what "dangling" is. The reader folds the lines back
   * onto the id.
   *
   * <p><b>{@code .Digest} is deliberately not asked for.</b> On the containerd image store it
   * prints the manifest digest, which is the same value as {@code .ID} — measured on docker 29.7.2
   * — so a column for it would carry no information and would read like a repo digest, which it is
   * not.
   */
  public static final String IMAGE_FORMAT =
      "{{.ID}}|{{.Repository}}|{{.Tag}}|{{.CreatedAt}}|{{.Size}}";

  /**
   * Every image the host holds, tags folded per id by the reader.
   *
   * <p><b>{@code --all}, and it took a live run to learn why.</b> The reasoning this call shipped
   * with was the classic image store's: a plain {@code image ls} hides intermediate layers and
   * shows dangling top-level images, so {@code --all} would only add rows nobody may remove. On the
   * <b>containerd</b> image store that is not true. Measured on the platform's own host, docker 29
   * with containerd: a plain {@code image ls} printed <b>zero</b> {@code <none>} rows while
   * {@code -a} printed 62 and {@code -f dangling=true} 55 — so 55 dangling images, about 12 GB,
   * were invisible to two whole collection runs.
   *
   * <p>What {@code --all} can add on a classic store is a layer another image is built on. It has
   * no tags, so it reaches the candidate set as a dangling image — and docker refuses to remove one
   * with {@code image has dependent child images}, which lands on the caller's {@code failed} list
   * where a person can read it. That is the safe direction, and it is why this is not paid for with
   * an {@code -f}: a parent that really is garbage becomes removable the run after its last child
   * goes.
   */
  public static List<String> imageLs(String runtimeBinary) {
    return List.of(
        runtimeBinary, "image", "ls", "--all", "--no-trunc", "--format", IMAGE_FORMAT);
  }

  /**
   * What every container on the host — running or not — was created from.
   *
   * <p><b>It is {@code .Image} and not {@code .ImageID}, and that is measured rather than
   * preferred.</b> Docker 29.7.2 answers {@code can't evaluate field ImageID in type
   * *formatter.ContainerContext} — the ps formatter has no such field, whatever the docs of a
   * neighbouring command suggest. {@code .Image} prints what the container was created from: a
   * reference ({@code registry:8080/qits/qits-ci:<sha>}), or a bare {@code sha256:} id when the
   * reference no longer resolves. The reader matches BOTH shapes against an image, and a line it
   * cannot read protects nothing — which is why the caller refuses to collect at all when this
   * listing did not answer whole.
   */
  public static List<String> psImageReferences(String runtimeBinary) {
    return List.of(runtimeBinary, "ps", "-a", "--no-trunc", "--format", "{{.Image}}");
  }

  /**
   * Remove one <b>untagged</b> image, by id and never forced.
   *
   * <p>No {@code -f}, here or in {@link #imageRmRefs}: a forced remove untags an image other
   * references still name and can take one a container is holding. The refusal docker answers
   * instead is the last belt under the keep rules, and it lands on the caller's {@code failed} list
   * where a person can read it.
   *
   * <p><b>An id is only the right argument for a DANGLING image</b> — measured on the platform's
   * first real collection run, where 20 of 32 candidates came back as {@code conflict: unable to
   * delete <id> (must be forced) - image is referenced in multiple repositories}. Docker refuses to
   * delete an id that more than one reference names, and two tags of the SAME repository are two
   * references ({@code projects-daemon:2026.820.154053} beside {@code projects-daemon:863933e…}).
   * A tagged image is removed by {@link #imageRmRefs} instead.
   *
   * <p>And there is no {@code image prune} anywhere in this file. A prune is docker deciding; the
   * whole point of the sweep above it is that this service decides, image by image, with the rows
   * and the pins in front of it.
   */
  public static List<String> imageRm(String runtimeBinary, String id) {
    return List.of(runtimeBinary, "image", "rm", ContainersIdentifiers.requireImageId(id));
  }

  /**
   * Remove a tagged image by <b>every reference that names it</b>, in one call.
   *
   * <p>Each argument untags; the last one takes the image itself, which is docker's own arithmetic
   * and is why this is not a forced delete under another name. It is one call rather than one per
   * tag so the image cannot be left half-untagged by a failure between two calls — and if docker
   * refuses partway, the next run sees whatever references are left and asks again.
   *
   * <p><b>The references are the tags {@code image ls} just printed, not anything a caller sent</b>,
   * and they are belt-checked as image references all the same: this is the one removal in the
   * service that takes a name, so the check that a name cannot open with a {@code -} or carry
   * whitespace is doing real work.
   */
  public static List<String> imageRmRefs(String runtimeBinary, List<String> references) {
    if (references == null || references.isEmpty()) {
      throw new IllegalArgumentException("Invalid image references: none");
    }
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "image", "rm"));
    for (String reference : references) {
      argv.add(ContainersIdentifiers.requireImage(reference));
    }
    return List.copyOf(argv);
  }

  /** Volumes no container references — the only volumes a collection may even consider. */
  public static List<String> volumeLsDangling(String runtimeBinary) {
    return List.of(runtimeBinary, "volume", "ls", "-q", "--filter", "dangling=true");
  }

  /**
   * When docker made the volume, then its labels one {@code k=v} per line.
   *
   * <p>Two facts in one call, separated by a newline the template emits, because a collection asks
   * both of every candidate. {@code CreatedAt} is RFC 3339 with an offset
   * ({@code 2026-08-11T17:03:01+02:00}) — measured — and the labels are ranged rather than indexed
   * for the reason {@link #VOLUME_LABELS_FORMAT} gives.
   */
  public static final String VOLUME_DETAIL_FORMAT =
      "{{.CreatedAt}}{{\"\\n\"}}{{range $k, $v := .Labels}}{{$k}}={{$v}}{{\"\\n\"}}{{end}}";

  /** One volume's creation time and labels — see {@link #VOLUME_DETAIL_FORMAT}. */
  public static List<String> volumeInspectDetail(String runtimeBinary, String name) {
    return List.of(
        runtimeBinary,
        "volume",
        "inspect",
        "--format",
        VOLUME_DETAIL_FORMAT,
        ContainersIdentifiers.requireVolumeName(name));
  }

  /**
   * The containers that reference this volume, by name. Dangling already says there are none; this
   * is asked of a builder's state volume anyway, because the two questions have different answers
   * often enough to matter — a builder container that exists and is stopped holds its state volume
   * without keeping it out of a dangling listing.
   */
  public static List<String> psByVolume(String runtimeBinary, String volumeName) {
    return List.of(
        runtimeBinary,
        "ps",
        "-a",
        "--filter",
        "volume=" + ContainersIdentifiers.requireVolumeName(volumeName),
        "--format",
        "{{.Names}}");
  }

  /** The builder containers on this host. Docker's name filter is a substring match. */
  public static List<String> psBuildxBuilders(String runtimeBinary) {
    return List.of(
        runtimeBinary,
        "ps",
        "-a",
        "--filter",
        "name=" + ContainersIdentifiers.BUILDER_PREFIX,
        "--format",
        "{{.Names}}");
  }

  /**
   * Prune the host builder's cache down to {@code keepStorageBytes}.
   *
   * <p><b>{@code --all}, because without it a prune only considers DANGLING cache records.</b>
   * Measured on the platform's second real collection run: the host prune freed 4 GB of a 40 GB
   * cache while {@code buildx du} reported 18 GB reclaimable. {@code --all} widens the candidate
   * set to every record no build is using; the keep-storage still decides how much survives, LRU,
   * and a record an in-flight build holds is untouchable either way. So the flag changes what may
   * be considered and never what may be taken.
   *
   * <p><b>{@code --keep-storage} takes BYTES here and megabytes in {@link #buildctlPrune}</b>, which
   * is the one trap in this family. Docker 29.7.2 renamed the flag to {@code --reserved-space} and
   * keeps {@code --keep-storage} as a deprecated alias that still takes bytes — it prints
   * {@code Flag --keep-storage has been deprecated} and works. The alias is what is spelled here,
   * because it is the flag every docker the platform runs understands.
   */
  public static List<String> builderPrune(String runtimeBinary, long keepStorageBytes) {
    return List.of(
        runtimeBinary,
        "builder",
        "prune",
        "--all",
        "--force",
        "--keep-storage",
        String.valueOf(ContainersIdentifiers.requireKeepStorageBytes(keepStorageBytes)));
  }

  /** What the host builder's cache holds. A read; it removes nothing. */
  public static List<String> buildxDu(String runtimeBinary) {
    return List.of(runtimeBinary, "buildx", "du");
  }

  /** Where the buildx plugin is told to keep its own state — see {@link #buildxEnvironment}. */
  public static final String BUILDX_CONFIG_DIR = "/tmp/qits-buildx";

  /**
   * The environment the two host build-cache calls are made with.
   *
   * <p><b>Measured on the platform's first real collection run:</b> {@code docker builder prune}
   * answered {@code mkdir /work/config/buildx: permission denied}. The buildx plugin keeps state
   * under {@code $DOCKER_CONFIG}, and this service is deployed with that pointed at a READ-ONLY
   * config volume — the one carrying its registry credentials — so the plugin cannot create its
   * directory and the prune fails before it starts.
   *
   * <p>{@code BUILDX_CONFIG} moves that state and nothing else: {@code DOCKER_CONFIG} stays exactly
   * as the deployment set it, so the credentials are still found. The directory is left for buildx
   * to create, which it can, because {@value #BUILDX_CONFIG_DIR} is under a writable {@code /tmp}
   * in every container this service runs in.
   *
   * <p>It is a map beside the argvs rather than a variable set on this process, because a
   * {@code setenv} would apply to every docker call this service ever makes — including the
   * {@code exec}s into a builder, where it would mean nothing, and any future call where it would
   * mean something nobody decided.
   */
  public static Map<String, String> buildxEnvironment() {
    return Map.of("BUILDX_CONFIG", BUILDX_CONFIG_DIR);
  }

  /**
   * Prune one builder container's own cache, from inside it.
   *
   * <p><b>This is the only {@code docker exec} in the service, and both its words are constants.</b>
   * The container is a name a {@code ps} filtered on {@link ContainersIdentifiers#BUILDER_PREFIX} answered, re-
   * checked here by {@link ContainersIdentifiers#requireBuilderContainer}; the command is
   * {@code buildctl prune} and a number. Nothing an owner sends reaches it, and no caller can name
   * the container or the command — which is what keeps {@code exec} from becoming a general
   * capability of this service.
   *
   * <p><b>buildctl's {@code --keep-storage} is in MEGABYTES</b>, measured against the buildctl
   * inside a live builder: {@code --keep-storage float  Keep data below this limit (in MB)}. Handing
   * it the byte count the wire carries would ask a builder to keep a million times what was meant,
   * which prunes nothing and reads like a working call. The conversion rounds UP, so a rounding
   * error keeps cache rather than deleting it.
   */
  public static List<String> buildctlPrune(
      String runtimeBinary, String container, long keepStorageBytes) {
    return List.of(
        runtimeBinary,
        "exec",
        ContainersIdentifiers.requireBuilderContainer(container),
        "buildctl",
        "prune",
        "--keep-storage",
        String.valueOf(keepStorageMegabytes(keepStorageBytes)));
  }

  /** What one builder container's cache holds. A read; it removes nothing. */
  public static List<String> buildctlDu(String runtimeBinary, String container) {
    return List.of(
        runtimeBinary,
        "exec",
        ContainersIdentifiers.requireBuilderContainer(container),
        "buildctl",
        "du");
  }

  /** Bytes as the whole megabytes buildctl asks for, rounded up — see {@link #buildctlPrune}. */
  static long keepStorageMegabytes(long keepStorageBytes) {
    long bytes = ContainersIdentifiers.requireKeepStorageBytes(keepStorageBytes);
    return (bytes + MEGABYTE - 1) / MEGABYTE;
  }

  /** buildctl's megabyte, which is the decimal one its own help text means. */
  private static final long MEGABYTE = 1_000_000L;

  /**
   * This service's labels and the owner's, in one sorted map. The owner's keys are re-checked here
   * rather than trusted from the spec — the second checkpoint, on the value that would forge a
   * namespace label.
   */
  private static Map<String, String> merged(
      Map<String, String> labels, Map<String, String> extraLabels) {
    Map<String, String> all = new TreeMap<>(labels == null ? Map.of() : labels);
    all.putAll(ContainersIdentifiers.requireExtraLabels(extraLabels));
    return all;
  }
}
