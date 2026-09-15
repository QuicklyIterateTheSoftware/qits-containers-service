package eu.wohlben.qits.containers.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

/**
 * Every route with the gate ON — the posture a deployment reaches by setting
 * {@code QITS_AUTH_MACHINE_REQUIRED=true}.
 *
 * <p>Tokens are real: signed RS256, verified by quarkus-oidc against the public key in
 * {@link MachineGuardProfile}. So these cases fail if the OIDC configuration in
 * application.properties is wrong, not only if a guard is missing.
 *
 * <p><b>The claim under test is one no sibling makes: the owner in the path is the caller.</b>
 * Every other service in the fleet asks whether a token was granted a {@code project} or a
 * {@code workspace}; this one asks whether the token <em>is</em> the owner whose rows the request
 * addresses. The subject carries its environment — {@code dev-qits-ci} and {@code prod-qits-ci} are
 * two owners — and that prefix is exactly what keeps two environments sharing one docker daemon
 * from reaching each other's containers.
 *
 * <p><b>Three doors, and this suite pins which one shuts first.</b> A token that does not carry the
 * platform audience is refused by {@code quarkus.oidc.token.audience} before any identity is built,
 * so the answer is a 401 challenge. A token addressed here but granted no roles authenticates and is
 * refused 403 by the {@code @RolesAllowed("qits:system")} both resources carry — qits-idp copies a
 * client's {@code roles} into the token's {@code groups} claim and quarkus-oidc reads that claim as
 * roles with no configuration at all. A token that holds the role and is somebody else's is refused
 * 403 by {@link OwnerGuard}, which is this service's own decision and the one a new route could drop
 * by forgetting to call it. {@code MachineAuth}'s audience check is a fourth belt behind the first,
 * reachable only if that validation is ever loosened, so it is asserted nowhere here.
 *
 * <p>Two of those three answer 403 and the difference is which grant is missing — the role, or the
 * ownership. That is the distinction an operator needs to tell a missing idp grant from a caller
 * addressing rows it does not own.
 */
@QuarkusTest
@TestProfile(MachineGuardProfile.class)
class MachineGuardTest {

  /** The caller, and therefore the owner of every row it may reach. */
  private static final String OWNER = "dev-qits-ci";

  /** Another platform module, with a perfectly good token of its own. */
  private static final String OTHER_OWNER = "dev-qits-workspaces";

  /** The one platform audience, which every token qits-idp mints carries. */
  private static final String PLATFORM_AUDIENCE = "qits-platform";

  /** An audience that is not it, and therefore a token no platform caller holds. */
  private static final String FOREIGN_AUDIENCE = "prod-qits-deployments";

  private static final String OWN_PLACE = "/containers/api/containers/dev-qits-ci/step/guarded";

  private static final String OTHER_PLACE =
      "/containers/api/containers/dev-qits-workspaces/step/guarded";

  private static final String SPEC =
      """
      {"spec":{"image":"alpine:3","network":"qits-net"},"policy":{"type":"EXPLICIT"}}""";

  @Test
  void aCallWithNoMachineTokenIsFourOhOne() {
    // 401, not 403: nothing was presented, so the answer is "present something". The forward-auth
    // dev user is blanked in this profile, and a user is not a machine in any case.
    given().contentType(ContentType.JSON).body(SPEC).when().put(OWN_PLACE).then().statusCode(401);
  }

  @Test
  void aReadWithNoMachineTokenIsFourOhOneToo() {
    // The half of the rule this service does not share with the fleet: nothing here is open,
    // because nothing here is read by a person.
    given().when().get("/containers/api/containers/dev-qits-ci").then().statusCode(401);
  }

  @Test
  void aTokenWithoutThePlatformAudienceIsRefused() {
    given()
        .contentType(ContentType.JSON)
        .header("Authorization", "Bearer " + MachineTokens.token(OWNER, FOREIGN_AUDIENCE))
        .body(SPEC)
        .when()
        .put(OWN_PLACE)
        .then()
        .statusCode(401);
  }

