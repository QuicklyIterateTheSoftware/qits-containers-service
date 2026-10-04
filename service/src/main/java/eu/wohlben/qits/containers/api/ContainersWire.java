package eu.wohlben.qits.containers.api;

import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.entity.DesiredState;
import eu.wohlben.qits.containers.entity.ObservedState;
import eu.wohlben.qits.containers.entity.VolumeState;
import eu.wohlben.qits.containers.spec.ContainerLabels;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything this service accepts and answers on the wire, in one file.
 *
 * <p><b>They mirror {@code core}'s records rather than being them.</b> The domain records carry
 * their belts in their compact constructors, and a Jackson deserializer that ran those on the way
 * in would turn a hostile field into a 500 before any resource had said what was wrong with it. So
 * the wire types are plain carriers, the mapping into the domain is one method, and the belts fire
 * there — where an {@code IllegalArgumentException} is a 400 with the field named in it.
 *
 * <p><b>No entity is on this surface.</b> A caller reads a row through {@code
 * ContainerRegistry.Place}, which is already the row copied out as plain values; these records are
 * that shape with the names a caller uses.
 */
public final class ContainersWire {

  private ContainersWire() {}

  // --- what a caller asks for -------------------------------------------------------------------

  /** A named volume of the workload's own, and where it lands inside the container. */
  public record VolumeMountDto(String volumeName, String containerPath) {}

  /** One of the platform's shared volumes. A different type because ownership differs. */
  public record SharedMountDto(String sharedName, String containerPath) {}

  /** The sandbox. Every field optional: a null renders no flag, so "unset" is not "off". */
  public record SecurityDto(
      boolean capDropAll,
      boolean noNewPrivileges,
      String memory,
      String memorySwap,
      Long pidsLimit,
      String cpus,
      Integer oomScoreAdj) {}

  /** {@link ContainerSpec} on the wire. */
  public record SpecDto(
      String image,
      List<String> entrypoint,
      List<String> args,
      Map<String, String> env,
      Map<String, String> extraLabels,
      String network,
      List<String> aliases,
      List<String> addHosts,
      List<VolumeMountDto> volumeMounts,
      List<SharedMountDto> sharedMounts,
      boolean hostDockerSocket,
      SecurityDto security,
      ContainerSpec.PullPolicy pullPolicy,
      String explicitName,
      String user,
      Boolean init) {}

  /**
   * {@link LifecyclePolicy} on the wire. The two durations are seconds rather than an ISO string
   * because they are stored as seconds and a caller that reads a row back would otherwise get a
   * different spelling than it sent.
   */
  public record PolicyDto(LifecyclePolicy.Type type, Long idleAfterSeconds, Long maxAgeSeconds) {}

  /**
   * Whether a spec change may replace what is running.
   *
   * <p>Lower case, because these are the two words the contract spells and an enum constant is what
   * a caller sends. {@code never} is the default and the safe one: an owner that only wants the
   * place occupied gets what is there, told that it differs, rather than a restart it did not ask
   * for.
   */
  public enum Recreate {
    never,
    ifChanged
  }

  /** The body of the one write that starts something. */
  public record EnsureRequest(SpecDto spec, PolicyDto policy, Recreate recreate) {}

  // --- what this service answers with -----------------------------------------------------------

  /** What was asked for, and what the last look found. Never merged into one word. */
  public record StateDto(DesiredState desired, ObservedState observed) {}

  /**
   * Where a caller's own containers reach this workload.
   *
   * <p>{@code proxy} is always null today and is on the envelope anyway: the reverse tunnels two
   * modules carry by hand are meant to centralize here, and a caller that has already been reading
   * {@code endpoint} will find them without a second shape to learn.
   */
  public record EndpointDto(String containerName, String network, String alias, String proxy) {}

  /** One place, as every route that answers about one answers. */
  public record ContainerEnvelope(
      UUID id,
      String containerName,
      StateDto state,
      EndpointDto endpoint,
      String specHash,
      boolean created,
      String detail) {}

  public record ListResponse(List<ContainerEnvelope> containers) {}

  /** A bounded tail, and whether the front of it was dropped. */
  public record LogsResponse(String text, boolean truncated) {}

  /** {@code existed=false} is a success: the caller asked for nothing to be there, and nothing is. */
  public record DeleteResponse(
      UUID id, String containerName, boolean existed, String logTail, String detail) {}

