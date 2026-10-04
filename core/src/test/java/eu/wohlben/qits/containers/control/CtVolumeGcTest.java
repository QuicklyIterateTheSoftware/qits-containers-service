package eu.wohlben.qits.containers.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.entity.VolumeState;
import eu.wohlben.qits.containers.spec.ContainerLabels;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The volume collection's classes, one test each — and the two that are really the invariant.
 *
 * <p>A dangling volume this platform cannot account for is kept {@code unmanaged}, and a builder's
 * state volume whose holders could not be listed is kept too. Both are asserted as the ABSENCE of a
 * {@code removeVolume:} call, because "it was not removed" is the whole claim and a return value
 * would not carry it.
 *
 * <p>Rows are the other half: this class may read them and may not write them, so every test that
 * seeds one asserts it is still there afterwards.
 */
@QuarkusTest
public class CtVolumeGcTest extends CtTestSupport {

  private static final String ANON =
      "1a398b208e7c286766e0962b9f90c0902104b53fa0879e99d8eebdda2968d6ad";
  private static final String BUILDER_STATE = "buildx_buildkit_qits-bootstrap-builder-v40_state";
  private static final String MANAGED = "ws-data-orphan";

  private static final Duration A_DAY = Duration.ofHours(24);

  private static final Map<String, String> OURS =
      Map.of(ContainerLabels.MANAGED, ContainerLabels.MANAGED_VOLUME);

  @Inject VolumeGc gc;

  private static Instant lastWeek() {
    return Instant.now().minus(Duration.ofDays(7));
  }

  private static Optional<String> reasonOf(List<VolumeGc.Outcome> outcomes, String name) {
    return outcomes.stream()
        .filter(outcome -> outcome.name().equals(name))
        .map(VolumeGc.Outcome::reason)
        .findFirst();
  }

  @Test
  public void aVolumeOfOursThatNoRowClaimsIsRemoved() {
    driver.scriptDanglingVolumes(List.of(MANAGED));
    driver.scriptVolumeDetail(MANAGED, OURS, lastWeek());

    VolumeGc.Result result = gc.sweep(false, A_DAY);

    assertEquals(Optional.of(VolumeGc.MANAGED_NO_ROW), reasonOf(result.removed(), MANAGED));
    assertTrue(driver.calls().contains("removeVolume:" + MANAGED));
  }

  @Test
  public void aVolumeOfOursThatARowStillClaimsIsLeftToTheReconcile() {
    seedVolume("qits-workspaces", MANAGED, VolumeState.PRESENT);
    driver.scriptDanglingVolumes(List.of(MANAGED));
    driver.scriptVolumeDetail(MANAGED, OURS, lastWeek());

    VolumeGc.Result result = gc.sweep(false, A_DAY);

    assertEquals(Optional.of(VolumeGc.LIVE_ROW), reasonOf(result.kept(), MANAGED));
    assertFalse(driver.calls().contains("removeVolume:" + MANAGED));
    assertTrue(
        QuarkusTransaction.requiringNew()
                .call(() -> volumes.findByOwnerAndName("qits-workspaces", MANAGED))
            != null,
        "the collection reads rows and never writes them");
  }

  @Test
  public void aVolumeOfOursMadeInsideMinAgeIsKept() {
    driver.scriptDanglingVolumes(List.of(MANAGED));
    driver.scriptVolumeDetail(MANAGED, OURS, Instant.now().minus(Duration.ofMinutes(2)));

    VolumeGc.Result result = gc.sweep(false, A_DAY);

    assertEquals(Optional.of(VolumeGc.TOO_YOUNG), reasonOf(result.kept(), MANAGED));
    assertFalse(driver.calls().contains("removeVolume:" + MANAGED));
  }

  @Test
  public void aDeadBuildersStateVolumeIsRemoved() {
    driver.scriptDanglingVolumes(List.of(BUILDER_STATE));
    driver.scriptVolumeDetail(BUILDER_STATE, Map.of(), lastWeek());
    driver.scriptVolumeHolders(BUILDER_STATE, List.of());

    VolumeGc.Result result = gc.sweep(false, A_DAY);

    assertEquals(Optional.of(VolumeGc.BUILDX_STATE), reasonOf(result.removed(), BUILDER_STATE));
  }

  @Test
  public void aBuilderThatIsMerelyStoppedKeepsItsCache() {
    driver.scriptDanglingVolumes(List.of(BUILDER_STATE));
    driver.scriptVolumeDetail(BUILDER_STATE, Map.of(), lastWeek());
    driver.scriptVolumeHolders(BUILDER_STATE, List.of("buildx_buildkit_qits-bootstrap-builder-v40"));

    VolumeGc.Result result = gc.sweep(false, A_DAY);

    assertEquals(Optional.of(VolumeGc.BUILDX_LIVE), reasonOf(result.kept(), BUILDER_STATE));
    assertFalse(driver.calls().contains("removeVolume:" + BUILDER_STATE));
  }

