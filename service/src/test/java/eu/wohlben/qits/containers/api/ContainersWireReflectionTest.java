package eu.wohlben.qits.containers.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.entity.DesiredState;
import eu.wohlben.qits.containers.entity.ObservedState;
import eu.wohlben.qits.containers.entity.VolumeState;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Guards the completeness of {@link ContainersWireReflection}: the holder's list and the wire's
 * family have to stay the same list.
 *
 * <p>Same honest limit as its {@code core} twin — on a JVM these types reflect whether anyone
 * registered them or not, so this catches a <em>forgotten</em> entry and never a broken
 * registration. It is a plain unit test rather than a {@code @QuarkusTest} because it asks a
 * question about two classes and needs no application, no database and no docker to answer it.
 */
public class ContainersWireReflectionTest {

  /** The enums the DTOs carry in from {@code core}, which no nested-type walk would find. */
  private static final Set<Class<?>> IMPORTED =
      Set.of(
          ContainerSpec.PullPolicy.class,
          LifecyclePolicy.Type.class,
          DesiredState.class,
          ObservedState.class,
          VolumeState.class);

  @Test
  public void everyWireTypeIsRegistered() {
    RegisterForReflection registration =
        ContainersWireReflection.class.getAnnotation(RegisterForReflection.class);
    assertNotNull(registration, "the annotation IS the class; without it that file is a no-op");
    Set<Class<?>> registered = Set.of(registration.targets());

    for (Class<?> nested : ContainersWire.class.getDeclaredClasses()) {
      assertTrue(
          registered.contains(nested),
          nested.getSimpleName()
              + " is on the wire and not in ContainersWireReflection. The whole family is"
              + " registered on purpose — see that class for why naming only the doubted entries"
              + " is the shape that rots.");
    }
    for (Class<?> imported : IMPORTED) {
      assertTrue(
          registered.contains(imported),
          imported.getName() + " is carried by a wire record and is not registered");
    }
  }
}
