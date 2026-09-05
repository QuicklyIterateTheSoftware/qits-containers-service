package eu.wohlben.qits.containers.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.spec.ContainerLabels;
import eu.wohlben.qits.containers.spec.ContainerSpec;
import eu.wohlben.qits.containers.spec.ContainersIdentifiers;
import eu.wohlben.qits.containers.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.spec.VolumeSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Argv assembly only — the real docker calls arrive in WP4 and are proved by an integration test
 * that opts in. Worth asserting <b>element for element</b> for the reason {@code
 * CiDaemonLauncherTest} states: the argv IS the sandbox, and a flag lost in a refactor is invisible
 * everywhere else until it is invisible in production.
 */
public class DockerArgvTest {

  private static final String BOOTSTRAP = "set -e\nexec /tmp/qits-ci-daemon\n";

  /** A full-featured ci-step-shaped spec: every sandbox flag, both kinds of mount, the socket on. */
  private static ContainerSpec.Builder ciStep() {
    return ContainerSpec.builder("maven:3.9")
        .network("qits-net")
        .alias("ci-step-1")
        .addHost("host.docker.internal:host-gateway")
        .label("qits.ci.run", "run-1")
        .mount("qits-ci-run-1", "/work")
        .shared("qits_shared_m2", "/caches/m2")
        .hostDockerSocket(true)
        .security(new ContainerSpec.SecurityPosture(true, true, "4g", "4g", 2048L, "2", 1000))
        .env("QITS_CI_SHA", "cafebabe")
        .env("QITS_CI_DAEMON_ID", "daemon-7")
        .entrypoint("/bin/sh")
        .args("-c", BOOTSTRAP)
        .name("qits-ci-abc-0")
        .user("build");
  }

  private static Map<String, String> labels() {
    return ContainerLabels.forContainer("qits-ci", "step", "run-1", "row-1", "instance-1");
  }

  private static List<String> render(ContainerSpec spec, LifecyclePolicy policy) {
    return DockerArgv.run("docker", "qits-ci-abc-0", spec, labels(), policy);
  }

  @Test
  public void rendersTheWholeEphemeralStepArgv() {
    assertEquals(
        List.of(
            "docker",
            "run",
            "-d",
            "--name",
            "qits-ci-abc-0",
            "--network",
            "qits-net",
            "--network-alias",
            "ci-step-1",
            "--add-host=host.docker.internal:host-gateway",
            "--user",
            "build",
            "--label",
            "qits.ci.run=run-1",
            "--label",
            "qits.containers.instance=instance-1",
            "--label",
            "qits.containers.managed=container",
            "--label",
            "qits.containers.owner=qits-ci",
            "--label",
            "qits.containers.ref=run-1",
            "--label",
            "qits.containers.row=row-1",
            "--label",
            "qits.containers.workload=step",
            "--security-opt=no-new-privileges",
            "--cap-drop=ALL",
            "--memory",
            "4g",
            "--memory-swap",
            "4g",
            "--pids-limit",
            "2048",
            "--cpus",
            "2",
            "--oom-score-adj",
            "1000",
            "-v",
            "qits-ci-run-1:/work",
            "-v",
            "qits_shared_m2:/caches/m2",
            "-v",
            "/var/run/docker.sock:/var/run/docker.sock",
            "-e",
            "QITS_CI_DAEMON_ID=daemon-7",
            "-e",
            "QITS_CI_SHA=cafebabe",
            "--entrypoint",
            "/bin/sh",
            "maven:3.9",
            "-c",
            BOOTSTRAP),
        render(ciStep().build(), LifecyclePolicy.ephemeral(Duration.ofHours(6))));
  }

