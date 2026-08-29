package eu.wohlben.qits.containers.stories.refusals;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import eu.wohlben.qits.containers.api.TokenValidationBootstrapIT;
import eu.wohlben.qits.containers.stories.boot.HostBootstrapIT;
import eu.wohlben.qits.containers.stories.lifecycle.WorkloadLifecycleIT;
import eu.wohlben.qits.containers.stories.ownership.OwnershipBoundaryIT;
import eu.wohlben.qits.containers.stories.reap.WorkloadReapIT;
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
 * <b>Everything this service will not do</b>, and the one thing every refusal has in common: none of
 * them reaches the docker daemon.
 *
 * <p>That is the assertion these two stories are for. A guard, a belt or a 404 that refused
 * <em>after</em> a container had been started would be no guard at all — it would have handed the
 * caller exactly what it asked for and then declined to say so. Every other repository can write the
 * status codes; this one can write {@code assertNoEdgesTo("docker")} beside them, because the docker
 * hop here is observed rather than assumed.
 *
 * <p>The second story is the belt that is easiest to underrate. An owner sends its own bookkeeping
 * labels along with a spec, and the label namespace {@code qits.containers.} is what a sweep reads to
 * decide whether a container is this service's — so an owner that could write one could label
 * somebody else's workload as its own, or its own as another owner's. It is refused at the API layer
 * and refused again in {@code DockerArgv}, and the point of two checkpoints is that neither has to be
 * trusted alone.
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AccessRefusalIT {

  static final String CATEGORY = "refusals";

  static final String REFUSED_SLUG = "a-request-this-service-refuses-never-reaches-the-daemon";
  static final String NAMESPACE_SLUG = "an-owner-cannot-label-a-container-as-this-service-s-own";

  /** Who a caller is when this service could not work out who it is. */
  static final String ANONYMOUS = "an unauthenticated caller";

  /** …and who it is when it was understood and was granted nothing. */
  static final String UNGRANTED = "a client with no grant";

  static final String GUARDED_PLACE =
      StoryTarget.placePath(StoryIdentities.CI, StoryWorkloads.STEP, "story-refused");

  static final String INVALID_PLACE =
      StoryTarget.placePath(StoryIdentities.CI, StoryWorkloads.STEP, "story-invalid");

  static final String ABSENT_PLACE =
      StoryTarget.placePath(StoryIdentities.CI, StoryWorkloads.STEP, "story-nowhere");

  static final String FORGED_PLACE =
      StoryTarget.placePath(StoryIdentities.CI, StoryWorkloads.STEP, "story-forged");

  private static final List<String> MINTED = new ArrayList<>();

  @BeforeAll
  static void tapBothSidesOfTheService() {
    NetworkTaps.restAssured(StoryTarget.SERVICE);
    StoryDocker.installSource();
  }

  @UserStory(
      value = "A request this service refuses never reaches the daemon",
      category = CATEGORY)
  @UserStoryDescription(
      """
      Four different refusals, and each is a different door. No credential at all is 401 — there is
      no caller yet, so there is nobody to have been forbidden. A token minted for another service's
      audience is 401 too, refused by validation before an identity exists, which is why it reads
      the same from outside even though a completely different thing went wrong. A token this
      service authenticated but whose client was granted no roles is 403: understood, and missing a
      grant. And a value this service will not put in an argv is 400 with the field named in it,
      while a place no live row names is a plain 404. Knowing which of them shut is how a missing
      grant is debugged. What they share is the assertion this story exists for: not one of them
      reached the docker daemon, so nothing was started, nothing was inspected, and the host is
      exactly as it was.
      """)
  @UserflowRunsAfter({
    TokenValidationBootstrapIT.class,
    HostBootstrapIT.class,
    WorkloadLifecycleIT.class,
    OwnershipBoundaryIT.class,
    WorkloadReapIT.class
  })
  @Order(1)
  void everyRefusalStopsBeforeTheDockerSeam(Interactions story) {
    NetworkCapture.actor(ANONYMOUS);

    given()
        .contentType(ContentType.JSON)
        .body(StoryWorkloads.explicit(StoryWorkloads.spec(StoryDocker.WORKLOAD_IMAGE)))
        .put(GUARDED_PLACE)
        .then()
        .statusCode(401);
    story
        .note(
            "no credential at all is 401 — every route of this service is a machine's, reads"
                + " included, and there is no anonymous door to fall through to")
        .as("no-credential-refused");

    String foreign = StoryIdentities.foreignAudienceToken(StoryIdentities.CI);
    MINTED.add(foreign);
    StoryIdentities.bearer(given(), foreign)
        .contentType(ContentType.JSON)
        .body(StoryWorkloads.explicit(StoryWorkloads.spec(StoryDocker.WORKLOAD_IMAGE)))
        .put(GUARDED_PLACE)
        .then()
        .statusCode(401);
    // The same edge as above: same actor, same route, same status, so the diagram draws one arrow
    // and the notes are what keep the two credentials apart. That is the right division of labour —
    // the graph says who reached what and got what, the steps say why.
    story
        .note(
            "a token minted for qits-githost's audience is refused by validation, before any"
                + " identity exists — so it is 401 and not 403")
        .as("foreign-audience-refused");

    NetworkCapture.actor(UNGRANTED);
    String roleless = StoryIdentities.rolelessToken(StoryIdentities.CI);
    MINTED.add(roleless);
    StoryIdentities.bearer(given(), roleless)
        .contentType(ContentType.JSON)
        .body(StoryWorkloads.explicit(StoryWorkloads.spec(StoryDocker.WORKLOAD_IMAGE)))
        .put(GUARDED_PLACE)
        .then()
        .statusCode(403);
    story
        .note(
            "a perfectly valid token whose client was granted no roles authenticates and is then"
                + " refused 403 — the credential became an identity, and the identity has no grant")
        .as("roleless-refused");

    NetworkCapture.actor(StoryIdentities.CI);
    String bearer = StoryIdentities.token(StoryIdentities.CI);
    MINTED.add(bearer);

    StoryIdentities.bearer(given(), bearer)
        .contentType(ContentType.JSON)
        .body(StoryWorkloads.explicit(StoryWorkloads.spec(" ")))
        .put(INVALID_PLACE)
        .then()
        .statusCode(400)
        .body("code", is("INVALID"))
        .body("message", containsString("image"));
    story
        .note(
            "a value this service will not put in an argv is 400 with the field named in it — the"
                + " belt fires where a refusal can still be a sentence a caller reads")
        .as("invalid-value-refused");

    StoryIdentities.bearer(given(), bearer).get(ABSENT_PLACE).then().statusCode(404);
    story
        .note(
            "and a place no live row names is 404 — which means the row is cleanly absent and"
                + " nothing else: a read that could not reach the database is a 5xx, because a"
                + " caller that read 404 for that would start its workload a second time")
        .as("absent-place-is-404");
  }

  @UserStory(
      value = "An owner cannot label a container as this service's own",
      category = CATEGORY)
  @UserStoryDescription(
      """
      A spec carries the owner's own bookkeeping labels, and they are rendered onto the container
      beside this service's. But the qits.containers. namespace is what a sweep reads to decide
      whether a container belongs to this registry at all — so an owner that could write one could
      label another module's workload as its own, or its own as another module's, and the whole
      "unclaimed means somebody else's" rule would have a way in. The key is refused with the
      namespace named in the message. So is one carrying an = , for a different reason that is just
      as concrete: a label is one argv element of the shape k=v, and a key with an = in it moves the
      boundary between the key and the value.
      """)
  @UserflowRunsAfter({
    TokenValidationBootstrapIT.class,
    HostBootstrapIT.class,
    WorkloadLifecycleIT.class,
    OwnershipBoundaryIT.class,
    WorkloadReapIT.class
  })
  @Order(2)
  void anOwnerCannotWriteInsideThisServicesLabelNamespace(Interactions story) {
    NetworkCapture.actor(StoryIdentities.CI);
    String bearer = StoryIdentities.token(StoryIdentities.CI);
    MINTED.add(bearer);

    StoryIdentities.bearer(given(), bearer)
        .contentType(ContentType.JSON)
        .body(
            StoryWorkloads.explicit(
                StoryWorkloads.withExtraLabels(
                    StoryWorkloads.spec(StoryDocker.WORKLOAD_IMAGE),
                    Map.of("qits.containers.owner", StoryIdentities.WORKSPACES))))
        .put(FORGED_PLACE)
        .then()
        .statusCode(400)
        .body("code", is("INVALID"))
        .body("message", containsString("qits.containers."));
    story
        .note(
            "qits-ci cannot label its own container as qits-workspaces': the namespace is this"
                + " service's, and the refusal names it")
        .as("namespace-forgery-refused");

    StoryIdentities.bearer(given(), bearer)
        .contentType(ContentType.JSON)
        .body(
            StoryWorkloads.explicit(
                StoryWorkloads.withExtraLabels(
                    StoryWorkloads.spec(StoryDocker.WORKLOAD_IMAGE),
                    Map.of("team=platform", "yes"))))
        .put(FORGED_PLACE)
        .then()
        .statusCode(400)
        .body("code", is("INVALID"));
    story
        .note(
            "and a key carrying an = is refused too — a label is one argv element of the shape k=v,"
                + " so an = in the key would move the boundary between the two")
        .as("element-boundary-refused");
    story
        .note(
            "both are the same arrow, because they are the same route answering the same status —"
                + " and neither one of them reached the daemon")
        .as("nothing-was-started");
  }

  @AfterAll
  static void everyRefusalStoryIsComplete() {
    // --- the four doors ------------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, REFUSED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, REFUSED_SLUG, "no-credential-refused");
    ReportAssertions.assertStepId(CATEGORY, REFUSED_SLUG, "foreign-audience-refused");
    ReportAssertions.assertStepId(CATEGORY, REFUSED_SLUG, "roleless-refused");
    ReportAssertions.assertStepId(CATEGORY, REFUSED_SLUG, "invalid-value-refused");
    ReportAssertions.assertStepId(CATEGORY, REFUSED_SLUG, "absent-place-is-404");
    http(REFUSED_SLUG, ANONYMOUS, "PUT " + GUARDED_PLACE + " -> 401");
    http(REFUSED_SLUG, UNGRANTED, "PUT " + GUARDED_PLACE + " -> 403");
    http(REFUSED_SLUG, StoryIdentities.CI, "PUT " + INVALID_PLACE + " -> 400");
    http(REFUSED_SLUG, StoryIdentities.CI, "GET " + ABSENT_PLACE + " -> 404");
    // FIVE requests, FOUR arrows — the two 401s are one edge — and nothing at all left this
    // process. That last claim is the story: a refusal that had already run a container would look
    // identical from the caller's side.
    ReportAssertions.assertEdgeCount(CATEGORY, REFUSED_SLUG, 4);
    ReportAssertions.assertNoEdgesFrom(CATEGORY, REFUSED_SLUG, StoryTarget.SERVICE);
    ReportAssertions.assertNoEdgesTo(CATEGORY, REFUSED_SLUG, StoryDocker.DAEMON);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY, REFUSED_SLUG, List.of(ANONYMOUS, UNGRANTED, StoryIdentities.CI));

    // --- the namespace ---------------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, NAMESPACE_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, NAMESPACE_SLUG, "namespace-forgery-refused");
    ReportAssertions.assertStepId(CATEGORY, NAMESPACE_SLUG, "element-boundary-refused");
    ReportAssertions.assertStepId(CATEGORY, NAMESPACE_SLUG, "nothing-was-started");
    http(NAMESPACE_SLUG, StoryIdentities.CI, "PUT " + FORGED_PLACE + " -> 400");
    ReportAssertions.assertEdgeCount(CATEGORY, NAMESPACE_SLUG, 1);
    ReportAssertions.assertNoEdgesFrom(CATEGORY, NAMESPACE_SLUG, StoryTarget.SERVICE);
    ReportAssertions.assertNoEdgesTo(CATEGORY, NAMESPACE_SLUG, StoryDocker.DAEMON);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY, NAMESPACE_SLUG, List.of(StoryIdentities.CI));

    for (String slug : List.of(REFUSED_SLUG, NAMESPACE_SLUG)) {
      for (String bearer : MINTED) {
        ReportAssertions.assertNotLeaked(CATEGORY, slug, bearer);
      }
    }
  }

  private static void http(String slug, String actor, String label) {
    ReportAssertions.assertEdge(
        CATEGORY, slug, NetworkEdge.HTTP, actor, StoryTarget.SERVICE, label);
  }
}
