package eu.wohlben.qits.containers.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.containers.control.ContainersDriver;
import eu.wohlben.qits.containers.control.FakeContainersDriver;
import eu.wohlben.qits.containers.persistence.CtContainerRepository;
import eu.wohlben.qits.containers.persistence.CtVolumeRepository;
import eu.wohlben.qits.containers.spec.ContainerLabels;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The four garbage-collection routes on the wire — the shapes qits-platform-orchestrator builds
 * against without seeing this repository.
 *
 * <p><b>The field names are the contract</b>, which is why this suite reads them out of the JSON
 * rather than out of a record: a rename that compiled here would be a step of the gc process
 * silently reading null, and nothing in either build would notice.
 *
 * <p>The rules themselves are asserted next door, on the collections
 * ({@code CtImageGcTest}, {@code CtVolumeGcTest}, {@code CtBuildCacheGcTest}). What is left for
 * this class is the wire: the shapes, the defaults a missing field takes, and the refusals a body
 * this service will not act on gets.
 */
@QuarkusTest
@TestProfile(FakeDriverProfile.class)
class GcApiTest {

  private static final String USAGE = "/containers/api/gc/usage";
  private static final String IMAGES = "/containers/api/gc/images";
  private static final String VOLUMES = "/containers/api/gc/volumes";
  private static final String BUILD_CACHE = "/containers/api/gc/build-cache";

  private static final String DANGLER =
      "sha256:3333333333333333333333333333333333333333333333333333333333333333";

  @Inject FakeContainersDriver driver;

  @Inject CtContainerRepository containers;

  @Inject CtVolumeRepository volumes;

