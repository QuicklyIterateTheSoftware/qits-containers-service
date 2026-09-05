package eu.wohlben.qits.containers.api;

import eu.wohlben.qits.containers.api.ContainersWire.ContainerEnvelope;
import eu.wohlben.qits.containers.api.ContainersWire.DeleteResponse;
import eu.wohlben.qits.containers.api.ContainersWire.DestroyAllResponse;
import eu.wohlben.qits.containers.api.ContainersWire.DestroyedDto;
import eu.wohlben.qits.containers.api.ContainersWire.EndpointDto;
import eu.wohlben.qits.containers.api.ContainersWire.EnsureRequest;
import eu.wohlben.qits.containers.api.ContainersWire.ErrorBody;
import eu.wohlben.qits.containers.api.ContainersWire.ListResponse;
import eu.wohlben.qits.containers.api.ContainersWire.LogsResponse;
import eu.wohlben.qits.containers.api.ContainersWire.Recreate;
import eu.wohlben.qits.containers.api.ContainersWire.StateDto;
import eu.wohlben.qits.containers.control.ContainerRegistry;
import eu.wohlben.qits.containers.control.ContainersDriver;
import eu.wohlben.qits.containers.dockerhost.PlatformBuildkit;
import eu.wohlben.qits.containers.entity.DesiredState;
import eu.wohlben.qits.containers.entity.ObservedState;
import eu.wohlben.qits.containers.spec.ContainerSpec;
import eu.wohlben.qits.containers.spec.ContainersIdentifiers;
import eu.wohlben.qits.containers.spec.LifecyclePolicy;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The orchestration surface: a place is {@code {owner}/{workload}/{ref}}, and every route addresses
 * one or lists an owner's own.
 *
 * <p><b>Every route is guarded, reads included</b> — see {@link OwnerGuard}. The owner in the path
 * is the caller's own identity, so there is no route here a caller may use to look at another
 * module's inventory.
 *
 * <p><b>404 means the row is cleanly absent, and it means nothing else.</b> A read that could not
 * reach the database throws out of {@code ContainerRegistry}'s retried bracket and lands as a 5xx,
 * because the alternative is the failure mode this rule was written for: a caller reads 404,
 * concludes its workload was never started, and starts a second one. The githost cutover learned
 * that on fe26a6c and it applies twice as hard to a service whose 404 means "no container".
 *
 * <p><b>Every delete is idempotent</b>, so a caller retrying one gets the same 200 rather than a
 * 404 it has to special-case. That is the whole reason a boot reap can be a retry.
 */
@Path("/containers")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits:system")
public class ContainersResource {

  /**
   * How docker says the image was the problem.
   *
   * <p>Brittle by nature — docker's wording is not an API — so the list is <b>narrow</b> and the
   * match errs toward saying nothing: an unrecognised refusal is a place recorded {@code MISSING}
   * with docker's own text on it, never a false "nothing published this image". A bare
   * {@code "not found"} is deliberately absent, because {@code executable file not found in $PATH}
   * is a bad entrypoint and would be reported as a missing image.
   */
  private static final List<String> IMAGE_MISSING_MARKERS =
      List.of(
          "manifest unknown",
          "name unknown",
          "repository does not exist",
          "pull access denied",
          "no such image");

  @Inject OwnerGuard guard;

  @Inject ContainerRegistry registry;

  @Inject PlatformBuildkit buildkit;

  // --- the one write that starts something -------------------------------------------------------

  /**
   * Put a container at this place, or confirm the one already there.
   *
   * <p>201 the first time the place is seen, 200 afterwards. An {@code ensure} whose container did
   * not start is still a 200: the row exists, it says {@code MISSING}, and it carries what docker
   * said — which is a true answer the observer keeps working on. The one failure a caller can
   * actually act on is {@code IMAGE_MISSING}, and that is a 409.
   */
  @PUT
  @Path("/{owner}/{workload}/{ref}")
  // @Consumes is on this method rather than on the class: it is the only route that reads a body,
  // and a class-level one would answer 415 to a stop, a touch or a delete that carries none.
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(summary = "Put a container at this place, or confirm the one already there")
  @APIResponse(responseCode = "200", description = "The place, as it stands now")
  @APIResponse(responseCode = "201", description = "The place was new")
  @APIResponse(responseCode = "400", description = "A value this service will not put in an argv")
  @APIResponse(responseCode = "409", description = "SPEC_CONFLICT, NAME_TAKEN or IMAGE_MISSING")
  public Response ensure(
      @PathParam("owner") String owner,
      @PathParam("workload") String workload,
      @PathParam("ref") String ref,
      EnsureRequest request) {
    guard.require(owner);
    if (request == null) {
      throw new IllegalArgumentException("Invalid request: no body");
    }
    // Every CI step — and any socket-holding workload — is handed the platform builder's address,
    // unless the caller sent the key, whose value (empty included) wins. The rule and its reasons
    // are PlatformBuildkit's.
    ContainerSpec spec = buildkit.handOut(workload, ContainersWire.toSpec(request.spec()));
    LifecyclePolicy policy = ContainersWire.toPolicy(request.policy());

    ContainerRegistry.Ensured ensured =
        registry.ensure(
            owner, workload, ref, spec, policy, request.recreate() == Recreate.ifChanged);

    if (ensured.observed() == ObservedState.MISSING && imageMissing(ensured.detail())) {
      return Response.status(Response.Status.CONFLICT)
          .entity(
              new ErrorBody(
                  ContainersWire.IMAGE_MISSING,
                  "Could not start " + ensured.containerName() + ": " + ensured.detail()))
          .build();
    }
    return Response.status(ensured.created() ? Response.Status.CREATED : Response.Status.OK)
        .entity(
            new ContainerEnvelope(
                ensured.rowId(),
                ensured.containerName(),
                new StateDto(ensured.desired(), ensured.observed()),
                endpointOf(ensured.containerName(), spec),
                ensured.specHash(),
                ensured.created(),
                ensured.detail()))
        .build();
  }

