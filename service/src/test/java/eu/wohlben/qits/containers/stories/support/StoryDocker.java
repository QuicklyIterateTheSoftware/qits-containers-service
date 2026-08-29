package eu.wohlben.qits.containers.stories.support;

import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * <b>A recording docker stand-in</b>, and the tap that draws what the launched process asked the
 * daemon for.
 *
 * <h2>Why this shape and not an HTTP mock</h2>
 *
 * <p>qits-containers does not speak to dockerd over a socket: {@code core/docker/ContainerProcess}
 * <b>spawns the docker CLI</b> and reads its pipes, and which binary that is arrives as one runtime
 * key, {@code qits.containers.container-runtime}. So the honest stand-in for this service's one
 * outbound dependency is not a stubbed HTTP endpoint — it is an executable. Pointing that key at the
 * script this class writes makes the docker hop <b>observable</b> rather than declared: every call
 * lands in a file with the exit code it answered, and a little state under {@code state/} keeps the
 * answers consistent enough that the registry's own state machine — the row before the run, the
 * inspect that settles it, the boot sweep, the idempotent delete — runs for real against it.
 *
 * <p>That matters more here than the convenience: this is the repository whose subject is docker,
 * and its first rule is that a clone builds and tests green with no daemon. A story that reached for
 * a real socket would break that rule; a story that only <i>declared</i> the docker edge would
 * document the one dependency this service exists for as a claim rather than as evidence. The
 * container this suite runs in has no docker socket and no capability to reach one, so a stand-in
 * was never optional — the choice was only between a claim and a recording, and this is the
 * recording.
 *
 * <h2>Everything shared is a file or a system property</h2>
 *
 * <p>{@link #install()} is called from the {@code QuarkusTestProfile}, because the launched process
 * needs the binary's path before it boots — and a test profile is instantiated in more than one
 * classloader, so the directory travels in a system property exactly as {@code MockService}'s port
 * does. The stand-in itself runs as a <b>separate process</b>, a child of the launched artifact,
 * which is why its recording is a file and not a list in a field: no two of the three JVMs and
 * processes involved share a heap.
 *
 * <h2>The recording has no floor, deliberately</h2>
 *
 * <p>Every other file-backed tap in the fleet takes the current end of its log as a floor at install
 * time, because what happened before the first story is fixture traffic. Here it is the opposite:
 * the calls this service makes <b>at boot</b> — three shared volumes made, the platform network
 * asked about — are the whole subject of {@code stories.boot.HostBootstrapIT}, the same way the
 * startup JWKS fetch is the subject of {@code api.TokenValidationBootstrapIT}. So the source is
 * registered at zero and the framework's per-source cursor attributes those lines to whichever story
 * drains first, which the class ordering makes the story about them. Run another story class on its
 * own and its first story inherits the boot calls and fails its edge count — loudly, which is the
 * right way for that assumption to break.
 *
 * <h2>What a line becomes</h2>
 *
 * <p>The script records the exit code and the whole argv, tab separated. {@link #summarize} reduces
 * the argv to the shape a reader needs — {@code run qits-ct-qits-ci-step-alpha}, {@code volume
 * create qits_shared_m2}, {@code system df} — and never the whole command line, for two reasons that
 * are both about the {@code networkHash}: a {@code docker run} carries {@code --label
 * qits.containers.row=<uuid>}, which is generated per run and would move the hash on every one, and
 * a Go {@code --format} template is full of braces that mermaid reads as syntax. The exit code
 * follows as {@code -> 0}, in the shape an HTTP label's status has, because it is the same half of
 * the evidence: that the call was <i>answered</i>, not merely made.
 */
public final class StoryDocker {

  /** How a diagram names the daemon on the far side of the pipes. */
  public static final String DAEMON = "docker";

  /**
   * The kind these edges carry. {@code process} rather than {@code socket}: this service talks to a
   * spawned CLI over its pipes and never opens {@code /var/run/docker.sock} itself — which is a real
   * property of the design (the argv <i>is</i> the sandbox, assertable element for element with no
   * daemon anywhere) and not an accident of the stand-in.
   */
  public static final String KIND = NetworkEdge.PROCESS;

  /** Where the directory is parked, for the profile's other classloader to find. */
  private static final String DIR_PROPERTY = "qits.test.story-docker.dir";

  private static final String SOURCE_ID = "story-docker";

  /**
   * The image reference the stand-in refuses, at both the pull and the run, with docker's own words
   * for it. {@code manifest unknown} is one of {@code ContainersResource}'s five markers, which is
   * what turns a failed run into a 409 {@code IMAGE_MISSING} rather than a recorded {@code MISSING}.
   */
  public static final String UNPUBLISHED_IMAGE = "qits/story-unpublished:1";

  /** An image two live containers on the stand-in's host were created from. */
  public static final String BUSY_IMAGE = "qits/story-busy:2";

  /** The image the workload stories run, and the one a live registry row therefore names. */
  public static final String WORKLOAD_IMAGE = "qits/story-example:1";

  private static final Object LOCK = new Object();

  private static boolean registered;

  private static int harvested;

  private static final List<NetworkEdge> EDGES = new ArrayList<>();

  private StoryDocker() {}

  // --- the binary ---------------------------------------------------------------------------------

  /**
   * Write the stand-in, wipe whatever an earlier run left, and answer the path to hand {@code
   * qits.containers.container-runtime}.
   *
   * <p>Idempotent per JVM through the parked property: the profile is instantiated more than once
   * and only the first copy has any business truncating the recording, since by the time the second
   * asks, the launched process may already be booting against it.
   */
  public static synchronized String install() {
    String parked = System.getProperty(DIR_PROPERTY);
    if (parked != null) {
      return Path.of(parked).resolve("docker").toString();
    }
    Path dir = Path.of(System.getProperty("user.dir"), "target", "story-docker").toAbsolutePath();
    try {
      deleteRecursively(dir);
      Files.createDirectories(dir.resolve("state").resolve("containers"));
      Files.createDirectories(dir.resolve("state").resolve("volumes"));
      Files.createDirectories(dir.resolve("state").resolve("logs"));
      Path binary = dir.resolve("docker");
      Files.writeString(binary, SCRIPT, StandardCharsets.UTF_8);
      Set<PosixFilePermission> executable =
          EnumSet.of(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.OWNER_WRITE,
              PosixFilePermission.OWNER_EXECUTE,
              PosixFilePermission.GROUP_READ,
              PosixFilePermission.GROUP_EXECUTE,
              PosixFilePermission.OTHERS_READ,
              PosixFilePermission.OTHERS_EXECUTE);
      Files.setPosixFilePermissions(binary, executable);
      System.setProperty(DIR_PROPERTY, dir.toString());
      return binary.toString();
    } catch (IOException e) {
      throw new UncheckedIOException("could not write the docker stand-in under " + dir, e);
    }
  }

  /** Where the calls are recorded. Resolved through the parked directory, never recomputed. */
  public static Path callLog() {
    String parked = System.getProperty(DIR_PROPERTY);
    Path dir =
        parked != null
            ? Path.of(parked)
            : Path.of(System.getProperty("user.dir"), "target", "story-docker").toAbsolutePath();
    return dir.resolve("calls.log");
  }

  // --- what a story class calls ---------------------------------------------------------------

  /**
   * Register the recording as a cumulative {@link NetworkCapture} source, once per JVM.
   *
   * <p>At zero rather than at a floor — see the class javadoc. Called from every story class's
   * {@code @BeforeAll} so that each class is self-contained; whichever runs first does the work.
   */
  public static void installSource() {
    synchronized (LOCK) {
      if (registered) {
        return;
      }
      harvested = 0;
      EDGES.clear();
      NetworkCapture.source(SOURCE_ID, StoryDocker::edges);
      registered = true;
    }
  }

  /**
   * Every call the stand-in has answered so far, summarized and with its exit code — the same
   * strings the diagram carries.
   *
   * <p>A story reads it to <b>assert</b> what the service asked docker for, rather than inferring it
   * from the diagram it also emits. The two are the same evidence; one of them is documentation and
   * the other is a test, and a story that only drew it would be a story nothing could fail.
   */
  public static List<String> calls() {
    List<String> summarized = new ArrayList<>();
    for (String line : allLines()) {
      String[] fields = line.split("\t", -1);
      if (fields.length < 2) {
        continue;
      }
      summarized.add(label(summarize(argv(fields)), fields[0]));
    }
    return List.copyOf(summarized);
  }

  /** The label one answered call renders as — what an assertion has to spell. */
  public static String label(String summary, String exitCode) {
    return summary + " -> " + exitCode;
  }

  /** The derived container name of a place, as {@code control/ContainerNames} spells it. */
  public static String containerName(String owner, String workload, String ref) {
    return "qits-ct-" + owner + "-" + workload + "-" + ref;
  }

  // --- the source -------------------------------------------------------------------------------

  private static List<NetworkEdge> edges() {
    synchronized (LOCK) {
      harvest();
      return List.copyOf(EDGES);
    }
  }

  private static void harvest() {
    List<String> lines = allLines();
    if (harvested > lines.size()) {
      // The file was truncated under us. Start over rather than mis-slice a prefix.
      harvested = 0;
      EDGES.clear();
    }
    for (String line : lines.subList(harvested, lines.size())) {
      String[] fields = line.split("\t", -1);
      if (fields.length < 2) {
        continue;
      }
      EDGES.add(
          new NetworkEdge(
              KIND, StoryTarget.SERVICE, DAEMON, label(summarize(argv(fields)), fields[0])));
    }
    harvested = lines.size();
  }

  private static List<String> argv(String[] fields) {
    return List.of(fields).subList(1, fields.length);
  }

  /**
   * One argv as the label a reader wants: the command, and the one name it is addressed to.
   *
   * <p>Flags, label sets, environment and Go templates are all dropped — see the class javadoc for
   * why keeping them would move the hash and break the mermaid. What survives is the sentence a
   * person reading a dependency map needs: which docker verb, against which container, volume,
   * network or image.
   */
  static String summarize(List<String> argv) {
    if (argv.isEmpty()) {
      return "docker";
    }
    String command = argv.getFirst();
    String last = argv.getLast();
    return switch (command) {
      case "run" -> "run " + nameFlag(argv, last);
      case "inspect", "start", "stop", "logs", "pull" -> command + " " + last;
      case "rm" -> "rm " + last;
      case "network" -> "network " + second(argv) + " " + last;
      case "volume" ->
          switch (second(argv)) {
            case "create", "rm", "inspect" -> "volume " + second(argv) + " " + last;
            default -> "volume " + second(argv);
          };
      case "image" -> "image " + second(argv);
      case "system" -> "system " + second(argv);
      case "ps" -> ps(argv);
      case "builder", "buildx", "exec" -> command + " " + second(argv);
      default -> command;
    };
  }

  /**
   * The four {@code docker ps} shapes this service makes, told apart by their intent rather than by
   * their flags — a label carrying {@code --format {{.Image}}} would put mermaid syntax in a diagram.
   */
  private static String ps(List<String> argv) {
    if (argv.contains("-aq")) {
      return "ps by label";
    }
    if (argv.contains("--no-trunc")) {
      return "ps image references";
    }
    for (String argument : argv) {
      if (argument.startsWith("volume=")) {
        return "ps by volume " + argument.substring("volume=".length());
      }
      if (argument.startsWith("name=")) {
        return "ps builders";
      }
    }
    return "ps";
  }

  private static String second(List<String> argv) {
    return argv.size() > 1 ? argv.get(1) : "";
  }

  /** The value of {@code --name}, which is the only part of a run a diagram is about. */
  private static String nameFlag(List<String> argv, String fallback) {
    for (int i = 0; i + 1 < argv.size(); i++) {
      if ("--name".equals(argv.get(i))) {
        return argv.get(i + 1);
      }
    }
    return fallback;
  }

  /**
   * The recording's complete lines. A missing file is an empty recording rather than a failure, and
   * an <b>unterminated tail is dropped</b>: the stand-in appends while this reads, and half a line
   * would shape half an edge. The next harvest sees it whole.
   */
  private static List<String> allLines() {
    Path log = callLog();
    if (!Files.isRegularFile(log)) {
      return List.of();
    }
    String text;
    try {
      text = Files.readString(log, StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      return List.of();
    }
    int lastComplete = text.lastIndexOf('\n');
    if (lastComplete < 0) {
      return List.of();
    }
    return List.of(text.substring(0, lastComplete).split("\n"));
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path directory, IOException failure)
              throws IOException {
            Files.delete(directory);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  // --- the stand-in itself ------------------------------------------------------------------------

  /**
   * The script. POSIX {@code sh} and nothing else — this repository's suite runs on the platform's
   * Alpine build image, and a bashism here would be a failure a developer's machine never sees.
   *
   * <p>Every answer below is one docker really gives, in docker's own words where the wording is
   * read: {@code No such object} is what {@code DockerContainersDriver.ABSENT_MARKERS} matches to
   * tell "docker has no such container" from "docker did not answer", and {@code manifest unknown}
   * is what {@code ContainersResource.IMAGE_MISSING_MARKERS} matches to turn a refused run into a
   * 409. Getting those two strings wrong would make the stories pass against a daemon that behaves
   * differently from every real one.
   */
  private static final String SCRIPT =
      """
      #!/bin/sh
      # A RECORDING DOCKER STAND-IN. Written by StoryDocker; never edited by hand.
      #
      # qits-containers shells out to whatever `qits.containers.container-runtime` names, element by
      # element, through ContainerProcess. Pointing that key here makes the docker hop observable:
      # every call is appended to calls.log with the exit code it answered, and enough state lives
      # under state/ for the registry's own state machine to run for real against it.
      set -u

      dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
      containers="$dir/state/containers"
      volumes="$dir/state/volumes"
      logs="$dir/state/logs"
      calls="$dir/calls.log"
      mkdir -p "$containers" "$volumes" "$logs"
      tab=$(printf '\\t')

      # A container id has to be 64 hex characters to look like one. It reaches no wire field and no
      # label, so one constant is the whole of what is needed.
      cid=3f9a1c7e5b04d28610fa73c9e8b52d41f60c9a37e21d48b5c07f3a6e29d10b84

      # One pass over the argv: the value of --name, the last positional, and whether the image the
      # registry never published is anywhere in it.
      name=''
      last=''
      prev=''
      unpublished=0
      for a in "$@"; do
        if [ "$prev" = '--name' ]; then name=$a; fi
        case $a in *story-unpublished*) unpublished=1 ;; esac
        prev=$a
        last=$a
      done
      if [ -z "$name" ]; then name=$last; fi

      code=0
      out=''
      now=$(date -u +%Y-%m-%dT%H:%M:%SZ)

      case "${1:-}" in
        run)
          if [ "$unpublished" = 1 ]; then
            # Docker's own two lines for a reference no registry serves. `manifest unknown` is the
            # marker qits-containers reads, and 125 is what the CLI exits with when the daemon
            # refused before the container existed.
            code=125
            out="Unable to find image '$last' locally
      docker: Error response from daemon: manifest unknown: manifest unknown."
          elif [ -e "$containers/$name" ]; then
            code=125
            out="docker: Error response from daemon: Conflict. The container name \\"/$name\\" is already in use."
          else
            printf 'running %s\\n' "$now" > "$containers/$name"
            printf '%s\\n' "$name booted and is serving" > "$logs/$name"
            out=$cid
          fi
          ;;
        inspect)
          if [ -e "$containers/$name" ]; then
            read -r status started < "$containers/$name"
            out="$cid|$status/none|$started"
          else
            code=1
            out="Error: No such object: $name"
          fi
          ;;
        start)
          if [ -e "$containers/$name" ]; then
            printf 'running %s\\n' "$now" > "$containers/$name"
            out=$name
          else
            code=1
            out="Error response from daemon: No such container: $name"
          fi
          ;;
        stop)
          if [ -e "$containers/$name" ]; then
            read -r status started < "$containers/$name"
            # A stop leaves the container restartable and does not move when the run began.
            printf 'exited %s\\n' "$started" > "$containers/$name"
            out=$name
          else
            code=1
            out="Error response from daemon: No such container: $name"
          fi
          ;;
        rm)
          if [ -e "$containers/$name" ]; then
            rm -f "$containers/$name" "$logs/$name"
            out=$name
          else
            code=1
            out="Error response from daemon: No such container: $name"
          fi
          ;;
        logs)
          if [ -e "$logs/$name" ]; then
            out=$(cat "$logs/$name")
          else
            code=1
            out="Error response from daemon: No such container: $name"
          fi
          ;;
        pull)
          if [ "$unpublished" = 1 ]; then
            code=1
            out="Error response from daemon: manifest unknown: manifest unknown."
          else
            out="Status: Image is up to date for $name"
          fi
          ;;
        volume)
          case "${2:-}" in
            create)
              : > "$volumes/$name"
              out=$name
              ;;
            rm)
              if [ -e "$volumes/$name" ]; then
                rm -f "$volumes/$name"
                out=$name
              else
                code=1
                out="Error: No such volume: $name"
              fi
              ;;
            ls)
              # Nothing dangling and nothing labelled: the volume reconcile has no candidates, so a
              # background pass can never remove anything a story made.
              out=''
              ;;
            inspect)
              if [ -e "$volumes/$name" ]; then
                out='2026-08-20T04:46:43+02:00'
              else
                code=1
                out="Error: No such volume: $name"
              fi
              ;;
            *)
              code=1
              out="unknown volume command: ${2:-}"
              ;;
          esac
          ;;
        network)
          # Asked about, never created — the platform's own network is here and nothing else is.
          if [ "$name" = 'qits-net' ]; then
            out='[]'
          else
            code=1
            out="Error response from daemon: network $name not found"
          fi
          ;;
        ps)
          case " $* " in
            *' --no-trunc '*) out='qits/story-busy:2' ;;
            *) out='' ;;
          esac
          ;;
        system)
          out='Images|3|1|253MB|9MB
      Containers|1|1|12MB|0B
      Local Volumes|4|1|64MB|32MB
      Build Cache|0|0|0B|0B'
          ;;
        image)
          case "${2:-}" in
            ls)
              # Three images: one a live registry row names, one two containers were created from,
              # and one dangling. What each of them is FOR is decided by the keep rules, not here.
              out='sha256:1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f809|qits/story-example|1|2026-08-20 04:46:43 +0200 CEST|180MB
      sha256:2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8091a|qits/story-busy|2|2026-08-19 04:46:43 +0200 CEST|64MB
      sha256:3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8091a2b|<none>|<none>|2026-08-18 04:46:43 +0200 CEST|9MB'
              ;;
            rm)
              out=''
              ;;
            *)
              code=1
              out="unknown image command: ${2:-}"
              ;;
          esac
          ;;
        *)
          code=1
          out="qits story docker: no such command: ${1:-}"
          ;;
      esac

      # Recorded before the answer leaves, so a caller that observed an effect can rely on the line
      # for it already being on disk. One printf, so concurrent calls interleave by line.
      line=$code
      for a in "$@"; do line="$line$tab$a"; done
      printf '%s\\n' "$line" >> "$calls"

      if [ -n "$out" ]; then printf '%s\\n' "$out"; fi
      exit "$code"
      """;
}
