package eu.wohlben.qits.containers.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.containers.control.ContainerNames;
import eu.wohlben.qits.containers.control.FakeContainersDriver;
import eu.wohlben.qits.containers.persistence.CtContainerRepository;
import eu.wohlben.qits.containers.persistence.CtVolumeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The whole REST surface, against the scripted driver — every route, and the three answers that are
 * easy to get wrong.
 *
 * <p><b>404 is asserted for absence and never for anything else.</b> A caller that reads 404 here
 * concludes its workload was never started and starts a second one, so "no row" is the only thing
 * that may produce one. The read paths go through {@code ContainerRegistry}'s retried bracket,
 * which gives up loudly rather than answering empty — that is what makes this claim hold for a
 * database that is down as well as for one that is up.
 *
 * <p><b>The gate is off here</b>, which is the shipped default and the deployment posture the whole
 * fleet is still in. What the gate ON looks like is {@link MachineGuardTest}
 * next door; splitting them is the same arrangement every sibling has, because a suite that had to
 * hold a token for every call would say nothing about either.
 */
@QuarkusTest
@TestProfile(FakeDriverProfile.class)
class ContainersApiTest {

  private static final String OWNER = "qits-ci";
  private static final String WORKLOAD = "step";
  private static final String REF = "run-1";

  /** Absolute, like every address in this suite: a moved prefix is then a 404 rather than a pass. */
  private static final String PLACE = "/containers/api/containers/qits-ci/step/run-1";

  private static final String WORKLOAD_PATH = "/containers/api/containers/qits-ci/step";

  private static final String OWNER_PATH = "/containers/api/containers/qits-ci";

  private static final String EXPLICIT =
      """
      {"spec":{"image":"alpine:3","network":"qits-net","args":["sleep","infinity"]},
       "policy":{"type":"EXPLICIT"},"recreate":"never"}""";

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

  // --- ensure -------------------------------------------------------------------------------------

  @Test
  void anEnsureCreatesThePlaceOnceAndConfirmsItAfterwards() {
    given()
        .contentType(ContentType.JSON)
        .body(EXPLICIT)
        .when()
        .put(PLACE)
        .then()
        .statusCode(201)
        .body("id", notNullValue())
        .body("containerName", is(ContainerNames.of(OWNER, WORKLOAD, REF)))
        .body("state.desired", is("RUNNING"))
        .body("state.observed", is("RUNNING"))
        .body("endpoint.network", is("qits-net"))
        // No alias in the spec, so the container's own name is the address — never a null a caller
        // would have to know to fall back from.
        .body("endpoint.alias", is(ContainerNames.of(OWNER, WORKLOAD, REF)))
        .body("endpoint.proxy", nullValue())
        .body("specHash", notNullValue())
        .body("created", is(true));

    // The same ask again is the same place, not a second container: 200, created=false, and the
    // driver was asked to run exactly once.
    given()
        .contentType(ContentType.JSON)
        .body(EXPLICIT)
        .when()
        .put(PLACE)
        .then()
        .statusCode(200)
        .body("created", is(false))
        .body("state.observed", is("RUNNING"));

    org.junit.jupiter.api.Assertions.assertEquals(
        1,
        driver.calls().stream().filter(call -> call.startsWith("run:")).count(),
        "a second ensure of an unchanged spec must not start a second container");
  }

  @Test
  void aSpecChangeARunOnceWorkloadCannotAnswerIsAFourOhNine() {
    given()
        .contentType(ContentType.JSON)
        .body(
            """
            {"spec":{"image":"alpine:3","network":"qits-net"},
             "policy":{"type":"EPHEMERAL"},"recreate":"never"}""")
        .when()
        .put(PLACE)
        .then()
        .statusCode(201);

    given()
        .contentType(ContentType.JSON)
        .body(
            """
            {"spec":{"image":"alpine:3.20","network":"qits-net"},
             "policy":{"type":"EPHEMERAL"},"recreate":"ifChanged"}""")
        .when()
        .put(PLACE)
        .then()
        .statusCode(409)
        .body("code", is("SPEC_CONFLICT"))
        .body("message", containsString("runs once"));
  }

  @Test
  void aNameALiveContainerOfAnotherPlaceHoldsIsAFourOhNineACallerCanBranchOn() {
    // The refusal that is left after V3, and the one qits-projects pre-checks for. Until it had a
    // code it was a raw 23505 the retry rethrew: a 500 with a null code, which no consumer can act
    // on — and every delete-then-ensure of one place looked the same way for a week.
    String squatting =
        """
        {"spec":{"image":"alpine:3","network":"qits-net","explicitName":"qits-shared-agent"},
         "policy":{"type":"EXPLICIT"},"recreate":"never"}""";

    given()
        .contentType(ContentType.JSON)
        .body(squatting)
        .when()
        .put(PLACE)
        .then()
        .statusCode(201);

    given()
        .contentType(ContentType.JSON)
        .body(squatting)
        .when()
        .put("/containers/api/containers/qits-ci/step/run-2")
        .then()
        .statusCode(409)
        .body("code", is("NAME_TAKEN"))
        .body("message", containsString("qits-shared-agent"));
  }

