package eu.wohlben.qits.containers.stories.ownership;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

import eu.wohlben.qits.containers.api.TokenValidationBootstrapIT;
import eu.wohlben.qits.containers.stories.boot.HostBootstrapIT;
import eu.wohlben.qits.containers.stories.lifecycle.WorkloadLifecycleIT;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * <b>Whose containers are whose</b>, told from two machine callers at once — and then the one family
 * of routes that belongs to nobody.
 *
 * <p>This is the claim no sibling repository's guard can make. Every other service on the platform
 * guards on something a token was <em>granted</em> (a project, a workspace); this one guards on who
 * is <em>calling</em>: {@code api/OwnerGuard} compares the owner in the path against the token's
 * subject, <b>whole</b>. The prefix matters as much as the name — {@code dev-qits-ci} and {@code
 * prod-qits-ci} are two owners, and that is precisely what keeps two environments sharing one docker
 * daemon out of each other's containers.
 *
 * <p><b>And the negative is the interesting half.</b> A refused write must never have started
 * anything, so the first story's real assertion is that nothing at all reached the daemon. A guard
 * that refused <em>after</em> a {@code docker run} would be a guard that had already handed the
 * caller what it asked for.
 *
 * <p>The third story is the deliberate exception. The four {@code gc/} routes carry the machine role
 * and no OwnerGuard, because they are addressed to the HOST — its images, its dangling volumes, its
 * build cache — which belong to no owner. There is no path owner to compare a subject against and no
 * honest way to invent one, and a route that quietly gained one would refuse
 * qits-platform-orchestrator, whose subject is nobody's owner. {@code MachineGuardTest} pins that
 * absence as a unit; this pins what it is <em>for</em>.
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class OwnershipBoundaryIT {

  static final String CATEGORY = "ownership";

  static final String REFUSED_SLUG = "a-module-may-only-put-containers-in-its-own-name";
  static final String OWN_SLUG = "and-a-module-s-own-workload-is-served-on-the-same-token";
  static final String HOST_SLUG = "the-host-s-own-stores-belong-to-no-owner";

  /** A place in qits-ci's namespace that qits-workspaces has no business creating. */
  static final String INTRUDER_PLACE =
      StoryTarget.placePath(StoryIdentities.CI, StoryWorkloads.STEP, "story-intruder");

  /** qits-ci's live place, which the refused caller tries to delete. */
  static final String CI_PLACE =
      StoryTarget.placePath(StoryIdentities.CI, StoryWorkloads.STEP, WorkloadLifecycleIT.REF);

  /** qits-ci's whole inventory, which is qits-ci's business and nobody else's. */
  static final String CI_INVENTORY = StoryTarget.ownerPath(StoryIdentities.CI);

  /** The workspace qits-workspaces owns, and may therefore start. */
  static final String WORKSPACE_REF = "story-one";

  static final String WORKSPACE_PLACE =
      StoryTarget.placePath(StoryIdentities.WORKSPACES, StoryWorkloads.WORKSPACE, WORKSPACE_REF);

  static final String WORKSPACE_CONTAINER =
      StoryDocker.containerName(
          StoryIdentities.WORKSPACES, StoryWorkloads.WORKSPACE, WORKSPACE_REF);

  static final String USAGE = StoryTarget.GC_PATH + "/usage";

  static final String IMAGE_GC = StoryTarget.GC_PATH + "/images";

  private static final List<String> MINTED = new ArrayList<>();

  @BeforeAll
  static void tapBothSidesOfTheService() {
    NetworkTaps.restAssured(StoryTarget.SERVICE);
    StoryDocker.installSource();
  }

  @UserStory(value = "A module may only put containers in its own name", category = CATEGORY)
  @UserStoryDescription(
      """
      qits-workspaces holds an impeccable machine token: the platform's own issuer signed it, the
      audience is this service, and it carries the coarse machine role every route here demands. It
      is still refused 403 on every route addressed to qits-ci — starting a container there, taking
      one away, and even reading the inventory. That is OwnerGuard, and 403 rather than 401 is the
      distinction an operator needs: the token was understood, and the grant it is missing is
      ownership. Nothing here is read by a person, and a row says which containers another module
      has running, so a read is guarded exactly as a write is. The half that matters most is what
      does NOT happen: not one of the three refusals reached the docker daemon, so nothing was
      started, nothing was stopped, and qits-ci's own workload is exactly where it was.
      """)
  @UserflowRunsAfter({TokenValidationBootstrapIT.class, HostBootstrapIT.class, WorkloadLifecycleIT.class})
  @Order(1)
  void anotherModulesTokenOpensNothingOfQitsCis(Interactions story) {
    NetworkCapture.actor(StoryIdentities.WORKSPACES);
    String stranger = StoryIdentities.token(StoryIdentities.WORKSPACES);
    MINTED.add(stranger);

    StoryIdentities.bearer(given(), stranger)
        .contentType(ContentType.JSON)
        .body(StoryWorkloads.explicit(StoryWorkloads.spec(StoryDocker.WORKLOAD_IMAGE)))
        .put(INTRUDER_PLACE)
        .then()
        .statusCode(403);
    story
        .note(
            "qits-workspaces cannot start a container in qits-ci's namespace, however good its own"
                + " token is")
        .as("foreign-start-refused");

    StoryIdentities.bearer(given(), stranger).delete(CI_PLACE).then().statusCode(403);
    story
        .note("nor take one of qits-ci's away — which is the refusal that matters most")
        .as("foreign-delete-refused");

    StoryIdentities.bearer(given(), stranger).get(CI_INVENTORY).then().statusCode(403);
    story
        .note(
            "nor even read the inventory: a row says which containers another module has running,"
                + " so a read here is guarded exactly as a write is")
        .as("foreign-read-refused");

    // And qits-ci's workload is untouched — asked as qits-ci, because that is the only caller who
    // may ask. This is the assertion the three refusals are for.
    NetworkCapture.actor(StoryIdentities.CI);
    String owner = StoryIdentities.token(StoryIdentities.CI);
    MINTED.add(owner);
    StoryIdentities.bearer(given(), owner)
        .get(CI_PLACE)
        .then()
        .statusCode(200)
        .body("state.observed", is("RUNNING"));
    story
        .note("and qits-ci's step is still running, because nothing that was refused ever ran")
        .as("owners-workload-untouched");
  }

  @UserStory(
      value = "And a module's own workload is served on the same token",
      category = CATEGORY)
  @UserStoryDescription(
      """
      The other half of the pair, and the reason the refusals above are about ownership rather than
      about qits-workspaces being unwelcome. The same credential that opened nothing of qits-ci's
      starts a workspace container in qits-workspaces' own namespace, and this service does exactly
      what it did for qits-ci: derives a name from the place, commits the row, runs the container,
      settles the row on an inspect. One guard, two answers, decided by nothing but whose name is in
      the path.
      """)
  @UserflowRunsAfter({TokenValidationBootstrapIT.class, HostBootstrapIT.class, WorkloadLifecycleIT.class})
  @Order(2)
  void aModuleStartsItsOwnWorkloadOnTheSameToken(Interactions story) {
    NetworkCapture.actor(StoryIdentities.WORKSPACES);
    String bearer = StoryIdentities.token(StoryIdentities.WORKSPACES);
    MINTED.add(bearer);

    StoryIdentities.bearer(given(), bearer)
        .contentType(ContentType.JSON)
        .body(StoryWorkloads.explicit(StoryWorkloads.spec(StoryDocker.WORKLOAD_IMAGE)))
        .put(WORKSPACE_PLACE)
        .then()
        .statusCode(201)
        .body("containerName", is(WORKSPACE_CONTAINER))
        .body("state.observed", is("RUNNING"));
    story
        .note(
            "the same token starts qits-workspaces' own workspace container, named for the place it"
                + " occupies: " + WORKSPACE_CONTAINER)
        .as("own-place-created");

    StoryIdentities.bearer(given(), bearer)
        .get(WORKSPACE_PLACE)
        .then()
        .statusCode(200)
        .body("state.observed", is("RUNNING"));
    story
        .note("so the refusals were about ownership, and about nothing else")
        .as("own-place-served");
  }

  @UserStory(value = "The host's own stores belong to no owner", category = CATEGORY)
  @UserStoryDescription(
      """
      An image is named by no owner. qits-ci built it, qits-platform-deployments pinned it, and a
      container of any module may be running from it — so the four garbage-collection routes carry
      the machine role and deliberately no owner guard, and qits-platform-orchestrator, whose
      subject is nobody's owner, is the caller they exist for. What stands in for ownership is a
      list of reasons to KEEP, checked in order, with removal as the fall-through: in use by a
      container, named by a live registry row, pinned by the caller, younger than the caller's
      grace. The collection is a dry run unless the body says otherwise — a body that forgot the
      field must never remove anything — so this walk decides about every image on the host and
      touches none of them.
      """)
  @UserflowRunsAfter({TokenValidationBootstrapIT.class, HostBootstrapIT.class, WorkloadLifecycleIT.class})
  @Order(3)
  void theCollectionIsAddressedToTheHostAndDecidesByKeepRules(Interactions story) {
    NetworkCapture.actor(StoryIdentities.ORCHESTRATOR);
    String bearer = StoryIdentities.token(StoryIdentities.ORCHESTRATOR);
    MINTED.add(bearer);

    StoryIdentities.bearer(given(), bearer)
        .get(USAGE)
        .then()
        .statusCode(200)
        .body("images.count", is(3))
        .body("buildCache.count", is(0));
    story
        .note(
            "qits-platform-orchestrator reads what the host's four stores hold — a caller no owner"
                + " guard could ever admit, because its subject owns nothing")
        .as("host-usage-read");

    StoryIdentities.bearer(given(), bearer)
        .contentType(ContentType.JSON)
        .body(Map.of("dryRun", true, "keep", List.of(), "keepPrefixes", List.of()))
        .post(IMAGE_GC)
        .then()
        .statusCode(200)
        .body("dryRun", is(true))
        .body("examined", is(3))
        .body("kept.reason", hasItem("in-use"))
        .body("kept.reason", hasItem("live-row"))
        .body("removed.reason", hasItem("dangling"));
    story
        .note(
            "the image two containers were created from is kept in-use, and the image a live"
                + " registry row names is kept live-row — a workload's image is not collectable"
                + " while the workload has a row")
        .as("keep-rules-decided");
    story
        .note(
            "only the dangling one is named for removal, and the run removed nothing: the"
                + " candidate set is always the daemon's own listing, never a prune and never a"
                + " label sweep")
        .as("dry-run-removed-nothing");
  }

  @AfterAll
  static void everyOwnershipStoryIsComplete() {
    // --- the refusals -----------------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, REFUSED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, REFUSED_SLUG, "foreign-start-refused");
    ReportAssertions.assertStepId(CATEGORY, REFUSED_SLUG, "foreign-delete-refused");
    ReportAssertions.assertStepId(CATEGORY, REFUSED_SLUG, "foreign-read-refused");
    ReportAssertions.assertStepId(CATEGORY, REFUSED_SLUG, "owners-workload-untouched");
    http(REFUSED_SLUG, StoryIdentities.WORKSPACES, "PUT " + INTRUDER_PLACE + " -> 403");
    http(REFUSED_SLUG, StoryIdentities.WORKSPACES, "DELETE " + CI_PLACE + " -> 403");
    http(REFUSED_SLUG, StoryIdentities.WORKSPACES, "GET " + CI_INVENTORY + " -> 403");
    http(REFUSED_SLUG, StoryIdentities.CI, "GET " + CI_PLACE + " -> 200");
    // THE CLAIM: four requests, four arrows, and not one of them to the daemon. A guard that
    // refused after a docker run would have already handed the caller what it asked for.
    ReportAssertions.assertEdgeCount(CATEGORY, REFUSED_SLUG, 4);
    ReportAssertions.assertNoEdgesTo(CATEGORY, REFUSED_SLUG, StoryDocker.DAEMON);
    ReportAssertions.assertNoEdgesFrom(CATEGORY, REFUSED_SLUG, StoryTarget.SERVICE);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY, REFUSED_SLUG, List.of(StoryIdentities.WORKSPACES, StoryIdentities.CI));

    // --- the same token on its own rows ------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, OWN_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, OWN_SLUG, "own-place-created");
    ReportAssertions.assertStepId(CATEGORY, OWN_SLUG, "own-place-served");
    http(OWN_SLUG, StoryIdentities.WORKSPACES, "PUT " + WORKSPACE_PLACE + " -> 201");
    http(OWN_SLUG, StoryIdentities.WORKSPACES, "GET " + WORKSPACE_PLACE + " -> 200");
    docker(OWN_SLUG, "run " + WORKSPACE_CONTAINER, "0");
    docker(OWN_SLUG, "inspect " + WORKSPACE_CONTAINER, "0");
    // No volume create: this spec owns none. The asymmetry with the qits-ci story is the spec's,
    // not the guard's.
    ReportAssertions.assertEdgeCount(CATEGORY, OWN_SLUG, 4);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY, OWN_SLUG, List.of(StoryIdentities.WORKSPACES, StoryTarget.SERVICE));

    // --- the host's own stores ---------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, HOST_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, HOST_SLUG, "host-usage-read");
    ReportAssertions.assertStepId(CATEGORY, HOST_SLUG, "keep-rules-decided");
    ReportAssertions.assertStepId(CATEGORY, HOST_SLUG, "dry-run-removed-nothing");
    http(HOST_SLUG, StoryIdentities.ORCHESTRATOR, "GET " + USAGE + " -> 200");
    http(HOST_SLUG, StoryIdentities.ORCHESTRATOR, "POST " + IMAGE_GC + " -> 200");
    docker(HOST_SLUG, "system df", "0");
    // The listing that PROTECTS, asked first and never allowed to degrade: an empty answer from it
    // is what would make an image a container is holding removable.
    docker(HOST_SLUG, "ps image references", "0");
    docker(HOST_SLUG, "image ls", "0");
    // FIVE, and the fifth is not an `image rm`. A dry run decides about every image on the host and
    // asks the daemon to remove none of them, which is what makes a missing dryRun safe.
    ReportAssertions.assertEdgeCount(CATEGORY, HOST_SLUG, 5);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY, HOST_SLUG, List.of(StoryIdentities.ORCHESTRATOR, StoryTarget.SERVICE));

    for (String slug : List.of(REFUSED_SLUG, OWN_SLUG, HOST_SLUG)) {
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
