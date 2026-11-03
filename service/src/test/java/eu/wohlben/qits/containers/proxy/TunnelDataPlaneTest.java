package eu.wohlben.qits.containers.proxy;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.control.FakeContainersDriver;
import eu.wohlben.qits.containers.persistence.CtContainerRepository;
import eu.wohlben.qits.containers.persistence.CtVolumeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The data plane switched on, driven by a container daemon that never leaves this JVM. No docker,
 * no image, no daemon binary: the host cannot tell {@link FakeContainerDaemon} from a container, so
 * the whole byte path is provable in an ordinary {@code ./mvnw verify}.
 *
 * <p>The round trip below is the claim the whole work package exists to make, end to end and in one
 * method because it is one event: a caller connects to a loopback port this service bound, the
 * connection is parked, the container is asked to come and collect it by nonce, it dials the stream
 * path back, the two are married into a byte pipe, and the container's own HTTP answer comes out of
 * the caller's {@code HttpClient}. Every one of those steps is the failure mode of the one before
 * it, which is why splitting them would only assert the seams.
 */
@QuarkusTest
// The caller is stated, not inherited from forward-auth's %test dev user (qits-1361): past the
// socket's role check, so what is measured is the secret and the row behind it.
@TestSecurity(user = "qits-ci", roles = "qits:system")
@TestProfile(TunnelEnabledProfile.class)
class TunnelDataPlaneTest {

  private static final Duration SOON = Duration.ofSeconds(10);

  private static final String PLACE = "/containers/api/containers/qits-ci/step/tunnel-1";

  private static final String EXPLICIT =
      """
      {"spec":{"image":"alpine:3","network":"qits-net","args":["sleep","infinity"]},
       "policy":{"type":"EXPLICIT"},"recreate":"never"}""";

  /** What the fake container answers with — a whole HTTP/1.1 response, because the pipe is bytes. */
  private static final String BODY = "hello from inside";

  private static final String CANNED =
      "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: "
          + BODY.getBytes(StandardCharsets.UTF_8).length
          + "\r\n\r\n"
          + BODY;

  @Inject ContainerTunnels tunnels;

  @Inject FakeContainersDriver driver;

  @Inject CtContainerRepository containers;

  @Inject CtVolumeRepository volumes;

  @TestHTTPResource("/")
  URI base;

