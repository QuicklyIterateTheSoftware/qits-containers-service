package eu.wohlben.qits.containers.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.client.ContainersWire.EnsureRequest;
import eu.wohlben.qits.containers.client.ContainersWire.Policy;
import eu.wohlben.qits.containers.client.ContainersWire.Recreate;
import eu.wohlben.qits.containers.client.ContainersWire.Spec;
import eu.wohlben.qits.eventstream.CausationHeader;
import eu.wohlben.qits.eventstream.CausationScope;
import eu.wohlben.qits.http.QitsHttpClients;
import io.opentelemetry.api.OpenTelemetry;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What actually leaves this client: the address, the method, the query and the headers.
 *
 * <p>The stub answers a bland 200 to everything; nothing here is about the answer. What is under
 * test is the half of the contract the service cannot check for a caller — an address one segment
 * wrong is a 404 or, worse, a 200 about a different place.
 */
class ContainersClientRequestTest {

  private static final EnsureRequest ENSURE =
      new EnsureRequest(
          Spec.of("alpine:3", "qits-net"), Policy.idleStop(3600L), Recreate.ifChanged);

  private StubContainersServer stub;

  private ContainersClient client;

  @BeforeEach
  void start() throws IOException {
    stub = new StubContainersServer();
    client =
        new ContainersClient(
            stub.url(),
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            () -> Optional.of("test-machine-token"));
  }

  @AfterEach
  void stop() {
    stub.close();
  }

  // --- the addresses -------------------------------------------------------------------------------

  @Test
  void everyRouteAddressesThePathTheServiceServes() {
    client.ensure("qits-ci", "step", "run-1", ENSURE);
    assertEquals("PUT", stub.last().method());
    assertEquals("/containers/api/containers/qits-ci/step/run-1", stub.last().path());

    client.status("qits-ci", "step", "run-1");
    assertEquals("GET", stub.last().method());
    assertEquals("/containers/api/containers/qits-ci/step/run-1", stub.last().path());

    client.list("qits-ci");
    assertEquals("/containers/api/containers/qits-ci", stub.last().path());

    client.list("qits-ci", "step");
    assertEquals("/containers/api/containers/qits-ci/step", stub.last().path());

    // A blank workload is the owner listing, not an address with an empty segment in it.
    client.list("qits-ci", "  ");
    assertEquals("/containers/api/containers/qits-ci", stub.last().path());

    client.stop("qits-ci", "step", "run-1");
    assertEquals("POST", stub.last().method());
    assertEquals("/containers/api/containers/qits-ci/step/run-1/stop", stub.last().path());

    client.touch("qits-ci", "step", "run-1");
    assertEquals("POST", stub.last().method());
    assertEquals("/containers/api/containers/qits-ci/step/run-1/touch", stub.last().path());

    client.logs("qits-ci", "step", "run-1", 200);
    assertEquals("GET", stub.last().method());
    assertEquals("/containers/api/containers/qits-ci/step/run-1/logs", stub.last().path());
    assertEquals("tail=200", stub.last().query());

    client.delete("qits-ci", "step", "run-1", true, true);
    assertEquals("DELETE", stub.last().method());
    assertEquals("/containers/api/containers/qits-ci/step/run-1", stub.last().path());
    assertEquals("volumes=true&logs=true", stub.last().query());

    client.destroyAll("qits-ci", "step", Instant.parse("2026-08-11T09:00:00Z"));
    assertEquals("DELETE", stub.last().method());
    assertEquals("/containers/api/containers/qits-ci/step", stub.last().path());
    assertEquals("createdBefore=2026-08-11T09%3A00%3A00Z", stub.last().query());

    client.ensureVolume("qits-ci", "qits-ci-cache");
    assertEquals("PUT", stub.last().method());
    assertEquals("/containers/api/volumes/qits-ci/qits-ci-cache", stub.last().path());

    client.volume("qits-ci", "qits-ci-cache");
    assertEquals("GET", stub.last().method());

    client.deleteVolume("qits-ci", "qits-ci-cache");
    assertEquals("DELETE", stub.last().method());
    assertEquals("/containers/api/volumes/qits-ci/qits-ci-cache", stub.last().path());
  }

