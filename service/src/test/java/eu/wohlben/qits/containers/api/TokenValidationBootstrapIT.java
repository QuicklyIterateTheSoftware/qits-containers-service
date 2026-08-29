package eu.wohlben.qits.containers.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;

/**
 * The whole service as it is <b>packaged</b> — like {@link ContainersPackagedSurfaceIT} beside it,
 * but with the OIDC tenant <b>on</b>, which no {@code @QuarkusTest} here can prove. The shipped
 * tenant is gated: {@code quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}}, and
 * every suite in this repository leaves that gate shut except {@link MachineGuardTest}, which flips
 * it and then stops exactly where this one starts — it inlines {@code quarkus.oidc.public-key} and
 * clears {@code auth-server-url}, so nothing there ever fetches a key. The block this service
 * actually deploys with (auth-server-url plus {@code discovery-enabled=false} and
 * {@code jwks-path=jwks} joined onto it, {@code token.audience} resolved through
 * {@code ${qits.auth.machine.audience}}, the {@code groups} claim becoming roles) is therefore
 * exercised nowhere else. The far side is {@link MockIdp}, whose recordings make the interaction
 * assertable on <b>both ends</b>.
 *
 * <p>It is also this repo's first <b>userflow</b>: the proof doubles as documentation, emitted under
 * {@code target/userstories/} with the interactions drawn as a sequence diagram. Both stories are
 * browserless (an {@code Interactions} parameter and no {@code Flow}), so the framework's transitive
 * Playwright never launches anything.
 *
 * <p><b>Still no docker, in the repository whose subject is docker.</b> The profile inherits
 * {@code qits.containers.container-runtime} pointed at a binary that does not exist, so the boot
 * steps that would otherwise reach a daemon — {@code SharedResources}, {@code BootSweep}, the
 * observer's first tick — degrade to warnings exactly as they must on a host whose docker is down.
 * Nothing in this file opens the docker socket, and nothing in it starts a container.
 *
 * <p><b>ITs stay skipped by default here and this one does NOT flip that.</b> {@code skipITs} is
 * {@code true} in the root pom because {@code ContainersRestartAdoptionIT} binds to the same
 * failsafe run and is the docker-backed proof — it starts real containers on a real daemon. It is
 * tagged {@code extended} and the root pom's {@code qits.it.excluded-groups} would exclude it, but
 * that property is <b>empty by default</b> and only the {@code native} profile sets it, deliberately,
 * so that {@code -DskipITs=false} still means "run everything". Naming the class is therefore the
 * only opt-in that is correct on a plain {@code verify}, and it is what
 * {@code .config/qits/ci-event-userflows.yml} passes:
 *
 * <pre>{@code ./mvnw verify -DskipITs=false -Dit.test=TokenValidationBootstrapIT}</pre>
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
public class TokenValidationBootstrapIT {

  static final String CATEGORY = "authentication";
  static final String ACCEPTED_SLUG = "on-start-qits-containers-fetches-the-platform-s-signing-keys";
  static final String DENIED_SLUG =
      "a-stranger-s-token-never-opens-another-module-s-container-inventory";

  /**
   * The caller these stories are told from — one of the three modules this service exists for, and
   * therefore the owner of the rows it may reach. It is the {@code sub} of the token AND the owner
   * in the path, because that is this service's whole guard: see {@link OwnerGuard}.
   *
   * <p>Unprefixed, and its audience below is unprefixed with it. A deployed tier mints both with
   * its environment on the front — {@code dev-qits-ci} presenting {@code dev-qits-containers} — and
   * {@link MachineGuardTest} is where that prefix is pinned, since it is the half OwnerGuard argues
   * about rather than the half a JWKS fetch proves. What matters here is that the two names agree,
   * and one naming used consistently says that without inventing a tier this test is not in.
   */
  static final String OWNER = "qits-ci";

  /** Another platform module, with a perfectly good token of its own. */
  static final String OTHER_OWNER = "qits-workspaces";

  /** The route both stories present a bearer to. See the accept story for why it is this one. */
  static final String GUARDED_ROUTE = "/containers/api/containers/" + OWNER;

  /** The same listing, addressed to the rows of a module that is not the caller. */
  static final String OTHER_OWNERS_ROUTE = "/containers/api/containers/" + OTHER_OWNER;

  /**
   * {@link ContainersPackagedSurfaceIT.PackagedUnderTarget} — the two {@code QITS_RESOURCE_*}
   * triples on this JVM's embedded postgres and the absent container runtime, parked in system
   * properties because a test profile is instantiated in more than one classloader — <b>plus the
   * two things this story is about</b>: the gate that turns the shipped OIDC tenant on, and where
   * the idp is.
   *
   * <p>Extending rather than copying is deliberate. What a launched qits-containers needs in order
   * to boot at all is one answer, it is written out at length over there — including why the
   * VARIABLES are supplied rather than the datasource keys, so the shipped
   * {@code ${QITS_RESOURCE_DB_URL}} expressions stay the ones under test — and a second copy of the
   * parking trick would be a second place for it to drift.
   *
   * <p>The mock idp starts <b>before</b> the application, via {@link MockIdp#ensureStarted()}, which
   * parks its coordinates (and its keypair) in system properties for the same classloader reason —
   * that is also how the story method's {@link MockIdp#attach()} reaches the very server the
   * launched process fetched its keys from.
   *
   * <p><b>Every key below is a RUNTIME key.</b> A packaged process takes its configuration as
   * {@code -D} arguments on a jar that was already built, so a build-time key here would be silently
   * ignored and the test would prove the opposite of what it says.
   */
  public static class PackagedWithMockIdp extends ContainersPackagedSurfaceIT.PackagedUnderTarget {

    /**
     * The audience this service enforces, and it is a LITERAL rather than a variable name.
     * {@code qits.auth.machine.audience=qits-containers} is spelled out in
     * {@code application.properties} — the default stays the bare name so an environment-qualified
     * one is not baked into an image every tier shares — so the audience under test is the shipped
     * one and there is no expression to feed. {@code
     * quarkus.oidc.token.audience=${qits.auth.machine.audience}} is what carries it to quarkus-oidc,
     * so minting against this string is also what proves that indirection is read.
     */
    static final String AUDIENCE = "qits-containers";

    @Override
    public Map<String, String> getConfigOverrides() {
      MockIdp idp = MockIdp.ensureStarted();
      Map<String, String> overrides = new LinkedHashMap<>(super.getConfigOverrides());

      // THE GATE, and turning it on is the point: the shipped tenant is
      // quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}, so this one key is the
      // difference between a service that validates machine bearers and one that trusts the network
      // — which is the posture every route here is deployed in today. Flipping the derived key
      // directly would prove the tenant and skip the seam.
      overrides.put("qits.auth.machine.required", "true");
      // The one seam this test MOVES: where the idp is. A runtime key, so the packaged artifact is
      // otherwise exactly what ships — discovery stays off and `jwks-path=jwks` is joined onto it.
      overrides.put("quarkus.oidc.auth-server-url", idp.baseUrl());

      // --- the two dials a host-run process has no deployment behind ----------------------------
      // Dark outside a deployment, like %dev/%test — both runtime keys, and both needed here because
      // a LAUNCHED ARTIFACT RUNS UNDER NEITHER PROFILE: the %test lines in application.properties
      // are the suite's, not this process's. The eventstream DATASOURCE is still opened and migrated
      // (dark stops publishing, sweeping and dialling, never the datasource), which is why the
      // second triple the parent supplies is not optional.
      overrides.put("quarkus.otel.sdk.disabled", "true");
      overrides.put("qits.eventstream.enabled", "false");
      return overrides;
    }
  }

  @UserStory(
      value = "On start, qits-containers fetches the platform's signing keys",
      category = "authentication")
  @UserStoryDescription(
      """
      A freshly deployed qits-containers must validate service bearers before any caller arrives:
      at startup it fetches the signing keys (JWKS) from qits-platform-idp — discovery stays off,
      the path is configured — so the very first machine request is judged on the platform's own
      keys. Nothing here is read by a person, so this is the only door there is: qits-ci,
      qits-workspaces and qits-projects reach their containers through it and through nothing else.
      """)
  void serviceBootFetchesJwksAndAcceptsPlatformTokens(Interactions story) {
    MockIdp idp = MockIdp.attach();

    story.note(
        "qits-containers starts with the OIDC tenant on, beside a reachable qits-platform-idp");
    given().get("/containers/q/health/ready").then().statusCode(200);

    // End (a), the idp side: the JWKS was served during startup — before this story presented any
    // token at all. That ordering is the whole claim. A service that fetched keys lazily, on the
    // first bearer, would look identical from this end and fail its first caller after a restart —
    // which is what quarkus.oidc.connection-delay=30S exists to prevent, and it matters here more
    // than most: an orchestrator is restarted precisely when the platform is being repaired.
    assertTrue(
        idp.recordedRequests().stream().anyMatch(r -> "/idp/jwks".equals(r.path())),
        "the packaged service never fetched /idp/jwks at startup");
    story
        .happened("qits-containers", "qits-platform-idp", "GET /idp/jwks (at startup)")
        .as("jwks-fetched");

    // End (b), the containers side: those keys are what token validation now runs on. A platform
    // service's bearer (aud = this service, roles in `groups`) opens the guarded inventory listing.
    //
    // GET /containers/api/containers/{owner} is the right door for this story on three counts. It
    // is a plain read of this service's own rows — no docker call anywhere on the path, so what it
    // proves is the token and not a daemon's availability, which is what lets the runtime stay
    // pointed at a binary that does not exist. The owner is the token's own subject, so the route
    // needs no arrangement and no fixture: an owner with nothing running is a 200 with an empty
    // list, and the story stays about who may read rather than about what happens to be in the
    // table. And it is a read, which in this repository is guarded exactly as a write is —
    // @RolesAllowed("qits:system") on the resource, then OwnerGuard — so the coarse role a platform
    // peer really holds is the one being exercised.
    String platformToken =
        idp.token()
            .subject(OWNER)
            .audience(PackagedWithMockIdp.AUDIENCE)
            .groups("qits:system")
            .mint();
    given()
        .header("Authorization", "Bearer " + platformToken)
        .get(GUARDED_ROUTE)
        .then()
        .statusCode(200)
        .body("containers", notNullValue());
    story
        .happened(
            "qits-ci",
            "qits-containers",
            "GET /containers/api/containers/qits-ci (Bearer, groups=[qits:system])")
        .as("inventory-served");
  }

  @UserStory(
      value = "A stranger's token never opens another module's container inventory",
      category = "authentication")
  @UserStoryDescription(
      """
      The flip side of trusting the platform's keys, and it has two halves. A token signed by a key
      the published JWKS never carried, or minted for another service's audience, is refused at the
      door — however well-formed it looks — and both are 401 and not 403: the credential never
      became an identity, so there is no caller to have been forbidden. Then the half no sibling
      service has: a token that is impeccable and is somebody else's is refused 403, because the
      owner in the path is the caller and a module's inventory is its own. That is what keeps two
      environments sharing one docker daemon out of each other's containers.
      """)
  void aStrangersTokenIsRefused(Interactions story) {
    MockIdp idp = MockIdp.attach();

    String strangersToken =
        idp.token()
            .subject(OWNER)
            .audience(PackagedWithMockIdp.AUDIENCE)
            .groups("qits:system")
            .signedByUnknownKey()
            .mint();
    given()
        .header("Authorization", "Bearer " + strangersToken)
        .get(GUARDED_ROUTE)
        .then()
        .statusCode(401);
    story
        .happened(
            "an impostor",
            "qits-containers",
            "GET /containers/api/containers/qits-ci (token signed by an unknown key) -> 401")
        .as("unknown-key-refused");

    // qits-githost and not an invented name: it is a real audience the platform's idp mints, so the
    // story documents the confusion that could actually happen on qits-net rather than a strawman.
    String wrongAudienceToken =
        idp.token().subject(OWNER).audience("qits-githost").groups("qits:system").mint();
    given()
        .header("Authorization", "Bearer " + wrongAudienceToken)
        .get(GUARDED_ROUTE)
        .then()
        .statusCode(401);
    story
        .happened(
            "an impostor",
            "qits-containers",
            "GET /containers/api/containers/qits-ci (another service's audience) -> 401")
        .as("wrong-audience-refused");

    // The third door, and the only one that is this service's own decision rather than
    // quarkus-oidc's: right issuer, right signature, right audience, right role — and the wrong
    // owner. 403 rather than 401 is the distinction an operator needs, because it says the token
    // was understood and the grant it is missing is ownership.
    String anotherModulesToken =
        idp.token()
            .subject(OTHER_OWNER)
            .audience(PackagedWithMockIdp.AUDIENCE)
            .groups("qits:system")
            .mint();
    given()
        .header("Authorization", "Bearer " + anotherModulesToken)
        .get(GUARDED_ROUTE)
        .then()
        .statusCode(403);
    story
        .happened(
            "qits-workspaces",
            "qits-containers",
            "GET /containers/api/containers/qits-ci (a valid token, another module's) -> 403")
        .as("other-owner-refused");

    // And the same token on its own rows is served, so the refusal above is about ownership and not
    // about this caller being unwelcome.
    given()
        .header("Authorization", "Bearer " + anotherModulesToken)
        .get(OTHER_OWNERS_ROUTE)
        .then()
        .statusCode(200)
        .body("containers", notNullValue());
    story
        .happened(
            "qits-workspaces",
            "qits-containers",
            "GET /containers/api/containers/qits-workspaces (its own rows) -> 200")
        .as("own-owner-served");
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    // The extension emits each report in its afterEach, so both are on disk before @AfterAll runs.
    ReportAssertions.assertComplete(CATEGORY, ACCEPTED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertInteraction(
        CATEGORY,
        ACCEPTED_SLUG,
        "qits-containers",
        "qits-platform-idp",
        "GET /idp/jwks (at startup)");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "jwks-fetched");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "inventory-served");

    ReportAssertions.assertComplete(CATEGORY, DENIED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "unknown-key-refused");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "wrong-audience-refused");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "other-owner-refused");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "own-owner-served");
  }
}
