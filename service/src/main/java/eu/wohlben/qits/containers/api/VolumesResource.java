package eu.wohlben.qits.containers.api;

import eu.wohlben.qits.containers.api.ContainersWire.VolumeEnvelope;
import eu.wohlben.qits.containers.control.ContainerRegistry;
import eu.wohlben.qits.containers.entity.VolumeState;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * Volumes an owner asks for <b>by name</b> — one that outlives every container that mounts it.
 *
 * <p>A workload's own volumes are made by {@code ensure} along with its container and taken by its
 * delete; this surface is for the other kind, the store a caller wants to exist before anything is
 * started and to survive everything it starts. Both write the same table, so both are covered by
 * the same rule: <b>a volume no row names is somebody else's and is never removed.</b>
 *
 * <p>The platform's three shared volumes are not reachable here and never will be. They are the
 * platform's rather than any owner's, they carry no row on purpose, and {@code SharedResources}
 * makes sure they exist at boot precisely so nobody has to ask.
 */
// No @Consumes anywhere here: every route names the volume in its path and none reads a body, so a
// class-level one would answer 415 to a request that is complete.
@Path("/volumes")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits:system")
public class VolumesResource {

  @Inject OwnerGuard guard;

  @Inject ContainerRegistry registry;

  /** Make sure this owner has this volume. Idempotent — docker's own create is. */
  @PUT
  @Path("/{owner}/{name}")
  @Operation(summary = "Make sure this owner has this volume")
  @APIResponse(responseCode = "200", description = "The volume, claimed by a row")
  @APIResponse(responseCode = "400", description = "Not a volume name this service will use")
  public VolumeEnvelope ensure(
      @PathParam("owner") String owner, @PathParam("name") String name) {
    guard.require(owner);
    ContainerRegistry.VolumeOutcome outcome = registry.ensureVolume(owner, name);
    return new VolumeEnvelope(
        outcome.rowId(), owner, name, VolumeState.PRESENT, outcome.existed(), outcome.detail());
  }

  /** The row claiming this volume. 404 only when this owner claims none by that name. */
  @GET
  @jakarta.annotation.security.RolesAllowed({"qits:system", "qits:agent"})
  @Path("/{owner}/{name}")
  @Operation(summary = "The row claiming this volume")
  @APIResponse(responseCode = "404", description = "This owner claims no volume by that name")
  public VolumeEnvelope status(@PathParam("owner") String owner, @PathParam("name") String name) {
    guard.require(owner);
    ContainerRegistry.Volume volume =
        registry
            .volume(owner, name)
            .orElseThrow(() -> new NotFoundException(owner + " claims no volume named " + name));
    return new VolumeEnvelope(
        volume.rowId(), volume.owner(), volume.name(), volume.desired(), true, null);
  }

  /**
   * Take this owner's volume away. Idempotent: one that was already absent answers 200 with
   * {@code existed=false}.
   *
   * <p>A remove docker refused leaves the row {@code ABSENT} rather than dropping it, which is
   * exactly what {@code VolumeReconcile} replays — so a failed delete is a delete that finishes
   * later rather than one that has to be asked for again.
   */
  @DELETE
  @Path("/{owner}/{name}")
  @Operation(summary = "Take this owner's volume away")
  @APIResponse(responseCode = "200", description = "It is gone, or it already was")
  public VolumeEnvelope delete(@PathParam("owner") String owner, @PathParam("name") String name) {
    guard.require(owner);
    ContainerRegistry.VolumeOutcome outcome = registry.deleteVolume(owner, name);
    return new VolumeEnvelope(
        outcome.rowId(),
        owner,
        name,
        outcome.ok() ? VolumeState.ABSENT : VolumeState.PRESENT,
        outcome.existed(),
        outcome.detail());
  }
}
