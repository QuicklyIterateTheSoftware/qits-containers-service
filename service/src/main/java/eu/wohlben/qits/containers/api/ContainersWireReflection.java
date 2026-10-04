package eu.wohlben.qits.containers.api;

import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.entity.DesiredState;
import eu.wohlben.qits.containers.entity.ObservedState;
import eu.wohlben.qits.containers.entity.VolumeState;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * What a native image owes this service's own wire, registered whole rather than where it is
 * doubted.
 *
 * <p>Most of {@link ContainersWire} <em>is</em> auto-registered: Quarkus reflects the types it finds
 * on a resource method signature, and {@code ContainerEnvelope}, {@code ListResponse},
 * {@code LogsResponse}, {@code DeleteResponse}, {@code DestroyAllResponse},
 * {@code VolumeEnvelope} and {@code EnsureRequest} are all one of those. {@link
 * ContainersWire.ErrorBody} is not: it reaches a caller only as the entity of a {@code Response} —
 * from {@code ensure}'s {@code IMAGE_MISSING} arm, from {@code InvalidRequestMapper}, from
 * {@code SpecConflictMapper} and from {@code NameTakenMapper} — and an entity handed to a builder is
 * a value the build step's
 * signature scan never sees. So a native binary would answer every 400 and every 409 with a failure
 * to serialize the body explaining them: the errors would be lost exactly when a caller needs them.
 *
 * <p><b>The whole family is on the list anyway.</b> Naming only the doubted entry would make this
 * file a record of what one reading of the build step's analysis concluded, and the next record
 * added to {@link ContainersWire} would inherit that reading without anyone checking it. Registering
 * every nested type costs a few bytes of image and removes the question;
 * {@code ContainersWireReflectionTest} keeps the list and the family the same list.
 *
 * <p>The five types below the nested ones are the enums the DTOs carry in from {@code core}. They
 * overlap {@code spec/SpecReflection} by one entry ({@link ContainerSpec.PullPolicy}), deliberately:
 * each list is complete on its own, so deleting either holder cannot half-break the other's family.
 *
 * <p>Nothing else in this module hands a type to Jackson. The reverse tunnel's frames are Vert.x
 * {@code JsonObject}s, read and written field by field with no binding to a record at all, and the
 * docker driver parses Go-template output rather than JSON.
 *
 * <p><b>A JVM test cannot prove this works</b> — see {@code spec/SpecReflection} for the full
 * statement of what it can and cannot say. The native binary answering a 409 with a readable
 * {@code ErrorBody} is the proof.
 */
@RegisterForReflection(
    targets = {
      ContainersWire.VolumeMountDto.class,
      ContainersWire.SharedMountDto.class,
      ContainersWire.SecurityDto.class,
      ContainersWire.SpecDto.class,
      ContainersWire.PolicyDto.class,
      ContainersWire.Recreate.class,
      ContainersWire.EnsureRequest.class,
      ContainersWire.StateDto.class,
      ContainersWire.EndpointDto.class,
      ContainersWire.ContainerEnvelope.class,
      ContainersWire.ListResponse.class,
      ContainersWire.LogsResponse.class,
      ContainersWire.DeleteResponse.class,
      ContainersWire.DestroyedDto.class,
      ContainersWire.DestroyAllResponse.class,
      ContainersWire.VolumeEnvelope.class,
      ContainersWire.ErrorBody.class,
      ContainersWire.UsageDto.class,
      ContainersWire.UsageResponse.class,
      ContainersWire.ImageGcRequest.class,
      ContainersWire.ImageOutcomeDto.class,
      ContainersWire.ImageFailureDto.class,
      ContainersWire.ImageGcResponse.class,
      ContainersWire.VolumeGcRequest.class,
      ContainersWire.VolumeOutcomeDto.class,
      ContainersWire.VolumeFailureDto.class,
      ContainersWire.VolumeGcResponse.class,
      ContainersWire.BuildCacheGcRequest.class,
      ContainersWire.BuildCacheHostDto.class,
      ContainersWire.BuildCacheBuilderDto.class,
      ContainersWire.BuildCacheGcResponse.class,
      ContainerSpec.PullPolicy.class,
      LifecyclePolicy.Type.class,
      DesiredState.class,
      ObservedState.class,
      VolumeState.class
    })
public final class ContainersWireReflection {

  private ContainersWireReflection() {}
}
