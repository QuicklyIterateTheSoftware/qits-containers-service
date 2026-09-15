package eu.wohlben.qits.containers.api;

import io.smallrye.jwt.build.Jwt;
import io.smallrye.jwt.util.KeyUtils;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.Set;

/**
 * The test issuer: mints the RS256 tokens qits-idp will mint in production, signed with the key
 * pair in {@code src/test/resources}. {@link MachineGuardProfile} hands the public half to
 * quarkus-oidc, so a token made here is validated by the real extension — signature, issuer,
 * audience — rather than by a fake identity slipped past it.
 *
 * <p>The shape is the contract's: {@code iss} is the configured issuer, {@code sub} is the client
 * id, {@code aud} is a JSON <b>array</b> of target service ids, and {@code groups} carries the
 * machine roles the idp copies from the client's own {@code qits.idp.client.<id>.roles}. All four
 * are what the idp emits and therefore what this service must accept.
 *
 * <p><b>{@code sub} matters more here than in any sibling.</b> Everywhere else the subject is
 * incidental and a structured claim decides what a token covers; this service's rows are owned by
 * the subject itself, whole, environment prefix and all.
 *
 * <p><b>{@code groups} is not decoration either.</b> quarkus-oidc reads that claim as the identity's
 * roles with no configuration at all, which is what lets a machine caller satisfy the {@code
 * @RolesAllowed("qits:system")} every route of this service carries. A token minted without it
 * authenticates perfectly and is then refused 403 — the shape a client granted no roles has, and the
 * reason {@link #rolelessToken} exists to assert it.
 */
final class MachineTokens {

  static final String SIGNING_KEY = "/machine-token-signing-key.pem";

  static final String VERIFICATION_KEY = "/machine-token-verification-key.pem";

  /** The issuer this service is configured against — see quarkus.oidc.auth-server-url. */
  static final String ISSUER = "http://qits-platform-idp:8080/idp";

  /**
   * The coarse machine role qits-idp grants every platform service client — the shipped {@code
   * qits.idp.client.<id>.roles} of qits-ci, qits-workspaces, qits-projects and qits-artifacts alike,
   * and the one every route here asks for.
   */
  static final Set<String> SYSTEM_ROLES = Set.of("qits:system");

  /**
   * A token from {@code clientId}, addressed to {@code audiences}, carrying the machine role a
   * platform service client holds. Valid for five minutes.
   */
  static String token(String clientId, String... audiences) {
    return token(clientId, SYSTEM_ROLES, audiences);
  }

  /**
   * A correctly signed token from a client that was granted no roles — an id added to {@code
   * qits.idp.clients} with no {@code .roles} line beside it. It authenticates and covers nothing.
   */
  static String rolelessToken(String clientId, String... audiences) {
    return token(clientId, Set.of(), audiences);
  }

  /** An agent's token: its own role, {@code qits:agent}, and none of its owner's. */
  static String agentToken(String clientId, String... audiences) {
    return token(clientId, Set.of("qits:agent"), audiences);
  }

  private static String token(String clientId, Set<String> roles, String... audiences) {
    return Jwt.claims()
        .issuer(ISSUER)
        .subject(clientId)
        .audience(Set.of(audiences))
        .groups(roles)
        .expiresIn(Duration.ofMinutes(5))
        .jws()
        .sign(privateKey());
  }

  /** The PEM's contents, for a caller that needs the key material rather than a token. */
  static String pem(String resource) {
    try (var in = MachineTokens.class.getResourceAsStream(resource)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Missing test key " + resource, e);
    }
  }

  private static PrivateKey privateKey() {
    try {
      return KeyUtils.decodePrivateKey(pem(SIGNING_KEY));
    } catch (Exception e) {
      throw new IllegalStateException("Cannot read the test signing key", e);
    }
  }

  private MachineTokens() {}
}
