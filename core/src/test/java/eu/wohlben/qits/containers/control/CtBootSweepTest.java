package eu.wohlben.qits.containers.control;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.entity.CtContainer;
import eu.wohlben.qits.containers.entity.DesiredState;
import eu.wohlben.qits.containers.entity.ObservedState;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The restart story, driven straight through {@link BootSweep#sweepOnce()} — the
 * {@code PdSweepAdoptionTest} arrangement, with rows written as a dead process would have left them.
 *
 * <p><b>The claim that matters most is an absence.</b> A container that is still running is left
 * running: the fake's call log is asserted to carry no {@code stop:} and no {@code remove:} for it,
 * which is the only way to say "adopted" rather than "happened to end up RUNNING".
 */
@QuarkusTest
public class CtBootSweepTest extends CtTestSupport {

  private static final String OWNER = "qits-ci";

  @Inject BootSweep sweep;

  @Test
  public void aRunningContainerIsAdoptedAndNothingIsDoneToIt() {
    UUID id =
        seed(
            OWNER,
            "step",
            "run-adopt",
            LifecyclePolicy.ephemeral(null),
            DesiredState.RUNNING,
            ObservedState.STARTING);
    String name = nameOf(OWNER, "step", "run-adopt");
    driver.scriptContainer(name, "running", "none", Instant.EPOCH);

    sweep.sweepOnce();

    CtContainer row = row(id);
    assertEquals(ObservedState.RUNNING, row.observedState);
    assertEquals(DesiredState.RUNNING, row.desiredState);
    assertTrue(row.detail.contains("[adopted at startup"));
    assertEquals(
        List.of("inspect:" + name),
        driver.calls(),
        "adoption is one inspect: a restart must be invisible to a container that is still running");
  }

  @Test
  public void aRunOnceWorkloadThatHadFinishedIsRemovedAndSettled() {
    UUID id =
        seed(
            OWNER,
            "step",
            "run-done",
            LifecyclePolicy.ephemeral(null),
            DesiredState.RUNNING,
            ObservedState.STARTING);
    String name = nameOf(OWNER, "step", "run-done");
    driver.scriptContainer(name, "exited", "none", Instant.EPOCH);

    sweep.sweepOnce();

    CtContainer row = row(id);
    assertEquals(DesiredState.ABSENT, row.desiredState);
    assertEquals(ObservedState.GONE, row.observedState);
    assertEquals(List.of("inspect:" + name, "remove:" + name), driver.calls());
  }

  @Test
  public void aLongLivedWorkloadThatHadStoppedKeepsItsContainer() {
    UUID id =
        seed(
            OWNER,
            "workspace",
            "ws-stopped",
            LifecyclePolicy.explicitLifetime(),
            DesiredState.RUNNING,
            ObservedState.STARTING);
    String name = nameOf(OWNER, "workspace", "ws-stopped");
    driver.scriptContainer(name, "exited", "none", Instant.EPOCH);

    sweep.sweepOnce();

    CtContainer row = row(id);
    assertEquals(ObservedState.EXITED, row.observedState);
    assertEquals(DesiredState.RUNNING, row.desiredState, "nobody asked for it to go");
    assertEquals(
        List.of("inspect:" + name),
        driver.calls(),
        "only a delete removes a container of a policy that outlives its process");
  }

  @Test
  public void aContainerDockerNeverHeardOfIsRecordedMissing() {
    UUID id =
        seed(
            OWNER,
            "workspace",
            "ws-vanished",
            LifecyclePolicy.explicitLifetime(),
            DesiredState.RUNNING,
            ObservedState.PENDING);

    sweep.sweepOnce();

    assertEquals(ObservedState.MISSING, row(id).observedState);
  }

  @Test
  public void anInterruptedDeleteIsReplayedAndSettled() {
    UUID id =
        seed(
            OWNER,
            "step",
            "run-halfdeleted",
            LifecyclePolicy.ephemeral(null),
            DesiredState.ABSENT,
            ObservedState.RUNNING);
    String name = nameOf(OWNER, "step", "run-halfdeleted");
    driver.scriptContainer(name, "running", "none", Instant.EPOCH);

    sweep.sweepOnce();

    CtContainer row = row(id);
    assertEquals(ObservedState.GONE, row.observedState);
    assertTrue(row.detail.contains("the interrupted delete was replayed"));
    assertEquals(List.of("remove:" + name), driver.calls());
  }

  @Test
  public void aDockerThatIsNotThereLeavesEveryRowExactlyAsItWasAndTheBootCarriesOn() {
    UUID inFlight =
        seed(
            OWNER,
            "step",
            "run-nodocker",
            LifecyclePolicy.ephemeral(null),
            DesiredState.RUNNING,
            ObservedState.STARTING);
    UUID unsettled =
        seed(
            OWNER,
            "step",
            "run-nodocker-del",
            LifecyclePolicy.ephemeral(null),
            DesiredState.ABSENT,
            ObservedState.RUNNING);
    driver.scriptDown("Cannot connect to the Docker daemon at unix:///var/run/docker.sock");

    // A host that has just rebooted has no daemon yet. An orchestrator that refused to start here
    // would be one that cannot be deployed to fix docker.
    assertDoesNotThrow(sweep::sweepOnce);

    assertEquals(ObservedState.STARTING, row(inFlight).observedState);
    assertEquals(DesiredState.RUNNING, row(inFlight).desiredState);
    assertEquals(ObservedState.RUNNING, row(unsettled).observedState);
    assertEquals(DesiredState.ABSENT, row(unsettled).desiredState);
  }

  @Test
  public void aRowAlreadyRunningIsNotEvenLookedAt() {
    seed(
        OWNER,
        "workspace",
        "ws-serving",
        LifecyclePolicy.explicitLifetime(),
        DesiredState.RUNNING,
        ObservedState.RUNNING);

    sweep.sweepOnce();

    assertEquals(
        List.of(),
        driver.calls(),
        "the observer confirms a running row on its own schedule; the boot sweep has nothing to"
            + " decide about one");
  }

  @Test
  public void aContainerNoRowNamesIsNeverListedAndNeverTouched() {
    // The invariant, stated as an absence. A container on the host that this registry does not name
    // must not appear in any call the sweep makes — there is no listing here to find it with.
    driver.scriptContainer("some-compose-original", "running", "none", Instant.EPOCH);
    seed(
        OWNER,
        "step",
        "run-mine",
        LifecyclePolicy.ephemeral(null),
        DesiredState.RUNNING,
        ObservedState.STARTING);

    sweep.sweepOnce();

    assertFalse(
        driver.calls().stream().anyMatch(call -> call.endsWith("some-compose-original")),
        "unclaimed means somebody else's, and somebody else's is untouchable");
  }
}
