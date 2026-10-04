package eu.wohlben.qits.containers.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.entity.VolumeState;
import eu.wohlben.qits.containers.spec.ContainerLabels;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The volume reconcile's one rule, from both sides: a volume whose own row asks to be absent is
 * removed, and a volume no row names is reported and left standing.
 *
 * <p>The second half is the invariant applied to volumes, and it is asserted as an absence — the
 * fake's call log must carry no {@code removeVolume:} for the unclaimed one.
 */
@QuarkusTest
public class CtVolumeReconcileTest extends CtTestSupport {

  private static final Map<String, String> MANAGED =
      Map.of(ContainerLabels.MANAGED, ContainerLabels.MANAGED_VOLUME);

  @Inject VolumeReconcile reconcile;

  @Test
  public void aVolumeWhoseRowAsksToBeAbsentIsRemovedAndItsRowGoesWithIt() {
    seedVolume("qits-workspaces", "ws-data-1", VolumeState.ABSENT);
    driver.scriptVolumeListing(MANAGED, List.of("ws-data-1"));

    assertEquals(1, reconcile.reconcileOnce());

    assertEquals(
        List.of("listVolumesByLabels:" + ContainerLabels.MANAGED + "=" + ContainerLabels.MANAGED_VOLUME,
            "removeVolume:ws-data-1"),
        driver.calls());
    assertNull(
        QuarkusTransaction.requiringNew()
            .call(() -> volumes.findByOwnerAndName("qits-workspaces", "ws-data-1")),
        "a delete that finished has nothing left to describe");
  }

  @Test
  public void aVolumeNoRowNamesIsReportedAndLeftStanding() {
    driver.scriptVolumeListing(MANAGED, List.of("somebody-elses-volume"));

    assertEquals(0, reconcile.reconcileOnce());

    assertFalse(
        driver.calls().contains("removeVolume:somebody-elses-volume"),
        "unclaimed means somebody else's, for a volume exactly as for a container");
  }

  @Test
  public void aVolumeAWorkloadStillWantsIsLeftAlone() {
    seedVolume("qits-workspaces", "ws-data-live", VolumeState.PRESENT);
    driver.scriptVolumeListing(MANAGED, List.of("ws-data-live"));

    assertEquals(0, reconcile.reconcileOnce());

    assertFalse(driver.calls().contains("removeVolume:ws-data-live"));
    assertNotNull(
        QuarkusTransaction.requiringNew()
            .call(() -> volumes.findByOwnerAndName("qits-workspaces", "ws-data-live")));
  }

  @Test
  public void anEnsureWritesTheVolumeRowBeforeItAsksDockerForTheVolume() {
    // Same ordering as the container row, for the same reason: a volume this service made and could
    // not name would be one nothing may ever remove.
    ContainerSpec withVolume =
        ContainerSpec.builder("alpine:3")
            .network("qits-net")
            .mount("ws-data-new", "/workspace")
            .build();

    ContainerRegistry.Ensured ensured =
        registry.ensure(
            "qits-workspaces",
            "workspace",
            "ws-new",
            withVolume,
            LifecyclePolicy.explicitLifetime(),
            false);

    assertNotNull(
        QuarkusTransaction.requiringNew()
            .call(() -> volumes.findByOwnerAndName("qits-workspaces", "ws-data-new")));
    assertEquals(
        List.of(
            "ensureVolume:ws-data-new",
            "run:" + ensured.containerName(),
            "inspect:" + ensured.containerName()),
        driver.calls(),
        "the volume exists before the run that mounts it, or docker makes an unlabelled one");
  }

  @Test
  public void deletingAnIdleStopWorkloadNeverTakesItsVolume() {
    ContainerSpec withVolume =
        ContainerSpec.builder("alpine:3")
            .network("qits-net")
            .mount("agent-home", "/home/agent")
            .build();
    registry.ensure(
        "qits-projects",
        "agent",
        "proj-vol",
        withVolume,
        LifecyclePolicy.idleStop(java.time.Duration.ofHours(4)),
        false);
    driver.reset();

    registry.delete("qits-projects", "agent", "proj-vol", true, false);

    assertFalse(
        driver.calls().contains("removeVolume:agent-home"),
        "an IDLE_STOP workload is stopped and never removed, so its volume is what it comes back to");
  }
}
