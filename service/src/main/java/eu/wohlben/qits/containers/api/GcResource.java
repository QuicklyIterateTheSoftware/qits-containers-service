package eu.wohlben.qits.containers.api;

import eu.wohlben.qits.containers.api.ContainersWire.BuildCacheBuilderDto;
import eu.wohlben.qits.containers.api.ContainersWire.BuildCacheGcRequest;
import eu.wohlben.qits.containers.api.ContainersWire.BuildCacheGcResponse;
import eu.wohlben.qits.containers.api.ContainersWire.BuildCacheHostDto;
import eu.wohlben.qits.containers.api.ContainersWire.ImageFailureDto;
import eu.wohlben.qits.containers.api.ContainersWire.ImageGcRequest;
import eu.wohlben.qits.containers.api.ContainersWire.ImageGcResponse;
import eu.wohlben.qits.containers.api.ContainersWire.ImageOutcomeDto;
import eu.wohlben.qits.containers.api.ContainersWire.UsageDto;
import eu.wohlben.qits.containers.api.ContainersWire.UsageResponse;
import eu.wohlben.qits.containers.api.ContainersWire.VolumeFailureDto;
import eu.wohlben.qits.containers.api.ContainersWire.VolumeGcRequest;
import eu.wohlben.qits.containers.api.ContainersWire.VolumeGcResponse;
import eu.wohlben.qits.containers.api.ContainersWire.VolumeOutcomeDto;
import eu.wohlben.qits.containers.control.BuildCacheGc;
import eu.wohlben.qits.containers.control.ContainersDriver;
import eu.wohlben.qits.containers.control.GcUsage;
import eu.wohlben.qits.containers.control.ImageGc;
import eu.wohlben.qits.containers.control.VolumeGc;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The host's own stores: what they hold, and the three collections that shrink them.
 *
 * <p><b>These four routes are the platform's, not an owner's, and that is the one place this file
 * departs from every other resource here.</b> {@code ContainersResource} and
 * {@code VolumesResource} address a place whose {@code {owner}} is the caller's own name, so both
 * carry {@link OwnerGuard} on top of the machine role. An image is named by no owner: qits-ci built
 * it, qits-platform-deployments pinned it and a container of any module may be running from it, so
 * there is no owner in the path to compare a subject against and no honest way to invent one. What
 * guards these routes instead is the coarse machine role plus the rules the collections are made
 * of — see {@code control/ImageGc} and {@code control/VolumeGc}, where the keeping is decided.
 *
 * <p><b>The caller decides and this service performs.</b> Every policy value — the pins, the ages,
 * the keep-storage — arrives in the body, read once per run by qits-platform-orchestrator from the
 * services that own it. Nothing here has a default that would remove more than the caller asked
 * for: a missing {@code dryRun} is a dry run, a missing {@code minAge} protects nothing but removes
 * nothing extra either, and a real prune with no keep-storage is refused.
 *
 * <p><b>No route here touches a registry row</b>, and two of them read rows only to protect
 * something: the image sweep keeps what a live row names, and the volume sweep keeps what a row
 * claims. A collection that wrote a row would be able to erase the record of what it removed.
 */
@Path("/gc")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits:system")
public class GcResource {

  @Inject GcUsage usage;

  @Inject ImageGc images;

  @Inject VolumeGc volumes;

  @Inject BuildCacheGc buildCache;

  /**
   * What the host's four stores hold.
   *
   * <p>A read, and the only route here that answers 5xx for a docker that did not answer rather
   * than reporting per item: a usage nobody could measure must not come back as an empty host.
   */
  @GET
  @jakarta.annotation.security.RolesAllowed({"qits:system", "qits:agent"})
  @Path("/usage")
  @Operation(summary = "What the host's images, containers, volumes and build cache hold")
  @APIResponse(responseCode = "200", description = "The four stores, as docker reports them")
  public UsageResponse usage() {
    ContainersDriver.DiskUsage df = usage.read();
    return new UsageResponse(
        line(df.images()), line(df.containers()), line(df.volumes()), line(df.buildCache()));
  }

  /** Collect images nothing is using, nothing names and nobody pinned. */
  @POST
  @Path("/images")
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(summary = "Collect images nothing is using, nothing names and nobody pinned")
  @APIResponse(responseCode = "200", description = "What was removed, kept and refused")
  @APIResponse(responseCode = "400", description = "A minAge that is not an ISO 8601 duration")
  public ImageGcResponse images(ImageGcRequest request) {
    ImageGcRequest body =
        request == null ? new ImageGcRequest(null, null, List.of(), List.of()) : request;
    ImageGc.Result result =
        images.sweep(
            dryRun(body.dryRun()),
            duration(body.minAge(), "minAge"),
            body.keep(),
            body.keepPrefixes());
    return new ImageGcResponse(
        result.dryRun(),
        result.examined(),
        result.bytesReclaimed(),
        result.removed().stream().map(GcResource::imageOutcome).toList(),
        result.kept().stream().map(GcResource::imageOutcome).toList(),
        result.failed().stream()
            .map(failure -> new ImageFailureDto(failure.id(), failure.tags(), failure.error()))
            .toList());
  }