  @Test
  public void aBuilderWhoseHoldersCouldNotBeListedKeepsItsCache() {
    // The listing that protects. An unanswerable docker is a builder's cache kept, and the volume
    // is reported as failed rather than quietly passed over.
    driver.scriptDanglingVolumes(List.of(BUILDER_STATE));
    driver.scriptVolumeDetail(BUILDER_STATE, Map.of(), lastWeek());
    driver.scriptVolumeHoldersUnreadable(BUILDER_STATE, "connection refused");

    VolumeGc.Result result = gc.sweep(false, A_DAY);

    assertEquals(1, result.failed().size());
    assertEquals(BUILDER_STATE, result.failed().getFirst().name());
    assertFalse(driver.calls().contains("removeVolume:" + BUILDER_STATE));
  }

  @Test
  public void anAnonymousVolumeOlderThanMinAgeIsRemoved() {
    driver.scriptDanglingVolumes(List.of(ANON));
    driver.scriptVolumeDetail(ANON, Map.of("com.docker.volume.anonymous", ""), lastWeek());

    VolumeGc.Result result = gc.sweep(false, A_DAY);

    assertEquals(Optional.of(VolumeGc.ANONYMOUS), reasonOf(result.removed(), ANON));
  }

  @Test
  public void anAnonymousVolumeMadeMinutesAgoIsKept() {
    driver.scriptDanglingVolumes(List.of(ANON));
    driver.scriptVolumeDetail(
        ANON, Map.of("com.docker.volume.anonymous", ""), Instant.now().minus(Duration.ofMinutes(2)));

    VolumeGc.Result result = gc.sweep(false, A_DAY);

    assertEquals(Optional.of(VolumeGc.TOO_YOUNG), reasonOf(result.kept(), ANON));
  }

  @Test
  public void aDanglingVolumeThisPlatformCannotAccountForIsKeptAndNamed() {
    // The invariant, for volumes: a compose original, a bootstrap seed, another module's store.
    driver.scriptDanglingVolumes(List.of("some-composed-postgres-data"));
    driver.scriptVolumeDetail("some-composed-postgres-data", Map.of(), lastWeek());

    VolumeGc.Result result = gc.sweep(false, A_DAY);

    assertEquals(
        Optional.of(VolumeGc.UNMANAGED), reasonOf(result.kept(), "some-composed-postgres-data"));
    assertFalse(driver.calls().contains("removeVolume:some-composed-postgres-data"));
  }

  @Test
  public void aVolumeThatWentBetweenTheListingAndTheInspectIsNotAFailure() {
    driver.scriptDanglingVolumes(List.of(ANON));

    VolumeGc.Result result = gc.sweep(false, A_DAY);

    assertEquals(Optional.of(VolumeGc.VANISHED), reasonOf(result.kept(), ANON));
    assertTrue(result.failed().isEmpty());
  }

  @Test
  public void aDryRunDecidesAndRemovesNothing() {
    driver.scriptDanglingVolumes(List.of(MANAGED, ANON));
    driver.scriptVolumeDetail(MANAGED, OURS, lastWeek());
    driver.scriptVolumeDetail(ANON, Map.of(), lastWeek());

    VolumeGc.Result result = gc.sweep(true, A_DAY);

    assertTrue(result.dryRun());
    assertEquals(2, result.removed().size());
    assertFalse(
        driver.calls().stream().anyMatch(call -> call.startsWith("removeVolume:")),
        "a dry run decides and asks docker to do nothing");
  }

  @Test
  public void aRemoveDockerRefusedIsReportedRatherThanCountedAsDone() {
    driver.scriptDanglingVolumes(List.of(MANAGED));
    driver.scriptVolumeDetail(MANAGED, OURS, lastWeek());
    driver.scriptOp(new ContainersDriver.OpResult(false, "volume is in use"));

    VolumeGc.Result result = gc.sweep(false, A_DAY);

    assertTrue(result.removed().isEmpty());
    assertEquals(1, result.failed().size());
    assertEquals(MANAGED, result.failed().getFirst().name());
  }

  @Test
  public void nothingDanglingIsAnEmptyAnswerAndNoFurtherCalls() {
    VolumeGc.Result result = gc.sweep(false, A_DAY);

    assertEquals(List.of("listDanglingVolumes"), driver.calls());
    assertTrue(result.removed().isEmpty() && result.kept().isEmpty());
  }
}