  // --- reads --------------------------------------------------------------------------------------

  /** What is at this place. 404 only when no live row names it — see the class javadoc. */
  @GET
  @Path("/{owner}/{workload}/{ref}")
  @Operation(summary = "What is at this place")
  @APIResponse(responseCode = "200", description = "The place")
  @APIResponse(responseCode = "404", description = "No row names this place")
  public ContainerEnvelope status(
      @PathParam("owner") String owner,
      @PathParam("workload") String workload,
      @PathParam("ref") String ref) {
    guard.require(owner);
    return envelope(place(owner, workload, ref));
  }

  /** Every live place of this owner, oldest first. */
  @GET
  @Path("/{owner}")
  @Operation(summary = "Every live place of this owner")
  public ListResponse listOwner(@PathParam("owner") String owner) {
    guard.require(owner);
    return new ListResponse(registry.list(owner, null).stream().map(this::envelope).toList());
  }

  /** Every live place of one of this owner's workloads, oldest first. */
  @GET
  @Path("/{owner}/{workload}")
  @Operation(summary = "Every live place of one of this owner's workloads")
  public ListResponse listWorkload(
      @PathParam("owner") String owner, @PathParam("workload") String workload) {
    guard.require(owner);
    return new ListResponse(registry.list(owner, workload).stream().map(this::envelope).toList());
  }

  /**
   * The tail of what the container printed.
   *
   * <p><b>It works while the place is {@code EXITED}</b>, which is the case that matters: a
   * workload that died on its first breath has nothing else to offer, and that is why no argv in
   * this service carries {@code --rm}.
   */
  @GET
  @Path("/{owner}/{workload}/{ref}/logs")
  @Operation(summary = "A bounded tail of what this container printed")
  @APIResponse(responseCode = "404", description = "No row names this place")
  public LogsResponse logs(
      @PathParam("owner") String owner,
      @PathParam("workload") String workload,
      @PathParam("ref") String ref,
      @QueryParam("tail") @DefaultValue("0") int tail) {
    guard.require(owner);
    place(owner, workload, ref); // the 404, before any docker call
    ContainersDriver.LogTail logs = registry.logs(owner, workload, ref, tail);
    return new LogsResponse(logs.text(), logs.truncated());
  }

  // --- the writes that change what is running -----------------------------------------------------

  /** Stop what is here, leaving it restartable. */
  @POST
  @Path("/{owner}/{workload}/{ref}/stop")
  @Operation(summary = "Stop what is here, leaving it restartable")
  @APIResponse(responseCode = "404", description = "No row names this place")
  public ContainerEnvelope stop(
      @PathParam("owner") String owner,
      @PathParam("workload") String workload,
      @PathParam("ref") String ref) {
    guard.require(owner);
    ContainerRegistry.Stopped stopped = registry.stop(owner, workload, ref);
    if (stopped.rowId() == null) {
      throw new NotFoundException("Nothing is at " + owner + "/" + workload + "/" + ref);
    }
    return new ContainerEnvelope(
        stopped.rowId(),
        stopped.containerName(),
        new StateDto(DesiredState.STOPPED, stopped.observed()),
        new EndpointDto(stopped.containerName(), null, null, null),
        null,
        false,
        stopped.detail());
  }