  /** One place's outcome inside a destroy-all. */
  public record DestroyedDto(String ref, String containerName, boolean removed, String detail) {}

  public record DestroyAllResponse(List<DestroyedDto> destroyed) {}

  /** A volume an owner asked for by name. */
  public record VolumeEnvelope(
      UUID id, String owner, String name, VolumeState desired, boolean existed, String detail) {}

  /**
   * The one error shape. {@code code} is what a caller branches on — {@code SPEC_CONFLICT},
   * {@code NAME_TAKEN}, {@code IMAGE_MISSING}, {@code INVALID} — and {@code message} is the sentence
   * a person reads.
   */
  public record ErrorBody(String code, String message) {}

  /** A value this service will not put in an argv. 400. */
  public static final String INVALID = "INVALID";

  /** A spec change the workload's lifecycle policy cannot answer. 409. */
  public static final String SPEC_CONFLICT = "SPEC_CONFLICT";

  /**
   * The container name this place would claim is held by a live container of a different place. 409.
   *
   * <p>Its own code and not {@code SPEC_CONFLICT}, because the two ask for different answers: that
   * one says the policy cannot replace what is here, and a caller reading it deletes and asks again
   * under a new ref. This one says the name belongs to somebody else's running workload, which no
   * ref of the caller's changes.
   */
  public static final String NAME_TAKEN = "NAME_TAKEN";

  /** The registry has no such image, so the run had nothing to start. 409. */
  public static final String IMAGE_MISSING = "IMAGE_MISSING";

  // --- garbage collection -------------------------------------------------------------------
  //
  // The one family on this surface that is NOT about a place. Everything above is addressed to
  // {owner}/{workload}/{ref} and guarded by the owner in the path being the caller; these four are
  // about the HOST — its images, its dangling volumes, its build cache — which belong to no owner
  // and are therefore guarded by the machine role alone. The shapes are pinned by
  // qits-orchestrator-plan.md and restated by qits-platform-orchestrator, which builds against
  // them without seeing this file: a field renamed here is a step of the gc process that silently
  // reads null.

  /** One store's line of {@code docker system df}. */
  public record UsageDto(long count, long active, long sizeBytes, long reclaimableBytes) {}

  /** What the host's four stores hold. */
  public record UsageResponse(
      UsageDto images, UsageDto containers, UsageDto volumes, UsageDto buildCache) {}

  /**
   * What an image collection may keep.
   *
   * <p><b>{@code dryRun} absent means a dry run</b>, which is this record's one decision that is not
   * simply JSON's reading of its own absence. A body that forgot the field would otherwise remove
   * images, and the caller that always sends it — the orchestrator — is unaffected either way. The
   * same stance {@code createdBefore} takes on the boot reap: the destructive reading is never the
   * one a missing value gets.
   *
   * <p>{@code minAge} is an ISO 8601 duration ({@code PT6H}). It is the caller's policy and not this
   * service's: an absent one protects nothing by age, because inventing a grace here would put the
   * policy in two places.
   */
  public record ImageGcRequest(
      Boolean dryRun, String minAge, List<String> keep, List<String> keepPrefixes) {}

  /** One image and what was decided about it. */
  public record ImageOutcomeDto(String id, List<String> tags, long sizeBytes, String reason) {}

  /** One image docker refused to remove. */
  public record ImageFailureDto(String id, List<String> tags, String error) {}

  /** The whole image collection. In a dry run {@code removed} is what a real run would remove. */
  public record ImageGcResponse(
      boolean dryRun,
      int examined,
      long bytesReclaimed,
      List<ImageOutcomeDto> removed,
      List<ImageOutcomeDto> kept,
      List<ImageFailureDto> failed) {}

  /** What a volume collection may keep. Same {@code dryRun} stance as the image one. */
  public record VolumeGcRequest(Boolean dryRun, String minAge) {}

  /** One volume and what was decided about it. */
  public record VolumeOutcomeDto(String name, String reason) {}

  /** One volume docker refused, or could not be asked about. */
  public record VolumeFailureDto(String name, String error) {}

  /** The whole volume collection. */
  public record VolumeGcResponse(
      boolean dryRun,
      List<VolumeOutcomeDto> removed,
      List<VolumeOutcomeDto> kept,
      List<VolumeFailureDto> failed) {}

