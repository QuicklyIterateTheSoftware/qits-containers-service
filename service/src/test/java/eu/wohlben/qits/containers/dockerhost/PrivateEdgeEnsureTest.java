package eu.wohlben.qits.containers.dockerhost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.containers.api.FakeDriverProfile;
import eu.wohlben.qits.containers.control.FakeContainersDriver;
import eu.wohlben.qits.containers.driver.docker.ContainerProcess;
import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.persistence.CtContainerRepository;
import eu.wohlben.qits.containers.persistence.CtVolumeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code privateEdge} from the wire to the driver's {@code run} (qits-1991): absent is no
 * resolution at all, true is the resolved spec handed to the run, and neither reaches the row — the
 * flagged spec and its unflagged twin carry the same {@code specHash}, so turning the flag on
 * recreates nothing.
 *
 * <p>The resolver bean is handed a scripted docker CLI for the length of each test and given its
 * own back afterwards: it is a {@code @Singleton}, so the instance injected here is the one the
 * resource calls.
 */
@QuarkusTest
@TestSecurity(user = "qits-workspaces", roles = "qits:system")
@TestProfile(FakeDriverProfile.class)
class PrivateEdgeEnsureTest {

  private static final String NETWORK_ID = "4k2x9q7m1n0p3r5s6t8u";

  private static final String PLACE = "/containers/api/containers/qits-workspaces/admin/ws-1";

  @Inject FakeContainersDriver driver;

  @Inject PrivateEdgeResolver resolver;

  @Inject CtContainerRepository containers;

  @Inject CtVolumeRepository volumes;

  private PrivateEdgeResolver.DockerCommand realDocker;

  private String realResolvConf;

  private final List<List<String>> dockerCalls = new ArrayList<>();

  @BeforeEach
  void scriptTheHost() throws IOException {
    driver.reset();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              containers.deleteAll();
              volumes.deleteAll();
            });
    realDocker = resolver.docker;
    realResolvConf = resolver.resolvConf;
    Path resolvConf = Files.createTempFile("qits-1991-resolv", ".conf");
    Files.writeString(resolvConf, "nameserver 127.0.0.11\n# ExtServers: [host(127.0.0.53)]\n");
    resolver.resolvConf = resolvConf.toString();
    resolver.docker =
        (argv, timeout, maxChars) -> {
          dockerCalls.add(argv);
          String output =
              switch (argv.get(1) + " " + argv.get(2)) {
                case "network inspect" -> NETWORK_ID;
                case "service ls" -> "y8w2v6b4c1d3";
                case "service inspect" ->
                    "[{\"NetworkID\":\"" + NETWORK_ID + "\",\"Addr\":\"10.231.0.5/20\"}]";
                default -> null;
              };
          return output == null
              ? new ContainerProcess.Result(1, "unscripted", false, false)
              : new ContainerProcess.Result(0, output + "\n", false, false);
        };
  }

  @AfterEach
  void giveTheResolverItsHostBack() throws IOException {
    Files.deleteIfExists(Path.of(resolver.resolvConf));
    resolver.docker = realDocker;
    resolver.resolvConf = realResolvConf;
  }

  private static String body(String privateEdge, String recreate) {
    return """
        {"spec":{"image":"alpine:3","network":"qits-net","hostDockerSocket":true%s},
         "policy":{"type":"EXPLICIT"},"recreate":"%s"}"""
        .formatted(privateEdge == null ? "" : ",\"privateEdge\":" + privateEdge, recreate);
  }

  private String ensure(String body, int status) {
    return given()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .put(PLACE)
        .then()
        .statusCode(status)
        .extract()
        .path("specHash");
  }

  @Test
  void anAbsentFlagAsksTheHostNothingAndRunsTheSpecAsAsked() {
    ensure(body(null, "never"), 201);

    ContainerSpec ran = driver.ranSpecs().getLast();
    assertEquals(List.of(), ran.extraNetworks());
    assertEquals(List.of(), ran.dns());
    assertEquals(List.of(), dockerCalls, "no flag, no docker question");
  }

  @Test
  void aFalseFlagIsTheAbsentOne() {
    ensure(body("false", "never"), 201);

    assertEquals(List.of(), driver.ranSpecs().getLast().extraNetworks());
    assertEquals(List.of(), dockerCalls);
  }

  @Test
  void aTrueFlagRunsTheResolvedSpecAndStoresTheAskedOne() {
    String unflaggedHash = ensure(body(null, "never"), 201);
    given().when().delete(PLACE).then().statusCode(200);

    String flaggedHash = ensure(body("true", "never"), 201);

    ContainerSpec ran = driver.ranSpecs().getLast();
    assertEquals(List.of("qits-private"), ran.extraNetworks());
    assertEquals(List.of("10.231.0.5", "127.0.0.53"), ran.dns());
    assertEquals(unflaggedHash, flaggedHash, "the flag never enters the fingerprint");
  }

  @Test
  void togglingTheFlagOnARunningContainerRecreatesNothing() {
    String before = ensure(body(null, "ifChanged"), 201);
    long runs = driver.ranSpecs().size();

    String after = ensure(body("true", "ifChanged"), 200);

    assertEquals(before, after);
    assertEquals(runs, driver.ranSpecs().size(), "same hash, so no replacement run");
  }
}
