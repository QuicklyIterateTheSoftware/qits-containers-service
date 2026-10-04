package eu.wohlben.qits.containers.api;

import eu.wohlben.qits.auth.MachineAuth;
import eu.wohlben.qits.containers.driver.spec.ContainersIdentifiers;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * The one gate every route of this service passes through: a machine token, and the owner in the
 * path being the owner the token <em>is</em>.
 *
 * <p><b>Both halves, on every route, reads included.</b> Most of the platform guards its writes and
 * leaves its reads open, because a person reads through the gateway. Nothing here is read by a
 * person: a row says which containers another module has running, and that module's inventory is
 * its own. So there is no open route and no exception to argue about later.
 *
 * <p><b>The owner is the token's subject, whole.</b> qits-idp mints a client id per environment —
 * {@code dev-qits-ci}, {@code prod-qits-ci} — and the environment prefix is precisely what keeps
 * two environments sharing one docker daemon from reaching each other's rows. Trimming it to
 * {@code qits-ci} would merge them, so the subject is compared as it arrives.
 *
 * <p><b>The subject is read off the token rather than through {@code MachineIdentity}.</b> That
 * class answers about audiences and structured claims and offers no subject accessor — measured,
 * 2026-08-11 — because every other service in the fleet guards on a claim ({@code project},
 * {@code workspace}) rather than on who is calling. This one guards on who is calling: the owner is
 * not a thing a token is granted, it is the caller's own name.
 *
 * <p><b>Gate off, the path owner is trusted.</b> {@code qits.auth.machine.required=false} is the
 * shipped default and the whole fleet's rollout posture: {@code require()} returns at once and this
 * service behaves as it does today — network trust, no bearer. The belt on the owner string still
 * runs, because that one is not about identity at all.
 */
@ApplicationScoped
public class OwnerGuard {

  @Inject MachineAuth machineAuth;

  @Inject SecurityIdentity identity;

  /**
   * Demand a machine token addressed to this service, and that it belongs to this owner.
   *
   * @return the owner, belt-checked, so a caller can use the answer rather than the argument
   * @throws IllegalArgumentException the owner is not a name this service accepts (400)
   * @throws io.quarkus.security.UnauthorizedException no machine token was presented (401)
   * @throws ForbiddenException the token is another service's, or another owner's (403)
   */
  public String require(String owner) {
    // The belt first: an owner that could not name a row is a bad request whether or not a token
    // came with it, and the message names the field.
    String checked = ContainersIdentifiers.requireOwner(owner);
    machineAuth.require();
    if (!machineAuth.enforced()) {
      return checked;
    }
    String subject = subject();
    if (subject == null || !subject.equals(checked)) {
      throw new ForbiddenException("This token does not own " + checked);
    }
    return checked;
  }

  /** The token's {@code sub}, or null when the caller is not a machine. */
  private String subject() {
    return identity != null && identity.getPrincipal() instanceof JsonWebToken jwt
        ? jwt.getSubject()
        : null;
  }
}
