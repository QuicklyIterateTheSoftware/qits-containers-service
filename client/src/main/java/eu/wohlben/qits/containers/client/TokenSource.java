package eu.wohlben.qits.containers.client;

import java.util.Optional;

/**
 * Where the machine token comes from, when there is one.
 *
 * <p><b>A seam rather than a dependency.</b> This jar mints nothing, caches nothing and knows no
 * issuer: the platform has exactly one answer to "who is calling" and it lives in qits-idp and
 * qits-auth-core. A consumer that already holds a platform token — the one audience, {@code
 * qits-platform}, which every token qits-idp mints carries — hands it over here; a consumer with
 * none returns {@link Optional#empty()} and the client sends no {@code Authorization} header at all.
 *
 * <p><b>Empty is the shipped posture, not a degraded one.</b> The service's rollout gate
 * ({@code qits.auth.machine.required}) ships off everywhere, and with it off the owner in the path
 * is trusted — network trust, no bearer, exactly as every sibling behaves today. So a consumer can
 * be wired up and cut over before it holds a token at all, and turning the gate on is a deployment
 * change on both sides rather than a code change on either.
 *
 * <p><b>It is asked per request on purpose.</b> A token expires and a client that captured one at
 * construction would hold it until the process restarted. The consumer's implementation is where
 * caching belongs, because the consumer is what knows when its token is stale.
 *
 * <p>A source that throws is a source that returned nothing: the client catches it and sends no
 * header, so a broken token endpoint fails as a 401 from the service rather than as an exception on
 * the caller's worker thread.
 */
@FunctionalInterface
public interface TokenSource {

  /** The bearer to put on the next request, or empty for none. */
  Optional<String> bearer();

  /** The no-token source, for a deployment whose gate is off. Named, so it reads as a decision. */
  static TokenSource none() {
    return Optional::empty;
  }
}
