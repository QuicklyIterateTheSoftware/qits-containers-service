package eu.wohlben.qits.containers.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.entity.DesiredState;
import eu.wohlben.qits.containers.entity.ObservedState;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Every keep rule of the image collection, from both sides.
 *
 * <p><b>An image is named by no row, so the rules ARE the safety</b> — which is why each of the
 * four is asserted as a keep with its own reason on it rather than as "nothing happened". A test
 * that only counted removals would pass with two rules deleted, since a keep and a keep look the
 * same from the outside.
 *
 * <p>The last one is the one that matters most and is easiest to lose: a docker that would not say
 * what containers are running must stop the collection, not empty it.
 */
@QuarkusTest
public class CtImageGcTest extends CtTestSupport {

  private static final String CI = "sha256:1111111111111111111111111111111111111111111111111111111111111111";
  private static final String OLD = "sha256:2222222222222222222222222222222222222222222222222222222222222222";
  private static final String DANGLER = "sha256:3333333333333333333333333333333333333333333333333333333333333333";

  private static final Duration SIX_HOURS = Duration.ofHours(6);

  @Inject ImageGc gc;

  @Inject GcUsage usageRead;

  /** Every image was built a week ago unless a test says otherwise. */
  private static Instant lastWeek() {
    return Instant.now().minus(Duration.ofDays(7));
  }

  private ImageGc.Result sweep(boolean dryRun, List<String> keep, List<String> keepPrefixes) {
    return gc.sweep(dryRun, SIX_HOURS, keep, keepPrefixes);
  }

  private static Optional<ImageGc.Outcome> outcome(List<ImageGc.Outcome> outcomes, String id) {
    return outcomes.stream().filter(o -> o.id().equals(id)).findFirst();
  }

  private static void assertKept(ImageGc.Result result, String id, String reason) {
    assertEquals(
        Optional.of(reason),
        outcome(result.kept(), id).map(ImageGc.Outcome::reason),
        "the image was expected kept as " + reason);
    assertTrue(outcome(result.removed(), id).isEmpty(), "and it must not also be removed");
  }

  @Test
  public void anImageAContainerWasCreatedFromIsKeptEvenWhenTheContainerHasExited() {
    driver.scriptImage(CI, List.of("registry:8080/qits/qits-ci:cafe"), 325_000_000L, lastWeek());
    driver.scriptImageReferencesInUse(List.of("registry:8080/qits/qits-ci:cafe"));

    assertKept(sweep(false, List.of(), List.of()), CI, ImageGc.IN_USE);
    assertFalse(driver.calls().contains("removeImage:" + CI));
  }

  @Test
  public void aContainerNamingABareIdStillProtectsItsImage() {
    // Docker prints the id when the reference no longer resolves — a container of an untagged
    // image, or one whose tag moved. It is the same protection and it must not need a tag.
    driver.scriptImage(DANGLER, List.of(), 90_000_000L, lastWeek());
    driver.scriptImageReferencesInUse(List.of(DANGLER));

    assertKept(sweep(false, List.of(), List.of()), DANGLER, ImageGc.IN_USE);
  }

  @Test
  public void anImageALiveRowNamesIsKeptThoughNoContainerIsThere() {
    // The place is live, its container is gone, and an ensure is one restart away from needing the
    // image again. Nothing on the host says so except the row.
    seed(
        "qits-ci",
        "step",
        "run-7",
        LifecyclePolicy.explicitLifetime(),
        DesiredState.RUNNING,
        ObservedState.MISSING,
        Instant.now(),
        spec("registry:8080/qits/qits-ci:cafe"));
    driver.scriptImage(CI, List.of("registry:8080/qits/qits-ci:cafe"), 325_000_000L, lastWeek());

    assertKept(sweep(false, List.of(), List.of()), CI, ImageGc.LIVE_ROW);
  }

  @Test
  public void aRowNamingTheImageWithoutItsRegistryHostStillProtectsIt() {
    seed(
        "qits-ci",
        "step",
        "run-8",
        LifecyclePolicy.explicitLifetime(),
        DesiredState.RUNNING,
        ObservedState.RUNNING,
        Instant.now(),
        spec("qits/qits-ci:cafe"));
    driver.scriptImage(CI, List.of("registry:8080/qits/qits-ci:cafe"), 325_000_000L, lastWeek());

    assertKept(sweep(false, List.of(), List.of()), CI, ImageGc.LIVE_ROW);
  }