  @Test
  void aPlaceThatWasDeletedIsStartedAgainUnderTheSameNameRatherThanRefused() {
    given().contentType(ContentType.JSON).body(EXPLICIT).when().put(PLACE).then().statusCode(201);
    given().when().delete(PLACE).then().statusCode(200);

    given()
        .contentType(ContentType.JSON)
        .body(EXPLICIT)
        .when()
        .put(PLACE)
        .then()
        .statusCode(201)
        .body("containerName", is(ContainerNames.of(OWNER, WORKLOAD, REF)))
        .body("created", is(true))
        .body("state.observed", is("RUNNING"));
  }

  @Test
  void aBodyWithNoInitFieldStartsAContainerWithoutOneAndABodyWithItAsksForTini() {
    // The pair of bodies a rollout really sees: every caller written before the field existed sends
    // the first shape forever, and an absent field has to keep meaning what it meant then. A 400
    // here — or a container that quietly gained a PID 1 — is what a required field would have cost.
    given().contentType(ContentType.JSON).body(EXPLICIT).when().put(PLACE).then().statusCode(201);
    org.junit.jupiter.api.Assertions.assertFalse(driver.ranSpecs().getFirst().init());

    given()
        .contentType(ContentType.JSON)
        .body(
            """
            {"spec":{"image":"alpine:3","network":"qits-net","init":true},
             "policy":{"type":"EXPLICIT"},"recreate":"never"}""")
        .when()
        .put("/containers/api/containers/qits-ci/step/run-init")
        .then()
        .statusCode(201);
    org.junit.jupiter.api.Assertions.assertTrue(driver.ranSpecs().getLast().init());
  }

  @Test
  void anImageNothingPublishedIsAFourOhNineAndNotAGreenPlace() {
    driver.scriptRun(
        new eu.wohlben.qits.containers.control.ContainersDriver.Started(
            false, "", "docker: Error response from daemon: manifest unknown: manifest unknown."));

    given()
        .contentType(ContentType.JSON)
        .body(EXPLICIT)
        .when()
        .put(PLACE)
        .then()
        .statusCode(409)
        .body("code", is("IMAGE_MISSING"));

    // The row is still there and says what happened — a 409 is the answer to the caller, not a
    // reason to forget the place.
    given().when().get(PLACE).then().statusCode(200).body("state.observed", is("MISSING"));
  }

  @Test
  void aValueThisServiceWillNotPutInAnArgvIsAFourHundred() {
    given()
        .contentType(ContentType.JSON)
        .body(
            """
            {"spec":{"image":"alpine:3","network":"qits-net","aliases":["Not A Label"]},
             "policy":{"type":"EXPLICIT"}}""")
        .when()
        .put(PLACE)
        .then()
        .statusCode(400)
        .body("code", is("INVALID"))
        .body("message", containsString("network alias"));

    // And the belt on the path itself, which runs before any body is read.
    given()
        .contentType(ContentType.JSON)
        .body(EXPLICIT)
        .when()
        .put("/containers/api/containers/NOT-AN-OWNER/step/run-1")
        .then()
        .statusCode(400)
        .body("code", is("INVALID"));
  }

  // --- reads --------------------------------------------------------------------------------------

  @Test
  void aStatusIsFourOhFourOnlyWhenNoRowNamesThePlace() {
    given().when().get(PLACE).then().statusCode(404);

    ensured();

    given()
        .when()
        .get(PLACE)
        .then()
        .statusCode(200)
        .body("containerName", is(ContainerNames.of(OWNER, WORKLOAD, REF)))
        .body("state.observed", is("RUNNING"))
        .body("created", is(false));
  }

  @Test
  void aListingComesFromTheRows() {
    ensured();
    given()
        .contentType(ContentType.JSON)
        .body(EXPLICIT)
        .when()
        .put("/containers/api/containers/qits-ci/agent/run-2")
        .then()
        .statusCode(201);

    given()
        .when()
        .get(OWNER_PATH)
        .then()
        .statusCode(200)
        .body("containers", hasSize(2))
        .body("containers.containerName", hasItem(ContainerNames.of(OWNER, WORKLOAD, REF)));

    given()
        .when()
        .get(WORKLOAD_PATH)
        .then()
        .statusCode(200)
        .body("containers", hasSize(1))
        .body("containers[0].endpoint.network", is("qits-net"));

    // Nothing listed by label anywhere: an owner with no rows has no places, whatever is on the
    // host.
    given()
        .when()
        .get("/containers/api/containers/qits-workspaces")
        .then()
        .statusCode(200)
        .body("containers", hasSize(0));
  }