  @Test
  public void theSocketMountIsTheOnlyDifferenceBetweenAskingAndNotAsking() {
    LifecyclePolicy policy = LifecyclePolicy.ephemeral(null);
    List<String> withSocket = render(ciStep().build(), policy);
    List<String> without = render(ciStep().hostDockerSocket(false).build(), policy);

    int mount = withSocket.indexOf("/var/run/docker.sock:/var/run/docker.sock");
    assertTrue(mount > 0, withSocket.toString());
    assertEquals("-v", withSocket.get(mount - 1));

    // Exactly the pair, and nothing else: the sandbox does not relax for a workload that asked for
    // the socket, because cap-drop and no-new-privileges cost a socket CLIENT nothing and keeping
    // them unconditional is what keeps them meaning something for the workloads that never opt in.
    List<String> minusThePair = new ArrayList<>(withSocket);
    minusThePair.subList(mount - 1, mount + 1).clear();
    assertEquals(without, minusThePair, "the socket must add a mount and change nothing else");
    assertTrue(withSocket.contains("--cap-drop=ALL"), withSocket.toString());
    assertTrue(withSocket.contains("--security-opt=no-new-privileges"), withSocket.toString());
  }

  @Test
  public void theSocketsGroupIsJoinedBesideTheBindAndNowhereElse() {
    LifecyclePolicy policy = LifecyclePolicy.ephemeral(null);
    List<String> joined =
        DockerArgv.run("docker", "qits-ci-abc-0", ciStep().build(), labels(), policy, "993");

    // Beside the bind, in that order: the bind is what the membership is for, and a --group-add
    // that could drift away from it would be a membership nothing justifies.
    int mount = joined.indexOf("/var/run/docker.sock:/var/run/docker.sock");
    assertEquals("-v", joined.get(mount - 1));
    assertEquals("--group-add", joined.get(mount + 1));
    assertEquals("993", joined.get(mount + 2));

    // And it is exactly that pair on top of the ungrouped rendering — nothing else moves.
    List<String> ungrouped = render(ciStep().build(), policy);
    List<String> minusTheGroup = new ArrayList<>(joined);
    minusTheGroup.subList(mount + 1, mount + 3).clear();
    assertEquals(ungrouped, minusTheGroup, "the group must add two elements and change nothing else");
  }

  @Test
  public void aWorkloadWithNoSocketJoinsNoGroupWhateverTheHostsIs() {
    // The security assertion of this pair: the deployment's group is a fact about the host and is
    // held for every launch, so a workload that did not declare the bind must never pick it up.
    List<String> argv =
        DockerArgv.run(
            "docker",
            "qits-ci-abc-0",
            ciStep().hostDockerSocket(false).build(),
            labels(),
            LifecyclePolicy.ephemeral(null),
            "993");
    assertFalse(argv.contains("--group-add"), argv.toString());
    assertFalse(argv.contains("993"), argv.toString());
  }

