package eu.wohlben.qits.containers.stories.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bodies a story sends, in the shape {@code api/ContainersWire} binds — and nothing that reaches
 * for the domain records to build them.
 *
 * <p><b>Plain maps on purpose.</b> A story is a caller, and a caller of this service is another
 * platform module holding {@code qits-containers-client}'s mirror of the wire or writing the JSON
 * itself. Building the request out of {@code ContainersWire.EnsureRequest} would let a field renamed
 * on the service's own records travel silently into every story, which is precisely the drift the
 * two mirrored wire types exist to catch. What a story asserts is what a consumer sends.
 *
 * <p>Every name in here is a stable literal — see {@link StoryTarget} on why a stamped one would
 * move each story's {@code networkHash}.
 */
public final class StoryWorkloads {

  /** What a build step is called in this service's vocabulary — qits-ci's own workload name. */
  public static final String STEP = "step";

  /** What a workspace is called. qits-workspaces owns these rows and nobody else may. */
  public static final String WORKSPACE = "workspace";

  /** The volume the lifecycle story's workload owns, and which its delete may take with it. */
  public static final String STEP_VOLUME = "qits-story-step-work";

  private StoryWorkloads() {}

  /**
   * The minimum spec: an image, and the network every container of this platform is addressed on.
   *
   * <p>{@code pullPolicy} is {@code MISSING}, which renders <b>no docker call at all</b> — docker's
   * own {@code run} fetches an image the host does not have, and that is exactly what MISSING means.
   * A story that wanted the pull to be its own recorded outcome asks for {@link #alwaysPull}.
   */
  public static Map<String, Object> spec(String image) {
    Map<String, Object> spec = new LinkedHashMap<>();
    spec.put("image", image);
    spec.put("network", StoryTarget.NETWORK);
    spec.put("pullPolicy", "MISSING");
    return spec;
  }

  /** The same spec, with the explicit {@code docker pull} an {@code ALWAYS} policy makes. */
  public static Map<String, Object> alwaysPull(String image) {
    Map<String, Object> spec = spec(image);
    spec.put("pullPolicy", "ALWAYS");
    return spec;
  }

  /** …and one named volume of the workload's own, which its delete may take with it. */
  public static Map<String, Object> withOwnVolume(
      Map<String, Object> spec, String volumeName, String containerPath) {
    spec.put(
        "volumeMounts",
        List.of(Map.of("volumeName", volumeName, "containerPath", containerPath)));
    return spec;
  }

  /** …and the owner's own bookkeeping labels, which are checked against this service's namespace. */
  public static Map<String, Object> withExtraLabels(
      Map<String, Object> spec, Map<String, String> labels) {
    spec.put("extraLabels", labels);
    return spec;
  }

  /**
   * The body of the one write that starts something.
   *
   * <p>{@code recreate} is left at the contract's own default — {@code never} — because that is what
   * an owner which only wants the place occupied asks for, and it is what makes the second ensure of
   * an unchanged place a confirmation rather than a restart.
   */
  public static Map<String, Object> ensure(Map<String, Object> spec, String policyType) {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("spec", spec);
    request.put("policy", Map.of("type", policyType));
    return request;
  }

  /**
   * {@code EXPLICIT}: it runs until somebody says otherwise. The lifecycle stories use it because it
   * is the policy whose container is <b>kept</b> — an {@code EPHEMERAL} one has done its work when
   * it exits, so a second ensure of one is a {@code KEEP} rather than the confirmation a story about
   * "the place is already occupied" is about.
   */
  public static Map<String, Object> explicit(Map<String, Object> spec) {
    return ensure(spec, "EXPLICIT");
  }
}
