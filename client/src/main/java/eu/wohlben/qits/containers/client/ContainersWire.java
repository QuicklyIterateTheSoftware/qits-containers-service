package eu.wohlben.qits.containers.client;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything qits-containers accepts and answers, as a caller names it.
 *
 * <p><b>These mirror the service's own wire records rather than being them.</b> The service module
 * has a {@code ContainersWire} of its own and neither jar sees the other: the agreement is the JSON
 * body, and each side names its own types over it. That is what lets a consumer be built, tested
 * and released without this repository on its classpath at all — and it is the same trade the poms
 * here make with their duplicated versions.
 *
 * <p><b>Nothing here validates anything.</b> The belts are the service's, where a refusal can name
 * the field and come back as a 400 with {@code INVALID} on it. A record that checked its own
 * arguments would turn that into an exception thrown before the request left, in a caller that has
 * no way to report it, and the two sets of rules would drift the first time the service's widened.
 *
 * <p><b>The enums are restated, and a value this client does not know reads as null.</b> The
 * service's catalogue grows — {@code ObservedState} has gained a value before — and a client that
 * threw on an unrecognised word would turn "the service learned something" into a broken consumer
 * that has to be released in lock step. See {@link ContainersJson} for the mapper flag that makes
 * that true and for what it costs.
 */
public final class ContainersWire {

  private ContainersWire() {}

  // --- what a caller asks for ---------------------------------------------------------------------

  /** A named volume of the workload's own, and where it lands inside the container. */
  public record VolumeMount(String volumeName, String containerPath) {}

  /** One of the platform's shared volumes. A different type because ownership differs. */
  public record SharedMount(String sharedName, String containerPath) {}

  /** The sandbox. Every field optional: a null renders no flag, so "unset" is not "off". */
  public record Security(
      boolean capDropAll,
      boolean noNewPrivileges,
      String memory,
      String memorySwap,
      Long pidsLimit,
      String cpus,
      Integer oomScoreAdj) {

    /** No flags at all — for a workload the platform itself wrote and runs unsandboxed. */
    public static Security none() {
      return new Security(false, false, null, null, null, null, null);
    }
  }

  /** Whether the service pulls the image before running it. */
  public enum PullPolicy {
    MISSING,
    ALWAYS,
    NEVER
  }

