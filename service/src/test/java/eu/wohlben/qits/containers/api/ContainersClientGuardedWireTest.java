package eu.wohlben.qits.containers.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.client.ContainersAnswer;
import eu.wohlben.qits.containers.client.ContainersAnswer.Refused;
import eu.wohlben.qits.containers.client.ContainersClient;
import eu.wohlben.qits.containers.client.ContainersWire.EnsureRequest;
import eu.wohlben.qits.containers.client.ContainersWire.Envelope;
import eu.wohlben.qits.containers.client.ContainersWire.Policy;
import eu.wohlben.qits.containers.client.ContainersWire.Spec;
import eu.wohlben.qits.containers.client.TokenSource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.net.URL;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The client's {@link TokenSource} against the gate ON — the posture a deployment reaches by
 * setting {@code QITS_AUTH_MACHINE_REQUIRED=true}.
 *
 * <p><b>Nothing is faked between the two halves.</b> The token is real and RS256-signed, quarkus-oidc
 * validates it against the public key in {@link MachineGuardProfile}, and {@link OwnerGuard}
 * compares its subject against the owner in the path the client built. So this file is what says the
 * seam actually works end to end — that a consumer which implements {@code TokenSource} needs no
 * further wiring, and that the client's own encoding of the owner reaches the guard intact.
 *
 * <p><b>Both refusals are {@code Refused} and neither is {@code Unreachable}</b>, which is the
 * property worth pinning here rather than the status codes: a 401 and a 403 are the service
 * answering. A consumer that treated an authorization failure as an unreachable orchestrator would
 * retry it forever and never see the misconfiguration.
 *
 * <p>What the gate OFF looks like is {@link ContainersClientWireTest}, which is the shipped default
 * and every other test in this suite.
 *
 * <p><b>The ref is this class's own, and that is not decoration.</b> Two test classes sharing a
 * profile share one Quarkus instance and therefore one database, with no restart and no wipe between
 * them — so a place named by both is a row the second one finds already there. Measured, on the
 * first full run of this file: it took {@link MachineGuardTest}'s 201 down to a 200. Nothing here
 * asserts {@code created()} either, so this class is independent of the order it runs in.
 */
@QuarkusTest
@TestProfile(MachineGuardProfile.class)
class ContainersClientGuardedWireTest {

  /** The caller, and therefore the owner of every row it may reach. */
  private static final String OWNER = "dev-qits-ci";

  /** Another platform module, with a perfectly good token of its own. */
  private static final String OTHER_OWNER = "dev-qits-workspaces";

  /** The one platform audience, which every token qits-idp mints carries. */
  private static final String AUDIENCE = "qits-platform";

  private static final EnsureRequest ENSURE =
      EnsureRequest.of(Spec.of("alpine:3", "qits-net"), Policy.explicitLifetime());

  @TestHTTPResource URL root;

  @Test
  void aTokenSourceIsAllAConsumerWiresUp() {
    ContainersClient client = client(() -> Optional.of(MachineTokens.token(OWNER, AUDIENCE)));

    ContainersAnswer<Envelope> answer = client.ensure(OWNER, "step", "client-guarded", ENSURE);

    assertTrue(answer.succeeded(), answer.detail());
    assertTrue(client.status(OWNER, "step", "client-guarded").succeeded());
    assertTrue(client.list(OWNER).succeeded(), "reads are guarded here too, and pass the same way");
  }

  @Test
  void noTokenIsAFourOhOneRefusalAndNotAnUnreachableService() {
    ContainersClient client = client(TokenSource.none());

    ContainersAnswer<Envelope> answer = client.status(OWNER, "step", "client-guarded");

    assertTrue(answer.refused());
    assertFalse(answer.unreachable());
    assertEquals(401, ((Refused<Envelope>) answer).status());
  }

  @Test
  void aTokenThatIsNotThisOwnerIsAFourOhThreeRefusal() {
    // A valid token for this service, held by another module, addressing this owner's rows. The
    // owner IS the caller here, which is the one guard no sibling service makes.
    ContainersClient client = client(() -> Optional.of(MachineTokens.token(OTHER_OWNER, AUDIENCE)));

    ContainersAnswer<Envelope> answer = client.status(OWNER, "step", "client-guarded");

    assertTrue(answer.refused());
    assertEquals(403, ((Refused<Envelope>) answer).status());
  }

  private ContainersClient client(TokenSource tokens) {
    String base = root.getProtocol() + "://" + root.getHost() + ":" + root.getPort();
    return new ContainersClient(base, Duration.ofSeconds(20), Duration.ofSeconds(20), tokens);
  }
}