  @Test
  public void aPinnedTagIsKeptWithOrWithoutTheRegistryHostInFrontOfIt() {
    driver.scriptImage(CI, List.of("registry:8080/qits/qits-ci:cafe"), 325_000_000L, lastWeek());

    assertKept(sweep(false, List.of("qits/qits-ci:cafe"), List.of()), CI, ImageGc.PINNED);
  }

  @Test
  public void aPinnedImageIdIsKept() {
    driver.scriptImage(DANGLER, List.of(), 90_000_000L, lastWeek());

    assertKept(sweep(false, List.of(DANGLER), List.of()), DANGLER, ImageGc.PINNED);
  }

  @Test
  public void aKeepPrefixMatchesThePartAfterTheRegistryHost() {
    driver.scriptImage(
        OLD, List.of("registry:8080/qits/build-images/node-docker-base:1"), 600_000_000L, lastWeek());

    assertKept(sweep(false, List.of(), List.of("qits/build-images/")), OLD, ImageGc.PINNED);
  }

  @Test
  public void anImageBuiltInsideMinAgeIsKeptEvenThoughNothingNamesIt() {
    // The CI step's own protection: a step has built it and not pushed it yet, so it is untagged,
    // unheld, unnamed and unpinned — and every other rule would let it go.
    driver.scriptImage(DANGLER, List.of(), 90_000_000L, Instant.now().minus(Duration.ofMinutes(5)));

    assertKept(sweep(false, List.of(), List.of()), DANGLER, ImageGc.TOO_YOUNG);
  }

  @Test
  public void aDanglingImageNothingSpokeForIsRemoved() {
    driver.scriptImage(DANGLER, List.of(), 90_000_000L, lastWeek());

    ImageGc.Result result = sweep(false, List.of(), List.of());

    assertEquals(
        Optional.of(ImageGc.DANGLING), outcome(result.removed(), DANGLER).map(ImageGc.Outcome::reason));
    assertEquals(90_000_000L, result.bytesReclaimed());
    assertTrue(driver.calls().contains("removeImage:" + DANGLER));
  }

  @Test
  public void aTaggedImageNobodyPinnedIsRemovedToo() {
    driver.scriptImage(OLD, List.of("registry:8080/qits/qits-ci:beef"), 325_000_000L, lastWeek());

    ImageGc.Result result = sweep(false, List.of(), List.of());

    assertEquals(
        Optional.of(ImageGc.UNPINNED), outcome(result.removed(), OLD).map(ImageGc.Outcome::reason));
    assertTrue(
        driver.calls().contains("removeImageReferences:registry:8080/qits/qits-ci:beef"),
        "a tagged image is untagged, never deleted by id");
  }

  @Test
  public void anImageWithTwoTagsIsRemovedByBothOfThem() {
    // The defect the platform's first real collection run found: `image rm <id>` is refused with
    // `must be forced` for an image more than one reference names, and two tags of ONE repository
    // count as two. 20 of 32 candidates failed that way. Every reference goes in one call, and the
    // last untag is what removes the image.
    driver.scriptImage(
        OLD,
        List.of(
            "registry:8080/qits/projects-daemon:2026.820.154053",
            "registry:8080/qits/projects-daemon:863933e"),
        603_000_000L,
        lastWeek());

    ImageGc.Result result = sweep(false, List.of(), List.of());

    assertEquals(1, result.removed().size());
    assertTrue(result.failed().isEmpty());
    assertEquals(603_000_000L, result.bytesReclaimed());
    assertTrue(
        driver
            .calls()
            .contains(
                "removeImageReferences:registry:8080/qits/projects-daemon:2026.820.154053,"
                    + "registry:8080/qits/projects-daemon:863933e"),
        "both references in one call: " + driver.calls());
    assertFalse(
        driver.calls().stream().anyMatch(call -> call.startsWith("removeImage:")),
        "an id is only ever the argument for a dangling image");
  }

  @Test
  public void aDanglingImageIsStillRemovedByItsId() {
    // The other half of the same rule: an untagged image has no reference to untag, and its id is
    // the only name it has.
    driver.scriptImage(DANGLER, List.of(), 90_000_000L, lastWeek());

    sweep(false, List.of(), List.of());

    assertTrue(driver.calls().contains("removeImage:" + DANGLER));
  }

