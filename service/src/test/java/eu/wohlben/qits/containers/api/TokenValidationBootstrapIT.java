package eu.wohlben.qits.containers.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.stories.support.StoryDocker;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.NetworkTaps;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

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
 * <p>It is also the <b>first class of this repository's userflow catalogue</b>, and the one that runs
 * first: the proof doubles as documentation, emitted under {@code target/userstories/} with a
 * network diagram beside the steps. The diagram is <b>observed, never narrated</b> — {@link
 * NetworkTaps#restAssured} taps what a story sends into this service, {@link MockIdp}'s recordings
 * supply what this service sent to the idp, and the framework drains both at story end. A story
 * method therefore asserts and notes; it draws nothing. Both stories are browserless (an {@code
 * Interactions} parameter and no {@code Flow}), so the framework's transitive Playwright never
 * launches anything.
 *
 * <p><b>The two stories are ordered</b>, and that is load-bearing rather than tidiness: a cumulative
 * source is attributed by a cursor, so traffic that happened before any story ran — the startup JWKS
 * fetch, which is the whole subject of the first story — lands in whichever story drains
 * <i>first</i>. Pinning the order is what keeps that the story it belongs to.
 *
 * <p><b>No docker socket, in the repository whose subject is docker.</b> The rest of the catalogue
 * lives under {@code stories/} and does start containers — against {@code
 * stories.support.StoryDocker}, a recording stand-in for the docker CLI this profile points {@code
 * qits.containers.container-runtime} at. This class needs none of it: its guarded route is the
 * inventory listing, a read of this service's own rows with no driver call anywhere on it, which is
 * what keeps the claim about the token rather than about a daemon's availability. Nothing anywhere
 * in this suite opens {@code /var/run/docker.sock}.
 *
 * <p><b>ITs stay skipped by default here and this one does NOT flip that.</b> {@code skipITs} is
 * {@code true} in the root pom because {@code ContainersRestartAdoptionIT} binds to the same
 * failsafe run and is the docker-backed proof — it starts real containers on a real daemon. It is
 * tagged {@code extended} and the root pom's {@code qits.it.excluded-groups} would exclude it, but
 * that property is <b>empty by default</b> and only the {@code native} profile sets it, deliberately,
 * so that {@code -DskipITs=false} still means "run everything". Naming the classes is therefore the
 * only opt-in that is correct on a plain {@code verify}, and the whole catalogue is what
 * {@code .config/qits/ci-event-userflows.yml} passes:
 *
 * <pre>{@code
 * ./mvnw verify -DskipITs=false \
 *   -Dit.test=TokenValidationBootstrapIT,HostBootstrapIT,WorkloadLifecycleIT,\
 * OwnershipBoundaryIT,WorkloadReapIT,AccessRefusalIT
 * }</pre>
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
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

  /** How the diagram names this service on both sides of an edge. */
  static final String SERVICE = "qits-containers";

  /**
   * The bearers these stories mint, kept so {@code @AfterAll} can assert none of them reached the
   * published bundle. A story's report carries every observed label and every note; a credential
   * that ended up in one would be a credential in a docs site.
   */
  private static final List<String> MINTED = new ArrayList<>();

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

      // --- databases of this profile's OWN --------------------------------------------------------
      // The parent's parking trick, asked for two more database names. The stories under
      // stories/ WRITE containers and volumes for qits-ci, while ContainersPackagedSurfaceIT
      // asserts that owner's listing is empty — so sharing one store would make that IT pass or
      // fail on which profile group failsafe happened to run first, which is not a fact about the
      // packaged artifact. Two separate launches were already the cost of two profiles; two
      // databases cost nothing on top of it.
      overrides.put("QITS_RESOURCE_DB_URL", databaseUrl(URL_PROPERTY, "containers_userflow_it"));
      overrides.put(
          "QITS_RESOURCE_EVENTSTREAM_URL",
          databaseUrl(EVENTSTREAM_URL_PROPERTY, "containers_userflow_eventstream_it"));

      // THE GATE, and turning it on is the point: the shipped tenant is
      // quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}, so this one key is the
      // difference between a service that validates machine bearers and one that trusts the network
      // — which is the posture every route here is deployed in today. Flipping the derived key
      // directly would prove the tenant and skip the seam.
      overrides.put("qits.auth.machine.required", "true");
      // The one seam this test MOVES: where the idp is. A runtime key, so the packaged artifact is
      // otherwise exactly what ships — discovery stays off and `jwks-path=jwks` is joined onto it.
      overrides.put("quarkus.oidc.auth-server-url", idp.baseUrl());

      // --- THE DOCKER SEAM, POINTED AT A RECORDING STAND-IN ---------------------------------------
      // This is the one override that makes the catalogue under stories/ possible, and it is worth
      // reading twice in the repository whose subject is docker. The parent points this key at a
      // binary that does not exist, which is the right seam for an IT about the ARTIFACT: every
      // driver call degrades to a warning and nothing on the host is touched. It is the wrong seam
      // for an IT about the SERVICE, because a service whose every docker call fails can start no
      // container and therefore has no lifecycle to tell a story about.
      //
      // The stand-in is neither a daemon nor a mocked socket: `core/docker/ContainerProcess` SPAWNS
      // the docker CLI and reads its pipes, so the honest substitute is an executable. StoryDocker
      // writes one, it records every argv with the exit code it answered, and the stories observe
      // that recording — which is how the docker hop is drawn as evidence rather than declared as a
      // claim. Nothing here opens /var/run/docker.sock, and this suite still runs in a container
      // that has neither a socket nor the capability to reach one.
      overrides.put("qits.containers.container-runtime", StoryDocker.install());

      // NO OBSERVATION TICKER. Zero is a shipped configuration and its own documented behaviour —
      // "a row's state will be whatever the operation that wrote it said" — rather than a test-only
      // switch. It is set because the pass is a TIMER: a `docker inspect` it made mid-story would
      // land in whichever story happened to drain next, and nothing in the recorded argv
      // distinguishes it from the inspect an ensure caused, so it could not even be excluded by
      // content. What that costs this catalogue is stated in AGENTS.md: the observer's own
      // transitions are the one part of this service no story here reaches.
      overrides.put("qits.containers.observe-interval-seconds", "0");

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

    private static final String URL_PROPERTY = "qits.test.userflow-it.db-url";

    private static final String EVENTSTREAM_URL_PROPERTY =
        "qits.test.userflow-it.eventstream-url";
  }

  /**
   * Wires both halves of the network diagram, once, before either story runs.
   *
   * <p>The near side (what a story sends here) is {@link NetworkTaps#restAssured}, the tap the
   * framework <b>ships</b>. It replaced a local {@code StoryNetworkFilter} four repositories had
   * hand-copied; the copy that used to sit beside this file is gone, and a new story class calls
   * this instead. Its default skip is any path carrying a {@code /q/} segment, which is right here:
   * this service's probe root is {@code /containers/q}, so a story is free to call readiness without
   * putting a health check in a dependency map.
   *
   * <p>The idp is the far side, registered as a <b>cumulative</b> source: the supplier hands over
   * the mock's whole request log every time it is asked and the framework remembers how much of it
   * earlier stories already consumed, so the startup fetch — recorded long before any story existed
   * — is attributed to the first story and to that one only. It is invoked lazily at story end, so
   * registering it here is safe even though nothing has been recorded yet.
   *
   * <p>The label carries the status the mock <i>answered</i> with, which is the half a method and
   * path cannot supply: {@code "GET /idp/jwks -> 200"} is evidence that the keys were served, not
   * merely asked for.
   *
   * <p><b>The docker recording is deliberately not registered here.</b> It is registered by the
   * story classes under {@code stories/}, and the first of those owns the calls this service made
   * while it booted — three shared volumes made and the platform network asked about — exactly as
   * this class's first story owns the JWKS fetch. Registering it here would drag the boot's docker
   * traffic into a story about signing keys.
   */
  @BeforeAll
  static void tapBothEndsOfTheNetwork() {
    NetworkTaps.restAssured(SERVICE);
    NetworkCapture.source(
        "mock-idp",
        () ->
            MockIdp.attach().recordedRequests().stream()
                .map(
                    request ->
                        NetworkEdge.http(
                            SERVICE,
                            MockIdp.SERVICE_NAME,
                            request.method() + " " + request.path() + " -> " + request.status()))
                .toList());
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
  @Order(1)
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
        .note("the signing keys were fetched at startup, before this story presented any token")
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
    //
    // The actor is set BEFORE the call: the tap sees a request, never a narrative role, and this is
    // what makes the observed edge read `qits-ci -> qits-containers`. Here the caller is named
    // rather than described, because an owner IS a named module in this service's model.
    NetworkCapture.actor(OWNER);
    String platformToken =
        idp.token()
            .subject(OWNER)
            .audience(PackagedWithMockIdp.AUDIENCE)
            .groups("qits:system")
            .mint();
    MINTED.add(platformToken);
    given()
        .header("Authorization", "Bearer " + platformToken)
        .get(GUARDED_ROUTE)
        .then()
        .statusCode(200)
        .body("containers", notNullValue());
    story
        .note("qits-ci's own bearer (aud=qits-containers, groups=[qits:system]) opens its rows")
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
  @Order(2)
  void aStrangersTokenIsRefused(Interactions story) {
    MockIdp idp = MockIdp.attach();

    // The first two credentials are an impostor's, so the actor is set once, up front; the
    // ownership pair below sets its own, because that caller is a real module with a real token.
    NetworkCapture.actor("an impostor");

    String strangersToken =
        idp.token()
            .subject(OWNER)
            .audience(PackagedWithMockIdp.AUDIENCE)
            .groups("qits:system")
            .signedByUnknownKey()
            .mint();
    MINTED.add(strangersToken);
    given()
        .header("Authorization", "Bearer " + strangersToken)
        .get(GUARDED_ROUTE)
        .then()
        .statusCode(401);
    // Both 401s are the same edge — same actor, same route, same status — so the diagram draws one
    // arrow and the notes are what keep the two credentials distinguishable. That is the right
    // division: the graph says who reached what and got what, the steps say why.
    story
        .note("a token signed by a key the published JWKS never carried is refused")
        .as("unknown-key-refused");

    // qits-githost and not an invented name: it is a real audience the platform's idp mints, so the
    // story documents the confusion that could actually happen on qits-net rather than a strawman.
    String wrongAudienceToken =
        idp.token().subject(OWNER).audience("qits-githost").groups("qits:system").mint();
    MINTED.add(wrongAudienceToken);
    given()
        .header("Authorization", "Bearer " + wrongAudienceToken)
        .get(GUARDED_ROUTE)
        .then()
        .statusCode(401);
    story
        .note("a token minted for qits-githost's audience is refused just the same")
        .as("wrong-audience-refused");

    // The third door, and the only one that is this service's own decision rather than
    // quarkus-oidc's: right issuer, right signature, right audience, right role — and the wrong
    // owner. 403 rather than 401 is the distinction an operator needs, because it says the token
    // was understood and the grant it is missing is ownership.
    //
    // A real module with a real token, so it is named on the edge rather than described. The two
    // calls below share this actor and differ only in the owner they address, which is exactly what
    // the pair of edges has to show.
    NetworkCapture.actor(OTHER_OWNER);
    String anotherModulesToken =
        idp.token()
            .subject(OTHER_OWNER)
            .audience(PackagedWithMockIdp.AUDIENCE)
            .groups("qits:system")
            .mint();
    MINTED.add(anotherModulesToken);
    given()
        .header("Authorization", "Bearer " + anotherModulesToken)
        .get(GUARDED_ROUTE)
        .then()
        .statusCode(403);
    story
        .note("an impeccable token that is another module's is refused 403 on qits-ci's rows")
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
        .note("the same token on its own rows is served 200 — the refusal was about ownership")
        .as("own-owner-served");
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    // The extension emits each report in its afterEach, so both are on disk before @AfterAll runs.
    // assertComplete now also proves the network section: the sidecar's edges are canonical, the
    // networkHash recomputes from them, and every mermaid line is in the markdown.
    ReportAssertions.assertComplete(CATEGORY, ACCEPTED_SLUG, UserflowReport.PASSED);
    // Observed on the far side, drained from the mock's recording, and attributed to this story
    // because it is the first one that ran (see the class javadoc on ordering).
    ReportAssertions.assertEdge(
        CATEGORY, ACCEPTED_SLUG, "http", SERVICE, MockIdp.SERVICE_NAME, "GET /idp/jwks -> 200");
    // Observed on the near side, by the filter, with the actor this story set.
    ReportAssertions.assertEdge(
        CATEGORY, ACCEPTED_SLUG, "http", OWNER, SERVICE, "GET " + GUARDED_ROUTE + " -> 200");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "jwks-fetched");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "inventory-served");

    ReportAssertions.assertComplete(CATEGORY, DENIED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertEdge(
        CATEGORY, DENIED_SLUG, "http", "an impostor", SERVICE, "GET " + GUARDED_ROUTE + " -> 401");
    // The ownership pair, and it is the claim no sibling repo's copy of this story can make: one
    // named caller, two owners in the path, two different answers.
    ReportAssertions.assertEdge(
        CATEGORY, DENIED_SLUG, "http", OTHER_OWNER, SERVICE, "GET " + GUARDED_ROUTE + " -> 403");
    ReportAssertions.assertEdge(
        CATEGORY,
        DENIED_SLUG,
        "http",
        OTHER_OWNER,
        SERVICE,
        "GET " + OTHER_OWNERS_ROUTE + " -> 200");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "unknown-key-refused");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "wrong-audience-refused");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "other-owner-refused");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "own-owner-served");

    // Not one of the four bearers is anywhere in either bundle. The reports are published per
    // commit as a docs site, so this is the assertion that keeps a credential out of one.
    for (String bearer : MINTED) {
      ReportAssertions.assertNotLeaked(CATEGORY, ACCEPTED_SLUG, bearer);
      ReportAssertions.assertNotLeaked(CATEGORY, DENIED_SLUG, bearer);
    }
  }
}
