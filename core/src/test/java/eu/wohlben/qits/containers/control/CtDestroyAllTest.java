package eu.wohlben.qits.containers.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.entity.DesiredState;
import eu.wohlben.qits.containers.entity.ObservedState;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What qits-ci's host-wide boot reap becomes: an owner destroying its own workloads, by row.
 *
 * <p><b>The claim is a scope, and it is asserted as an absence.</b> qits-ci removes every container
 * carrying its label on the daemon it talks to, so two instances sharing one docker daemon reap each
 * other's running steps — which is a constraint written into that repository's README. Scoped to
 * rows, an instance can only reach what its own registry named, and the fake's call log is what
 * proves another owner's containers were never even addressed.
 */
@QuarkusTest
public class CtDestroyAllTest extends CtTestSupport {

  private static final Instant BOOT = Instant.parse("2026-08-11T12:00:00Z");

  private UUID before(String owner, String workload, String ref) {
    return seed(
        owner,
        workload,
        ref,
        LifecyclePolicy.ephemeral(null),
        DesiredState.RUNNING,
        ObservedState.RUNNING,
        BOOT.minus(Duration.ofMinutes(5)),
        spec("alpine:3"));
  }

  @Test
  public void itDestroysOnlyTheNamedOwnersWorkloadsFromBeforeTheInstant() {
    UUID mine = before("qits-ci", "step", "run-old");
    UUID mineToo = before("qits-ci", "step", "run-older");
    UUID otherWorkload = before("qits-ci", "workspace", "ws-old");
    UUID otherOwner = before("qits-workspaces", "step", "ws-someone-else");
    UUID startedAfterBoot =
        seed(
            "qits-ci",
            "step",
            "run-new",
            LifecyclePolicy.ephemeral(null),
            DesiredState.RUNNING,
            ObservedState.RUNNING,
            BOOT.plus(Duration.ofSeconds(30)),
            spec("alpine:3"));

    List<ContainerRegistry.Destroyed> outcomes = registry.destroyAll("qits-ci", "step", BOOT);

    assertEquals(2, outcomes.size());
    assertTrue(outcomes.stream().allMatch(ContainerRegistry.Destroyed::removed));
    assertEquals(DesiredState.ABSENT, row(mine).desiredState);
    assertEquals(ObservedState.GONE, row(mine).observedState);
    assertEquals(DesiredState.ABSENT, row(mineToo).desiredState);

    assertEquals(
        DesiredState.RUNNING, row(otherWorkload).desiredState, "another workload of the same owner");
    assertEquals(DesiredState.RUNNING, row(otherOwner).desiredState, "another owner entirely");
    assertEquals(
        DesiredState.RUNNING,
        row(startedAfterBoot).desiredState,
        "createdBefore is what makes this a boot reap rather than a purge");
  }

  @Test
  public void noContainerOfAnotherOwnerIsEvenAddressed() {
    before("qits-ci", "step", "run-target");
    before("qits-workspaces", "step", "ws-bystander");
    String bystander = nameOf("qits-workspaces", "step", "ws-bystander");

    registry.destroyAll("qits-ci", "step", BOOT);

    assertFalse(
        driver.calls().stream().anyMatch(call -> call.endsWith(bystander)),
        "an owner reaches its own rows and nothing else — there is no listing here to widen");
  }

  @Test
  public void destroyingWhatIsAlreadyGoneIsASuccessWithNothingToDo() {
    List<ContainerRegistry.Destroyed> outcomes =
        registry.destroyAll("qits-ci", "step", Instant.now());

    assertEquals(List.of(), outcomes);
    assertEquals(List.of(), driver.calls());
  }

  @Test
  public void aDestroyCapturesNoLogsAndTakesNoVolumes() {
    before("qits-ci", "step", "run-quiet");

    registry.destroyAll("qits-ci", "step", BOOT);

    assertFalse(
        driver.calls().stream().anyMatch(call -> call.startsWith("logs:")),
        "nobody is holding a connection waiting to read them");
    assertFalse(driver.calls().stream().anyMatch(call -> call.startsWith("removeVolume:")));
  }
}