  @Test
  void theBaseUrlCarriesNoPathAndATrailingSlashIsToleratedRatherThanDoubled() {
    ContainersClient slashed =
        new ContainersClient(
            stub.url() + "/",
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            () -> Optional.of("test-machine-token"));
    slashed.status("qits-ci", "step", "run-1");

    assertEquals("/containers/api/containers/qits-ci/step/run-1", stub.last().path());
  }

  @Test
  void aSegmentCannotForgeAPath() {
    // The service's own belts refuse this value with a 400 naming the field, which is where a
    // refusal belongs. What must not happen on the way there is the slash addressing another route.
    client.status("qits-ci", "step", "run/../../volumes/other");

    assertEquals(
        "/containers/api/containers/qits-ci/step/run%2F..%2F..%2Fvolumes%2Fother",
        stub.last().path());
  }

  @Test
  void aDestroyAllWithNoInstantIsRefusedBeforeAnythingIsSent() {
    // A caller that reached this method with no instant has a bug, not an outage — and a default
    // "now" would turn a boot reap into a purge of everything the owner has running.
    assertThrows(
        IllegalArgumentException.class, () -> client.destroyAll("qits-ci", "step", null));
    assertTrue(stub.received().isEmpty());
  }

  // --- the body ---------------------------------------------------------------------------------------

  @Test
  void anEnsureBodyCarriesWhatWasSetAndOmitsWhatWasNot() {
    client.ensure("qits-ci", "step", "run-1", ENSURE);
    String body = stub.last().body();

    assertTrue(body.contains("\"image\":\"alpine:3\""));
    assertTrue(body.contains("\"network\":\"qits-net\""));
    assertTrue(body.contains("\"type\":\"IDLE_STOP\""));
    assertTrue(body.contains("\"idleAfterSeconds\":3600"));
    assertTrue(body.contains("\"recreate\":\"ifChanged\""));
    assertTrue(
        !body.contains("\"entrypoint\""),
        "an unset field is omitted, not sent as null: " + body);
    assertEquals("application/json", stub.last().header("Content-Type"));

    // init is nullable for this reason: the field is omitted entirely, so the body a caller that
    // never heard of tini sends is byte for byte the body it sent before the field existed.
    assertTrue(!body.contains("\"init\""), body);
  }

  @Test
  void anAskForTiniIsOnTheBodyAndNothingElseMoves() {
    client.ensure(
        "qits-ci",
        "step",
        "run-1",
        new EnsureRequest(
            Spec.of("alpine:3", "qits-net").withInit(true),
            Policy.idleStop(3600L),
            Recreate.ifChanged));
    String body = stub.last().body();

    assertTrue(body.contains("\"init\":true"), body);
    assertTrue(body.contains("\"image\":\"alpine:3\""), body);
    assertTrue(body.contains("\"network\":\"qits-net\""), body);
  }

  // --- the headers -------------------------------------------------------------------------------------

  @Test
  void aTokenSourceWithATokenPutsItOnEveryRequest() throws IOException {
    ContainersClient guarded =
        new ContainersClient(
            stub.url(),
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            () -> Optional.of("a-machine-token"));

    guarded.status("qits-ci", "step", "run-1");
    assertEquals("Bearer a-machine-token", stub.last().header("Authorization"));

    guarded.ensure("qits-ci", "step", "run-1", ENSURE);
    assertEquals("Bearer a-machine-token", stub.last().header("Authorization"));

    guarded.deleteVolume("qits-ci", "cache");
    assertEquals("Bearer a-machine-token", stub.last().header("Authorization"));
  }

  @Test
  void noTokenSourceAndAnEmptyOrBlankOneSendNoHeaderAtAll() {
    // The call still goes out. Every route of the service carries @RolesAllowed, so a bare request
    // comes back 401 — one of the four answers, naming the real problem — rather than an exception
    // on the caller's worker thread.
    ContainersClient nothing =
        new ContainersClient(stub.url(), Duration.ofSeconds(5), Duration.ofSeconds(5), null);
    nothing.status("qits-ci", "step", "run-1");
    assertNull(stub.last().header("Authorization"));

    ContainersClient none =
        new ContainersClient(
            stub.url(), Duration.ofSeconds(5), Duration.ofSeconds(5), TokenSource.none());
    none.status("qits-ci", "step", "run-1");
    assertNull(stub.last().header("Authorization"));

    // A blank token is an absent one, not a header saying "Bearer ".
    ContainersClient blank =
        new ContainersClient(
            stub.url(), Duration.ofSeconds(5), Duration.ofSeconds(5), () -> Optional.of("  "));
    blank.status("qits-ci", "step", "run-1");
    assertNull(stub.last().header("Authorization"));
  }

