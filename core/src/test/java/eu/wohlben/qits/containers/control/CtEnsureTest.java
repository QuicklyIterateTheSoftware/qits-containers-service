package eu.wohlben.qits.containers.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.entity.CtContainer;
import eu.wohlben.qits.containers.entity.DesiredState;
import eu.wohlben.qits.containers.entity.ObservedState;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The claims {@code ensure} exists to make: the row comes first, a refused run is adopted rather
 * than repeated, a stopped container is started where it stands while a changed spec is replaced
 * only where the policy allows it, and the stored spec carries no credential while the hash still
 * sees one change.
 */
@QuarkusTest
public class CtEnsureTest extends CtTestSupport {

  private static final String OWNER = "qits-ci";
  private static final String WORKLOAD = "step";

  @Test
  public void theRowExistsAndSaysPendingBeforeDockerIsAskedToRunAnything() {
    // The whole repository rests on this ordering, so it is asserted at the instant it matters
    // rather than inferred from the order of two log lines afterwards.
    AtomicReference<String> stateWhenDockerWasCalled = new AtomicReference<>("no row at all");
    driver.duringRun(
        name ->
            stateWhenDockerWasCalled.set(
                QuarkusTransaction.requiringNew()
                    .call(
                        () -> {
                          CtContainer row = containers.findLiveByContainerName(name);
                          return row == null ? "no row at all" : row.observedState.name();
                        })));

    ContainerRegistry.Ensured ensured =
        registry.ensure(
            OWNER, WORKLOAD, "run-first", spec("alpine:3"), LifecyclePolicy.ephemeral(null), false);

    assertEquals(
        "PENDING",
        stateWhenDockerWasCalled.get(),
        "the row has to be committed, and PENDING, before docker is asked for anything");
    assertTrue(ensured.created());
    assertEquals(ObservedState.RUNNING, ensured.observed());
    assertEquals(
        List.of("run:" + ensured.containerName(), "inspect:" + ensured.containerName()),
        driver.calls(),
        "one run and one confirming inspect, and nothing else");
  }

  @Test
  public void aRefusedRunAdoptsTheContainerTheRowAlreadyNames() {
    // The crash-retry case: a previous attempt started the container and died before recording it,
    // so docker refuses the name. The row named it first, so it is ours.
    // Only the container is scripted: the fake refuses a name that is already taken by itself, the
    // way a daemon does, so nothing here has to spell out the refusal the registry is measured on.
    String name = nameOf(OWNER, WORKLOAD, "run-dup");
    driver.scriptContainer(name, "running", "none", java.time.Instant.EPOCH);

    ContainerRegistry.Ensured ensured =
        registry.ensure(
            OWNER, WORKLOAD, "run-dup", spec("alpine:3"), LifecyclePolicy.ephemeral(null), false);

    assertEquals(ObservedState.RUNNING, ensured.observed());
    assertTrue(row(ensured.rowId()).detail.contains("adopted after a refused run"));
    assertFalse(
        driver.calls().contains("remove:" + name),
        "adoption never removes: the container is the one this row wanted");
    assertFalse(driver.calls().contains("stop:" + name));
  }

  @Test
  public void aChangedSpecIsReplacedUnderTheSameNameWhenARecreateWasAskedFor() {
    ContainerRegistry.Ensured first =
        registry.ensure(
            OWNER,
            "workspace",
            "ws-1",
            spec("alpine:3"),
            LifecyclePolicy.explicitLifetime(),
            false);
    String name = first.containerName();

    ContainerRegistry.Ensured second =
        registry.ensure(
            OWNER,
            "workspace",
            "ws-1",
            spec("alpine:3.20"),
            LifecyclePolicy.explicitLifetime(),
            true);

    assertEquals(name, second.containerName(), "a replacement keeps the place's name");
    assertFalse(second.created());
    assertEquals(ObservedState.RUNNING, second.observed());
    assertEquals(
        List.of(
            "run:" + name,
            "inspect:" + name,
            "stop:" + name,
            "remove:" + name,
            "run:" + name,
            "inspect:" + name),
        driver.calls(),
        "stop and remove precede the replacement run, and the row was never removed");
    assertEquals("alpine:3.20", row(second.rowId()).image);
  }

