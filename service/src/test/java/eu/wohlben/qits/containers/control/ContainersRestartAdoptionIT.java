package eu.wohlben.qits.containers.control;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.wohlben.qits.containers.entity.CtContainer;
import eu.wohlben.qits.containers.entity.ObservedState;
import eu.wohlben.qits.containers.persistence.CtContainerRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * <b>The headline claim of this whole repository, against a real docker daemon: a restart of this
 * service is invisible to a container that is still running.</b>
 *
 * <p>Everything else here is proved against a scripted fake, which is what keeps a clone's
 * {@code ./mvnw verify} docker-free — and a fake can only ever say that the code does what the fake
 * was told to expect. Adoption is the one claim that has to be made against the daemon: what is
 * asserted is that a container's docker {@code Id} and {@code StartedAt} are <b>unchanged</b>
 * afterwards, and those two numbers only exist on a host.
 *
 * <p>The arrangement is a crash, reproduced exactly:
 *
 * <ol>
 *   <li>a container is started through the service's own REST surface, so the row and the container
 *       are the ones production would have made;
 *   <li>its row is flipped back to {@code PENDING} — the state a process that died between
 *       {@code docker run} and the answer leaves, and the arm of the sweep that adopts;
 *   <li>a container this registry names nothing about is started beside it, by the test, directly;
 *   <li>{@link BootSweep#sweepOnce()} and {@link ContainerObserver#observeOnce()} run, which is
 *       what a restart runs and in the order a restart runs them.
 * </ol>
 *
 * <p>The assertions are three, and the third is the one that would catch a reap:
 * the row says {@code RUNNING} with {@code [adopted at startup]} on it; the container's id and
 * start time did not move, so nothing restarted it; and the <b>unlabelled bystander is still
 * running</b>. A sweep that listed the host rather than its rows would have taken that one, and
 * that sweep is the regression this repository was built to remove.
 *
 * <p><b>It lives in {@code control} rather than beside the driver</b> for
 * {@code CiRestartReconciliationIT}'s reason: {@code sweepOnce} and {@code observeOnce} are
 * package-private there by design — the suite is meant to drive them and no other module may — and
 * a test that had to make them public to reach them would have widened the seam it is testing. The
 * beans are the <b>injected</b> ones, so what runs is the deployment's configured driver against
 * the deployment's configured runtime.
 *
 * <p><b>A second claim rides in this file</b>, because it is the same kind of claim: an {@code
 * ensure} of a workload that was stopped starts the container that is there instead of running its
 * spec again. A stopped container still holds its name, so on a host the difference between the two
 * is a refusal — and a fake can only refuse because it was told to.
 *
 * <p>Tagged {@code extended}: it needs a reachable docker daemon and it starts real containers, so
 * it is out of the default build and out of the {@code -Dnative} gate. Run it with
 * {@code ./mvnw verify -DskipITs=false}. It skips cleanly when there is no daemon, and it removes
 * everything it started — its own place through the service's {@code destroyAll}, the bystander by
 * hand, in a {@code finally}.
 */
@QuarkusTest
// The caller is stated, not inherited from forward-auth's %test dev user (qits-1361): the owner
// every path here names, holding the system role a calling service holds.
@TestSecurity(user = "qits-ci", roles = "qits:system")
@Tag("extended")
public class ContainersRestartAdoptionIT {

  /** Only a shell is wanted. Docker pulls it implicitly on the run this test drives. */
  private static final String IMAGE = "alpine:3";

  private static final String RUNTIME =
      System.getProperty("qits.containers.container-runtime", "docker");

  /** A DNS-label owner of this suite's own, so no real module's rows are ever in the set. */
  private static final String OWNER = "qits-containers-it";

  private static final String WORKLOAD = "adoption";

  private static final String API = "/containers/api/containers/" + OWNER + "/" + WORKLOAD;

  @Inject BootSweep bootSweep;

  @Inject ContainerObserver observer;

  @Inject CtContainerRepository containers;

  @Test
  public void aRestartAdoptsItsOwnRunningContainerAndLeavesEverythingElseAlone() throws Exception {
    assumeTrue(dockerAnswers(), "a reachable docker daemon is required for this IT");

    String ref = "run-" + UUID.randomUUID();
    String bystander = "qits-ct-it-bystander-" + UUID.randomUUID();

    try {
      // 1. The production path: a row written first, then a real `docker run`.
      String rowId =
          given()
              .contentType(ContentType.JSON)
              .body(
                  """
                  {"spec":{"image":"%s","network":"bridge","args":["sleep","infinity"]},
                   "policy":{"type":"EXPLICIT"},"recreate":"never"}"""
                      .formatted(IMAGE))
              .when()
              .put(API + "/" + ref)
              .then()
              .statusCode(201)
              .body("state.observed", is("RUNNING"))
              .extract()
              .path("id");

      String containerName = ContainerNames.of(OWNER, WORKLOAD, ref);

      // 2. What the host says about it now. These two numbers are the whole proof: an adoption
      //    leaves both alone, and anything that stopped, removed or restarted the container moves
      //    at least one of them.
      String idBefore = inspect(containerName, "{{.Id}}");
      String startedAtBefore = inspect(containerName, "{{.State.StartedAt}}");
      assertTrue(idBefore.length() > 12, "docker gave no id for " + containerName);

      // 3. Somebody else's container. No label of ours, no row of ours, and a sweep that listed the
      //    host by label rather than reading its rows would take it.
      exec(RUNTIME, "run", "-d", "--name", bystander, IMAGE, "sleep", "300");

      // 4. The crash: the row back in flight, exactly as a process that died between the run and
      //    its answer would have left it.
      QuarkusTransaction.requiringNew()
          .run(
              () -> {
                CtContainer row = containers.findById(UUID.fromString(rowId));
                row.observedState = ObservedState.PENDING;
              });

      // 5. The restart, in the order a restart runs it.
      bootSweep.sweepOnce();
      observer.observeOnce();

      CtContainer adopted =
          QuarkusTransaction.requiringNew()
              .call(() -> containers.findById(UUID.fromString(rowId)));
      assertEquals(
          ObservedState.RUNNING,
          adopted.observedState,
          "a row whose container is still up must be adopted, not failed");
      assertTrue(
          adopted.detail != null && adopted.detail.contains("[adopted at startup"),
          "the adoption must be recorded on the row; detail was: " + adopted.detail);

      assertEquals(
          idBefore,
          inspect(containerName, "{{.Id}}"),
          "the container was replaced: an adoption must not touch it");
      assertEquals(
          startedAtBefore,
          inspect(containerName, "{{.State.StartedAt}}"),
          "the container was restarted: an adoption must not touch it");
      assertEquals("running", inspect(containerName, "{{.State.Status}}"));

      assertEquals(
          "running",
          inspect(bystander, "{{.State.Status}}"),
          "a container no row of this registry names must be left alone");

      // 6. The owner's own boot reap, through the surface a consumer calls: its rows, filtered by
      //    an instant, and never a listing by label.
      given()
          .when()
          .delete(API + "?createdBefore=" + Instant.now().plusSeconds(60))
          .then()
          .statusCode(200)
          .body("destroyed[0].removed", is(true));

      assertEquals(
          "", inspect(containerName, "{{.Id}}"), "destroyAll left the container on the host");
      assertEquals(
          "running",
          inspect(bystander, "{{.State.Status}}"),
          "the reap must reach this owner's rows and nothing else");
    } finally {
      exec(RUNTIME, "rm", "-f", bystander);
      exec(RUNTIME, "rm", "-f", ContainerNames.of(OWNER, WORKLOAD, ref));
      QuarkusTransaction.requiringNew()
          .run(() -> containers.delete("owner = ?1", OWNER));
    }
  }

  /**
   * <b>The second claim that only a daemon can settle: a workload that was stopped and asked for
   * again comes back as the SAME container.</b>
   *
   * <p>It belongs here rather than in the fake-driven suite for the reason the adoption above does.
   * A stopped container still holds its name, so the difference between starting it and running the
   * spec again is a refusal on a real host — and the fake could only ever refuse because it was
   * written to. What is asserted is docker's own {@code Id}: unchanged means the container came
   * back, and a new one means the writable layer the workload was stopped with is gone.
   */
  @Test
  public void aStoppedWorkloadIsStartedAgainAsTheSameContainer() throws Exception {
    assumeTrue(dockerAnswers(), "a reachable docker daemon is required for this IT");

    String ref = "restart-" + UUID.randomUUID();
    String containerName = ContainerNames.of(OWNER, WORKLOAD, ref);
    String body =
        """
        {"spec":{"image":"%s","network":"bridge","args":["sleep","infinity"]},
         "policy":{"type":"EXPLICIT"},"recreate":"never"}"""
            .formatted(IMAGE);

    try {
      given()
          .contentType(ContentType.JSON)
          .body(body)
          .when()
          .put(API + "/" + ref)
          .then()
          .statusCode(201)
          .body("state.observed", is("RUNNING"));

      String idBefore = inspect(containerName, "{{.Id}}");
      assertTrue(idBefore.length() > 12, "docker gave no id for " + containerName);

      given().when().post(API + "/" + ref + "/stop").then().statusCode(200);
      assertEquals("exited", inspect(containerName, "{{.State.Status}}"));

      // The same spec again. It is a start of what is there, not a run of a name docker will refuse.
      given()
          .contentType(ContentType.JSON)
          .body(body)
          .when()
          .put(API + "/" + ref)
          .then()
          .statusCode(200)
          .body("created", is(false))
          .body("state.observed", is("RUNNING"));

      assertEquals("running", inspect(containerName, "{{.State.Status}}"));
      assertEquals(
          idBefore,
          inspect(containerName, "{{.Id}}"),
          "a restart of an unchanged spec must start the container that was stopped, not make one");
    } finally {
      exec(RUNTIME, "rm", "-f", containerName);
      QuarkusTransaction.requiringNew().run(() -> containers.delete("owner = ?1", OWNER));
    }
  }

  /**
   * One field of one container, or blank when docker has no such container. Deliberately the test's
   * own docker call rather than the driver's: what is being checked is the host, and reading it
   * through the code under test would be the code agreeing with itself.
   */
  private static String inspect(String name, String format) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(RUNTIME, "inspect", "--format", format, name);
    pb.redirectErrorStream(true);
    Process process = pb.start();
    String output = new String(process.getInputStream().readAllBytes()).strip();
    return process.waitFor() == 0 ? output : "";
  }

  private static boolean dockerAnswers() {
    try {
      ProcessBuilder pb = new ProcessBuilder(RUNTIME, "info", "--format", "{{.ServerVersion}}");
      pb.redirectErrorStream(true);
      Process process = pb.start();
      process.getInputStream().readAllBytes();
      return process.waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  /** Best effort: a cleanup of something that is already gone is not a failure. */
  private static void exec(String... argv) {
    try {
      ProcessBuilder pb = new ProcessBuilder(argv);
      pb.redirectErrorStream(true);
      Process process = pb.start();
      process.getInputStream().readAllBytes();
      process.waitFor();
    } catch (Exception e) {
      // A cleanup of something that is already gone is not a failure.
    }
  }
}