  @BeforeEach
  void wipe() {
    driver.reset();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              containers.deleteAll();
              volumes.deleteAll();
            });
  }

  // --- usage ------------------------------------------------------------------------------------

  @Test
  void theUsageIsFourStoresWithFourNumbersEach() {
    driver.scriptDiskUsage(
        new ContainersDriver.DiskUsage(
            new ContainersDriver.UsageLine(425, 18, 308_300_000_000L, 286_800_000_000L),
            new ContainersDriver.UsageLine(37, 18, 29_900_000L, 262_100L),
            new ContainersDriver.UsageLine(23, 7, 33_490_000_000L, 1_492_000_000L),
            new ContainersDriver.UsageLine(1877, 0, 302_500_000_000L, 103_500_000_000L)));

    given()
        .when()
        .get(USAGE)
        .then()
        .statusCode(200)
        .body("images.count", is(425))
        .body("images.active", is(18))
        .body("images.sizeBytes", is(308_300_000_000L))
        .body("images.reclaimableBytes", is(286_800_000_000L))
        .body("containers.count", is(37))
        .body("volumes.count", is(23))
        .body("buildCache.count", is(1877))
        .body("buildCache.reclaimableBytes", is(103_500_000_000L));
  }

  @Test
  void aUsageDockerCouldNotAnswerIsAFailureAndNeverAnEmptyHost() {
    driver.scriptDown("connection refused");

    given().when().get(USAGE).then().statusCode(500);
  }

  // --- images -----------------------------------------------------------------------------------

  @Test
  void anImageCollectionAnswersTheWholeShape() {
    driver.scriptImage(DANGLER, List.of(), 90_000_000L, Instant.now().minus(Duration.ofDays(7)));

    given()
        .contentType(ContentType.JSON)
        .body("{\"dryRun\":false,\"minAge\":\"PT6H\",\"keep\":[],\"keepPrefixes\":[]}")
        .when()
        .post(IMAGES)
        .then()
        .statusCode(200)
        .body("dryRun", is(false))
        .body("examined", is(1))
        .body("bytesReclaimed", is(90_000_000))
        .body("removed", hasSize(1))
        .body("removed[0].id", is(DANGLER))
        .body("removed[0].tags", emptyIterable())
        .body("removed[0].sizeBytes", is(90_000_000))
        .body("removed[0].reason", is("dangling"))
        .body("kept", emptyIterable())
        .body("failed", emptyIterable());
  }

  @Test
  void aPinnedImageComesBackOnTheKeptListWithItsReason() {
    driver.scriptImage(
        DANGLER,
        List.of("registry:8080/qits/build-images/node-docker-base:1"),
        600_000_000L,
        Instant.now().minus(Duration.ofDays(7)));

    given()
        .contentType(ContentType.JSON)
        .body(
            "{\"dryRun\":false,\"minAge\":\"PT6H\",\"keep\":[],"
                + "\"keepPrefixes\":[\"qits/build-images/\"]}")
        .when()
        .post(IMAGES)
        .then()
        .statusCode(200)
        .body("removed", emptyIterable())
        .body("kept[0].reason", is("pinned"))
        .body("kept[0].tags", hasItem("registry:8080/qits/build-images/node-docker-base:1"));
  }

  @Test
  void aBodyThatForgotDryRunIsADryRun() {
    // The destructive reading is never the one a missing value gets — the boot reap's stance,
    // applied to the one call on this surface that removes things nothing named.
    driver.scriptImage(DANGLER, List.of(), 90_000_000L, Instant.now().minus(Duration.ofDays(7)));

    given()
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post(IMAGES)
        .then()
        .statusCode(200)
        .body("dryRun", is(true))
        .body("removed", hasSize(1));

    org.junit.jupiter.api.Assertions.assertFalse(
        driver.calls().stream().anyMatch(call -> call.startsWith("removeImage")));
  }

  @Test
  void aMinAgeThatIsNotADurationIsRefusedWithTheFieldNamed() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"dryRun\":true,\"minAge\":\"6 hours\"}")
        .when()
        .post(IMAGES)
        .then()
        .statusCode(400)
        .body("code", is("INVALID"))
        .body("message", org.hamcrest.Matchers.containsString("minAge"));
  }

  // --- volumes ----------------------------------------------------------------------------------

  @Test
  void aVolumeCollectionAnswersTheWholeShape() {
    driver.scriptDanglingVolumes(List.of("ws-data-orphan", "somebody-elses"));
    driver.scriptVolumeDetail(
        "ws-data-orphan",
        Map.of(ContainerLabels.MANAGED, ContainerLabels.MANAGED_VOLUME),
        Instant.now().minus(Duration.ofDays(7)));
    driver.scriptVolumeDetail("somebody-elses", Map.of(), Instant.now().minus(Duration.ofDays(7)));

    given()
        .contentType(ContentType.JSON)
        .body("{\"dryRun\":false,\"minAge\":\"PT24H\"}")
        .when()
        .post(VOLUMES)
        .then()
        .statusCode(200)
        .body("dryRun", is(false))
        .body("removed", hasSize(1))
        .body("removed[0].name", is("ws-data-orphan"))
        .body("removed[0].reason", is("managed-no-row"))
        .body("kept", hasSize(1))
        .body("kept[0].name", is("somebody-elses"))
        .body("kept[0].reason", is("unmanaged"))
        .body("failed", emptyIterable());
  }

  @Test
  void aVolumeBodyThatForgotDryRunIsADryRunToo() {
    driver.scriptDanglingVolumes(List.of("ws-data-orphan"));
    driver.scriptVolumeDetail(
        "ws-data-orphan",
        Map.of(ContainerLabels.MANAGED, ContainerLabels.MANAGED_VOLUME),
        Instant.now().minus(Duration.ofDays(7)));

    given()
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post(VOLUMES)
        .then()
        .statusCode(200)
        .body("dryRun", is(true))
        .body("removed", hasSize(1));

    org.junit.jupiter.api.Assertions.assertFalse(
        driver.calls().contains("removeVolume:ws-data-orphan"));
  }

  // --- build cache ------------------------------------------------------------------------------

  @Test
  void aBuildCacheCollectionAnswersTheHostAndOneRowPerBuilder() {
    driver.scriptBuilders(List.of("buildx_buildkit_qits-bootstrap-builder-v40"));
    driver.scriptHostCache(
        new ContainersDriver.CacheResult(true, 103_500_000_000L, "Total: 103.5GB"));
    driver.scriptBuilderCache(
        "buildx_buildkit_qits-bootstrap-builder-v40",
        new ContainersDriver.CacheResult(true, 27_110_000_000L, "Total: 27.11GB"));

    given()
        .contentType(ContentType.JSON)
        .body("{\"dryRun\":false,\"keepStorageBytes\":20000000000}")
        .when()
        .post(BUILD_CACHE)
        .then()
        .statusCode(200)
        .body("dryRun", is(false))
        .body("host.reclaimedBytes", is(103_500_000_000L))
        .body("host.detail", is("Total: 103.5GB"))
        .body("host.error", nullValue())
        // The platform's own buildkitd rides first in every sweep, at the host's keep-storage;
        // the buildx builders follow.
        .body("builders", hasSize(2))
        .body(
            "builders[0].container",
            is(eu.wohlben.qits.containers.spec.ContainersIdentifiers.PLATFORM_BUILDER))
        .body("builders[1].container", is("buildx_buildkit_qits-bootstrap-builder-v40"))
        .body("builders[1].reclaimedBytes", is(27_110_000_000L))
        .body("builders[1].detail", notNullValue())
        .body("builders[1].error", nullValue());
  }

  @Test
  void aBuilderThatFailedIsARowWithAnErrorAndTheCallIsStillTwoHundred() {
    driver.scriptBuilders(List.of("buildx_buildkit_wedged"));
    driver.scriptBuilderCache(
        "buildx_buildkit_wedged",
        new ContainersDriver.CacheResult(false, 0, "Error response from daemon: is not running"));

    given()
        .contentType(ContentType.JSON)
        .body("{\"dryRun\":false,\"keepStorageBytes\":20000000000}")
        .when()
        .post(BUILD_CACHE)
        .then()
        .statusCode(200)
        .body("builders[1].error", notNullValue())
        .body("builders[1].reclaimedBytes", is(0));
  }

  @Test
  void aBuilderKeepStorageIsOptionalAndFallsBackToTheHostsNumber() {
    driver.scriptBuilders(List.of("buildx_buildkit_qits-bootstrap-builder-v40"));

    given()
        .contentType(ContentType.JSON)
        .body("{\"dryRun\":false,\"keepStorageBytes\":20000000000}")
        .when()
        .post(BUILD_CACHE)
        .then()
        .statusCode(200);

    org.junit.jupiter.api.Assertions.assertTrue(
        driver
            .calls()
            .contains("pruneBuilderCache:buildx_buildkit_qits-bootstrap-builder-v40:20000000000"),
        "a caller that does not know the two caches are separate must not empty one: "
            + driver.calls());
  }

  @Test
  void aBuilderKeepStorageOfItsOwnReachesTheBuilderAndNotTheHost() {
    driver.scriptBuilders(List.of("buildx_buildkit_qits-bootstrap-builder-v40"));

    given()
        .contentType(ContentType.JSON)
        .body(
            "{\"dryRun\":false,\"keepStorageBytes\":20000000000,"
                + "\"builderKeepStorageBytes\":1000000000}")
        .when()
        .post(BUILD_CACHE)
        .then()
        .statusCode(200);

    org.junit.jupiter.api.Assertions.assertEquals(
        List.of(
            "pruneBuildCache:20000000000",
            "pruneBuilderCache:"
                + eu.wohlben.qits.containers.spec.ContainersIdentifiers.PLATFORM_BUILDER
                + ":20000000000",
            "listBuildxBuilders",
            "pruneBuilderCache:buildx_buildkit_qits-bootstrap-builder-v40:1000000000"),
        driver.calls());
  }

  @Test
  void aNegativeBuilderKeepStorageIsRefusedLikeTheHostsOwn() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"dryRun\":false,\"keepStorageBytes\":1,\"builderKeepStorageBytes\":-1}")
        .when()
        .post(BUILD_CACHE)
        .then()
        .statusCode(400)
        .body("code", is("INVALID"))
        .body("message", org.hamcrest.Matchers.containsString("builderKeepStorageBytes"));
  }

  @Test
  void aRealPruneWithNoKeepStorageIsRefused() {
    // "Keep nothing" is not a value anybody leaves a field out to ask for.
    given()
        .contentType(ContentType.JSON)
        .body("{\"dryRun\":false}")
        .when()
        .post(BUILD_CACHE)
        .then()
        .statusCode(400)
        .body("code", is("INVALID"))
        .body("message", org.hamcrest.Matchers.containsString("keepStorageBytes"));
  }

  @Test
  void aDryRunNeedsNoKeepStorageBecauseItPrunesNothing() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"dryRun\":true}")
        .when()
        .post(BUILD_CACHE)
        .then()
        .statusCode(200)
        .body("dryRun", is(true))
        .body("host.reclaimedBytes", is(0));
  }
}
