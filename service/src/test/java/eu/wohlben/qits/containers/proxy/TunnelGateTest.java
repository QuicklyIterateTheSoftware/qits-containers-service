package eu.wohlben.qits.containers.proxy;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.api.FakeDriverProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The shipped posture: {@code qits.containers.proxy.enabled} is false, so the data plane is designed
 * and switched off. This class is what makes "off" a measured state rather than an assumption.
 *
 * <p><b>It runs on {@link FakeDriverProfile} deliberately</b> — the profile with no config overrides
 * at all — because the claim is about the value the jar ships and not about a value a test set. A
 * profile that turned the gate off explicitly would pass just as happily against a jar shipping it
 * on.
 *
 * <p>The three cases are the three surfaces the flag has to cover, and none of them substitutes for
 * another: a socket that refuses, a route that 404s, and a bean that binds nothing.
 */
@QuarkusTest
// The caller is stated, not inherited from forward-auth's %test dev user (qits-1361): past the
// socket's role check, so what is measured is the gate behind it.
@TestSecurity(user = "qits-ci", roles = "qits:system")
@TestProfile(FakeDriverProfile.class)
class TunnelGateTest {

  private static final Duration SOON = Duration.ofSeconds(10);

  @Inject ContainerTunnels tunnels;

  @TestHTTPResource("/")
  URI base;

  /**
   * A websockets-next endpoint cannot be conditionally unregistered — it is registered at
   * augmentation because the class is on the classpath — so the honest gate is a refusal at open
   * with a reason that names the switch. That is what an operator reads in a container's log when
   * its daemon cannot dial home, and it is why this reason is the one refusal here that does not
   * hide behind the generic one.
   */
  @Test
  void theControlSocketUpgradesAndThenRefusesEveryDialNamingTheSwitch() throws Exception {
    try (FakeContainerDaemon daemon = FakeContainerDaemon.dial(control(UUID.randomUUID()), "any")) {
      assertEquals(
          (Short) (short) TunnelProtocol.Close.POLICY_VIOLATION,
          daemon.awaitCloseCode(SOON),
          "a dial with the data plane off must be closed, not left open doing nothing");
      assertEquals(TunnelProtocol.Close.DISABLED, daemon.awaitCloseReason(SOON));
    }
  }

  /** No nonce is ever minted with the gate off, so every one of them is unknown. */
  @Test
  void theDialBackRouteAnswers404() {
    given().when().get(TunnelProtocol.STREAM_PATH_PREFIX + "anything").then().statusCode(404);
  }

  /**
   * Nothing is bound and nothing is issued. The listener count is the load-bearing one: a gate that
   * refused dials while still binding a loopback port per row would be a gate in name only.
   */
  @Test
  void nothingBindsAndNoSecretIsMinted() {
    UUID row = UUID.randomUUID();
    assertTrue(tunnels.issueSecret(row).isEmpty(), "a switched-off data plane mints no credential");
    assertTrue(tunnels.originFor(row).isEmpty());
    assertEquals(0, tunnels.openTunnels(), "no loopback listener may exist with the gate off");
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