  /**
   * Everything one container is started with. Seventeen fields, so build it rather than call it.
   *
   * <p>{@code privateEdge} is the one that is not a property of the container but an ask of the
   * service (qits-1991): true joins the container to the {@code qits-private} overlay and puts the
   * private edge's VIP first among its resolvers, ahead of the host's own. The service resolves
   * both off its docker daemon at each run, because only it can — see {@link #withPrivateEdge}.
   * Null and false are the same statement.
   */
  public record Spec(
      String image,
      List<String> entrypoint,
      List<String> args,
      Map<String, String> env,
      Map<String, String> extraLabels,
      String network,
      List<String> aliases,
      List<String> addHosts,
      List<VolumeMount> volumeMounts,
      List<SharedMount> sharedMounts,
      boolean hostDockerSocket,
      Security security,
      PullPolicy pullPolicy,
      String explicitName,
      String user,
      Boolean init,
      Boolean privateEdge) {

    /**
     * The sixteen fields every caller built before {@code privateEdge} existed, kept so a
     * positional call written then still compiles. It asks for no private edge, which is what such
     * a caller meant — and a null is left out of the body altogether ({@link ContainersJson}), so
     * the request it sends is byte for byte the one it sent before.
     */
    public Spec(
        String image,
        List<String> entrypoint,
        List<String> args,
        Map<String, String> env,
        Map<String, String> extraLabels,
        String network,
        List<String> aliases,
        List<String> addHosts,
        List<VolumeMount> volumeMounts,
        List<SharedMount> sharedMounts,
        boolean hostDockerSocket,
        Security security,
        PullPolicy pullPolicy,
        String explicitName,
        String user,
        Boolean init) {
      this(
          image,
          entrypoint,
          args,
          env,
          extraLabels,
          network,
          aliases,
          addHosts,
          volumeMounts,
          sharedMounts,
          hostDockerSocket,
          security,
          pullPolicy,
          explicitName,
          user,
          init,
          null);
    }

    /** The two fields the service refuses a spec without, and nothing else. */
    public static Spec of(String image, String network) {
      return new Spec(
          image, null, null, null, null, network, null, null, null, null, false, null, null, null,
          null, null, null);
    }

    /**
     * The same spec, run as somebody. Null or empty keeps the image's default, which is root.
     *
     * <p>It is a spec field rather than something a script does because a sandboxed container cannot
     * change user from the inside: {@code --cap-drop=ALL} takes CAP_SETUID and CAP_SETGID away, so
     * {@code su} fails there whatever the script says. The image has to carry a passwd entry for the
     * name — anything calling {@code getpwuid} needs one.
     */
    public Spec runAs(String value) {
      return new Spec(
          image,
          entrypoint,
          args,
          env,
          extraLabels,
          network,
          aliases,
          addHosts,
          volumeMounts,
          sharedMounts,
          hostDockerSocket,
          security,
          pullPolicy,
          explicitName,
          value,
          init,
          privateEdge);
    }

    /**
     * The same spec, with tini as PID 1 — {@code docker run --init}. Null and false are the same
     * statement, and both are what a spec that says nothing means.
     *
     * <p>Ask for it when the container hosts something long-lived that spawns processes of its own:
     * PID 1 inherits every orphan and reaps none unless it was written to, so a session container
     * collects zombies for as long as it runs. A container that runs one process and exits needs
     * none of that.
     */
    public Spec withInit(boolean value) {
      return new Spec(
          image,
          entrypoint,
          args,
          env,
          extraLabels,
          network,
          aliases,
          addHosts,
          volumeMounts,
          sharedMounts,
          hostDockerSocket,
          security,
          pullPolicy,
          explicitName,
          user,
          value,
          privateEdge);
    }

    /**
     * The same spec, on the private edge (qits-1991): joined to the {@code qits-private} overlay,
     * resolving through the private edge's VIP first and the host's own resolvers after it. Null and
     * false are the same statement, and both are what a spec that says nothing means.
     *
     * <p>The service resolves the network and the addresses off its own docker daemon each time it
     * runs the container, and never stores them: a spec with the flag and its twin without it carry
     * the same {@code specHash}, so turning it on recreates nothing — the container takes the edge at
     * its next run. A host with no {@code qits-private} network gets neither the join nor the
     * resolver, and the container runs as if the flag were off.
     */
    public Spec withPrivateEdge(boolean value) {
      return new Spec(
          image,
          entrypoint,
          args,
          env,
          extraLabels,
          network,
          aliases,
          addHosts,
          volumeMounts,
          sharedMounts,
          hostDockerSocket,
          security,
          pullPolicy,
          explicitName,
          user,
          init,
          value);
    }
  }

  /** How long a workload is meant to live, and what may happen to it when it stops. */
  public enum PolicyType {
    /** Runs once and exits. No restart policy, and a recreate is refused with SPEC_CONFLICT. */
    EPHEMERAL,
    /** Long-lived but stoppable: idle past {@code idleAfterSeconds} and it is stopped. */
    IDLE_STOP,
    /** Lives until somebody says otherwise. Only a delete ends it. */
    EXPLICIT
  }

  /**
   * The lifecycle policy. Seconds rather than an ISO duration because the service stores seconds,
   * and a caller reading a row back would otherwise get a different spelling than it sent.
   */
  public record Policy(PolicyType type, Long idleAfterSeconds, Long maxAgeSeconds) {

    public static Policy ephemeral(Long maxAgeSeconds) {
      return new Policy(PolicyType.EPHEMERAL, null, maxAgeSeconds);
    }

    public static Policy idleStop(Long idleAfterSeconds) {
      return new Policy(PolicyType.IDLE_STOP, idleAfterSeconds, null);
    }

    public static Policy explicitLifetime() {
      return new Policy(PolicyType.EXPLICIT, null, null);
    }
  }

  /**
   * Whether a spec change may replace what is running.
   *
   * <p>Lower case, because these are the two words the contract spells. {@link #never} is the safe
   * one: an owner that only wants the place occupied gets what is there, told that it differs,
   * rather than a restart it did not ask for.
   */
  public enum Recreate {
    never,
    ifChanged
  }

