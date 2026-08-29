package eu.wohlben.qits.containers.stories.reap;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import eu.wohlben.qits.containers.api.TokenValidationBootstrapIT;
import eu.wohlben.qits.containers.stories.boot.HostBootstrapIT;
import eu.wohlben.qits.containers.stories.lifecycle.WorkloadLifecycleIT;
import eu.wohlben.qits.containers.stories.ownership.OwnershipBoundaryIT;
import eu.wohlben.qits.containers.stories.support.StoryDocker;
import eu.wohlben.qits.containers.stories.support.StoryIdentities;
import eu.wohlben.qits.containers.stories.support.StoryTarget;
import eu.wohlben.qits.containers.stories.support.StoryWorkloads;
import eu.wohlben.qits.userflows.Interactions;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * <b>Taking a workload away</b> — the half of the lifecycle every consumer used to write by hand,
 * and the reason this service exists at all.
 *
 * <p>The two stories are the two shapes a removal has. One is addressed: a caller names a place, and
 * gets back the last thing the container printed because the tail was captured <em>before</em> the
 * removal and could not have been captured after it. The other is the <b>boot reap</b> — the sweep a
 * consumer runs when it restarts, to take away the workloads its previous life left behind.
 *
 * <p>The boot reap is where this service replaced something dangerous. qits-ci used to remove every
 * container carrying its label on the daemon it talked to, so two instances sharing one docker host
 * reaped each other's running steps. Here the sweep iterates the owner's <b>rows</b>, never a label
 * listing, and {@code createdBefore} is what keeps it a reap rather than a purge: an owner passes
 * the instant it came up, so a workload started after that — including one started while the sweep
 * runs — is not in the set. A defaulted "now" would take those with it and the caller would never
 * see that it had asked for something else, which is why a missing one is a 400.
 *
 * <p>This class removes the step {@code stories.lifecycle.WorkloadLifecycleIT} started, so it names
 * that class in {@code @UserflowRunsAfter}: the lifecycle is one walk told across two chapters, and
 * the ordering is what keeps the second chapter about the first one's container.
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class WorkloadReapIT {

  static final String CATEGORY = "reaping";

  static final String REMOVED_SLUG = "a-step-is-taken-away-with-its-last-logs-and-its-own-volume";
  static final String BOOT_REAP_SLUG =
      "a-boot-reap-takes-only-what-the-owner-started-before-it-came-up";

  /** The place the lifecycle chapter started, and this one takes away. */
  static final String PLACE =
      StoryTarget.placePath(StoryIdentities.CI, StoryWorkloads.STEP, WorkloadLifecycleIT.REF);

  static final String CONTAINER =
      StoryDocker.containerName(
          StoryIdentities.CI, StoryWorkloads.STEP, WorkloadLifecycleIT.REF);

  /**
   * A workload of this class's own for the sweep, so the collection it addresses holds exactly the
   * two places this story created — {@code step} already carries the lifecycle chapter's rows.
   */
  static final String WORKLOAD = "reap";

  static final String EARLY_REF = "story-early";
  static final String LATE_REF = "story-late";

  static final String EARLY_PLACE =
      StoryTarget.placePath(StoryIdentities.CI, WORKLOAD, EARLY_REF);
  static final String LATE_PLACE = StoryTarget.placePath(StoryIdentities.CI, WORKLOAD, LATE_REF);

  static final String COLLECTION = StoryTarget.workloadPath(StoryIdentities.CI, WORKLOAD);

  static final String EARLY_CONTAINER =
      StoryDocker.containerName(StoryIdentities.CI, WORKLOAD, EARLY_REF);
  static final String LATE_CONTAINER =
      StoryDocker.containerName(StoryIdentities.CI, WORKLOAD, LATE_REF);

  private static final List<String> MINTED = new ArrayList<>();

  @BeforeAll
  static void tapBothSidesOfTheService() {
    NetworkTaps.restAssured(StoryTarget.SERVICE);
    StoryDocker.installSource();
  }

  @UserStory(
      value = "A step is taken away with its last logs and its own volume",
      category = CATEGORY)
  @UserStoryDescription(
      """
      When a build step is over, qits-ci asks for the place to be empty — and asks for the tail of
      what the container printed on the way out. The ordering is the whole method: the logs are
      captured BEFORE the removal or they are lost with it, which is exactly the dance every
      consumer used to perform by hand. The workload's own volume goes with it because the caller
      said so and because the policy owns one; the platform's shared volumes never do, which is why
      a shared mount is a different type in the spec from a volume mount. And the row settles GONE
      only when the container really is gone — a remove docker refused leaves a row the next boot
      sweep replays, rather than a row nothing would ever look at again.
      """)
  @UserflowRunsAfter({
    TokenValidationBootstrapIT.class,
    HostBootstrapIT.class,
    WorkloadLifecycleIT.class,
    OwnershipBoundaryIT.class
  })
  @Order(1)
  void theStepIsRemovedWithItsLogsAndItsVolume(Interactions story) {
    NetworkCapture.actor(StoryIdentities.CI);
    String bearer = StoryIdentities.token(StoryIdentities.CI);
    MINTED.add(bearer);

    StoryIdentities.bearer(given(), bearer)
        .queryParam("volumes", true)
        .queryParam("logs", true)
        .delete(PLACE)
        .then()
        .statusCode(200)
        .body("containerName", is(CONTAINER))
        .body("existed", is(true))
        .body("logTail", containsString("booted and is serving"));
    story
        .note(
            "the tail comes back with the removal, because it was captured before it — after, there"
                + " is nothing left to read")
        .as("logs-captured-first");
    story
        .note(
            "the container is removed and the workload's own volume with it; the platform's shared"
                + " volumes are never taken, because they are not this workload's")
        .as("container-and-volume-removed");

    StoryIdentities.bearer(given(), bearer).get(PLACE).then().statusCode(404);
    story
        .note(
            "and the place is empty: 404 here means the row is cleanly absent and nothing else — a"
                + " read that could not reach the database is a 5xx, never this")
        .as("place-is-empty");
  }

  @UserStory(
      value = "A boot reap takes only what the owner started before it came up",
      category = CATEGORY)
  @UserStoryDescription(
      """
      A consumer that restarts has workloads its previous life left running, and no way of its own
      to tell them from the ones it is starting right now. So it passes the instant it came up and
      this service removes only the places of its own that are older than that — by iterating the
      owner's ROWS, never a label listing on the daemon. Both halves matter. The rows are what keeps
      two instances sharing one docker host out of each other's containers, since neither one's
      registry names the other's. The instant is what keeps this a reap rather than a purge: the
      workload started after the cut is still running when the sweep finishes, which is the whole
      difference between restarting a consumer and taking its work away.
      """)
  @UserflowRunsAfter({
    TokenValidationBootstrapIT.class,
    HostBootstrapIT.class,
    WorkloadLifecycleIT.class,
    OwnershipBoundaryIT.class
  })
  @Order(2)
  void theBootReapSpansOnlyTheRowsOlderThanTheCut(Interactions story) throws InterruptedException {
    NetworkCapture.actor(StoryIdentities.CI);
    String bearer = StoryIdentities.token(StoryIdentities.CI);
    MINTED.add(bearer);

    ensure(bearer, EARLY_PLACE, EARLY_CONTAINER);
    story
        .note("a workload from the consumer's previous life is running")
        .as("earlier-workload-running");

    // The cut is read between the two ensures, which is the only thing that makes them tell apart:
    // createdAt is written from this service's own injected Clock, and both processes are on this
    // host. The pauses are what keep the two rows on either side of a whole millisecond.
    Thread.sleep(50);
    Instant cameUp = Instant.now();
    Thread.sleep(50);

    ensure(bearer, LATE_PLACE, LATE_CONTAINER);
    story
        .note("the consumer restarts, and starts a second workload after coming up")
        .as("later-workload-running");

    StoryIdentities.bearer(given(), bearer)
        .queryParam("createdBefore", cameUp.toString())
        .delete(COLLECTION)
        .then()
        .statusCode(200)
        .body("destroyed", hasSize(1))
        .body("destroyed[0].ref", is(EARLY_REF))
        .body("destroyed[0].containerName", is(EARLY_CONTAINER))
        .body("destroyed[0].removed", is(true));
    story
        .note(
            "the reap names the instant the consumer came up, and takes exactly one place — the"
                + " one that was already there")
        .as("older-workload-reaped");

    StoryIdentities.bearer(given(), bearer)
        .get(COLLECTION)
        .then()
        .statusCode(200)
        .body("containers", hasSize(1))
        .body("containers.containerName", contains(LATE_CONTAINER));
    story
        .note(
            "and the workload started after the cut is untouched, which is what makes this a reap"
                + " rather than a purge — a defaulted 'now' would have taken it too")
        .as("newer-workload-survives");
  }

  /** One place, started. Part of the story's walk rather than a fixture: a reap needs something to reap. */
  private static void ensure(String bearer, String place, String container) {
    StoryIdentities.bearer(given(), bearer)
        .contentType(ContentType.JSON)
        .body(StoryWorkloads.explicit(StoryWorkloads.spec(StoryDocker.WORKLOAD_IMAGE)))
        .put(place)
        .then()
        .statusCode(201)
        .body("containerName", is(container))
        .body("state.observed", is("RUNNING"));
  }

  @AfterAll
  static void everyReapStoryIsComplete() {
    // --- the addressed removal --------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, REMOVED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, REMOVED_SLUG, "logs-captured-first");
    ReportAssertions.assertStepId(CATEGORY, REMOVED_SLUG, "container-and-volume-removed");
    ReportAssertions.assertStepId(CATEGORY, REMOVED_SLUG, "place-is-empty");
    http(REMOVED_SLUG, "DELETE " + PLACE + " -> 200");
    http(REMOVED_SLUG, "GET " + PLACE + " -> 404");
    // The three docker calls, in the order the method makes them — and the first one is the whole
    // reason the method has an order at all.
    docker(REMOVED_SLUG, "logs " + CONTAINER, "0");
    docker(REMOVED_SLUG, "rm " + CONTAINER, "0");
    docker(REMOVED_SLUG, "volume rm " + StoryWorkloads.STEP_VOLUME, "0");
    // FIVE, and there is no second inspect: the remove reported success, so the row settles GONE on
    // that. The inspect only happens when the remove did NOT work, which is what keeps a delete
    // from settling GONE over a container that is still running.
    ReportAssertions.assertEdgeCount(CATEGORY, REMOVED_SLUG, 5);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY, REMOVED_SLUG, List.of(StoryIdentities.CI, StoryTarget.SERVICE));

    // --- the boot reap ------------------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, BOOT_REAP_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, BOOT_REAP_SLUG, "earlier-workload-running");
    ReportAssertions.assertStepId(CATEGORY, BOOT_REAP_SLUG, "later-workload-running");
    ReportAssertions.assertStepId(CATEGORY, BOOT_REAP_SLUG, "older-workload-reaped");
    ReportAssertions.assertStepId(CATEGORY, BOOT_REAP_SLUG, "newer-workload-survives");
    http(BOOT_REAP_SLUG, "PUT " + EARLY_PLACE + " -> 201");
    http(BOOT_REAP_SLUG, "PUT " + LATE_PLACE + " -> 201");
    http(BOOT_REAP_SLUG, "DELETE " + COLLECTION + " -> 200");
    http(BOOT_REAP_SLUG, "GET " + COLLECTION + " -> 200");
    docker(BOOT_REAP_SLUG, "run " + EARLY_CONTAINER, "0");
    docker(BOOT_REAP_SLUG, "inspect " + EARLY_CONTAINER, "0");
    docker(BOOT_REAP_SLUG, "run " + LATE_CONTAINER, "0");
    docker(BOOT_REAP_SLUG, "inspect " + LATE_CONTAINER, "0");
    // ONE removal, and it names the earlier container. The later one is not in the sweep's set at
    // all, so there is no arrow for it — which is the negative this story is really about.
    docker(BOOT_REAP_SLUG, "rm " + EARLY_CONTAINER, "0");
    ReportAssertions.assertEdgeCount(CATEGORY, BOOT_REAP_SLUG, 9);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY, BOOT_REAP_SLUG, List.of(StoryIdentities.CI, StoryTarget.SERVICE));

    for (String slug : List.of(REMOVED_SLUG, BOOT_REAP_SLUG)) {
      for (String bearer : MINTED) {
        ReportAssertions.assertNotLeaked(CATEGORY, slug, bearer);
      }
    }
  }

  private static void http(String slug, String label) {
    ReportAssertions.assertEdge(
        CATEGORY, slug, NetworkEdge.HTTP, StoryIdentities.CI, StoryTarget.SERVICE, label);
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
