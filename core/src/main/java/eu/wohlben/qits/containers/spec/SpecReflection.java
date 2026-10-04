package eu.wohlben.qits.containers.spec;

import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.driver.spec.VolumeSpec;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * What a native image owes the spec records, because nothing else registers them.
 *
 * <p><b>The failure this class exists to stop is native-only and total.</b> {@code SpecFingerprint}
 * builds its <b>own</b> {@code ObjectMapper} — so that a consuming application's
 * {@code ObjectMapperCustomizer}s cannot reach a form that is hashed and compared byte for byte —
 * and a mapper built by hand is invisible to the build step that scans for what needs reflecting on.
 * Quarkus auto-registers the types it finds on a REST resource signature; {@link ContainerSpec} is
 * on no signature. It reaches Jackson only through the registry's own write, so on a native binary
 * it has no components to find and every {@code ensure} answers 500 with
 * {@code could not serialize eu.wohlben.qits.containers.spec.ContainerSpec} (its package then). Measured on the
 * 2026-08-11 rebootstrap, on the first real CI step this service was asked to start.
 *
 * <p>The fleet has been here twice: qits-eventstream's {@code EventPage}/{@code EventFrame} on
 * 2026-08-06 and the containers client's wire records the day after. Both fixes are this shape.
 *
 * <p><b>The records live in qits-containers-driver and this list stays here</b>, and both halves of
 * that are deliberate. The driver jar is plain Java with no Quarkus on its path, so it cannot carry
 * the annotation, and it serializes nothing — Jackson and {@code SpecFingerprint} are this service's,
 * so the registration is this service's too. {@code core} already has {@code quarkus-core} on its
 * compile path (through {@code quarkus-arc}) and is Jandex-indexed, so the annotation is discovered
 * by whatever application ships it, and {@code targets} names classes outside this jar's index. A
 * record component added to {@link ContainerSpec} upstream is caught here by {@code
 * SpecReflectionCoverageTest}, and before that by the library's own shape test and this service's
 * {@code SpecFingerprintGoldenTest}.
 *
 * <p><b>Both directions are on the list.</b> A type this service only writes needs the registration
 * as much as one it reads — on the writing side an unregistered record has no components to find,
 * which is exactly what the 500 above was.
 *
 * <p>Three things are deliberately <b>not</b> here:
 *
 * <ul>
 *   <li>{@link LifecyclePolicy} and its {@code Type}. A policy is never JSON on a row: it is stored
 *       as the {@code policy}, {@code idle_after_s} and {@code max_age_s} columns. It reaches
 *       Jackson only on the REST surface, where {@code api/ContainersWireReflection} covers it.
 *   <li>{@link VolumeSpec}. It is an argument to the docker seam and is never serialized.
 *   <li>The volume row's label map. {@code SpecFingerprint.write} is also called with a plain
 *       {@code Map<String, String>}, and Jackson serializes a map with a built-in serializer rather
 *       than by reflecting over a user type.
 * </ul>
 *
 * <p><b>A JVM test cannot prove this works</b> — on a JVM these types reflect whether anyone
 * registered them or not. What a test can prove is that the list is still <em>complete</em>, and
 * {@code SpecReflectionCoverageTest} is that: it walks {@link ContainerSpec} the way Jackson does
 * and fails when a reachable record or enum is missing here. The proof that the registration does
 * its job is the native binary starting a container.
 */
@RegisterForReflection(
    targets = {
      ContainerSpec.class,
      ContainerSpec.VolumeMount.class,
      ContainerSpec.SharedMount.class,
      ContainerSpec.SecurityPosture.class,
      ContainerSpec.PullPolicy.class
    })
public final class SpecReflection {

  private SpecReflection() {}
}