  /** Collect dangling volumes of the three classes this platform can name. */
  @POST
  @Path("/volumes")
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(summary = "Collect dangling volumes this platform can account for")
  @APIResponse(responseCode = "200", description = "What was removed, kept and refused")
  @APIResponse(responseCode = "400", description = "A minAge that is not an ISO 8601 duration")
  public VolumeGcResponse volumes(VolumeGcRequest request) {
    VolumeGcRequest body = request == null ? new VolumeGcRequest(null, null) : request;
    VolumeGc.Result result =
        volumes.sweep(dryRun(body.dryRun()), duration(body.minAge(), "minAge"));
    return new VolumeGcResponse(
        result.dryRun(),
        result.removed().stream().map(GcResource::volumeOutcome).toList(),
        result.kept().stream().map(GcResource::volumeOutcome).toList(),
        result.failed().stream()
            .map(failure -> new VolumeFailureDto(failure.name(), failure.error()))
            .toList());
  }

  /** Prune the host's build cache and every builder container's, down to a keep-storage. */
  @POST
  @Path("/build-cache")
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(summary = "Prune the build caches down to a keep-storage")
  @APIResponse(responseCode = "200", description = "The host's cache, then one row per builder")
  @APIResponse(responseCode = "400", description = "A real prune with no keepStorageBytes")
  // builderKeepStorageBytes is optional and defaults to keepStorageBytes — see the wire record.
  public BuildCacheGcResponse buildCache(BuildCacheGcRequest request) {
    BuildCacheGcRequest body =
        request == null ? new BuildCacheGcRequest(null, null, null) : request;
    boolean dryRun = dryRun(body.dryRun());
    long keepStorage = keepStorageBytes(body, dryRun);
    BuildCacheGc.Result result =
        buildCache.sweep(dryRun, keepStorage, builderKeepStorageBytes(body, keepStorage));
    return new BuildCacheGcResponse(
        result.dryRun(),
        new BuildCacheHostDto(
            result.host().reclaimedBytes(), result.host().detail(), result.host().error()),
        result.builders().stream()
            .map(
                builder ->
                    new BuildCacheBuilderDto(
                        builder.container(),
                        builder.reclaimedBytes(),
                        builder.detail(),
                        builder.error()))
            .toList());
  }

  // --- the readings a body needs before the collections see it ---------------------------------

  /** A missing {@code dryRun} is a dry run — see {@code ContainersWire.ImageGcRequest}. */
  private static boolean dryRun(Boolean value) {
    return value == null || value;
  }

  /** An ISO 8601 duration, or null for a field the caller left out. */
  private static Duration duration(String value, String field) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Duration.parse(value.strip());
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException(
          "Invalid " + field + ": expected an ISO 8601 duration such as PT6H");
    }
  }

  /**
   * How much cache a prune may keep. A dry run prunes nothing, so it may leave the field out; a
   * real one may not, because the value a missing number would mean is "keep nothing".
   */
  private static long keepStorageBytes(BuildCacheGcRequest body, boolean dryRun) {
    Long value = body.keepStorageBytes();
    if (value == null) {
      if (dryRun) {
        return 0;
      }
      throw new IllegalArgumentException(
          "Invalid keepStorageBytes: a prune that is not a dry run has to say how much to keep");
    }
    if (value < 0) {
      throw new IllegalArgumentException("Invalid keepStorageBytes: " + value);
    }
    return value;
  }

  /**
   * How much a builder container's own cache may keep. Optional, and it falls back to the host's
   * number rather than to zero — a caller that does not know the two caches are separate must not
   * be able to empty one of them by leaving a field out.
   */
  private static long builderKeepStorageBytes(BuildCacheGcRequest body, long keepStorage) {
    Long value = body.builderKeepStorageBytes();
    if (value == null) {
      return keepStorage;
    }
    if (value < 0) {
      throw new IllegalArgumentException("Invalid builderKeepStorageBytes: " + value);
    }
    return value;
  }

  private static UsageDto line(ContainersDriver.UsageLine line) {
    return new UsageDto(
        line.count(), line.active(), line.sizeBytes(), line.reclaimableBytes());
  }

  private static ImageOutcomeDto imageOutcome(ImageGc.Outcome outcome) {
    return new ImageOutcomeDto(
        outcome.id(), outcome.tags(), outcome.sizeBytes(), outcome.reason());
  }

  private static VolumeOutcomeDto volumeOutcome(VolumeGc.Outcome outcome) {
    return new VolumeOutcomeDto(outcome.name(), outcome.reason());
  }
}