  @Test
  public void aStoppedWorkloadIsStartedWhereItStandsRatherThanRunASecondTime() {
    // The claim this whole restart path exists for. A plan that ran instead of starting is refused
    // by a real daemon — the exited container still holds the name — and settles the row MISSING,
    // and a run that was NOT refused would be worse: a different container under the old name,
    // carrying none of the state the stopped one had.
    driver.scriptRun(new ContainersDriver.Started(true, "the-first-container", null));
    ContainerRegistry.Ensured first =
        registry.ensure(
            OWNER,
            "workspace",
            "ws-restart",
            spec("alpine:3"),
            LifecyclePolicy.explicitLifetime(),
            false);
    String name = first.containerName();

    registry.stop(OWNER, "workspace", "ws-restart");

    // Any second run would carry this id, so the identity assertion below names which container
    // came back rather than merely that one did.
    driver.scriptRun(new ContainersDriver.Started(true, "a-second-container", null));
    ContainerRegistry.Ensured again =
        registry.ensure(
            OWNER,
            "workspace",
            "ws-restart",
            spec("alpine:3"),
            LifecyclePolicy.explicitLifetime(),
            false);

    assertEquals(ObservedState.RUNNING, again.observed());
    assertFalse(again.created());
    assertEquals(first.rowId(), again.rowId(), "a restart is the same place, so it is the same row");
    assertEquals(
        List.of(
            "run:" + name,
            "inspect:" + name,
            "stop:" + name,
            "start:" + name,
            "inspect:" + name),
        driver.calls(),
        "the stopped container is started where it stands: no second run, and no removal");
    assertEquals(
        "the-first-container",
        driver.inspect(name, java.time.Duration.ofSeconds(10)).orElseThrow().id(),
        "identity is the claim: what came back is the container that was stopped");
  }

  @Test
  public void aStoppedWorkloadWhoseSpecChangedIsStillReplacedWhenARecreateWasAskedFor() {
    // The other half of the restart: a start is only ever for a spec that did not change. A changed
    // one is a replacement, exited container and all.
    ContainerRegistry.Ensured first =
        registry.ensure(
            OWNER,
            "workspace",
            "ws-restart-changed",
            spec("alpine:3"),
            LifecyclePolicy.explicitLifetime(),
            false);
    String name = first.containerName();

    registry.stop(OWNER, "workspace", "ws-restart-changed");

    ContainerRegistry.Ensured second =
        registry.ensure(
            OWNER,
            "workspace",
            "ws-restart-changed",
            spec("alpine:3.20"),
            LifecyclePolicy.explicitLifetime(),
            true);

    assertEquals(ObservedState.RUNNING, second.observed());
    assertEquals("alpine:3.20", row(second.rowId()).image);
    assertEquals(
        List.of(
            "run:" + name,
            "inspect:" + name,
            "stop:" + name,
            "stop:" + name,
            "remove:" + name,
            "run:" + name,
            "inspect:" + name),
        driver.calls(),
        "the exited container is removed before the replacement run, and never started");
  }