  /** The body of the one write that starts something. */
  public record EnsureRequest(Spec spec, Policy policy, Recreate recreate) {

    /** The ordinary ask: put this here, and leave what is already here alone. */
    public static EnsureRequest of(Spec spec, Policy policy) {
      return new EnsureRequest(spec, policy, Recreate.never);
    }
  }

  // --- what the service answers with ---------------------------------------------------------------

  /** What the owner asked for. */
  public enum Desired {
    RUNNING,
    STOPPED,
    ABSENT
  }

  /**
   * What the service's last look at the host found.
   *
   * <p>{@code MISSING} is "the container is not there and nobody asked for that"; {@code GONE} is
   * "it is not there because it was removed". The service never merges them and neither may a
   * caller reading them.
   */
  public enum Observed {
    PENDING,
    STARTING,
    RUNNING,
    EXITED,
    MISSING,
    GONE
  }

  /** What was asked for, and what the last look found. Never merged into one word. */
  public record State(Desired desired, Observed observed) {}

  /**
   * Where a caller's own containers reach this workload.
   *
   * <p>{@code proxy} is always null today: the data plane arrives behind it, and a caller already
   * reading {@code endpoint} will find it without a second shape to learn.
   */
  public record Endpoint(String containerName, String network, String alias, String proxy) {}

  /** One place, as every route that answers about one answers. */
  public record Envelope(
      UUID id,
      String containerName,
      State state,
      Endpoint endpoint,
      String specHash,
      boolean created,
      String detail) {}

  /** The listing body. A caller reads {@link ContainersClient#list} and gets the list itself. */
  public record Listing(List<Envelope> containers) {}

  /** A bounded tail, and whether the front of it was dropped. */
  public record LogTail(String text, boolean truncated) {}

  /** {@code existed=false} is a success: the caller asked for nothing to be there, and nothing is. */
  public record DeleteOutcome(
      UUID id, String containerName, boolean existed, String logTail, String detail) {}

  /** One place's outcome inside a destroy-all. */
  public record Destroyed(String ref, String containerName, boolean removed, String detail) {}

  /** The destroy-all body. {@link ContainersClient#destroyAll} hands back the list itself. */
  public record DestroyAllOutcome(List<Destroyed> destroyed) {}

  /** Whether a volume should be there. Two values, because a volume does not run and cannot exit. */
  public enum VolumeState {
    PRESENT,
    ABSENT
  }

  /** A volume an owner asked for by name. */
  public record VolumeEnvelope(
      UUID id, String owner, String name, VolumeState desired, boolean existed, String detail) {}

  /**
   * The one error shape. {@code code} is what a caller branches on and {@code message} is the
   * sentence a person reads.
   */
  public record ErrorBody(String code, String message) {}

  /** A value the service will not put in an argv. 400. */
  public static final String INVALID = "INVALID";

  /** A spec change the workload's lifecycle policy cannot answer. 409. */
  public static final String SPEC_CONFLICT = "SPEC_CONFLICT";

  /**
   * The container name this place would claim is held by a live container of a different place. 409.
   *
   * <p>Distinct from {@link #SPEC_CONFLICT} because the two want different answers: that one is
   * answered by deleting and asking again under a new ref, and this one is not answered by any ref
   * of the caller's — the name belongs to somebody else's running workload until it stops.
   */
  public static final String NAME_TAKEN = "NAME_TAKEN";

  /** The registry has no such image, so the run had nothing to start. 409. */
  public static final String IMAGE_MISSING = "IMAGE_MISSING";

  /**
   * A response arrived and this client could not read it: not a code the service ever sends, and
   * this jar's own word for it.
   *
   * <p>It is a {@link ContainersAnswer.Refused} and never an {@link ContainersAnswer.Unreachable},
   * which is the whole reason it exists. Something answered — the network is fine, the service is
   * up, the deadline was met — so the evidence is about the response and not about reachability,
   * and a caller that retried this forever would be retrying against a body that will not change.
   */
  public static final String UNREADABLE = "UNREADABLE";
}