  @Test
  public void aGroupThatCouldReadAsAFlagIsRefused() {
    // The second belt, on a value that reaches an argv element. Nothing a caller sends can arrive
    // here — the group is read off the socket — but "no caller can reach it" is exactly the claim
    // that stops being true one refactor later.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DockerArgv.run(
                "docker",
                "qits-ci-abc-0",
                ciStep().build(),
                labels(),
                LifecyclePolicy.ephemeral(null),
                "-rf"));
  }

  @Test
  public void aSpecThatAskedForNoSocketSeesNoSocketAtAll() {
    // THIS is the security assertion of the pair — the absence, not the presence. The docker socket
    // is root on the host, so "no socket unless the spec declared one" is the invariant, and an
    // accidental unconditional mount would be invisible until it was invisible in production.
    List<String> argv = render(ciStep().hostDockerSocket(false).build(), LifecyclePolicy.ephemeral(null));
    for (String element : argv) {
      assertFalse(element.contains("docker.sock"), "no workload may see a socket it did not ask for: " + element);
    }
  }

  @Test
  public void aSpecThatNamedNoUserRendersNoUserFlagAtAll() {
    // The absence is the claim. An unset user means the image's own default, and a --user that
    // appeared unasked would run every existing workload as somebody it was never built for.
    List<String> argv = render(ciStep().user("").build(), LifecyclePolicy.ephemeral(null));
    assertFalse(argv.contains("--user"), argv.toString());
    assertFalse(argv.contains("build"), argv.toString());
  }

  @Test
  public void theUserFlagIsTheOnlyDifferenceBetweenNamingOneAndNot() {
    LifecyclePolicy policy = LifecyclePolicy.ephemeral(null);
    List<String> withUser = render(ciStep().build(), policy);
    List<String> without = render(ciStep().user("").build(), policy);

    int flag = withUser.indexOf("--user");
    assertTrue(flag > 0, withUser.toString());
    assertEquals("build", withUser.get(flag + 1));

    List<String> minusThePair = new ArrayList<>(withUser);
    minusThePair.subList(flag, flag + 2).clear();
    assertEquals(without, minusThePair, "naming a user must add a flag and change nothing else");
  }

  @Test
  public void aHostileUserNeverReachesTheArgv() {
    // --user's own `user:group` form is what a colon here would forge, and a leading dash is the
    // usual option-shaped argument. Neither is a thing to leave to the CLI's parser.
    for (String hostile : List.of("build:root", "-u", "Build", "root user", "")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> ContainersIdentifiers.requireUser(hostile),
          hostile);
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> ContainerSpec.builder("img").network("qits-net").user("build:root").build());
    // A bare uid is fine — it is what a passwd-less image is addressed by.
    assertEquals("1001", ContainersIdentifiers.requireUser("1001"));
  }

  @Test
  public void aSecurityWithNoOomScoreAdjRendersNoFlagAtAll() {
    // The absence is the claim: oom-score-adj is a host-survival hint a consumer opts into, and a
    // value that appeared unasked would move a workload up or down the kernel's kill order it was
    // never meant to sit in. Null on the wire (an old consumer) has to render nothing.
    ContainerSpec noOom =
        ciStep()
            .security(new ContainerSpec.SecurityPosture(true, true, "4g", "4g", 2048L, "2", null))
            .build();
    List<String> argv = render(noOom, LifecyclePolicy.ephemeral(null));
    assertFalse(argv.contains("--oom-score-adj"), argv.toString());
  }

  @Test
  public void aSpecThatAskedForNoInitRendersNoInitFlagAtAll() {
    // The absence is the claim, as it is for the socket and the user: --init inserts a process
    // between docker and the workload's own PID 1, so every container that never asked has to keep
    // starting exactly the process its image declares. The whole-argv test above is the other half
    // — it lists every element of a spec that asked for nothing, and --init is not among them.
    LifecyclePolicy policy = LifecyclePolicy.ephemeral(null);
    assertFalse(render(ciStep().build(), policy).contains("--init"));
    assertFalse(render(ciStep().init(false).build(), policy).contains("--init"));
  }

  @Test
  public void theInitFlagIsTheOnlyDifferenceBetweenAskingForItAndNot() {
    LifecyclePolicy policy = LifecyclePolicy.ephemeral(null);
    List<String> withInit = render(ciStep().init(true).build(), policy);
    List<String> without = render(ciStep().build(), policy);

    // Immediately after -d, which is where the argv keeps what kind of run this is. The position is
    // asserted rather than left to the list comparison because the whole argv is asserted literally
    // elsewhere, and a flag that wandered would move every element after it.
    assertEquals("--init", withInit.get(3));
    assertEquals("-d", withInit.get(2));

    List<String> minusTheFlag = new ArrayList<>(withInit);
    minusTheFlag.remove(3);
    assertEquals(without, minusTheFlag, "asking for tini must add a flag and change nothing else");
  }

  @Test
  public void ephemeralRendersNoRestartPolicyAndRefusesRecreate() {
    // Its containers dial once and exit by design, so a restart policy would bring back a process
    // whose peer is gone — and a second container would redo work that happened once.
    LifecyclePolicy policy = LifecyclePolicy.ephemeral(Duration.ofHours(6));
    assertFalse(render(ciStep().build(), policy).contains("--restart"));
    assertFalse(policy.restartsUnlessStopped());
    assertFalse(policy.recreatable());
  }

  @Test
  public void theLongLivedPoliciesRenderUnlessStopped() {
    // Their containers outlive this service AND a dockerd restart, which is the point of them.
    // `unless-stopped` rather than `always`, so a container stopped on purpose does not race its
    // own restart back up.
    for (LifecyclePolicy policy :
        List.of(LifecyclePolicy.idleStop(Duration.ofHours(4)), LifecyclePolicy.explicitLifetime())) {
      List<String> argv = render(ciStep().build(), policy);
      int flag = argv.indexOf("--restart");
      assertTrue(flag > 0, policy + " -> " + argv);
      assertEquals("unless-stopped", argv.get(flag + 1));
      assertTrue(policy.restartsUnlessStopped());
      assertTrue(policy.recreatable());
    }
  }

  @Test
  public void nothingThisClassRendersEverCarriesRm() {
    // A self-removing container races the `docker logs` capture that is the only diagnosis a
    // container which died on its first breath can offer — and it would delete a container whose
    // registry row still names it, which is the one state the adoption rule forbids.
    List<List<String>> everything =
        List.of(
            render(ciStep().build(), LifecyclePolicy.ephemeral(null)),
            render(ciStep().build(), LifecyclePolicy.idleStop(Duration.ofHours(4))),
            render(ciStep().build(), LifecyclePolicy.explicitLifetime()),
            DockerArgv.inspectState("docker", "c"),
            DockerArgv.inspectStartedAt("docker", "c"),
            DockerArgv.stop("docker", "c"),
            DockerArgv.rm("docker", "c"),
            DockerArgv.logsTail("docker", "c", 200),
            DockerArgv.psByLabels("docker", Map.of(ContainerLabels.OWNER, "qits-ci")),
            DockerArgv.pull("docker", "maven:3.9"),
            DockerArgv.volumeCreate("docker", new VolumeSpec("v"), Map.of()),
            DockerArgv.volumeRm("docker", "v"),
            DockerArgv.volumeLs("docker", Map.of(ContainerLabels.OWNER, "qits-ci")),
            DockerArgv.volumeInspectLabels("docker", "v"),
            DockerArgv.networkInspect("docker", "qits-net"));
    for (List<String> argv : everything) {
      assertFalse(argv.contains("--rm"), argv.toString());
    }
  }

  @Test
  public void theLabelSetIsCompleteAndSorted() {
    List<String> argv = render(ciStep().build(), LifecyclePolicy.ephemeral(null));
    List<String> rendered = new ArrayList<>();
    for (int i = 0; i < argv.size() - 1; i++) {
      if ("--label".equals(argv.get(i))) {
        rendered.add(argv.get(i + 1));
      }
    }
    assertEquals(
        List.of(
            "qits.ci.run=run-1",
            "qits.containers.instance=instance-1",
            "qits.containers.managed=container",
            "qits.containers.owner=qits-ci",
            "qits.containers.ref=run-1",
            "qits.containers.row=row-1",
            "qits.containers.workload=step"),
        rendered,
        "every namespace label, the owner's own beside them, sorted");
  }

  @Test
  public void theInspectFormatsAreTheMeasuredOnes() {
    // Copied from DockerDeploymentDriver.observe unchanged. The if/else is not decoration: a bare
    // {{.State.Health.Status}} prints Go's `<no value>` for an image with no healthcheck, and
    // `<no value>` reads back as a health state no container has.
    assertEquals(
        List.of(
            "docker",
            "inspect",
            "--format",
            "{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}",
            "c"),
        DockerArgv.inspectState("docker", "c"));
    assertEquals(
        List.of("docker", "inspect", "--format", "{{.State.StartedAt}}", "c"),
        DockerArgv.inspectStartedAt("docker", "c"));
    // The same caveat again, on the other side: an `index` of an empty label map prints
    // `<no value>`, a range over it prints nothing — which is what an unlabelled volume means.
    assertTrue(
        DockerArgv.volumeInspectLabels("docker", "v").contains("{{range $k, $v := .Labels}}{{$k}}={{$v}}{{\"\\n\"}}{{end}}"),
        DockerArgv.volumeInspectLabels("docker", "v").toString());
  }

  @Test
  public void theObservationFormatIsTheMapSafeOneAndNotTheThreeAbovePastedTogether() {
    // MEASURED on docker 29.7.2, and the whole reason this constant is spelled out rather than
    // composed: `{{.Id}}` is not a field of the typed inspect object (the Go field is ID), so it
    // forces the CLI onto its raw-JSON fallback — and on a map, `{{if .State.Health}}` is the ERROR
    // `map has no entry for key "Health"` rather than a false. `index` answers the zero value
    // instead, so a container with no healthcheck reads `running/none` and one with a check reads
    // `running/healthy`. Pasting the two single-field formats after `{{.Id}}` compiles, renders in
    // a unit test, and fails against every container the platform runs without a healthcheck.
    assertEquals(
        List.of(
            "docker",
            "inspect",
            "--format",
            "{{.Id}}|{{.State.Status}}/"
                + "{{if index .State \"Health\"}}{{(index .State \"Health\").Status}}"
                + "{{else}}none{{end}}"
                + "|{{.State.StartedAt}}",
            "c"),
        DockerArgv.inspectObservation("docker", "c"));
    assertFalse(
        DockerArgv.OBSERVATION_FORMAT.contains("{{if .State.Health}}"),
        "the typed-path health belt does not work on the map path this format is on");
  }

  @Test
  public void theSmallCommandsAreWhatTheySay() {
    // A start takes the name and nothing else: what the container is was decided by the run that
    // made it, and a flag rendered here would be the sandbox written in a second place.
    assertEquals(List.of("docker", "start", "c"), DockerArgv.start("docker", "c"));
    assertEquals(List.of("docker", "stop", "c"), DockerArgv.stop("docker", "c"));
    assertEquals(List.of("docker", "rm", "-f", "c"), DockerArgv.rm("docker", "c"));
    assertEquals(
        List.of("docker", "logs", "--tail", "200", "c"), DockerArgv.logsTail("docker", "c", 200));
    assertEquals(List.of("docker", "pull", "maven:3.9"), DockerArgv.pull("docker", "maven:3.9"));
    assertEquals(
        List.of("docker", "network", "inspect", "qits-net"),
        DockerArgv.networkInspect("docker", "qits-net"));
    assertEquals(List.of("docker", "volume", "rm", "v"), DockerArgv.volumeRm("docker", "v"));
  }

  @Test
  public void listingsRenderOneFilterPerLabelInASortedOrder() {
    assertEquals(
        List.of(
            "docker",
            "ps",
            "-aq",
            "--filter",
            "label=qits.containers.owner=qits-ci",
            "--filter",
            "label=qits.containers.workload=step"),
        DockerArgv.psByLabels(
            "docker",
            Map.of(ContainerLabels.WORKLOAD, "step", ContainerLabels.OWNER, "qits-ci")));
    assertEquals(
        List.of("docker", "volume", "ls", "-q", "--filter", "label=qits.containers.owner=qits-ci"),
        DockerArgv.volumeLs("docker", Map.of(ContainerLabels.OWNER, "qits-ci")));
  }

  @Test
  public void aVolumeIsCreatedWithItsLabelsSorted() {
    assertEquals(
        List.of(
            "docker",
            "volume",
            "create",
            "--label",
            "qits.containers.managed=volume",
            "--label",
            "qits.containers.owner=qits-ci",
            "--label",
            "qits.containers.ref=ws-1",
            "--label",
            "qits.containers.workload=workspace",
            "qits-ws-1"),
        DockerArgv.volumeCreate(
            "docker",
            new VolumeSpec("qits-ws-1"),
            ContainerLabels.forVolume("qits-ci", "workspace", "ws-1")));
  }

  @Test
  public void aHostileOwnerNeverReachesALabel() {
    IllegalArgumentException refused =
        assertThrows(
            IllegalArgumentException.class,
            () -> ContainerLabels.forContainer("qits ci\n--privileged", "step", "r", "r", "i"));
    assertTrue(refused.getMessage().contains("owner"), refused.getMessage());
    // Safely echoed: the control character is gone, so a refusal cannot forge a second log line.
    assertFalse(refused.getMessage().contains("\n"), refused.getMessage());
  }

  @Test
  public void aHostileRefNeverReachesALabel() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ContainerLabels.forContainer("qits-ci", "step", "../../etc", "r", "i"));
  }

  @Test
  public void anOptionShapedImageIsRefusedBeforeAnyDockerCall() {
    // The image is a positional argument. Nothing is known to get through it — ProcessBuilder does
    // not shell-split — but an argument that can be read as an option is not a thing to leave to
    // the parser's good manners.
    assertThrows(
        IllegalArgumentException.class, () -> ContainerSpec.builder("--privileged").build());
    assertThrows(IllegalArgumentException.class, () -> ContainerSpec.builder("  ").build());
    assertThrows(IllegalArgumentException.class, () -> DockerArgv.pull("docker", "--privileged"));
  }

  @Test
  public void aHostileEnvironmentKeyIsRefused() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ContainerSpec.builder("img").network("qits-net").env("PATH=/evil ANY", "x").build());
  }

  @Test
  public void anExtraLabelMayNotCollideWithTheServicesOwnNamespace() {
    // An owner that could write a qits.containers.* label could claim somebody else's workload as
    // its own, or disown its own. The namespace is what a listing narrows on, so it is this
    // service's alone.
    IllegalArgumentException refused =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ContainerSpec.builder("img")
                    .network("qits-net")
                    .label(ContainerLabels.OWNER, "somebody-else")
                    .build());
    assertTrue(refused.getMessage().contains("qits.containers."), refused.getMessage());
  }

  @Test
  public void rendersTheWholePlatformBuilderArgv() {
    // The one privileged run this service makes, asserted whole for the same reason the step argv
    // is: --privileged appearing anywhere else, or a caller-shaped word appearing here, is the
    // regression.
    List<String> argv =
        DockerArgv.runBuildkitd(
            "docker", "moby/buildkit:v0.33.0", "qits-net", "qits-buildkitd-state",
            "[worker.oci]\n  gc = true\n", "cafe01", 4096, 500);

    assertEquals(
        List.of(
            "docker",
            "run",
            "-d",
            "--name",
            "qits-buildkitd",
            "--network",
            "qits-net",
            "--network-alias",
            "qits-buildkitd",
            "--label",
            DockerArgv.BUILDKITD_STAMP_LABEL + "=cafe01",
            "--privileged",
            "--restart",
            "unless-stopped",
            "--pids-limit",
            "4096",
            "--oom-score-adj",
            "500",
            "-v",
            "qits-buildkitd-state:/var/lib/buildkit",
            "-e",
            "BUILDKITD_TOML=[worker.oci]\n  gc = true\n",
            "--entrypoint",
            "/bin/sh",
            "moby/buildkit:v0.33.0",
            "-c",
            DockerArgv.BUILDKITD_BOOTSTRAP),
        argv);
  }

  @Test
  public void thePlatformBuilderBootstrapInterpolatesNothing() {
    // The toml travels as an environment value the shell reads; a word of it in the -c text would
    // be the interpolation the whole BOOTSTRAP idiom exists to rule out. What the text may hold is
    // the variable's NAME, exactly once, quoted — the printf that copies it into the file.
    assertEquals(1, DockerArgv.BUILDKITD_BOOTSTRAP.split("BUILDKITD_TOML", -1).length - 1);
    assertTrue(DockerArgv.BUILDKITD_BOOTSTRAP.contains("\"$BUILDKITD_TOML\""));
  }

  @Test
  public void privilegedAppearsInThePlatformBuilderArgvAndNowhereElse() {
    List<String> step =
        DockerArgv.run(
            "docker",
            "qits-ct-x",
            ciStep().build(),
            Map.of(),
            LifecyclePolicy.ephemeral(Duration.ofHours(1)),
            null);
    assertFalse(step.contains("--privileged"), "no spec can express the builder's privilege");
  }

  @Test
  public void theStampInspectReadsTheConfigLabel() {
    assertEquals(
        List.of(
            "docker",
            "inspect",
            "--format",
            "{{index .Config.Labels \"" + DockerArgv.BUILDKITD_STAMP_LABEL + "\"}}",
            "qits-buildkitd"),
        DockerArgv.inspectBuildkitdStamp("docker", "qits-buildkitd"));
  }

  @Test
  public void thePlatformBuilderIsAnAllowedExecTargetAndStrangersAreNot() {
    // The exec belt stays closed: the buildx prefix, the one platform constant, nothing else.
    assertEquals(
        "qits-buildkitd", ContainersIdentifiers.requireBuilderContainer("qits-buildkitd"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ContainersIdentifiers.requireBuilderContainer("qits-buildkitd-2"));
  }

  @Test
  public void aMountPathMayNotForgeAMountsOwnFields() {
    // `-v <volume>:<path>` is one argv element; a colon in either half would move the boundary and
    // could append a third field docker reads as a mode.
    assertThrows(
        IllegalArgumentException.class,
        () -> new ContainerSpec.VolumeMount("v", "/work:ro"));
    assertThrows(
        IllegalArgumentException.class, () -> new ContainerSpec.VolumeMount("v", "relative/path"));
  }
}