  @Test
  void aTokenSourceThatThrowsCostsTheHeaderAndNotTheCall() {
    // A 401 from the service is a better failure than an exception on the caller's worker thread:
    // it is reportable, it is one of the four answers, and it names the real problem.
    ContainersClient broken =
        new ContainersClient(
            stub.url(),
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            () -> {
              throw new IllegalStateException("no idp");
            });

    assertTrue(broken.status("qits-ci", "step", "run-1").succeeded());
    assertNull(stub.last().header("Authorization"));
  }

  @Test
  void theAmbientCauseIsStampedAndAnAbsentOneStampsNothing() {
    UUID cause = UUID.fromString("11111111-2222-4333-8444-555555555555");

    CausationScope.with(cause, () -> client.ensure("qits-ci", "step", "run-1", ENSURE));
    assertEquals(cause.toString(), stub.last().header(CausationHeader.NAME));

    // Outside a scope there is no cause, and an absent header is what "no cause" is on the wire.
    // Never a header with an empty value, which the far side would have to parse to reject.
    client.ensure("qits-ci", "step", "run-1", ENSURE);
    assertNull(stub.last().header(CausationHeader.NAME));

    // The client leaves the thread as it found it — CausationScope's own guarantee, asserted here
    // because this client is a caller of it and not only a reader.
    assertNull(CausationScope.current());
  }

  // --- the pin ------------------------------------------------------------------------------------------

  @Test
  void theHttpClientIsPinnedToHttpOneOne() {
    // ASSERTED ON THE CLIENT'S CONFIGURATION, NOT ON A RESPONSE, and that choice is the honest one:
    // HttpResponse.version() reports what the two ends NEGOTIATED, and every server this client
    // meets — the stub above, Quarkus on qits-net — speaks HTTP/1.1, so an unpinned client would
    // report 1.1 there too and the assertion would pass with the version(...) line deleted. The pin
    // is a property of this object, so this is where it can be seen.
    //
    // What it costs to lose: the JDK default is HTTP/2 with an h2c upgrade, and an upgrade carrying
    // a request body delivers that body twice — measured in qits-eventstream against its stub. Here
    // the doubled request would be a PUT ensure, racing itself through a registry that is writing a
    // row and calling docker between transactions.
    assertEquals(HttpClient.Version.HTTP_1_1, client.http.version());
  }

  @Test
  void theConnectTimeoutIsOnTheClientRatherThanOnACall() {
    // Two seconds, in code, because it belongs to the HttpClient instance and not to a request.
    assertEquals(Optional.of(Duration.ofSeconds(2)), client.http.connectTimeout());
  }

  @Test
  void aNullQitsHttpClientsLeavesTheClientExactlyAsTheFourArgConstructorDoes() {
    ContainersClient unwrapped =
        new ContainersClient(
            stub.url(), Duration.ofSeconds(5), Duration.ofSeconds(5), null, null);
    assertEquals(HttpClient.Version.HTTP_1_1, unwrapped.http.version());
  }

  @Test
  void aQitsHttpClientsWrapsTheClientAndKeepsThePin() {
    // The fifth, optional constructor argument (qits-1198): wrapping must not cost the HTTP/1.1
    // pin, which is why QitsHttpClients#wrap delegates version() to the client it wraps.
    ContainersClient wrapped =
        new ContainersClient(
            stub.url(),
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            null,
            QitsHttpClients.of(OpenTelemetry.noop(), "qits-containers-client-test"));
    assertEquals(HttpClient.Version.HTTP_1_1, wrapped.http.version());
    assertEquals(Optional.of(Duration.ofSeconds(2)), wrapped.http.connectTimeout());
  }

  @Test
  void aClientWithNoAddressOrAnUnusableDeadlineRefusesToBeBuilt() {
    Duration ok = Duration.ofSeconds(5);
    assertThrows(IllegalArgumentException.class, () -> new ContainersClient(null, ok, ok, null));
    assertThrows(IllegalArgumentException.class, () -> new ContainersClient("  ", ok, ok, null));
    assertThrows(
        IllegalArgumentException.class, () -> new ContainersClient("http://x:8080", null, ok, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ContainersClient("http://x:8080", ok, Duration.ZERO, null));
  }
}
