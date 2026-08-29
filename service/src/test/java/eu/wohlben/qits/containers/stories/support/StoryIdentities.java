package eu.wohlben.qits.containers.stories.support;

import eu.wohlben.qits.servicemock.idp.MockIdp;
import io.restassured.specification.RequestSpecification;

/**
 * The identities qits-containers accepts, and there is exactly one kind: <b>a machine's bearer</b>.
 *
 * <p>That is the whole difference between this service and its siblings, and it is why there is no
 * {@code person(...)} helper here to match qits-configuration's. Every route is guarded, reads
 * included: nothing here is read by a person, and a row says which containers another module has
 * running. So a story presenting the platform edge's {@code X-Qits-User} pair would be documenting a
 * door that does not exist — it would be refused 401 like any other anonymous request.
 *
 * <p><b>The guard is two halves and both matter to a story.</b> The outer one is {@code
 * @RolesAllowed("qits:system")}, the fleet's coarse machine role, which qits-platform-idp copies
 * from a client's granted roles into the token's {@code groups} claim and quarkus-oidc reads as
 * roles with no configuration at all. The inner one is {@code api/OwnerGuard}: the owner in the path
 * compared against the token's <b>subject, whole</b>. So a token is not merely valid or invalid here
 * — it is valid <i>for one owner</i>, and {@link #machine(RequestSpecification, String)} takes that
 * owner as its subject precisely so a story cannot accidentally present a credential that opens
 * everything.
 *
 * <p><b>The synthetic {@code %test} dev user is not available here, and that is the point.</b>
 * qits-auth-core's dev identity holds every platform role and is {@code LaunchMode}-guarded, while a
 * launched artifact runs in {@code NORMAL} mode — so an anonymous request really is anonymous and
 * the tokens below are the only thing opening these doors. Every refusal in {@code stories.refusals}
 * is a claim only a packaged run with the gate on can make.
 *
 * <p>Minting is local crypto against the keypair {@link MockIdp} parked at startup: it makes no
 * request to the mock at all, which is why no story's diagram carries an arrow for getting a token.
 */
public final class StoryIdentities {

  /**
   * The audience this service enforces, and it is a LITERAL rather than a variable name.
   * {@code qits.auth.machine.audience=qits-containers} is spelled out in {@code
   * application.properties} — the default stays the bare name so an environment-qualified one is not
   * baked into an image every tier shares — so the audience under test is the shipped one and there
   * is no expression to feed. {@code quarkus.oidc.token.audience=${qits.auth.machine.audience}} is
   * what carries it to quarkus-oidc, so minting against this string is also what proves that
   * indirection is read.
   */
  public static final String AUDIENCE = "qits-containers";

  /** The coarse machine role every route of this service demands. */
  public static final String MACHINE_ROLE = "qits:system";

  /**
   * The consumer this catalogue is mostly told from: qits-ci, which PUTs an ensure for every build
   * step and DELETEs it when the step is over. Unprefixed, like the audience — a deployed tier mints
   * {@code dev-qits-ci} presenting {@code dev-qits-containers}, and {@code MachineGuardTest} is
   * where that prefix is pinned, since it is the half OwnerGuard argues about rather than the half a
   * story exercises.
   */
  public static final String CI = "qits-ci";

  /** Another platform module with a perfectly good token of its own, and rows that are not qits-ci's. */
  public static final String WORKSPACES = "qits-workspaces";

  /**
   * The one caller that owns nothing: the orchestrator, which collects the host's images, dangling
   * volumes and build cache on a schedule. Its subject is nobody's owner, which is exactly why the
   * four {@code gc/} routes carry the machine role and deliberately not {@link
   * eu.wohlben.qits.containers.api.OwnerGuard}.
   */
  public static final String ORCHESTRATOR = "qits-platform-orchestrator";

  private StoryIdentities() {}

  /**
   * A platform peer's bearer, minted for one owner.
   *
   * <p>Minted fresh per call rather than cached: a token is a credential, and a helper that handed
   * the same string to two stories would make {@link
   * eu.wohlben.qits.userflows.report.ReportAssertions#assertNotLeaked} a weaker claim than it reads
   * as.
   */
  public static String token(String subject) {
    return MockIdp.attach()
        .token()
        .subject(subject)
        .audience(AUDIENCE)
        .groups(MACHINE_ROLE)
        .mint();
  }

  /**
   * A token this service will authenticate and then refuse: the right issuer, the right signature,
   * the right audience — and no roles at all.
   *
   * <p>It is the door a grant that was never made shuts, and it is 403 rather than 401 because the
   * credential <em>did</em> become an identity. Knowing which of the three doors closed is how a
   * missing grant is debugged.
   */
  public static String rolelessToken(String subject) {
    return MockIdp.attach().token().subject(subject).audience(AUDIENCE).mint();
  }

  /** A token minted for a real sibling's audience — the confusion that could happen on qits-net. */
  public static String foreignAudienceToken(String subject) {
    return MockIdp.attach()
        .token()
        .subject(subject)
        .audience("qits-githost")
        .groups(MACHINE_ROLE)
        .mint();
  }

  /** {@code given()} with one owner's bearer on it. */
  public static RequestSpecification machine(RequestSpecification request, String subject) {
    return bearer(request, token(subject));
  }

  /** {@code given()} with a bearer a story minted itself and wants to assert about afterwards. */
  public static RequestSpecification bearer(RequestSpecification request, String token) {
    return request.header("Authorization", "Bearer " + token);
  }
}