  /**
   * Record that the owner still wants this workload. One column and no docker call — the idle sweep
   * reads it and nothing else does.
   */
  @POST
  @Path("/{owner}/{workload}/{ref}/touch")
  @Operation(summary = "Record that the owner still wants this workload")
  @APIResponse(responseCode = "204", description = "Touched")
  @APIResponse(responseCode = "404", description = "No row names this place")
  public Response touch(
      @PathParam("owner") String owner,
      @PathParam("workload") String workload,
      @PathParam("ref") String ref) {
    guard.require(owner);
    ContainersIdentifiers.requireWorkload(workload);
    ContainersIdentifiers.requireRef(ref);
    if (!registry.touch(owner, workload, ref)) {
      throw new NotFoundException("Nothing is at " + owner + "/" + workload + "/" + ref);
    }
    return Response.noContent().build();
  }

  /**
   * Remove what is here. Idempotent: a place that was already absent answers 200 with
   * {@code existed=false}, because the caller asked for nothing to be there and nothing is.
   *
   * @param volumes take the workload's own volumes with it. Never a shared one.
   * @param logs capture a bounded tail <b>before</b> the removal and return it — after it, there is
   *     nothing left to read. This is what a consumer's own reap does by hand today.
   */
  @DELETE
  @Path("/{owner}/{workload}/{ref}")
  @Operation(summary = "Remove what is here, optionally with its volumes and its last logs")
  @APIResponse(responseCode = "200", description = "It is gone, or it already was")
  public DeleteResponse delete(
      @PathParam("owner") String owner,
      @PathParam("workload") String workload,
      @PathParam("ref") String ref,
      @QueryParam("volumes") @DefaultValue("false") boolean volumes,
      @QueryParam("logs") @DefaultValue("false") boolean logs) {
    guard.require(owner);
    ContainerRegistry.Deleted deleted = registry.delete(owner, workload, ref, volumes, logs);
    return new DeleteResponse(
        deleted.rowId(),
        deleted.containerName(),
        deleted.existed(),
        logs ? deleted.logs() : null,
        deleted.detail());
  }

  /**
   * Remove every one of this owner's workloads of this kind that was created before an instant —
   * what a consumer's boot reap becomes.
   *
   * <p><b>{@code createdBefore} is required, and a missing one is a 400 rather than a default.</b>
   * It is what makes this a boot reap instead of a purge: an owner passes the instant it came up,
   * so a workload it started afterwards — including one started while this sweep runs — is not in
   * the set. A defaulted "now" would take those with it, and the caller would never see that it
   * had asked for something else.
   *
   * <p>It iterates the owner's <b>rows</b> and never a label listing. That difference is the whole
   * reason this service exists: two instances sharing one docker daemon cannot reach each other's
   * containers, because neither one's registry names the other's.
   */
  @DELETE
  @Path("/{owner}/{workload}")
  @Operation(summary = "Remove this owner's workloads of this kind created before an instant")
  @APIResponse(responseCode = "200", description = "One outcome per place")
  @APIResponse(responseCode = "400", description = "createdBefore is missing or unreadable")
  public DestroyAllResponse destroyAll(
      @PathParam("owner") String owner,
      @PathParam("workload") String workload,
      @QueryParam("createdBefore") String createdBefore) {
    guard.require(owner);
    Instant cut = instant(createdBefore);
    return new DestroyAllResponse(
        registry.destroyAll(owner, workload, cut).stream()
            .map(
                destroyed ->
                    new DestroyedDto(
                        destroyed.ownerRef(),
                        destroyed.containerName(),
                        destroyed.removed(),
                        destroyed.detail()))
            .toList());
  }

  // --- the shapes the routes above share ----------------------------------------------------------

  private ContainerRegistry.Place place(String owner, String workload, String ref) {
    Optional<ContainerRegistry.Place> found = registry.status(owner, workload, ref);
    return found.orElseThrow(
        () -> new NotFoundException("Nothing is at " + owner + "/" + workload + "/" + ref));
  }

  private ContainerEnvelope envelope(ContainerRegistry.Place place) {
    return new ContainerEnvelope(
        place.rowId(),
        place.containerName(),
        new StateDto(place.desired(), place.observed()),
        new EndpointDto(place.containerName(), place.network(), place.alias(), null),
        place.specHash(),
        false,
        place.detail());
  }

  private static EndpointDto endpointOf(String containerName, ContainerSpec spec) {
    return new EndpointDto(
        containerName,
        spec.network(),
        spec.aliases().isEmpty() ? containerName : spec.aliases().getFirst(),
        null);
  }

  /** An ISO instant, or a 400 that says which one it could not read. */
  private static Instant instant(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(
          "Invalid createdBefore: it is required, and a default would turn a boot reap into a"
              + " purge of everything this owner has running");
    }
    try {
      return Instant.parse(value.strip());
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException(
          "Invalid createdBefore: expected an ISO instant such as 2026-08-11T09:00:00Z");
    }
  }

  private static boolean imageMissing(String detail) {
    if (detail == null) {
      return false;
    }
    String text = detail.toLowerCase(Locale.ROOT);
    return IMAGE_MISSING_MARKERS.stream().anyMatch(text::contains);
  }
}