  @Test
  public void aDryRunReportsWhatARealRunWouldRemoveAndAsksDockerForNothing() {
    driver.scriptImage(DANGLER, List.of(), 90_000_000L, lastWeek());
    driver.scriptImage(CI, List.of("registry:8080/qits/qits-ci:cafe"), 325_000_000L, lastWeek());
    driver.scriptImageReferencesInUse(List.of("registry:8080/qits/qits-ci:cafe"));

    ImageGc.Result result = sweep(true, List.of(), List.of());

    assertTrue(result.dryRun());
    assertEquals(2, result.examined());
    assertEquals(1, result.removed().size());
    assertEquals(90_000_000L, result.bytesReclaimed());
    assertFalse(
        driver.calls().stream().anyMatch(call -> call.startsWith("removeImage")),
        "a dry run decides and asks docker to do nothing");
  }

  @Test
  public void anImageDockerRefusesToRemoveIsReportedAndCountsForNothing() {
    driver.scriptImage(DANGLER, List.of(), 90_000_000L, lastWeek());
    driver.scriptImageRemoval(
        DANGLER, new ContainersDriver.OpResult(false, "image is being used by stopped container"));

    ImageGc.Result result = sweep(false, List.of(), List.of());

    assertEquals(1, result.failed().size());
    assertEquals(DANGLER, result.failed().getFirst().id());
    assertTrue(result.removed().isEmpty());
    assertEquals(0, result.bytesReclaimed(), "nothing was reclaimed, so nothing is reported");
  }

  @Test
  public void aDockerThatWillNotSayWhatIsRunningStopsTheCollectionRatherThanEmptyingIt() {
    driver.scriptImage(DANGLER, List.of(), 90_000_000L, lastWeek());
    driver.scriptInUseUnreadable("connection refused");

    assertThrows(IllegalStateException.class, () -> sweep(false, List.of(), List.of()));

    assertFalse(
        driver.calls().stream().anyMatch(call -> call.startsWith("removeImage")),
        "an unanswerable in-use listing is what keeps every running container's image");
  }

  @Test
  public void twoTagsOfOneImageAreOneImageAndOnePinIsEnough() {
    driver.scriptImage(
        CI,
        List.of("registry:8080/qits/qits-ci:cafe", "registry:8080/qits/qits-ci:latest"),
        325_000_000L,
        lastWeek());

    ImageGc.Result result = sweep(false, List.of("qits/qits-ci:latest"), List.of());

    assertEquals(1, result.examined());
    assertKept(result, CI, ImageGc.PINNED);
  }

  @Test
  public void theUsageReadIsTheDriversAnswerAndNotAnInvention() {
    ContainersDriver.DiskUsage usage =
        new ContainersDriver.DiskUsage(
            new ContainersDriver.UsageLine(425, 18, 308_300_000_000L, 286_800_000_000L),
            new ContainersDriver.UsageLine(37, 18, 29_900_000L, 262_100L),
            new ContainersDriver.UsageLine(23, 7, 33_490_000_000L, 1_492_000_000L),
            new ContainersDriver.UsageLine(1877, 0, 302_500_000_000L, 103_500_000_000L));
    driver.scriptDiskUsage(usage);

    assertEquals(usage, usageRead.read());
    assertEquals(List.of("diskUsage"), driver.calls());
  }

  @Test
  public void anImageThatIsBothPinnedAndInUseReportsTheFirstRuleThatSpokeForIt() {
    // The order is the contract: in-use is checked before the pins, so a person reading the answer
    // learns the strongest reason rather than the most recently configured one.
    driver.scriptImage(CI, List.of("registry:8080/qits/qits-ci:cafe"), 325_000_000L, lastWeek());
    driver.scriptImageReferencesInUse(List.of("registry:8080/qits/qits-ci:cafe"));

    assertKept(sweep(false, List.of("qits/qits-ci:cafe"), List.of()), CI, ImageGc.IN_USE);
  }

  @Test
  public void aMinAgeOfNothingProtectsNothingByAge() {
    driver.scriptImage(DANGLER, List.of(), 90_000_000L, Instant.now());

    ImageGc.Result result = gc.sweep(false, null, List.of(), List.of());

    assertEquals(1, result.removed().size());
  }

  @Test
  public void anEmptyHostIsAnEmptyAnswerRatherThanAFailure() {
    ImageGc.Result result = sweep(false, List.of(), List.of());

    assertEquals(0, result.examined());
    assertTrue(result.removed().isEmpty() && result.kept().isEmpty() && result.failed().isEmpty());
  }
}
