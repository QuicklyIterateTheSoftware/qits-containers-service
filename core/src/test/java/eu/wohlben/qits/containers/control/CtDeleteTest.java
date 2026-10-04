package eu.wohlben.qits.containers.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.entity.DesiredState;
import eu.wohlben.qits.containers.entity.ObservedState;
import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Delete's two properties: the logs are captured before the removal or they are lost with it, and a
 * place that is already empty is a success.
 */
@QuarkusTest
public class CtDeleteTest extends CtTestSupport {

  private static final String OWNER = "qits-ci";

  @Test
  public void logsAreCapturedBeforeTheContainerIsRemoved() {
    ContainerRegistry.Ensured ensured =
        registry.ensure(
            OWNER, "step", "run-logs", spec("alpine:3"), LifecyclePolicy.ephemeral(null), false);
    driver.reset();
    driver.scriptLogs(ensured.containerName(), "the last thing it printed");

    ContainerRegistry.Deleted deleted =
        registry.delete(OWNER, "step", "run-logs", false, true);

    assertTrue(deleted.existed());
    assertEquals("the last thing it printed", deleted.logs());
    assertEquals(
        List.of("logs:" + ensured.containerName(), "remove:" + ensured.containerName()),
        driver.calls(),
        "logs before the removal, or the one diagnosis a dead container offers goes with it");
    assertEquals(DesiredState.ABSENT, row(ensured.rowId()).desiredState);
    assertEquals(ObservedState.GONE, row(ensured.rowId()).observedState);
  }

  @Test
  public void deletingAPlaceThatIsAlreadyEmptyIsASuccessThatAsksDockerNothing() {
    ContainerRegistry.Deleted deleted =
        registry.delete(OWNER, "step", "run-never-existed", false, false);

    assertFalse(deleted.existed());
    assertEquals(List.of(), driver.calls());
  }

  @Test
  public void deletingTwiceIsTheSameAsDeletingOnce() {
    registry.ensure(
        OWNER, "step", "run-twice", spec("alpine:3"), LifecyclePolicy.ephemeral(null), false);
    registry.delete(OWNER, "step", "run-twice", false, false);
    driver.reset();

    ContainerRegistry.Deleted second = registry.delete(OWNER, "step", "run-twice", false, false);

    assertFalse(second.existed(), "the row went ABSENT, so the place is free and there is no live row");
    assertEquals(List.of(), driver.calls());
  }

  @Test
  public void aRemoveDockerCouldNotPerformLeavesTheRowForTheBootSweepToReplay() {
    ContainerRegistry.Ensured ensured =
        registry.ensure(
            OWNER, "step", "run-stuck", spec("alpine:3"), LifecyclePolicy.ephemeral(null), false);
    driver.scriptOp(new ContainersDriver.OpResult(false, "device or resource busy"));

    registry.delete(OWNER, "step", "run-stuck", false, false);

    assertEquals(DesiredState.ABSENT, row(ensured.rowId()).desiredState);
    assertFalse(
        row(ensured.rowId()).observedState == ObservedState.GONE,
        "settling GONE optimistically would abandon a container nothing looks at again");
    assertTrue(row(ensured.rowId()).detail.contains("could not remove"));
  }
}
