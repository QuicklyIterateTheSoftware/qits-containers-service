package eu.wohlben.qits.containers.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.client.ContainersAnswer;
import eu.wohlben.qits.containers.client.ContainersAnswer.Created;
import eu.wohlben.qits.containers.client.ContainersAnswer.Ready;
import eu.wohlben.qits.containers.client.ContainersAnswer.Refused;
import eu.wohlben.qits.containers.client.ContainersAnswer.Unreachable;
import eu.wohlben.qits.containers.client.ContainersClient;
import eu.wohlben.qits.containers.client.ContainersWire.DeleteOutcome;
import eu.wohlben.qits.containers.client.ContainersWire.Desired;
import eu.wohlben.qits.containers.client.ContainersWire.Destroyed;
import eu.wohlben.qits.containers.client.ContainersWire.EnsureRequest;
import eu.wohlben.qits.containers.client.ContainersWire.Envelope;
import eu.wohlben.qits.containers.client.ContainersWire.LogTail;
import eu.wohlben.qits.containers.client.ContainersWire.Observed;
import eu.wohlben.qits.containers.client.ContainersWire.Policy;
import eu.wohlben.qits.containers.client.ContainersWire.Recreate;
import eu.wohlben.qits.containers.client.ContainersWire.Spec;
import eu.wohlben.qits.containers.client.ContainersWire.VolumeEnvelope;
import eu.wohlben.qits.containers.client.ContainersWire.VolumeState;
import eu.wohlben.qits.containers.control.ContainerNames;
import eu.wohlben.qits.containers.control.FakeContainersDriver;
import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.persistence.CtContainerRepository;
import eu.wohlben.qits.containers.persistence.CtVolumeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The client against the real REST surface, in one JVM: every method it has, round-tripping through
 * the routes {@code ContainersResource} and {@code VolumesResource} actually serve.
 *
 * <p><b>It lives here because it cannot live anywhere else.</b> {@code client} must not depend on
 * {@code service} — that would put the deployable, its datasource and its Flyway lineage into every
 * consumer, which is the boundary the module split exists to hold — and {@code service} must not
 * depend on {@code client} at runtime, because it serves the routes rather than calling them. Test
 * scope on this module is the one classpath both halves may share, so this file is where "the
 * client speaks what the service serves" stops being two files written to agree and becomes a
 * measurement.
 *
 * <p><b>What is asserted is the pairing, not the behaviour.</b> {@link ContainersApiTest} next door
 * already says what each route does; the claims here are that the client addresses it, that the
 * status becomes the right one of the four answers, and that the JSON binds in both directions.
 * Where a claim is only about the client — an answer a real service will not produce on demand, the
 * headers on the wire, the HTTP/1.1 pin — it is asserted in the client module's own suite against
 * its stub, because a suite that needed this application booted to check a header would be the
 * slower place to check it.
 *
 * <p>The gate is off here, which is the shipped default. What the gate ON does to the client's
 * {@code TokenSource} is {@link ContainersClientGuardedWireTest}.
 */
@QuarkusTest
@TestProfile(FakeDriverProfile.class)
class ContainersClientWireTest {

  private static final String OWNER = "qits-ci";

  private static final String WORKLOAD = "step";

  private static final String REF = "run-1";

  private static final EnsureRequest EXPLICIT =
      EnsureRequest.of(Spec.of("alpine:3", "qits-net"), Policy.explicitLifetime());

  /**
   * Where this application answers. {@code @TestHTTPResource} knows the port the test instance
   * actually bound, which no properties file can — {@code quarkus.http.test-port=0} is what the
   * suite runs on. The authority is rebuilt without a path, because a base URL with one in it is
   * precisely what the client refuses to work with.
   */
  @TestHTTPResource URL root;

  @Inject FakeContainersDriver driver;

  @Inject CtContainerRepository containers;

  @Inject CtVolumeRepository volumes;

  private ContainersClient client;