  @Test
  void theLogTailIsReadableWhileThePlaceIsExited() {
    ensured();
    driver.scriptLogs(ContainerNames.of(OWNER, WORKLOAD, REF), "the workload said this\n");

    given().when().post(PLACE + "/stop").then().statusCode(200).body("state.observed", is("EXITED"));

    given()
        .when()
        .get(PLACE + "/logs?tail=50")
        .then()
        .statusCode(200)
        .body("text", containsString("the workload said this"))
        .body("truncated", is(false));

    given().when().get("/containers/api/containers/qits-ci/step/nothing/logs").then().statusCode(404);
  }

  // --- the writes that change what is running ------------------------------------------------------

  @Test
  void stopAndTouchAnswerFourOhFourForAPlaceThatIsNotThere() {
    given().when().post(PLACE + "/stop").then().statusCode(404);
    given().when().post(PLACE + "/touch").then().statusCode(404);

    ensured();

    given()
        .when()
        .post(PLACE + "/stop")
        .then()
        .statusCode(200)
        .body("state.desired", is("STOPPED"));
    given().when().post(PLACE + "/touch").then().statusCode(204);
  }

  @Test
  void aDeleteCapturesTheTailBeforeItRemovesAndIsIdempotent() {
    ensured();
    driver.scriptLogs(ContainerNames.of(OWNER, WORKLOAD, REF), "why it died\n");

    given()
        .when()
        .delete(PLACE + "?volumes=true&logs=true")
        .then()
        .statusCode(200)
        .body("existed", is(true))
        .body("logTail", containsString("why it died"))
        .body("detail", nullValue());

    // Captured BEFORE the removal or lost with it — which is the whole ordering of the method.
    int logs = driver.calls().indexOf("logs:" + ContainerNames.of(OWNER, WORKLOAD, REF));
    int removed = driver.calls().indexOf("remove:" + ContainerNames.of(OWNER, WORKLOAD, REF));
    org.junit.jupiter.api.Assertions.assertTrue(
        logs >= 0 && logs < removed, "the tail must be captured before the removal: " + driver.calls());

    given()
        .when()
        .delete(PLACE)
        .then()
        .statusCode(200)
        .body("existed", is(false))
        .body("detail", is("[already absent]"));
  }

  @Test
  void aDestroyAllNeedsTheInstantThatMakesItABootReap() {
    ensured();

    given().when().delete(WORKLOAD_PATH).then().statusCode(400).body("code", is("INVALID"));
    given()
        .when()
        .delete(WORKLOAD_PATH + "?createdBefore=yesterday")
        .then()
        .statusCode(400)
        .body("code", is("INVALID"));

    // An instant before the row was written reaches nothing: what an owner started after it came
    // up is not in the set.
    given()
        .when()
        .delete(WORKLOAD_PATH + "?createdBefore=2020-01-01T00:00:00Z")
        .then()
        .statusCode(200)
        .body("destroyed", hasSize(0));

    given()
        .when()
        .delete(WORKLOAD_PATH + "?createdBefore=" + Instant.now().plusSeconds(60))
        .then()
        .statusCode(200)
        .body("destroyed", hasSize(1))
        .body("destroyed[0].ref", is(REF))
        .body("destroyed[0].removed", is(true));

    given().when().get(PLACE).then().statusCode(404);
  }

  // --- volumes -------------------------------------------------------------------------------------

  @Test
  void aVolumeIsClaimedByARowAndFourOhFoursOnceItIsGone() {
    given().when().get("/containers/api/volumes/qits-ci/qits-ci-cache").then().statusCode(404);

    given()
        .when()
        .put("/containers/api/volumes/qits-ci/qits-ci-cache")
        .then()
        .statusCode(200)
        .body("desired", is("PRESENT"))
        .body("existed", is(false))
        .body("id", notNullValue());

    given()
        .when()
        .put("/containers/api/volumes/qits-ci/qits-ci-cache")
        .then()
        .statusCode(200)
        .body("existed", is(true));

    given()
        .when()
        .get("/containers/api/volumes/qits-ci/qits-ci-cache")
        .then()
        .statusCode(200)
        .body("name", is("qits-ci-cache"))
        .body("owner", is(OWNER));

    given()
        .when()
        .delete("/containers/api/volumes/qits-ci/qits-ci-cache")
        .then()
        .statusCode(200)
        .body("existed", is(true))
        .body("desired", is("ABSENT"));

    // Idempotent, exactly as a container delete is.
    given()
        .when()
        .delete("/containers/api/volumes/qits-ci/qits-ci-cache")
        .then()
        .statusCode(200)
        .body("existed", is(false));

    given().when().get("/containers/api/volumes/qits-ci/qits-ci-cache").then().statusCode(404);
  }

  @Test
  void aVolumeNameTheArgvWouldReadAsAnOptionIsRefused() {
    given()
        .when()
        .put("/containers/api/volumes/qits-ci/-nope")
        .then()
        .statusCode(400)
        .body("code", is("INVALID"))
        .body("message", containsString("volume name"));
  }

  /** One place, running, through the production path. */
  private static void ensured() {
    given()
        .contentType(ContentType.JSON)
        .body(EXPLICIT)
        .when()
        .put(PLACE)
        .then()
        .statusCode(201)
        .body("state.observed", equalTo("RUNNING"));
  }
}
