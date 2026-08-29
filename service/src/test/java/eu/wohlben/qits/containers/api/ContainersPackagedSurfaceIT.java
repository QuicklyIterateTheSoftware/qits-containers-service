package eu.wohlben.qits.containers.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.wohlben.qits.containers.testdb.EmbeddedPg;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The whole service as it is <b>packaged</b> — the fast-jar under {@code mvn verify
 * -DskipITs=false}, the GraalVM binary under {@code mvn verify -Dnative}. The assertions are chosen
 * for what a packaged build can silently lose rather than for API coverage (that is
 * {@link ContainersApiTest}'s job):
 *
 * <ul>
 *   <li>the routes are where the config says — {@code quarkus.rest.path} and
 *       {@code quarkus.http.non-application-root-path} are build-time settings baked into the
 *       artifact, and there is no unprefixed form to fall back to;
 *   <li>the shipped datasource <b>expressions</b> resolve and connect, and
 *       {@code db/containers/migration/} survived as a resource — migrations are loaded by scanning
 *       a classpath location, exactly the shape a native image drops;
 *   <li>a row round-trips through Hibernate/Panache in the packaged process: written, read back,
 *       and removed.
 * </ul>
 *
 * <p><b>The container runtime is pointed at a binary that does not exist</b>, which is what makes
 * this IT free of side effects on the host and what puts the driver's degrade path under test at
 * the same time. The boot steps run for real in this process — {@code SharedResources} tries three
 * volumes and asks about the network, {@code BootSweep} reads its rows, the observer's ticker
 * starts — and every one of them has to reach a warning rather than a failed start. A service that
 * would not boot without docker could not be deployed to a host whose docker is down, which is the
 * one host anybody would want to deploy it to.
 *
 * <p><b>The write that round-trips is a volume</b>, deliberately. Every container write ends in a
 * docker call whose answer decides the row, and with no daemon those are honest 5xx rather than
 * recorded outcomes — that is the driver telling "docker did not answer" from "docker has no such
 * container", and it is the difference that keeps a delete from settling {@code GONE} over a
 * container that is still running. A volume claim writes its row first and reports what docker
 * said, so it survives having no docker and still proves both tables and the whole persistence
 * stack in the packaged artifact.
 */
@QuarkusIntegrationTest
@TestProfile(ContainersPackagedSurfaceIT.PackagedUnderTarget.class)
public class ContainersPackagedSurfaceIT {

  private static final String SEGMENT = "/containers";

  private static final String OWNER = "qits-ci";

  /**
   * Hands the launched artifact its databases the way a deployment does — as the generic resource
   * triples, not as the datasource keys. {@code core} ships {@code jdbc.url=${QITS_RESOURCE_DB_URL}}
   * and its two siblings, and the qits-eventstream jar ships the same three over
   * {@code QITS_RESOURCE_EVENTSTREAM_*}, so supplying the variables leaves the <b>shipped</b>
   * expressions themselves under test.
   *
   * <p><b>Both triples are mandatory, which is itself the claim.</b> Neither jar's expressions have
   * a default behind them, so a packaged process missing either dies at Flyway naming what is
   * absent rather than opening a store nobody meant. This is the only test that boots the shipped
   * artifact and would find out.
   *
   * <p>The urls travel through system properties rather than static fields: a test profile is
   * instantiated in more than one classloader, so a field written by one copy is not the field the
   * other reads, while the process has exactly one property table.
   */
  public static class PackagedUnderTarget implements QuarkusTestProfile {

    private static final String URL_PROPERTY = "qits.test.packaged-it.db-url";

    private static final String EVENTSTREAM_URL_PROPERTY =
        "qits.test.packaged-it.eventstream-url";

    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "QITS_RESOURCE_DB_URL", databaseUrl(URL_PROPERTY, "containers_packaged_it"),
          "QITS_RESOURCE_DB_USERNAME", EmbeddedPg.USER,
          "QITS_RESOURCE_DB_PASSWORD", EmbeddedPg.PASSWORD,
          "QITS_RESOURCE_EVENTSTREAM_URL",
              databaseUrl(EVENTSTREAM_URL_PROPERTY, "containers_eventstream_packaged_it"),
          "QITS_RESOURCE_EVENTSTREAM_USERNAME", EmbeddedPg.USER,
          "QITS_RESOURCE_EVENTSTREAM_PASSWORD", EmbeddedPg.PASSWORD,
          // No docker on purpose: every driver call must degrade to a warning, never a failed boot.
          "qits.containers.container-runtime", "docker-absent-for-this-it");
    }

    /**
     * One embedded-postgres database, parked so both copies of this profile answer the same url.
     *
     * <p><b>Package-private rather than private</b>, so {@link
     * TokenValidationBootstrapIT.PackagedWithMockIdp} can ask for databases of its OWN rather than
     * copying the parking trick. It needs to: the userflow stories WRITE containers for {@code
     * qits-ci}, and this class asserts that owner's listing is empty. Sharing one store would make
     * this IT pass or fail on whether the stories happened to run first, which is not a fact about
     * the packaged artifact.
     */
    static synchronized String databaseUrl(String property, String database) {
      String recorded = System.getProperty(property);
      if (recorded != null) {
        return recorded;
      }
      // localhost resolves for the launched process too — it is a child of this JVM on this host.
      String url = EmbeddedPg.url(database);
      System.setProperty(property, url);
      return url;
    }
  }

  @Test
  public void theReadinessProbeIsWhereTheDeploymentLooksForItAndCoversBothStores() {
    given()
        .when()
        .get(SEGMENT + "/q/health/ready")
        .then()
        .statusCode(200)
        .body("status", equalTo("UP"))
        .body("checks.find { it.name == 'Database connections health check' }.data.containers",
            is("UP"))
        .body("checks.find { it.name == 'Database connections health check' }.data.eventstream",
            is("UP"));
  }

  @Test
  public void theOrchestrationRoutesAnswerUnderTheGatewaySegmentAndNowhereElse() {
    // The listing reaches ct_container, so a migration that did not make it into the artifact shows
    // up here as a 500 rather than as an empty answer.
    given()
        .when()
        .get(SEGMENT + "/api/containers/" + OWNER)
        .then()
        .statusCode(200)
        .body("containers", hasSize(0));

    // 404 rather than a row: absence is the only thing that produces one.
    given().when().get(SEGMENT + "/api/containers/" + OWNER + "/step/nothing").then().statusCode(404);

    // qits-gateway routes verbatim by prefix, so there is no unprefixed form.
    given().when().get("/api/containers/" + OWNER).then().statusCode(404);
    given().when().get("/q/health/ready").then().statusCode(404);
  }

  @Test
  public void theApiDocumentAndItsUiAreServedUnderTheGatewaySegment() {
    given().when().get(SEGMENT + "/q/openapi").then().statusCode(200);
    given().when().get(SEGMENT + "/q/swagger-ui/").then().statusCode(200);
  }

  @Test
  public void aRowRoundTripsAgainstTheShippedSchemaWithNoDockerOnTheHost() {
    String volume = SEGMENT + "/api/volumes/" + OWNER + "/packaged-it-store";

    // The claim is written before docker is asked, so the row exists whatever the daemon says — and
    // here it says nothing at all, because the binary is not there.
    String detail =
        given()
            .when()
            .put(volume)
            .then()
            .statusCode(200)
            .body("desired", is("PRESENT"))
            .body("existed", is(false))
            .extract()
            .path("detail");
    assertFalse(
        detail == null || detail.isBlank(),
        "a driver call with no docker behind it must be reported, not silently succeed");

    given().when().get(volume).then().statusCode(200).body("owner", is(OWNER));

    // The remove fails for the same reason, so the row stays ABSENT for the reconcile to replay
    // rather than being dropped over a volume that may still be on the host.
    given().when().delete(volume).then().statusCode(200).body("existed", is(true));
    given().when().get(volume).then().statusCode(200).body("desired", is("ABSENT"));
  }
}