  @BeforeEach
  void wipe() {
    driver.reset();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              containers.deleteAll();
              volumes.deleteAll();
            });
  }

  @Test
  void aRequestReachesTheContainerThroughTheTunnelAndTheAnswerComesBack() throws Exception {
    UUID rowId = ensurePlace();
    String secret = tunnels.issueSecret(rowId).orElseThrow();

    try (FakeContainerDaemon daemon = FakeContainerDaemon.dial(control(rowId), secret)) {
      daemon.answerWith(CANNED);
      daemon.sayHello(TunnelProtocol.CAPABILITY_VERSION);

      // The hello is what raises the capability, and it travels asynchronously — so the origin is
      // awaited rather than assumed. Before it arrives this row is correctly unreachable.
      ContainerTunnels.ProxyOrigin origin = awaitOrigin(rowId);

      assertEquals(BODY, getThrough(origin, "/ping"), "the container's own answer, byte for byte");

      String request = daemon.nextRequest(SOON);
      assertNotNull(request, "the request bytes must have reached the container");
      assertTrue(
          request.startsWith("GET /ping "),
          "the tunnel carries bytes and rewrites no path: " + request);

      JsonObject ask = firstOpenStream(daemon);
      assertNotNull(ask, "the host asks its container to come and collect the parked connection");
      String nonce = ask.getString(TunnelProtocol.Field.NONCE);
      assertEquals(TunnelProtocol.STREAM_PATH_PREFIX + nonce, ask.getString(TunnelProtocol.Field.PATH));

      // Claimed by the dial-back that just carried the request, so it is spent. A replay is a bare
      // 404 that says nothing about which of "unknown" and "already used" it was.
      given().when().get(TunnelProtocol.STREAM_PATH_PREFIX + nonce).then().statusCode(404);
    }
  }

  /**
   * The claim is an atomic map removal, which is what makes single-use structural rather than a rule
   * someone has to remember.
   *
   * <p>Staged without a dial-back at all — the fake is told to record the ask and never come — so
   * the nonce is still parked when the claims are made. A plain socket is enough to park one: what
   * the loopback listener accepts is a TCP connection, and nothing about the nonce depends on the
   * bytes that follow.
   */
  @Test
  void aNonceIsClaimableExactlyOnce() throws Exception {
    UUID rowId = ensurePlace();
    String secret = tunnels.issueSecret(rowId).orElseThrow();

    try (FakeContainerDaemon daemon = FakeContainerDaemon.dial(control(rowId), secret)) {
      daemon.answerWith(null);
      daemon.sayHello(TunnelProtocol.CAPABILITY_VERSION);
      ContainerTunnels.ProxyOrigin origin = awaitOrigin(rowId);

      try (Socket parked = new Socket("127.0.0.1", origin.port())) {
        OutputStream out = parked.getOutputStream();
        out.write("GET /parked HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.flush();

        JsonObject ask = firstOpenStream(daemon);
        assertNotNull(ask);
        String nonce = ask.getString(TunnelProtocol.Field.NONCE);

        assertTrue(tunnels.claim(nonce).isPresent(), "the first claim gets the parked connection");
        assertTrue(tunnels.claim(nonce).isEmpty(), "a second claim finds nothing");
        given().when().get(TunnelProtocol.STREAM_PATH_PREFIX + nonce).then().statusCode(404);
      }
    }
  }

  /**
   * The departure from what this was ported from. qits-projects' and qits-workspaces' control
   * sockets name their caller with a path parameter and check nothing, so anything on the platform
   * network can claim to be any project's daemon — a weakness both repos record and carry. This
   * contract has no container to be compatible with, so it checks.
   */
  @Test
  void aWrongSecretIsRefused() throws Exception {
    UUID rowId = ensurePlace();
    String secret = tunnels.issueSecret(rowId).orElseThrow();

    try (FakeContainerDaemon daemon = FakeContainerDaemon.dial(control(rowId), secret + "x")) {
      assertEquals(
          (Short) (short) TunnelProtocol.Close.POLICY_VIOLATION, daemon.awaitCloseCode(SOON));
      assertEquals(
          TunnelProtocol.Close.REFUSED,
          daemon.awaitCloseReason(SOON),
          "one reason for every refusal: a caller learns it was wrong, not which half it got right");
      assertTrue(tunnels.originFor(rowId).isEmpty(), "a refused dial leaves no reachable row");
    }
  }

  /**
   * A secret is not enough on its own: the row has to be one this service still names. The two
   * checks are independent, and this is the one that keeps a tunnel from outliving the place it
   * belongs to.
   */
  @Test
  void aSecretForNoLiveRowIsRefused() throws Exception {
    UUID nothing = UUID.randomUUID();
    String secret = tunnels.issueSecret(nothing).orElseThrow();

    try (FakeContainerDaemon daemon = FakeContainerDaemon.dial(control(nothing), secret)) {
      assertEquals(
          (Short) (short) TunnelProtocol.Close.POLICY_VIOLATION, daemon.awaitCloseCode(SOON));
      assertEquals(TunnelProtocol.Close.REFUSED, daemon.awaitCloseReason(SOON));
    }
  }

  /**
   * No hello, no capability, no origin — and no listener bound speculatively while it waits.
   *
   * <p>The listener count is compared against what it was rather than against zero: a live tunnel
   * deliberately survives its control connection going away, so earlier methods in this class leave
   * theirs bound and a zero here would be an assertion about test order.
   */
  @Test
  void aContainerThatHasNotSaidHelloIsNotReachable() throws Exception {
    UUID rowId = ensurePlace();
    String secret = tunnels.issueSecret(rowId).orElseThrow();
    int bound = tunnels.openTunnels();

    try (FakeContainerDaemon daemon = FakeContainerDaemon.dial(control(rowId), secret)) {
      // Admitted: the socket stays open, which is what tells this case apart from a refusal.
      assertNull(daemon.awaitCloseCode(Duration.ofMillis(500)));
      assertTrue(daemon.isOpen());
      assertTrue(tunnels.originFor(rowId).isEmpty());
      assertEquals(bound, tunnels.openTunnels());
    }
  }

  // --- staging ------------------------------------------------------------------------------------

  /** A real place through the real route, so the row the socket reads is one this service wrote. */
  private UUID ensurePlace() {
    return UUID.fromString(
        given()
            .contentType(ContentType.JSON)
            .body(EXPLICIT)
            .when()
            .put(PLACE)
            .then()
            .statusCode(201)
            .extract()
            .path("id"));
  }

  private ContainerTunnels.ProxyOrigin awaitOrigin(UUID rowId) throws InterruptedException {
    long deadline = System.nanoTime() + SOON.toNanos();
    while (System.nanoTime() < deadline) {
      ContainerTunnels.ProxyOrigin origin = tunnels.originFor(rowId).orElse(null);
      if (origin != null) {
        return origin;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("no tunnel origin for row " + rowId + " within " + SOON);
  }

  /**
   * One GET through the origin, on the client the origin handed over.
   *
   * <p>That client is not interchangeable with a shared one, and using it here is the point as much
   * as the assertion is: it belongs to this row's tunnel and is closed with it, which is what keeps
   * a reused ephemeral port from handing one owner a pooled connection into another owner's
   * container.
   */
  private String getThrough(ContainerTunnels.ProxyOrigin origin, String path) throws Exception {
    CompletableFuture<String> answered = new CompletableFuture<>();
    origin
        .client()
        .request(
            new RequestOptions()
                .setHost("127.0.0.1")
                .setPort(origin.port())
                .setURI(path)
                .setMethod(HttpMethod.GET))
        .compose(request -> request.send().compose(HttpClientResponse::body))
        .onSuccess(body -> answered.complete(body.toString(StandardCharsets.UTF_8)))
        .onFailure(answered::completeExceptionally);
    return answered.get(SOON.toMillis(), TimeUnit.MILLISECONDS);
  }

  /** The next {@code openStream} the host pushed, skipping anything else it may send later. */
  private JsonObject firstOpenStream(FakeContainerDaemon daemon) throws InterruptedException {
    for (JsonObject frame = daemon.nextFrame(SOON);
        frame != null;
        frame = daemon.nextFrame(Duration.ofMillis(200))) {
      if (TunnelProtocol.Type.OPEN_STREAM.equals(frame.getString(TunnelProtocol.Field.TYPE))) {
        return frame;
      }
    }
    return null;
  }

  private URI control(UUID rowId) {
    return URI.create(
        "http://"
            + base.getHost()
            + ":"
            + base.getPort()
            + TunnelProtocol.CONTROL_SOCKET_PATH_PREFIX
            + rowId);
  }
}
