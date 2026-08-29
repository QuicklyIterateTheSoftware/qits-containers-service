package eu.wohlben.qits.containers.stories.boot;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.api.TokenValidationBootstrapIT;
import eu.wohlben.qits.containers.stories.support.StoryDocker;
import eu.wohlben.qits.containers.stories.support.StoryTarget;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkTaps;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.UserflowRunsAfter;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * What this service does to the host <b>before any caller arrives</b>.
 *
 * <p>It is the docker half of {@code api.TokenValidationBootstrapIT}'s claim, told the same way: a
 * cumulative recording is attributed by a cursor, so traffic from before any story ran lands in
 * whichever story drains <i>first</i>. There it is the JWKS fetch; here it is the boot's own docker
 * calls, and this class is the story they belong to. That is why {@code
 * stories.support.StoryDocker}'s recording is registered with <b>no floor</b> while every other
 * file-backed tap in the fleet takes one, and it is why this class sorts first among the story
 * packages ({@code boot} before {@code lifecycle}, {@code ownership}, {@code reap}, {@code
 * refusals}) — the class orderer breaks ties inside a profile group by FQCN, and these packages are
 * named so that alphabetical is intended rather than incidental.
 *
 * <p><b>Run this class on its own and it is still correct.</b> Run one of the later ones on its own
 * and its first story inherits the boot calls and fails its edge count — loudly, which is the right
 * way for that assumption to break. {@code @UserflowRunsAfter} on every story of every later class
 * names this one, so a full run can only order them the way the diagrams assume.
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class HostBootstrapIT {

  static final String CATEGORY = "startup";

  static final String SLUG = "on-start-qits-containers-prepares-the-host-every-workload-expects";

  /** The three volumes the platform shares between workloads — {@code qits.containers.shared-volumes}. */
  static final List<String> SHARED_VOLUMES =
      List.of("qits_shared_dot_claude", "qits_shared_m2", "qits_shared_pnpm");

  @BeforeAll
  static void tapWhatALaunchedProcessAsksDockerFor() {
    NetworkTaps.restAssured(StoryTarget.SERVICE);
    StoryDocker.installSource();
  }

  @UserStory(
      value = "On start, qits-containers prepares the host every workload expects",
      category = CATEGORY)
  @UserStoryDescription(
      """
      Every container this service starts mounts the platform's shared stores — the coding agent's
      home, the maven repository, the pnpm store — and is addressed on the platform's own network.
      So before any module asks for anything, a freshly started qits-containers makes sure the three
      volumes exist and asks whether the network is there. The asymmetry is the whole step: a volume
      create is idempotent and a volume nobody mounts costs nothing, while a network invented here
      would be a network no other module's containers are on — and on a swarm-initialized host a
      bridge cannot be created at all. So one is made and the other is only asked about, and a
      missing network is a warning a deployment answers rather than something an orchestrator papers
      over.
      """)
  @UserflowRunsAfter(TokenValidationBootstrapIT.class)
  @Order(1)
  void theBootPreparesTheSharedVolumesAndAsksAboutTheNetwork(Interactions story) {
    // Readiness, which is also what says the boot finished — and which draws no arrow: the shipped
    // tap skips any path with a /q/ segment, and this service's probe root is /containers/q.
    given().get("/containers/q/health/ready").then().statusCode(200);
    story
        .note("qits-containers is up, and its startup steps have already run")
        .as("service-started");

    // The evidence, read off the stand-in's own recording rather than inferred from the diagram the
    // story also emits. The two are the same lines; one of them is documentation and the other is
    // a test, and a story that only drew it would be a story nothing could fail.
    List<String> calls = StoryDocker.calls();
    for (String volume : SHARED_VOLUMES) {
      assertTrue(
          calls.contains(StoryDocker.label("volume create " + volume, "0")),
          "the boot never made the shared volume " + volume + "; it made " + calls);
    }
    story
        .note(
            "the three shared volumes are made, unlabelled and with no registry row — they are the"
                + " platform's rather than any owner's, and a row is what would make one removable")
        .as("shared-volumes-made");

    assertTrue(
        calls.contains(StoryDocker.label("network inspect " + StoryTarget.NETWORK, "0")),
        "the boot never asked about " + StoryTarget.NETWORK + "; it made " + calls);
    story
        .note(
            "and the platform network is asked about, never created — one invented here would be a"
                + " network no other module's containers are on")
        .as("network-asked-about");
  }

  @AfterAll
  static void theBootStoryIsComplete() {
    ReportAssertions.assertComplete(CATEGORY, SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "service-started");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "shared-volumes-made");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "network-asked-about");

    for (String volume : SHARED_VOLUMES) {
      ReportAssertions.assertEdge(
          CATEGORY,
          SLUG,
          StoryDocker.KIND,
          StoryTarget.SERVICE,
          StoryDocker.DAEMON,
          StoryDocker.label("volume create " + volume, "0"));
    }
    ReportAssertions.assertEdge(
        CATEGORY,
        SLUG,
        StoryDocker.KIND,
        StoryTarget.SERVICE,
        StoryDocker.DAEMON,
        StoryDocker.label("network inspect " + StoryTarget.NETWORK, "0"));

    // FOUR, and no fifth. The boot sweep runs between the shared resources and the observer's
    // ticker, and on an empty registry it asks docker nothing at all — because it iterates ROWS and
    // never a label listing, which is the difference this whole repository exists for. A fifth edge
    // here would be this service having looked at the host rather than at what it named.
    ReportAssertions.assertEdgeCount(CATEGORY, SLUG, 4);
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, SLUG, List.of(StoryTarget.SERVICE));
    // And nothing reached this service while it did any of it: the whole story happened before the
    // first caller, which is exactly what a caller mounting a shared volume depends on.
    ReportAssertions.assertNoEdgesTo(CATEGORY, SLUG, StoryTarget.SERVICE);
  }
}