  @Test
  void aTokenGrantedNoRolesIsForbiddenFromItsOwnRows() {
    // A client id qits-idp knows with no `.roles` line beside it mints exactly this: correctly
    // signed, correctly addressed, empty `groups`. It is this owner's own token and it still covers
    // nothing, because @RolesAllowed shuts before OwnerGuard is ever asked. A 403 rather than the
    // 401 an absent token gets, which is what tells a missing grant from a missing sender.
    String roleless = "Bearer " + MachineTokens.rolelessToken(OWNER, PLATFORM_AUDIENCE);

    given()
        .contentType(ContentType.JSON)
        .header("Authorization", roleless)
        .body(SPEC)
        .when()
        .put(OWN_PLACE)
        .then()
        .statusCode(403);

    given().header("Authorization", roleless).when().get(OWN_PLACE).then().statusCode(403);
    given()
        .header("Authorization", roleless)
        .when()
        .get("/containers/api/containers/dev-qits-ci")
        .then()
        .statusCode(403);
    given()
        .header("Authorization", roleless)
        .when()
        .get("/containers/api/volumes/dev-qits-ci/whatever")
        .then()
        .statusCode(403);
  }

  @Test
  void aTokenThatIsNotThisOwnerIsForbiddenFromItsRows() {
    // The token is impeccable — right issuer, right signature, right audience — and it is somebody
    // else's. Writes and reads alike: an inventory is as much a module's own as its containers are.
    machine()
        .contentType(ContentType.JSON)
        .body(SPEC)
        .when()
        .put(OTHER_PLACE)
        .then()
        .statusCode(403);

    machine().when().get("/containers/api/containers/dev-qits-workspaces").then().statusCode(403);
    machine().when().get("/containers/api/volumes/dev-qits-workspaces/whatever").then().statusCode(403);
  }

  @Test
  void aTokenThatIsThisOwnerReachesItsOwnRows() {
    machine()
        .contentType(ContentType.JSON)
        .body(SPEC)
        .when()
        .put(OWN_PLACE)
        .then()
        .statusCode(201)
        .body("state.observed", is("RUNNING"));

    machine().when().get(OWN_PLACE).then().statusCode(200);
    machine().when().get("/containers/api/containers/dev-qits-ci").then().statusCode(200);
    machine().when().delete(OWN_PLACE).then().statusCode(200).body("existed", is(true));
  }

  @Test
  void theGarbageCollectionIsGuardedByTheRoleAndDeliberatelyNotByAnOwner() {
    // These four routes are the platform's rather than an owner's: an image is named by no owner,
    // so there is no path owner to compare a subject against and no honest way to invent one. What
    // guards them is the machine role — and the absence of OwnerGuard is asserted here, because a
    // route that quietly gained one would refuse the orchestrator, whose subject is nobody's owner.
    given().when().get("/containers/api/gc/usage").then().statusCode(401);

    given()
        .header("Authorization", "Bearer " + MachineTokens.rolelessToken(OWNER, PLATFORM_AUDIENCE))
        .when()
        .get("/containers/api/gc/usage")
        .then()
        .statusCode(403);

    machine().when().get("/containers/api/gc/usage").then().statusCode(200);

    given()
        .header(
            "Authorization",
            "Bearer " + MachineTokens.token("dev-qits-platform-orchestrator", PLATFORM_AUDIENCE))
        .contentType(ContentType.JSON)
        .body("{\"dryRun\":true}")
        .when()
        .post("/containers/api/gc/images")
        .then()
        .statusCode(200);
  }

  /** A caller holding a fresh token of its own, addressed to this service. */
  private static RequestSpecification machine() {
    return given()
        .header("Authorization", "Bearer " + MachineTokens.token(OWNER, PLATFORM_AUDIENCE));
  }
}
