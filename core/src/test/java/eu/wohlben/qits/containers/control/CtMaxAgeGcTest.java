package eu.wohlben.qits.containers.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.entity.DesiredState;
import eu.wohlben.qits.containers.entity.ObservedState;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The age collection, and the two things it must not do: reach a policy that declared no max age,
 * and reach a policy that is not run-once.
 *
 * <p>It also pins that the collection goes through {@code ContainerRegistry.delete} — the row is
 * {@code ABSENT}/{@code GONE} afterwards, which is the settled shape a hand-rolled remove here would
 * not produce and the boot sweep would then keep replaying.
 */
@QuarkusTest
public class CtMaxAgeGcTest extends CtTestSupport {

  private static final String OWNER = "qits-ci";
  private static final Instant BORN = Instant.parse("2026-08-11T12:00:00Z");
  private static final Duration MAX_AGE = Duration.ofHours(6);

  @Inject MaxAgeGc gc;

  @Test
  public void aRunOnceWorkloadPastItsMaxAgeIsCollectedThroughTheOrdinaryDeletePath() {
    UUID id =
        seed(
            OWNER,
            "step",
            "run-aged",
            LifecyclePolicy.ephemeral(MAX_AGE),
            DesiredState.RUNNING,
            ObservedState.EXITED,
            BORN,
            spec("alpine:3"));

    assertEquals(0, gc.sweepOnce(BORN.plus(Duration.ofHours(5))));
    assertEquals(DesiredState.RUNNING, row(id).desiredState);

    assertEquals(1, gc.sweepOnce(BORN.plus(MAX_AGE).plusSeconds(1)));

    assertEquals(DesiredState.ABSENT, row(id).desiredState);
    assertEquals(ObservedState.GONE, row(id).observedState);
    assertFalse(
        driver.calls().stream().anyMatch(call -> call.startsWith("logs:")),
        "the max age IS the moment the owner was given to stop caring about the output");
  }

  @Test
  public void aWorkloadThatDeclaredNoMaxAgeIsNeverCollected() {
    UUID id =
        seed(
            OWNER,
            "step",
            "run-forever",
            LifecyclePolicy.ephemeral(null),
            DesiredState.RUNNING,
            ObservedState.EXITED,
            BORN,
            spec("alpine:3"));

    assertEquals(0, gc.sweepOnce(Instant.parse("2030-01-01T00:00:00Z")));
    assertEquals(DesiredState.RUNNING, row(id).desiredState);
  }

  @Test
  public void aLongLivedWorkloadIsNeverCollectedHoweverOldItIs() {
    UUID id =
        seed(
            OWNER,
            "workspace",
            "ws-ancient",
            LifecyclePolicy.explicitLifetime(),
            DesiredState.RUNNING,
            ObservedState.RUNNING,
            BORN,
            spec("alpine:3"));

    assertEquals(0, gc.sweepOnce(Instant.parse("2030-01-01T00:00:00Z")));
    assertEquals(DesiredState.RUNNING, row(id).desiredState);
  }
}
