package eu.wohlben.qits.containers.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.driver.spec.ContainerSpec.PullPolicy;
import eu.wohlben.qits.containers.driver.spec.ContainerSpec.SecurityPosture;
import eu.wohlben.qits.containers.driver.spec.ContainerSpec.SharedMount;
import eu.wohlben.qits.containers.driver.spec.ContainerSpec.VolumeMount;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Golden literals for {@link SpecFingerprint}: the {@code spec_hash} and the {@code spec_json} that
 * {@code ContainerRegistry.upsert} stores for every container this service runs.
 *
 * <p><b>Never regenerate these literals.</b> The stored hash is the identity of every running
 * workspace, agent and refinement container, and {@code Recreate.ifChanged} recreates any container
 * whose stored hash differs from the hash of the spec it is ensured with. A literal here that has to
 * change therefore means that, on the deploy carrying the change, <b>every stored row recreates on
 * its next {@code ifChanged} ensure</b>: every workspace and agent on the estate restarted at once,
 * for a spec nobody changed. When this test goes red the fix is the code (a renamed or added record
 * component, a moved type, a mapper knob, a different normalization), never the literal. They were
 * computed once, on the code as it stood before the spec types moved out of this repository
 * (qits-789, epic qits-623), and that move is required to keep them byte-identical.
 *
 * <p>Five shapes together exercise all sixteen {@link ContainerSpec} components, each populated in
 * one shape and empty or defaulted in another: the minimal spec (every list and map empty, every
 * string defaulted, {@link SecurityPosture#none()} with all-null fields); the workspace shape
 * (labels, env, add-host, own and shared mounts, a full sandbox, explicit name, user and init); the
 * admin shape (the same with the host docker socket); the agent shape through the canonical
 * constructor with null entrypoint, args, aliases and pull policy (the null-normalization path) and
 * a null cpus; and a buildkitd-like maximal shape (entrypoint, args, aliases, cap-drop-all,
 * no-new-privileges, {@code ALWAYS}, a negative OOM score). The shapes with env pin that env is
 * hashed, and their persisted JSON pins that it is not stored.
 */
class SpecFingerprintGoldenTest {

  private static final String MINIMAL_HASH =
      "7e36d60aa307d99fafd6a8472fa0920941742a8a569a40e3731fa7998307fa94";

  private static final String MINIMAL_JSON =
      "{\"addHosts\":[],\"aliases\":[],\"args\":[],\"entrypoint\":[],\"env\":{},"
          + "\"explicitName\":\"\",\"extraLabels\":{},\"hostDockerSocket\":false,\"image\":\"alpine:3\","
          + "\"init\":false,\"network\":\"bridge\",\"pullPolicy\":\"MISSING\",\"security\":{\"capDropAll\":false,"
          + "\"cpus\":null,\"memory\":null,\"memorySwap\":null,\"noNewPrivileges\":false,"
          + "\"oomScoreAdj\":null,\"pidsLimit\":null},\"sharedMounts\":[],\"user\":\"\","
          + "\"volumeMounts\":[]}";

  private static final String WORKSPACE_HASH =
      "c27ba180f720b4cb2a76fa69984bdaca40b640827ecdd68d5963d8e6dbfe526c";

  private static final String WORKSPACE_JSON =
      "{\"addHosts\":[\"host.docker.internal:host-gateway\"],\"aliases\":[],\"args\":[],"
          + "\"entrypoint\":[],\"env\":{},\"explicitName\":\"qits-ws-1234-0a1b2c3d\","
          + "\"extraLabels\":{\"qits.branch\":\"task/qits-623/qits-785/qits-789\",\"qits.project\":\"qits\","
          + "\"qits.repository\":\"qits-qits\",\"qits.workspace\":\"1234\"},\"hostDockerSocket\":false,"
          + "\"image\":\"registry.qits.wohlben.eu/qits/qits-workspace-base:2026.1003.120000\","
          + "\"init\":true,\"network\":\"qits-net\",\"pullPolicy\":\"MISSING\",\"security\":{\"capDropAll\":false,"
          + "\"cpus\":\"2\",\"memory\":\"4g\",\"memorySwap\":\"8g\",\"noNewPrivileges\":false,"
          + "\"oomScoreAdj\":500,\"pidsLimit\":4096},\"sharedMounts\":[{\"containerPath\":\"/claude-home/.claude\","
          + "\"sharedName\":\"qits_shared_dot_claude\"},{\"containerPath\":\"/m2\",\"sharedName\":\"qits_shared_m2\"},"
          + "{\"containerPath\":\"/pnpm\",\"sharedName\":\"qits_shared_pnpm\"}],\"user\":\"1000\","
          + "\"volumeMounts\":[{\"containerPath\":\"/workspace\",\"volumeName\":\"qits_ws_1234_workspace\"}]}";

  private static final String ADMIN_HASH =
      "3ea4d9d6e07bd99474a1b12ebfbcd31a384d7c4c20ca6e88b88e262225f48ede";

  private static final String ADMIN_JSON =
      "{\"addHosts\":[\"host.docker.internal:host-gateway\"],\"aliases\":[],\"args\":[],"
          + "\"entrypoint\":[],\"env\":{},\"explicitName\":\"qits-ws-1234-0a1b2c3d\","
          + "\"extraLabels\":{\"qits.branch\":\"task/qits-623/qits-785/qits-789\",\"qits.project\":\"qits\","
          + "\"qits.repository\":\"qits-qits\",\"qits.workspace\":\"1234\"},\"hostDockerSocket\":true,"
          + "\"image\":\"registry.qits.wohlben.eu/qits/qits-workspace-base:2026.1003.120000\","
          + "\"init\":true,\"network\":\"qits-net\",\"pullPolicy\":\"MISSING\",\"security\":{\"capDropAll\":false,"
          + "\"cpus\":\"2\",\"memory\":\"4g\",\"memorySwap\":\"8g\",\"noNewPrivileges\":false,"
          + "\"oomScoreAdj\":500,\"pidsLimit\":4096},\"sharedMounts\":[{\"containerPath\":\"/claude-home/.claude\","
          + "\"sharedName\":\"qits_shared_dot_claude\"},{\"containerPath\":\"/m2\",\"sharedName\":\"qits_shared_m2\"},"
          + "{\"containerPath\":\"/pnpm\",\"sharedName\":\"qits_shared_pnpm\"}],\"user\":\"1000\","
          + "\"volumeMounts\":[{\"containerPath\":\"/workspace\",\"volumeName\":\"qits_ws_1234_workspace\"}]}";

  private static final String AGENT_HASH =
      "f509313ecd359dc690188d43523e573c8689343b4ea96ccbf2c6872263085d01";

  private static final String AGENT_JSON =
      "{\"addHosts\":[\"host.docker.internal:host-gateway\"],\"aliases\":[],\"args\":[],"
          + "\"entrypoint\":[],\"env\":{},\"explicitName\":\"qits-agent-qits\",\"extraLabels\":{\"qits.managed\":\"qits-projects\","
          + "\"qits.project\":\"qits\"},\"hostDockerSocket\":false,\"image\":\"registry.qits.wohlben.eu/qits/qits-projects-agent:2026.1003.130000\","
          + "\"init\":true,\"network\":\"qits-net\",\"pullPolicy\":\"MISSING\",\"security\":{\"capDropAll\":false,"
          + "\"cpus\":null,\"memory\":\"6g\",\"memorySwap\":\"6g\",\"noNewPrivileges\":false,"
          + "\"oomScoreAdj\":300,\"pidsLimit\":8192},\"sharedMounts\":[{\"containerPath\":\"/claude-home\","
          + "\"sharedName\":\"qits_shared_dot_claude\"},{\"containerPath\":\"/m2\",\"sharedName\":\"qits_shared_m2\"}],"
          + "\"user\":\"1000\",\"volumeMounts\":[{\"containerPath\":\"/workspace\",\"volumeName\":\"qits_project_qits\"}]}";

  private static final String BUILDKITD_HASH =
      "2ab658843633c82aa4a1f3bbd389b32b3af668d1cb27f603bef8cb38d63c0bb2";

  private static final String BUILDKITD_JSON =
      "{\"addHosts\":[],\"aliases\":[\"buildkitd\",\"qits-buildkitd\"],\"args\":[\"--oci-worker-no-process-sandbox\","
          + "\"--debug\"],\"entrypoint\":[\"buildkitd\",\"--addr\",\"tcp://0.0.0.0:1234\"],"
          + "\"env\":{},\"explicitName\":\"qits-ci-buildkitd\",\"extraLabels\":{\"owner\":\"qits-ci\"},"
          + "\"hostDockerSocket\":false,\"image\":\"moby/buildkit:v0.16.0\",\"init\":false,"
          + "\"network\":\"qits-net\",\"pullPolicy\":\"ALWAYS\",\"security\":{\"capDropAll\":true,"
          + "\"cpus\":\"1.5\",\"memory\":\"2g\",\"memorySwap\":null,\"noNewPrivileges\":true,"
          + "\"oomScoreAdj\":-100,\"pidsLimit\":1024},\"sharedMounts\":[],\"user\":\"buildkit\","
          + "\"volumeMounts\":[{\"containerPath\":\"/var/lib/buildkit\",\"volumeName\":\"qits_buildkit_state\"}]}";

  @Test
  void minimalSpecIsPinned() {
    assertGolden(minimal(), MINIMAL_HASH, MINIMAL_JSON);
  }

  @Test
  void workspaceSpecIsPinned() {
    assertGolden(workspace(), WORKSPACE_HASH, WORKSPACE_JSON);
  }

  @Test
  void adminSpecIsPinned() {
    assertGolden(admin(), ADMIN_HASH, ADMIN_JSON);
  }

  @Test
  void agentSpecIsPinned() {
    assertGolden(agent(), AGENT_HASH, AGENT_JSON);
  }

  @Test
  void buildkitdSpecIsPinned() {
    assertGolden(buildkitd(), BUILDKITD_HASH, BUILDKITD_JSON);
  }

  private static void assertGolden(ContainerSpec spec, String hash, String json) {
    assertEquals(hash, SpecFingerprint.hash(spec), "spec_hash");
    assertEquals(json, SpecFingerprint.persistedJson(spec), "spec_json");
    assertEquals(withoutEnv(spec), SpecFingerprint.fromPersistedJson(json), "spec_json read back");
  }

  /** What {@link SpecFingerprint} stores: the same spec with an empty environment. */
  private static ContainerSpec withoutEnv(ContainerSpec spec) {
    return new ContainerSpec(
        spec.image(),
        spec.entrypoint(),
        spec.args(),
        Map.of(),
        spec.extraLabels(),
        spec.network(),
        spec.aliases(),
        spec.addHosts(),
        spec.volumeMounts(),
        spec.sharedMounts(),
        spec.hostDockerSocket(),
        spec.security(),
        spec.pullPolicy(),
        spec.explicitName(),
        spec.user(),
        spec.init());
  }

  private static ContainerSpec minimal() {
    return ContainerSpec.builder("alpine:3").build();
  }

  private static ContainerSpec.Builder workspaceBuilder() {
    return ContainerSpec.builder("registry.qits.wohlben.eu/qits/qits-workspace-base:2026.1003.120000")
        .env("QITS_WORKSPACE_ID", "1234")
        .env("QITS_REPOSITORY", "qits-qits")
        .env("CLAUDE_CONFIG_DIR", "/claude-home/.claude")
        .env("MAVEN_OPTS", "-Dmaven.repo.local=/m2")
        .label("qits.repository", "qits-qits")
        .label("qits.workspace", "1234")
        .label("qits.branch", "task/qits-623/qits-785/qits-789")
        .label("qits.project", "qits")
        .network("qits-net")
        .addHost("host.docker.internal:host-gateway")
        .mount("qits_ws_1234_workspace", "/workspace")
        .shared("qits_shared_dot_claude", "/claude-home/.claude")
        .shared("qits_shared_m2", "/m2")
        .shared("qits_shared_pnpm", "/pnpm")
        .security(new SecurityPosture(false, false, "4g", "8g", 4096L, "2", 500))
        .pullPolicy(PullPolicy.MISSING)
        .name("qits-ws-1234-0a1b2c3d")
        .user("1000")
        .init(true);
  }

  private static ContainerSpec workspace() {
    return workspaceBuilder().build();
  }

  private static ContainerSpec admin() {
    return workspaceBuilder().hostDockerSocket(true).build();
  }

  /**
   * Through the canonical constructor, as qits-projects' {@code AgentContainerFactory} builds it:
   * null entrypoint, args and aliases, and a null pull policy, which normalize on the way in.
   */
  private static ContainerSpec agent() {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("QITS_PROJECT_ID", "qits");
    env.put("QITS_DOMAIN", "wohlben.eu");
    env.put("GIT_AUTHOR_NAME", "qits agent");
    Map<String, String> labels = new LinkedHashMap<>();
    labels.put("qits.managed", "qits-projects");
    labels.put("qits.project", "qits");
    return new ContainerSpec(
        "registry.qits.wohlben.eu/qits/qits-projects-agent:2026.1003.130000",
        null,
        null,
        env,
        labels,
        "qits-net",
        null,
        List.of("host.docker.internal:host-gateway"),
        List.of(new VolumeMount("qits_project_qits", "/workspace")),
        List.of(
            new SharedMount("qits_shared_dot_claude", "/claude-home"),
            new SharedMount("qits_shared_m2", "/m2")),
        false,
        new SecurityPosture(false, false, "6g", "6g", 8192L, null, 300),
        null,
        "qits-agent-qits",
        "1000",
        true);
  }

  private static ContainerSpec buildkitd() {
    return ContainerSpec.builder("moby/buildkit:v0.16.0")
        .entrypoint("buildkitd", "--addr", "tcp://0.0.0.0:1234")
        .args("--oci-worker-no-process-sandbox", "--debug")
        .env("BUILDKIT_STEP_LOG_MAX_SIZE", "10485760")
        .label("owner", "qits-ci")
        .network("qits-net")
        .alias("buildkitd")
        .alias("qits-buildkitd")
        .mount("qits_buildkit_state", "/var/lib/buildkit")
        .security(new SecurityPosture(true, true, "2g", null, 1024L, "1.5", -100))
        .pullPolicy(PullPolicy.ALWAYS)
        .name("qits-ci-buildkitd")
        .user("buildkit")
        .build();
  }
}
