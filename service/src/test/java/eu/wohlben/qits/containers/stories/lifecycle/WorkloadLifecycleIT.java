package eu.wohlben.qits.containers.stories.lifecycle;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import eu.wohlben.qits.containers.api.TokenValidationBootstrapIT;
import eu.wohlben.qits.containers.stories.boot.HostBootstrapIT;
import eu.wohlben.qits.containers.stories.support.StoryDocker;
import eu.wohlben.qits.containers.stories.support.StoryIdentities;
import eu.wohlben.qits.containers.stories.support.StoryTarget;
import eu.wohlben.qits.containers.stories.support.StoryWorkloads;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.Network;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.NetworkTaps;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.UserflowRunsAfter;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * <b>A workload, end to end</b> — the walk this service exists for, from a spec a consumer sends to
 * a container that is running and printing.
 *
 * <p>The three stories are the three answers an {@code ensure} has, and each is a different shape of
 * diagram:
 *
 * <ul>
 *   <li>the place is <b>new</b>: a row, a volume, a run, an inspect that settles it, and a log tail
 *       a caller can read back;
 *   <li>the place is <b>already what was asked for</b>: one HTTP call and <b>nothing else at all</b>
 *       — the strongest claim in this catalogue, because an orchestrator that re-ran a container on
 *       every confirmation would be one no consumer could poll;
 *   <li>the image was <b>never published</b>: a pull that failed, a run that failed, an inspect that
 *       found nothing, and a 409 the caller can act on rather than a row left quietly pending.
 * </ul>
 *
 * <p><b>The docker edges are observed, not declared.</b> {@code core/docker/ContainerProcess} spawns
 * the docker CLI and reads its pipes, so {@code stories.support.StoryDocker} stands in for the
 * binary and records every argv with the exit code it answered — which is what lets the one
 * dependency this service exists for be drawn as evidence. The one edge that <b>is</b> declared is
 * the registry's own postgres: no tap on this side can see a JDBC round trip, and the ordering it
 * guarantees (the row carrying the container's name is committed <em>before</em> {@code docker run})
 * is the invariant everything else here rests on.
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class WorkloadLifecycleIT {

  static final String CATEGORY = "workloads";

  static final String STARTED_SLUG = "qits-ci-puts-a-build-step-on-the-host-and-reads-what-it-printed";
  static final String CONFIRMED_SLUG = "a-place-that-is-already-occupied-is-confirmed-not-restarted";
  static final String UNPUBLISHED_SLUG = "a-workload-whose-image-was-never-published-is-refused";

  /** The place this class owns. Literals, so the labels a diagram hashes never move — see StoryTarget. */
  public static final String REF = "story-alpha";

  static final String MISSING_REF = "story-missing";

  static final String PLACE = StoryTarget.placePath(StoryIdentities.CI, StoryWorkloads.STEP, REF);

  static final String MISSING_PLACE =
      StoryTarget.placePath(StoryIdentities.CI, StoryWorkloads.STEP, MISSING_REF);

  static final String LOGS = StoryTarget.logsPath(StoryIdentities.CI, StoryWorkloads.STEP, REF);

  /** What {@code control/ContainerNames} derives for this place — the address every docker call uses. */
  static final String CONTAINER =
      StoryDocker.containerName(StoryIdentities.CI, StoryWorkloads.STEP, REF);

  static final String MISSING_CONTAINER =
      StoryDocker.containerName(StoryIdentities.CI, StoryWorkloads.STEP, MISSING_REF);

  /** The store behind every row — declared, because nothing on this side can observe it. */
  static final String STORE = "postgresql";

  static final String STORE_LABEL = "the row that names the container, committed before the run";

  private static final List<String> MINTED = new ArrayList<>();

  @BeforeAll
  static void tapBothSidesOfTheService() {
    NetworkTaps.restAssured(StoryTarget.SERVICE);
    StoryDocker.installSource();
  }

  @UserStory(
      value = "qits-ci puts a build step on the host and reads what it printed",
      category = CATEGORY)
  @UserStoryDescription(
      """
      qits-ci holds no docker socket. To run a build step it PUTs one place —
      {owner}/{workload}/{ref} — with the spec it wants, and this service does the rest: it commits
      a row carrying the container name it derived, makes the volume that workload owns, runs the
      container, and settles the row on what an inspect answers. The order is the point. The row is
      written BEFORE docker run, so a crash anywhere after it leaves a container the registry can
      still name — which is what makes "adopt on boot, never reap" possible at all. Then the caller
      reads the place back, and reads the tail of what the container printed, which is the whole
      diagnosis a workload that died on its first breath can offer.
      """)
  @UserflowRunsAfter({TokenValidationBootstrapIT.class, HostBootstrapIT.class})
  @Order(1)
  void aStepContainerIsPutOnTheHost(Interactions story, Network network) {
    NetworkCapture.actor(StoryIdentities.CI);
    String bearer = StoryIdentities.token(StoryIdentities.CI);
    MINTED.add(bearer);

    StoryIdentities.bearer(given(), bearer)
        .contentType(ContentType.JSON)
        .body(
            StoryWorkloads.explicit(
                StoryWorkloads.withOwnVolume(
                    StoryWorkloads.spec(StoryDocker.WORKLOAD_IMAGE),
                    StoryWorkloads.STEP_VOLUME,
                    "/work")))
        .put(PLACE)
        .then()
        .statusCode(201)
        .body("containerName", is(CONTAINER))
        .body("state.desired", is("RUNNING"))
        .body("state.observed", is("RUNNING"))
        .body("endpoint.network", is(StoryTarget.NETWORK))
        .body("created", is(true));
    story
        .note(
            "qits-ci asks for one place — qits-ci/step/" + REF + " — and gets 201 with the"
                + " container name this service derived: " + CONTAINER)
        .as("place-created");
    story
        .note(
            "the workload's own volume is made first, then the container is run, then an inspect"
                + " settles the row on what the host actually says")
        .as("container-running");

    StoryIdentities.bearer(given(), bearer)
        .get(PLACE)
        .then()
        .statusCode(200)
        .body("containerName", is(CONTAINER))
        .body("state.observed", is("RUNNING"))
        .body("created", is(false));
    story
        .note("reading the place back costs no docker call at all — the row IS the registry")
        .as("place-read-back");

    StoryIdentities.bearer(given(), bearer)
        .get(LOGS)
        .then()
        .statusCode(200)
        .body("text", containsString("booted and is serving"))
        .body("truncated", is(false));
    story
        .note(
            "and the tail of what the container printed comes back bounded — no argv in this"
                + " service carries --rm, so a workload that died still has this to offer")
        .as("logs-served");

    // The one dependency no tap on this side can see, and the one whose ORDERING is the invariant:
    // the row naming the container is committed before docker is asked for anything.
    network.declare(NetworkEdge.JDBC, StoryTarget.SERVICE, STORE, STORE_LABEL);
  }

  @UserStory(
      value = "A place that is already occupied is confirmed, not restarted",
      category = CATEGORY)
  @UserStoryDescription(
      """
      A consumer ensures the same place on every pass of its own reconcile loop, which means this
      service is asked "is my workload there" far more often than it is asked to start one. So an
      ensure whose spec has not changed and whose container is running is an ADOPTION: 200 rather
      than 201, created=false, and — this is the claim — not one call to docker. An orchestrator
      that re-ran a container on every confirmation would give every consumer a workload that
      restarts as often as it is checked on, and would lose the container id, the writable layer and
      everything the workload had done.
      """)
  @UserflowRunsAfter({TokenValidationBootstrapIT.class, HostBootstrapIT.class})
  @Order(2)
  void ensuringTheSamePlaceAgainAsksDockerNothing(Interactions story) {
    NetworkCapture.actor(StoryIdentities.CI);
    String bearer = StoryIdentities.token(StoryIdentities.CI);
    MINTED.add(bearer);

    StoryIdentities.bearer(given(), bearer)
        .contentType(ContentType.JSON)
        .body(
            StoryWorkloads.explicit(
                StoryWorkloads.withOwnVolume(
                    StoryWorkloads.spec(StoryDocker.WORKLOAD_IMAGE),
                    StoryWorkloads.STEP_VOLUME,
                    "/work")))
        .put(PLACE)
        .then()
        .statusCode(200)
        .body("containerName", is(CONTAINER))
        .body("state.observed", is("RUNNING"))
        .body("created", is(false));
    story
        .note(
            "the identical ensure answers 200 with created=false: the place is already what was"
                + " asked for")
        .as("place-adopted");
    story
        .note(
            "and this story's diagram has exactly one arrow in it — the confirmation never reached"
                + " the daemon, so the container kept its id and everything it had done")
        .as("docker-untouched");
  }

  @UserStory(
      value = "A workload whose image was never published is refused",
      category = CATEGORY)
  @UserStoryDescription(
      """
      The one failure a caller can actually act on. A spec asking to pull on every start, naming an
      image no registry serves, is a 409 with IMAGE_MISSING on it — not a 200 carrying a row that
      says MISSING, and not a 500. The distinction costs one explicit docker pull, which is exactly
      why the ALWAYS pull policy makes one: it turns "the registry has no such image" into its own
      recorded outcome rather than a run failure somebody has to read docker's wording to
      understand. The row still exists and still carries what docker said, because a row is written
      before anything is attempted — a caller that reads this 409 knows to fix its image reference,
      and an operator reading the row afterwards knows why.
      """)
  @UserflowRunsAfter({TokenValidationBootstrapIT.class, HostBootstrapIT.class})
  @Order(3)
  void anUnpublishedImageIsAConflictRatherThanASilentlyPendingRow(Interactions story) {
    NetworkCapture.actor(StoryIdentities.CI);
    String bearer = StoryIdentities.token(StoryIdentities.CI);
    MINTED.add(bearer);

    StoryIdentities.bearer(given(), bearer)
        .contentType(ContentType.JSON)
        .body(StoryWorkloads.explicit(StoryWorkloads.alwaysPull(StoryDocker.UNPUBLISHED_IMAGE)))
        .put(MISSING_PLACE)
        .then()
        .statusCode(409)
        .body("code", is("IMAGE_MISSING"))
        .body("message", containsString(MISSING_CONTAINER));
    story
        .note(
            "the explicit pull fails, the run fails with it, and the caller gets 409 IMAGE_MISSING"
                + " — a refusal naming the container rather than a row nobody was told about")
        .as("image-missing-refused");

    StoryIdentities.bearer(given(), bearer)
        .get(MISSING_PLACE)
        .then()
        .statusCode(200)
        .body("state.observed", is("MISSING"))
        .body("detail", containsString("manifest unknown"));
    story
        .note(
            "and the row is there anyway, carrying docker's own words — written before the pull,"
                + " which is what makes a failed start diagnosable at all")
        .as("row-carries-the-reason");
  }

  @AfterAll
  static void everyLifecycleStoryIsComplete() {
    // --- the place that was started -------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, STARTED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, STARTED_SLUG, "place-created");
    ReportAssertions.assertStepId(CATEGORY, STARTED_SLUG, "container-running");
    ReportAssertions.assertStepId(CATEGORY, STARTED_SLUG, "place-read-back");
    ReportAssertions.assertStepId(CATEGORY, STARTED_SLUG, "logs-served");
    http(STARTED_SLUG, StoryIdentities.CI, "PUT " + PLACE + " -> 201");
    http(STARTED_SLUG, StoryIdentities.CI, "GET " + PLACE + " -> 200");
    http(STARTED_SLUG, StoryIdentities.CI, "GET " + LOGS + " -> 200");
    docker(STARTED_SLUG, "volume create " + StoryWorkloads.STEP_VOLUME, "0");
    docker(STARTED_SLUG, "run " + CONTAINER, "0");
    docker(STARTED_SLUG, "inspect " + CONTAINER, "0");
    docker(STARTED_SLUG, "logs " + CONTAINER, "0");
    ReportAssertions.assertDeclaredEdge(
        CATEGORY, STARTED_SLUG, NetworkEdge.JDBC, StoryTarget.SERVICE, STORE, STORE_LABEL);
    // Three calls in, four calls out to the daemon, one store behind them. Nothing else: this
    // service asks no registry whether the image exists and asks no peer who qits-ci is — the
    // bearer is judged on keys fetched once, at startup.
    ReportAssertions.assertEdgeCount(CATEGORY, STARTED_SLUG, 8);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY, STARTED_SLUG, List.of(StoryIdentities.CI, StoryTarget.SERVICE));

    // --- the confirmation -----------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, CONFIRMED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, CONFIRMED_SLUG, "place-adopted");
    ReportAssertions.assertStepId(CATEGORY, CONFIRMED_SLUG, "docker-untouched");
    http(CONFIRMED_SLUG, StoryIdentities.CI, "PUT " + PLACE + " -> 200");
    // ONE edge, and it is the whole story. A confirmation that cost a docker call would be a
    // confirmation no consumer could afford to make on every reconcile pass.
    ReportAssertions.assertEdgeCount(CATEGORY, CONFIRMED_SLUG, 1);
    ReportAssertions.assertNoEdgesTo(CATEGORY, CONFIRMED_SLUG, StoryDocker.DAEMON);
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, CONFIRMED_SLUG, List.of(StoryIdentities.CI));

    // --- the image nobody published --------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, UNPUBLISHED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, UNPUBLISHED_SLUG, "image-missing-refused");
    ReportAssertions.assertStepId(CATEGORY, UNPUBLISHED_SLUG, "row-carries-the-reason");
    http(UNPUBLISHED_SLUG, StoryIdentities.CI, "PUT " + MISSING_PLACE + " -> 409");
    http(UNPUBLISHED_SLUG, StoryIdentities.CI, "GET " + MISSING_PLACE + " -> 200");
    docker(UNPUBLISHED_SLUG, "pull " + StoryDocker.UNPUBLISHED_IMAGE, "1");
    docker(UNPUBLISHED_SLUG, "run " + MISSING_CONTAINER, "125");
    // The re-inspect after a refused run, and it is not defensive: the row named the container
    // before the run, so a container answering to that name would be OURS — a previous attempt
    // that started it and died before recording the fact — and would be adopted rather than
    // reported. Here there is none, which is what makes the answer MISSING.
    docker(UNPUBLISHED_SLUG, "inspect " + MISSING_CONTAINER, "1");
    ReportAssertions.assertEdgeCount(CATEGORY, UNPUBLISHED_SLUG, 5);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY, UNPUBLISHED_SLUG, List.of(StoryIdentities.CI, StoryTarget.SERVICE));

    // No bearer this class minted is anywhere in the bundle it publishes.
    for (String slug : List.of(STARTED_SLUG, CONFIRMED_SLUG, UNPUBLISHED_SLUG)) {
      for (String bearer : MINTED) {
        ReportAssertions.assertNotLeaked(CATEGORY, slug, bearer);
      }
    }
  }

  private static void http(String slug, String actor, String label) {
    ReportAssertions.assertEdge(
        CATEGORY, slug, NetworkEdge.HTTP, actor, StoryTarget.SERVICE, label);
  }

  private static void docker(String slug, String summary, String exitCode) {
    ReportAssertions.assertEdge(
        CATEGORY,
        slug,
        StoryDocker.KIND,
        StoryTarget.SERVICE,
        StoryDocker.DAEMON,
        StoryDocker.label(summary, exitCode));
  }
}
