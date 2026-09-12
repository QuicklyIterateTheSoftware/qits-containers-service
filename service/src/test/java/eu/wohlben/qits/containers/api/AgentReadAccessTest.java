package eu.wohlben.qits.containers.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.oneOf;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

/**
 * An agent reads what it reads today, under its own role.
 *
 * <p>Agents will stop inheriting their owner's roles and hold {@code qits:agent} instead. Every
 * {@code GET} here accepts that role; no write does.
 *
 * <p>The gate is ON and the tokens are real, as in {@link MachineGuardTest}: an agent reaches this
 * service with a bearer, never with forward-auth headers. The role does not change the owner rule —
 * the path owner must still be the token's subject.
 */
@QuarkusTest
@TestProfile(MachineGuardProfile.class)
class AgentReadAccessTest {

  private static final String OWNER = "dev-qits-ci";
  private static final String OWN_AUDIENCE = "qits-containers";
  private static final String PLACE = "/containers/api/containers/dev-qits-ci/agent/reads";
  private static final String VOLUME = "/containers/api/volumes/dev-qits-ci/agent-reads";

  private static final String SPEC =
      """
      {"spec":{"image":"alpine:3","network":"qits-net"},"policy":{"type":"EXPLICIT"}}""";

  /** The owner itself, holding the system roles — how the rows are seeded and cleaned up. */
  private static RequestSpecification owner() {
    return given().header("Authorization", "Bearer " + MachineTokens.token(OWNER, OWN_AUDIENCE));
  }

  /** A token holding only {@code qits:agent}. */
  private static RequestSpecification agent() {
    return given()
        .header("Authorization", "Bearer " + MachineTokens.agentToken(OWNER, OWN_AUDIENCE));
  }

  @Test
  void anAgentReadsContainers() {
    owner().contentType(ContentType.JSON).body(SPEC).when().put(PLACE).then().statusCode(oneOf(200, 201));

    agent().when().get(PLACE).then().statusCode(200);
    agent().when().get("/containers/api/containers/dev-qits-ci").then().statusCode(200);
    agent().when().get("/containers/api/containers/dev-qits-ci/agent").then().statusCode(200);
    agent().when().get(PLACE + "/logs?tail=10").then().statusCode(200);

    owner().when().delete(PLACE).then().statusCode(200);
  }

  @Test
  void anAgentReadsVolumes() {
    owner().when().put(VOLUME).then().statusCode(oneOf(200, 201));

    agent().when().get(VOLUME).then().statusCode(200);

    owner().when().delete(VOLUME).then().statusCode(200);
  }

  @Test
  void anAgentReadsTheHostUsage() {
    agent().when().get("/containers/api/gc/usage").then().statusCode(200);
  }

  @Test
  void anAgentCannotWrite() {
    agent().contentType(ContentType.JSON).body(SPEC).when().put(PLACE).then().statusCode(403);
    agent().when().post(PLACE + "/stop").then().statusCode(403);
    agent().when().post(PLACE + "/touch").then().statusCode(403);
    agent().when().delete(PLACE).then().statusCode(403);
    agent().when().delete("/containers/api/containers/dev-qits-ci/agent").then().statusCode(403);
    agent().when().put(VOLUME).then().statusCode(403);
    agent().when().delete(VOLUME).then().statusCode(403);
    agent()
        .contentType(ContentType.JSON)
        .body("{\"dryRun\":true}")
        .when()
        .post("/containers/api/gc/images")
        .then()
        .statusCode(403);
  }

  @Test
  void anAgentWhoseSubjectIsNotTheOwnerIsStillRefused() {
    // Today's rule, unchanged: an agent's subject is its own commissioned client, never its owner.
    given()
        .header(
            "Authorization",
            "Bearer " + MachineTokens.agentToken("dyn-agent-container-reads", OWN_AUDIENCE))
        .when()
        .get("/containers/api/containers/dev-qits-ci")
        .then()
        .statusCode(403);
  }
}
