package eu.wohlben.qits.containers.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.entity.CtContainer;
import eu.wohlben.qits.containers.entity.DesiredState;
import eu.wohlben.qits.containers.entity.ObservedState;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The idle sweep's one subtlety: a workload this process has never heard from is stamped on sight
 * and ages out one window later, rather than being stopped the first time it is seen.
 *
 * <p>Time is a parameter rather than a clock to travel, which is qits-projects'
 * {@code AgentIdleSweep} shape and is why this suite runs in milliseconds instead of hours.
 */
@QuarkusTest
public class CtIdleSweepTest extends CtTestSupport {

  private static final String OWNER = "qits-projects";
  private static final Duration WINDOW = Duration.ofHours(4);

  @Inject IdleSweep sweep;

  private UUID idleWorkload(String ref) {
    return seed(
        OWNER,
        "agent",
        ref,
        LifecyclePolicy.idleStop(WINDOW),
        DesiredState.RUNNING,
        ObservedState.RUNNING);
  }

  @Test
  public void aWorkloadNobodyHasTouchedYetIsStampedRatherThanStopped() {
    UUID id = idleWorkload("proj-fresh");
    Instant now = Instant.parse("2026-08-11T12:00:00Z");

    int stopped = sweep.sweepOnce(now);

    assertEquals(0, stopped);
    CtContainer row = row(id);
    assertNotNull(row.lastTouchedAt, "an unknown workload is stamped on sight");
    assertEquals(now, row.lastTouchedAt);
    assertEquals(DesiredState.RUNNING, row.desiredState);
    assertEquals(List.of(), driver.calls(), "nothing was stopped, so nothing was asked of docker");
  }

  @Test
  public void itAgesOutFromTheStampAndNotFromWhenTheProcessStarted() {
    UUID id = idleWorkload("proj-ages");
    Instant boot = Instant.parse("2026-08-11T12:00:00Z");

    // Stamped on sight at boot. Three hours later it is still inside its window, even though the
    // row has existed far longer than that.
    sweep.sweepOnce(boot);
    assertEquals(0, sweep.sweepOnce(boot.plus(Duration.ofHours(3))));
    assertEquals(DesiredState.RUNNING, row(id).desiredState);

    // Past the window measured from the STAMP.
    assertEquals(1, sweep.sweepOnce(boot.plus(WINDOW).plusSeconds(1)));

    CtContainer row = row(id);
    assertEquals(DesiredState.STOPPED, row.desiredState);
    assertEquals(ObservedState.EXITED, row.observedState);
    assertTrue(row.detail.contains("stopped as idle"));
    assertEquals(
        List.of("stop:" + nameOf(OWNER, "agent", "proj-ages")),
        driver.calls(),
        "an idle workload is stopped and never removed — its volume is what it comes back to");
  }

  @Test
  public void aTouchInsideTheWindowKeepsItRunning() {
    UUID id = idleWorkload("proj-touched");
    Instant boot = Instant.parse("2026-08-11T12:00:00Z");
    sweep.sweepOnce(boot);

    touchAt(id, boot.plus(Duration.ofHours(3)));

    assertEquals(0, sweep.sweepOnce(boot.plus(WINDOW).plusSeconds(1)));
    assertEquals(DesiredState.RUNNING, row(id).desiredState);
  }

  @Test
  public void aWorkloadOfAnotherPolicyIsNeverIdleSwept() {
    UUID explicitLifetime =
        seed(
            OWNER,
            "workspace",
            "ws-never-idle",
            LifecyclePolicy.explicitLifetime(),
            DesiredState.RUNNING,
            ObservedState.RUNNING);
    Instant far = Instant.parse("2030-01-01T00:00:00Z");

    assertEquals(0, sweep.sweepOnce(far));

    CtContainer row = row(explicitLifetime);
    assertEquals(DesiredState.RUNNING, row.desiredState);
    assertNull(row.lastTouchedAt, "it is not a candidate, so it is not even stamped");
  }
}