  @Test
  public void aRestartOfAContainerThatVanishedIsRecordedOnTheRowRatherThanThrown() {
    ContainerRegistry.Ensured first =
        registry.ensure(
            OWNER,
            "workspace",
            "ws-restart-gone",
            spec("alpine:3"),
            LifecyclePolicy.explicitLifetime(),
            false);
    String name = first.containerName();

    registry.stop(OWNER, "workspace", "ws-restart-gone");
    // Removed on the host between the stop and the ask — by a person, or by a daemon that pruned.
    driver.scriptGone(name);

    ContainerRegistry.Ensured again =
        registry.ensure(
            OWNER,
            "workspace",
            "ws-restart-gone",
            spec("alpine:3"),
            LifecyclePolicy.explicitLifetime(),
            false);

    assertEquals(
        ObservedState.MISSING, again.observed(), "docker has no such container, so the row says so");
    assertTrue(again.detail().contains("could not start " + name), again.detail());
    assertTrue(row(again.rowId()).detail.contains("could not start " + name));
    assertEquals(
        List.of(
            "run:" + name,
            "inspect:" + name,
            "stop:" + name,
            "start:" + name,
            "inspect:" + name),
        driver.calls(),
        "a failed start is settled from the same inspect every other outcome uses");
  }

  @Test
  public void aChangedSpecIsLeftAloneWhenNoRecreateWasAsked() {
    registry.ensure(
        OWNER, "workspace", "ws-keep", spec("alpine:3"), LifecyclePolicy.explicitLifetime(), false);
    driver.reset();

    ContainerRegistry.Ensured second =
        registry.ensure(
            OWNER,
            "workspace",
            "ws-keep",
            spec("alpine:3.20"),
            LifecyclePolicy.explicitLifetime(),
            false);

    assertEquals(List.of(), driver.calls(), "nothing docker-side happens without a recreate");
    assertEquals("alpine:3", row(second.rowId()).image, "the row still describes what is running");
  }

  @Test
  public void recreatingARunOnceWorkloadIsRefusedWithATypedConflict() {
    registry.ensure(
        OWNER, WORKLOAD, "run-eph", spec("alpine:3"), LifecyclePolicy.ephemeral(null), false);
    driver.reset();

    SpecConflictException refused =
        assertThrows(
            SpecConflictException.class,
            () ->
                registry.ensure(
                    OWNER,
                    WORKLOAD,
                    "run-eph",
                    spec("alpine:3.20"),
                    LifecyclePolicy.ephemeral(null),
                    true));

    assertTrue(refused.getMessage().contains("EPHEMERAL"));
    assertEquals(List.of(), driver.calls(), "the refusal happens before anything docker-side");
  }

  @Test
  public void aDeletedPlaceIsStartedAgainUnderTheSameNameRatherThanRefusedForAWeek() {
    // The flow a consumer really runs: delete a container, then ask for the place again. The name
    // is derived from the place, so the second ask wants back the name the settled row still
    // records — and V1's table-wide unique refused exactly that until RowPrune released the row a
    // week later, as an uncoded 500.
    ContainerRegistry.Ensured first =
        registry.ensure(
            OWNER, WORKLOAD, "run-again", spec("alpine:3"), LifecyclePolicy.explicitLifetime(), false);
    registry.delete(OWNER, WORKLOAD, "run-again", false, false);
    driver.reset();

    ContainerRegistry.Ensured second =
        registry.ensure(
            OWNER, WORKLOAD, "run-again", spec("alpine:3"), LifecyclePolicy.explicitLifetime(), false);

    assertEquals(
        nameOf(OWNER, WORKLOAD, "run-again"),
        second.containerName(),
        "the name is derived from the place, so the place coming back brings the name back");
    assertEquals(first.containerName(), second.containerName());
    assertTrue(second.created(), "the settled row is history; this is a new row and a clean START");
    assertNotEquals(first.rowId(), second.rowId());
    assertEquals(ObservedState.RUNNING, second.observed());
    assertEquals(
        List.of("run:" + second.containerName(), "inspect:" + second.containerName()),
        driver.calls(),
        "a START and not a replacement: nothing is stopped or removed on the way");
    assertEquals(
        DesiredState.ABSENT,
        row(first.rowId()).desiredState,
        "the first row is still there, still carrying the name it ran under");
    assertEquals(first.containerName(), row(first.rowId()).containerName);
  }