  @BeforeEach
  void wipe() {
    client = client(base());
    driver.reset();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              containers.deleteAll();
              volumes.deleteAll();
            });
  }

  // --- ensure ---------------------------------------------------------------------------------------

  @Test
  void anEnsureIsCreatedOnceAndReadyAfterwards() {
    ContainersAnswer<Envelope> first = client.ensure(OWNER, WORKLOAD, REF, EXPLICIT);

    assertInstanceOf(Created.class, first, first.detail());
    assertTrue(first.created());
    assertEquals(ContainerNames.of(OWNER, WORKLOAD, REF), first.value().containerName());
    assertEquals(Desired.RUNNING, first.value().state().desired());
    assertEquals(Observed.RUNNING, first.value().state().observed());
    assertEquals("qits-net", first.value().endpoint().network());
    // No alias in the spec, so the container's own name is the address — never a null a caller has
    // to know to fall back from.
    assertEquals(ContainerNames.of(OWNER, WORKLOAD, REF), first.value().endpoint().alias());
    assertNull(first.value().endpoint().proxy(), "the data plane arrives behind this field");
    assertNotNull(first.value().id());
    assertNotNull(first.value().specHash());

    ContainersAnswer<Envelope> again = client.ensure(OWNER, WORKLOAD, REF, EXPLICIT);
    assertInstanceOf(Ready.class, again);
    assertFalse(again.created());
    assertEquals(
        1,
        driver.calls().stream().filter(call -> call.startsWith("run:")).count(),
        "a second ensure of an unchanged spec must not start a second container");
  }

  @Test
  void anEnsureCarriesEveryFieldOfTheSpecItWasGiven() {
    // The wide body, so a field the client renamed or dropped shows up as a 400 or as an argv that
    // does not carry it, rather than as a green test over the three fields everything else sends.
    EnsureRequest wide =
        new EnsureRequest(
            new Spec(
                "alpine:3",
                List.of("/bin/sh"),
                List.of("-c", "sleep 1"),
                java.util.Map.of("QITS_TOKEN", "secret"),
                java.util.Map.of("acme.owner", "a-team"),
                "qits-net",
                List.of("run-1-step"),
                List.of("host.docker.internal:host-gateway"),
                List.of(new eu.wohlben.qits.containers.client.ContainersWire.VolumeMount(
                    "qits-ci-run-1", "/work")),
                List.of(new eu.wohlben.qits.containers.client.ContainersWire.SharedMount(
                    "qits_shared_m2", "/root/.m2")),
                false,
                new eu.wohlben.qits.containers.client.ContainersWire.Security(
                    true, true, "512m", "512m", 128L, "1.5", 750),
                eu.wohlben.qits.containers.client.ContainersWire.PullPolicy.NEVER,
                "qits-ci-step-run-1-explicit",
                "build",
                true),
            Policy.idleStop(3600L),
            Recreate.never);

    ContainersAnswer<Envelope> answer = client.ensure(OWNER, WORKLOAD, REF, wide);

    assertTrue(answer.succeeded(), answer.detail());
    assertEquals("qits-ci-step-run-1-explicit", answer.value().containerName());
    assertEquals("run-1-step", answer.value().endpoint().alias());
    // The user survives the whole round trip — client record, JSON, SpecDto, domain spec — because
    // it is the only place the container's identity can be chosen: cap-drop=ALL leaves the script
    // no way to change it from the inside.
    assertEquals("build", driver.ranSpecs().getFirst().user());
    // So does the ask for tini, which is nullable on the wire and a plain boolean in the domain.
    assertTrue(driver.ranSpecs().getFirst().init());

    String argv = String.join(" ", driver.calls());
    assertTrue(argv.contains("run:qits-ci-step-run-1-explicit"), argv);
  }

  @Test
  void aSpecThatNamedNoUserArrivesWithNone() {
    // The absence, asserted where the presence is: a default that leaked in here would run every
    // existing workload as somebody it was never built for.
    assertTrue(client.ensure(OWNER, WORKLOAD, REF, EXPLICIT).succeeded());
    assertEquals("", driver.ranSpecs().getFirst().user());
  }

  @Test
  void aSpecThatSaidNothingAboutInitArrivesWithoutIt() {
    // Spec.of leaves init null, the client omits a null, and the service reads an absent field as
    // false — which is the whole of what makes a consumer built before this field existed still
    // correct. The same three steps are what a caller that never sets it does forever.
    assertTrue(client.ensure(OWNER, WORKLOAD, REF, EXPLICIT).succeeded());
    assertFalse(driver.ranSpecs().getFirst().init());
  }

  @Test
  void withInitCarriesTheAskAndChangesNothingElse() {
    assertTrue(
        client
            .ensure(
                OWNER,
                WORKLOAD,
                REF,
                EnsureRequest.of(
                    Spec.of("alpine:3", "qits-net").withInit(true), Policy.explicitLifetime()))
            .succeeded());

    ContainerSpec ran = driver.ranSpecs().getFirst();
    assertTrue(ran.init());
    assertEquals("alpine:3", ran.image());
    assertEquals("qits-net", ran.network());
  }

  @Test
  void aSpecConflictArrivesAsARefusalCarryingItsCode() {
    assertTrue(
        client
            .ensure(
                OWNER, WORKLOAD, REF, EnsureRequest.of(Spec.of("alpine:3", "qits-net"),
                    Policy.ephemeral(null)))
            .succeeded());

    ContainersAnswer<Envelope> conflict =
        client.ensure(
            OWNER,
            WORKLOAD,
            REF,
            new EnsureRequest(
                Spec.of("alpine:3.20", "qits-net"), Policy.ephemeral(null), Recreate.ifChanged));

    assertInstanceOf(Refused.class, conflict);
    assertTrue(conflict.specConflict(), conflict.detail());
    assertEquals(409, ((Refused<Envelope>) conflict).status());
    assertFalse(conflict.unreachable(), "a 409 is a response: the service answered");
  }

  @Test
  void anImageNothingPublishedArrivesAsImageMissing() {
    driver.scriptRun(
        new ContainersDriver.Started(
            false, "", "docker: Error response from daemon: manifest unknown: manifest unknown."));

    ContainersAnswer<Envelope> answer = client.ensure(OWNER, WORKLOAD, REF, EXPLICIT);

    assertTrue(answer.imageMissing(), answer.detail());
    assertFalse(answer.specConflict());

    // The row is still there and says what happened: a refusal to the caller is not a reason to
    // forget the place.
    ContainersAnswer<Envelope> place = client.status(OWNER, WORKLOAD, REF);
    assertTrue(place.succeeded());
    assertEquals(Observed.MISSING, place.value().state().observed());
  }

  @Test
  void aValueTheArgvWouldRefuseArrivesAsInvalid() {
    EnsureRequest bad =
        EnsureRequest.of(
            new Spec(
                "alpine:3", null, null, null, null, "qits-net",
                List.of("Not A Label"), null, null, null, false, null, null, null, null, null),
            Policy.explicitLifetime());

    ContainersAnswer<Envelope> answer = client.ensure(OWNER, WORKLOAD, REF, bad);

    assertTrue(answer.invalid(), answer.detail());
    assertEquals(400, ((Refused<Envelope>) answer).status());
    assertTrue(((Refused<Envelope>) answer).message().contains("network alias"));
  }

  @Test
  void aUserTheArgvWouldRefuseArrivesAsInvalid() {
    // `build:root` is --user's own user:group form — one value carrying a group nobody declared.
    ContainersAnswer<Envelope> answer =
        client.ensure(
            OWNER,
            WORKLOAD,
            REF,
            EnsureRequest.of(
                Spec.of("alpine:3", "qits-net").runAs("build:root"), Policy.explicitLifetime()));

    assertTrue(answer.invalid(), answer.detail());
    assertTrue(((Refused<Envelope>) answer).message().contains("user"));
  }

  // --- reads -----------------------------------------------------------------------------------------

  @Test
  void aPlaceNoRowNamesIsARefusedFourOhFourAndNeverAnUnreachableService() {
    ContainersAnswer<Envelope> absent = client.status(OWNER, WORKLOAD, "nothing");

    assertInstanceOf(Refused.class, absent);
    assertEquals(404, ((Refused<Envelope>) absent).status());
    assertFalse(
        absent.unreachable(),
        "a 404 is the service saying no row names this place; nothing was wrong with the network");
  }

  @Test
  void bothListingsComeBackAsTheListItself() {
    assertTrue(client.ensure(OWNER, WORKLOAD, REF, EXPLICIT).succeeded());
    assertTrue(client.ensure(OWNER, "agent", "run-2", EXPLICIT).succeeded());

    ContainersAnswer<List<Envelope>> owned = client.list(OWNER);
    assertTrue(owned.succeeded());
    assertEquals(2, owned.value().size());

    ContainersAnswer<List<Envelope>> workload = client.list(OWNER, WORKLOAD);
    assertEquals(1, workload.value().size());
    assertEquals(ContainerNames.of(OWNER, WORKLOAD, REF), workload.value().getFirst().containerName());

    // Nothing is listed by label anywhere: an owner with no rows has no places, whatever the host
    // is running.
    assertEquals(List.of(), client.list("qits-workspaces").value());
  }

  @Test
  void theLogTailIsReadableWhileThePlaceIsExited() {
    assertTrue(client.ensure(OWNER, WORKLOAD, REF, EXPLICIT).succeeded());
    driver.scriptLogs(ContainerNames.of(OWNER, WORKLOAD, REF), "the workload said this\n");

    ContainersAnswer<Envelope> stopped = client.stop(OWNER, WORKLOAD, REF);
    assertTrue(stopped.succeeded());
    assertEquals(Desired.STOPPED, stopped.value().state().desired());
    assertEquals(Observed.EXITED, stopped.value().state().observed());

    ContainersAnswer<LogTail> tail = client.logs(OWNER, WORKLOAD, REF, 50);
    assertTrue(tail.succeeded());
    assertTrue(tail.value().text().contains("the workload said this"));
    assertFalse(tail.value().truncated());
  }

  // --- the writes that change what is running -----------------------------------------------------------

  @Test
  void stopAndTouchRefuseAPlaceThatIsNotThereAndAnswerOneThatIs() {
    assertEquals(404, ((Refused<Envelope>) client.stop(OWNER, WORKLOAD, REF)).status());
    assertEquals(404, ((Refused<Void>) client.touch(OWNER, WORKLOAD, REF)).status());

    assertTrue(client.ensure(OWNER, WORKLOAD, REF, EXPLICIT).succeeded());

    assertTrue(client.stop(OWNER, WORKLOAD, REF).succeeded());

    ContainersAnswer<Void> touched = client.touch(OWNER, WORKLOAD, REF);
    assertInstanceOf(Ready.class, touched);
    assertNull(touched.value(), "the route is a 204; there is no body to bind");
  }

  @Test
  void aDeleteCarriesTheTailBackAndIsIdempotent() {
    assertTrue(client.ensure(OWNER, WORKLOAD, REF, EXPLICIT).succeeded());
    driver.scriptLogs(ContainerNames.of(OWNER, WORKLOAD, REF), "why it died\n");

    ContainersAnswer<DeleteOutcome> removed = client.delete(OWNER, WORKLOAD, REF, true, true);
    assertTrue(removed.succeeded());
    assertTrue(removed.value().existed());
    assertTrue(removed.value().logTail().contains("why it died"));

    // Captured BEFORE the removal or lost with it, which is the whole reason `withLogs` is a
    // parameter of the delete rather than a call a consumer makes first.
    int logs = driver.calls().indexOf("logs:" + ContainerNames.of(OWNER, WORKLOAD, REF));
    int gone = driver.calls().indexOf("remove:" + ContainerNames.of(OWNER, WORKLOAD, REF));
    assertTrue(logs >= 0 && logs < gone, driver.calls().toString());

    ContainersAnswer<DeleteOutcome> again = client.delete(OWNER, WORKLOAD, REF, false, false);
    assertTrue(again.succeeded(), "a retried delete is the same success, not a 404 to special-case");
    assertFalse(again.value().existed());
    assertNull(again.value().logTail());
  }

  @Test
  void aDestroyAllIsTheBootReapAndTakesOnlyWhatPredatesTheInstant() {
    assertTrue(client.ensure(OWNER, WORKLOAD, REF, EXPLICIT).succeeded());

    // An instant before the row was written reaches nothing: what an owner started after it came up
    // is not in the set.
    ContainersAnswer<List<Destroyed>> none =
        client.destroyAll(OWNER, WORKLOAD, Instant.parse("2020-01-01T00:00:00Z"));
    assertTrue(none.succeeded());
    assertEquals(List.of(), none.value());

    ContainersAnswer<List<Destroyed>> reaped =
        client.destroyAll(OWNER, WORKLOAD, Instant.now().plusSeconds(60));
    assertTrue(reaped.succeeded());
    assertEquals(1, reaped.value().size());
    assertEquals(REF, reaped.value().getFirst().ref());
    assertTrue(reaped.value().getFirst().removed());

    assertEquals(404, ((Refused<Envelope>) client.status(OWNER, WORKLOAD, REF)).status());
  }

  // --- volumes ------------------------------------------------------------------------------------------

  @Test
  void theThreeVolumeRoutesRoundTrip() {
    assertEquals(
        404, ((Refused<VolumeEnvelope>) client.volume(OWNER, "qits-ci-cache")).status());

    ContainersAnswer<VolumeEnvelope> made = client.ensureVolume(OWNER, "qits-ci-cache");
    assertTrue(made.succeeded());
    assertEquals(VolumeState.PRESENT, made.value().desired());
    assertFalse(made.value().existed());
    assertNotNull(made.value().id());

    assertTrue(client.ensureVolume(OWNER, "qits-ci-cache").value().existed());

    ContainersAnswer<VolumeEnvelope> read = client.volume(OWNER, "qits-ci-cache");
    assertTrue(read.succeeded());
    assertEquals(OWNER, read.value().owner());
    assertEquals("qits-ci-cache", read.value().name());

    ContainersAnswer<VolumeEnvelope> gone = client.deleteVolume(OWNER, "qits-ci-cache");
    assertTrue(gone.succeeded());
    assertEquals(VolumeState.ABSENT, gone.value().desired());
    assertTrue(gone.value().existed());

    // Idempotent, exactly as a container delete is.
    assertFalse(client.deleteVolume(OWNER, "qits-ci-cache").value().existed());
    assertEquals(404, ((Refused<VolumeEnvelope>) client.volume(OWNER, "qits-ci-cache")).status());
  }

  // --- the answer a running service cannot produce ----------------------------------------------------------

  @Test
  void aServiceThatIsNotThereIsUnreachableAndNotARefusal() {
    // Port 1 is reserved and nothing binds it. Asserted HERE as well as in the client's own suite
    // because this is the test that has a REAL service beside it: the same client, the same code
    // path, one address that answers and one that does not, and the two do not produce the same
    // answer. Merging them is what cost real events on 2026-08-10 — and here it would cost a second
    // container, because a caller that reads "nothing answered" as "the service said no" concludes
    // its workload was never started.
    ContainersClient nowhere = client("http://127.0.0.1:1");

    ContainersAnswer<Envelope> answer = nowhere.status(OWNER, WORKLOAD, REF);
    assertInstanceOf(Unreachable.class, answer);
    assertFalse(answer.refused());
    assertNotNull(((Unreachable<Envelope>) answer).cause());

    assertTrue(client.status(OWNER, WORKLOAD, REF).refused(), "the same call, against a service");
  }

  // --- the address ------------------------------------------------------------------------------------------

  /** Scheme, host and port. No path: the client appends its own, and a base with one doubles it. */
  private String base() {
    return root.getProtocol() + "://" + root.getHost() + ":" + root.getPort();
  }

  private static ContainersClient client(String url) {
    return new ContainersClient(url, Duration.ofSeconds(20), Duration.ofSeconds(20), null);
  }
}