  /**
   * How much build cache each cache may keep.
   *
   * <p><b>A real prune with no {@code keepStorageBytes} is refused</b> rather than read as zero: a
   * missing number would mean "keep nothing", which is the one value nobody would leave a field out
   * to ask for. A dry run does not need it, because it prunes nothing.
   *
   * <p>{@code builderKeepStorageBytes} is what a {@code buildx_buildkit_*} container's own cache may
   * keep, and it is <b>optional</b>: absent, it is {@code keepStorageBytes}. It exists because a
   * bootstrap builder is only useful while a bootstrap runs, so the platform wants it kept far
   * smaller than the host's own cache — and defaulting to the host's number is what keeps a caller
   * that does not care about the distinction from having to know about it.
   */
  public record BuildCacheGcRequest(
      Boolean dryRun, Long keepStorageBytes, Long builderKeepStorageBytes) {}

  /** The host builder's cache. {@code error} is null when it worked. */
  public record BuildCacheHostDto(long reclaimedBytes, String detail, String error) {}

  /** One builder container's cache. Its {@code error} is its own and never the call's. */
  public record BuildCacheBuilderDto(
      String container, long reclaimedBytes, String detail, String error) {}

  /** The whole build-cache collection. */
  public record BuildCacheGcResponse(
      boolean dryRun, BuildCacheHostDto host, List<BuildCacheBuilderDto> builders) {}

  // --- the wire to the domain, and the belts fire here -------------------------------------------

  /**
   * The spec a caller sent, as the domain record. Every belt in the compact constructor runs, and
   * then the owner-label belt: a key inside {@link ContainerLabels#NS} is refused here, before any
   * row is written. The spec record lives in qits-containers-driver and cannot know who embeds it,
   * so that refusal is this service's to make (the driver makes it again on every argv).
   */
  public static ContainerSpec toSpec(SpecDto dto) {
    if (dto == null) {
      throw new IllegalArgumentException("Invalid request: no spec");
    }
    ContainerSpec spec = new ContainerSpec(
        dto.image(),
        dto.entrypoint(),
        dto.args(),
        dto.env(),
        dto.extraLabels(),
        dto.network(),
        dto.aliases(),
        dto.addHosts(),
        dto.volumeMounts() == null
            ? List.of()
            : dto.volumeMounts().stream()
                .map(m -> new ContainerSpec.VolumeMount(m.volumeName(), m.containerPath()))
                .toList(),
        dto.sharedMounts() == null
            ? List.of()
            : dto.sharedMounts().stream()
                .map(m -> new ContainerSpec.SharedMount(m.sharedName(), m.containerPath()))
                .toList(),
        dto.hostDockerSocket(),
        dto.security() == null
            ? ContainerSpec.SecurityPosture.none()
            : new ContainerSpec.SecurityPosture(
                dto.security().capDropAll(),
                dto.security().noNewPrivileges(),
                dto.security().memory(),
                dto.security().memorySwap(),
                dto.security().pidsLimit(),
                dto.security().cpus(),
                dto.security().oomScoreAdj()),
        dto.pullPolicy(),
        dto.explicitName(),
        dto.user(),
        // Nullable on the wire and a plain false in the domain: a caller written before the field
        // existed sends no `init` at all, and an absent one has to mean the behaviour it had then.
        dto.init() != null && dto.init());
    ContainerLabels.NS.requireOwnerLabels(spec.extraLabels());
    return spec;
  }

  /** The policy a caller sent. A body with no policy is refused rather than defaulted. */
  public static LifecyclePolicy toPolicy(PolicyDto dto) {
    if (dto == null || dto.type() == null) {
      throw new IllegalArgumentException(
          "Invalid lifecycle policy: name one of EPHEMERAL, IDLE_STOP or EXPLICIT");
    }
    return new LifecyclePolicy(dto.type(), seconds(dto.idleAfterSeconds()), seconds(dto.maxAgeSeconds()));
  }

  private static Duration seconds(Long value) {
    if (value == null) {
      return null;
    }
    if (value <= 0) {
      throw new IllegalArgumentException("Invalid duration: " + value + " seconds");
    }
    return Duration.ofSeconds(value);
  }
}