  @Test
  public void aNameALiveContainerOfAnotherPlaceHoldsIsRefusedWithItsOwnCode() {
    // The collision that is honest, and the only one left: docker's name space is flat, so a second
    // live row claiming one name would be a contradiction no sweep could resolve.
    ContainerSpec squatter =
        ContainerSpec.builder("alpine:3").network("qits-net").name("qits-shared-agent").build();
    registry.ensure(
        OWNER, "workspace", "ws-one", squatter, LifecyclePolicy.explicitLifetime(), false);
    driver.reset();

    NameTakenException refused =
        assertThrows(
            NameTakenException.class,
            () ->
                registry.ensure(
                    OWNER,
                    "workspace",
                    "ws-two",
                    squatter,
                    LifecyclePolicy.explicitLifetime(),
                    false));

    assertTrue(refused.getMessage().contains("qits-shared-agent"));
    assertTrue(refused.getMessage().contains("ws-one"), "the refusal names who holds the name");
    assertEquals(List.of(), driver.calls(), "the refusal happens before anything docker-side");

    // And it stops being a refusal the moment the holder is gone, which is the whole of V3.
    registry.delete(OWNER, "workspace", "ws-one", false, false);
    ContainerRegistry.Ensured moved =
        registry.ensure(
            OWNER, "workspace", "ws-two", squatter, LifecyclePolicy.explicitLifetime(), false);
    assertEquals("qits-shared-agent", moved.containerName());
    assertTrue(moved.created());
  }

  @Test
  public void theStoredSpecCarriesNoEnvironmentAndTheHashStillSeesOneChange() {
    ContainerSpec withOneSecret =
        ContainerSpec.builder("alpine:3").network("qits-net").env("QITS_TOKEN", "s3cr3t-one").build();
    ContainerSpec withAnother =
        ContainerSpec.builder("alpine:3").network("qits-net").env("QITS_TOKEN", "s3cr3t-two").build();

    ContainerRegistry.Ensured one =
        registry.ensure(
            OWNER, WORKLOAD, "run-env-a", withOneSecret, LifecyclePolicy.ephemeral(null), false);
    ContainerRegistry.Ensured two =
        registry.ensure(
            OWNER, WORKLOAD, "run-env-b", withAnother, LifecyclePolicy.ephemeral(null), false);

    CtContainer first = row(one.rowId());
    CtContainer second = row(two.rowId());

    assertFalse(first.specJson.contains("s3cr3t-one"), "no credential is ever written to this table");
    assertFalse(first.specJson.contains("QITS_TOKEN"), "not even the key it rode under");
    assertEquals(
        first.specJson,
        second.specJson,
        "two workloads differing only by a secret store the same thing — which is why the hash has"
            + " to be what tells them apart");
    assertNotEquals(
        first.specHash,
        second.specHash,
        "change detection has to see an env change, or a rotated credential never redeploys");
  }

  @Test
  public void flippingInitIsASpecChangeTheHashSees() {
    // A container's PID 1 is not a detail the hash may be blind to: a workspace re-asked for with
    // tini and matched against a row that says it is already there would keep running the process
    // it was started without, and the owner would have no way to tell.
    ContainerSpec without = spec("alpine:3");
    ContainerSpec with =
        ContainerSpec.builder("alpine:3").network("qits-net").init(true).build();

    ContainerRegistry.Ensured plain =
        registry.ensure(OWNER, WORKLOAD, "run-init-a", without, LifecyclePolicy.explicitLifetime(), false);
    ContainerRegistry.Ensured inited =
        registry.ensure(OWNER, WORKLOAD, "run-init-b", with, LifecyclePolicy.explicitLifetime(), false);

    CtContainer plainRow = row(plain.rowId());
    CtContainer initedRow = row(inited.rowId());

    assertNotEquals(plainRow.specHash, initedRow.specHash);
    // And it is on the stored spec too, so a restart compares against what was really asked for.
    assertTrue(initedRow.specJson.contains("\"init\":true"), initedRow.specJson);
    assertTrue(plainRow.specJson.contains("\"init\":false"), plainRow.specJson);
  }
}
