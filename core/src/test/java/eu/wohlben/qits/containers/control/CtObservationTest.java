package eu.wohlben.qits.containers.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.entity.CtContainer;
import eu.wohlben.qits.containers.entity.DesiredState;
import eu.wohlben.qits.containers.entity.ObservedState;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The observation pass, driven directly. Its patience is the thing under test: one docker call that
 * could not answer must never flip a workload that is serving, and a container that comes back must
 * take its row with it without erasing why the row said otherwise.
 */
@QuarkusTest
public class CtObservationTest extends CtTestSupport {

  private static final String OWNER = "qits-workspaces";

  @Inject ContainerObserver observer;

  @Test
  public void oneMissedObservationIsNotAVerdictAndTwoAre() {
    UUID id =
        seed(
            OWNER,
            "workspace",
            "ws-strikes",
            LifecyclePolicy.explicitLifetime(),
            DesiredState.RUNNING,
            ObservedState.RUNNING);

    observer.observeOnce();
    assertEquals(
        ObservedState.RUNNING,
        row(id).observedState,
        "one inspect that could not find it is a hiccup, not a failure");

    observer.observeOnce();
    CtContainer row = row(id);
    assertEquals(ObservedState.MISSING, row.observedState);
    assertTrue(row.detail.contains("2 consecutive passes"));
    assertTrue(row.detail.contains("No container was touched"));
  }

  @Test
  public void everyPassStampsWhenSomethingLastLookedAtTheRow() {
    UUID id =
        seed(
            OWNER,
            "workspace",
            "ws-stamped",
            LifecyclePolicy.explicitLifetime(),
            DesiredState.RUNNING,
            ObservedState.RUNNING);
    driver.scriptContainer(
        nameOf(OWNER, "workspace", "ws-stamped"), "running", "healthy", Instant.EPOCH);

    observer.observeOnce();

    assertNotNull(row(id).lastObservedAt);
  }

  @Test
  public void theStrikeMapIsPrunedToTheRowsOfTheLatestPass() {
    UUID id =
        seed(
            OWNER,
            "workspace",
            "ws-pruned",
            LifecyclePolicy.explicitLifetime(),
            DesiredState.RUNNING,
            ObservedState.RUNNING);

    observer.observeOnce();
    assertEquals(1, observer.trackedStrikes(), "one row with one strike against it");

    // The owner deletes it. The next pass has no candidate for that row, so the debounce it was
    // holding must go with it rather than growing with the history.
    QuarkusTransaction.requiringNew()
        .run(() -> containers.findById(id).desiredState = DesiredState.ABSENT);
    observer.observeOnce();

    assertEquals(0, observer.trackedStrikes());
  }

  @Test
  public void aContainerThatComesBackRecoversItsRowAndKeepsWhyItFailed() {
    UUID id =
        seed(
            OWNER,
            "workspace",
            "ws-recovered",
            LifecyclePolicy.explicitLifetime(),
            DesiredState.RUNNING,
            ObservedState.MISSING);
    registry.settle(id, ObservedState.MISSING, "[the original diagnosis nobody may erase]");
    driver.scriptContainer(
        nameOf(OWNER, "workspace", "ws-recovered"), "running", "none", Instant.EPOCH);

    observer.observeOnce();

    CtContainer row = row(id);
    assertEquals(ObservedState.RUNNING, row.observedState);
    assertTrue(row.detail.contains("recovered by observation"));
    assertTrue(
        row.detail.contains("[the original diagnosis nobody may erase]"),
        "detail is appended, never overwritten — the failure text is the diagnosis");
  }

  @Test
  public void aRestartingContainerIsNotDeadAndClearsWhateverTheLastPassThought() {
    UUID id =
        seed(
            OWNER,
            "workspace",
            "ws-restarting",
            LifecyclePolicy.explicitLifetime(),
            DesiredState.RUNNING,
            ObservedState.RUNNING);

    observer.observeOnce(); // absent: one strike
    driver.scriptContainer(
        nameOf(OWNER, "workspace", "ws-restarting"), "restarting", "none", Instant.EPOCH);
    observer.observeOnce(); // answered, and not terminal
    observer.observeOnce(); // still restarting

    assertEquals(
        ObservedState.RUNNING,
        row(id).observedState,
        "a container coming back from a slow first boot must not be declared failed on the way");
  }

  @Test
  public void theObserverTouchesNoContainerAtAll() {
    seed(
        OWNER,
        "workspace",
        "ws-untouched",
        LifecyclePolicy.explicitLifetime(),
        DesiredState.RUNNING,
        ObservedState.RUNNING);

    observer.observeOnce();
    observer.observeOnce();

    assertTrue(
        driver.calls().stream().allMatch(call -> call.startsWith("inspect:")),
        "it writes rows and nothing else — no start, no stop, no remove, ever");
  }
}
