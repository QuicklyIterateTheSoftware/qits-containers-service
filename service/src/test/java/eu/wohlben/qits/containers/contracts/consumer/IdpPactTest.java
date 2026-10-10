package eu.wohlben.qits.containers.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwk.RsaJsonWebKey;
import org.junit.jupiter.api.Test;

/**
 * <b>What this service asks qits-idp-service</b> (ticket qits-1149): the two reads quarkus-oidc makes
 * when the machine-auth gate turns the tenant on. At startup ({@code jwks.resolve-early}, retried
 * for {@code connection-delay}) it reads the discovery document from {@code auth-server-url}, then
 * the JWKS at the document's {@code jwks_uri}. It finds a key by {@code kid} and builds it from
 * {@code kty}, {@code n} and {@code e}, selected by {@code alg} and {@code use}.
 *
 * <p>The pact is {@code pacts/qits-containers-service_qits-idp-service.json}; {@code
 * -Dgolden.update=true} rewrites it.
 */
class IdpPactTest {

  static final String CONSUMER = "qits-containers-service";

  static final GoldenMasters IDP = GoldenMasters.of("qits-idp-service", "qits-idp");

  static final String STATE = "a published signing key";

  static final Trigger STARTUP = Trigger.event("StartupEvent");

  /** The discovery document: the issuer the tenant checks {@code iss} against, and where the keys are. */
  static final GoldenInteraction DISCOVERY =
      GoldenInteraction.of(STARTUP, STATE, "getOpenIdConfiguration")
          .consumes("issuer", "jwks_uri", "token_endpoint");

  /** The JWKS: at least one key, each with what quarkus-oidc needs to build and pick it. */
  static final GoldenInteraction JWKS =
      GoldenInteraction.of(STARTUP, STATE, "getJwks")
          .consumes("keys[].kid", "keys[].kty", "keys[].n", "keys[].e", "keys[].alg", "keys[].use");

  static final ConsumerPact PACT = ConsumerPact.of(CONSUMER, IDP, DISCOVERY, JWKS);

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void quarkusOidcReadsTheDiscoveryDocument() {
    PACT.run(DISCOVERY, (url, recorded) -> {
      JsonNode doc = JSON.readTree(get(url + recorded.path()));
      assertTrue(doc.path("issuer").isTextual(), "issuer: " + doc);
      URI.create(doc.path("jwks_uri").asText());
      URI.create(doc.path("token_endpoint").asText());
    });
  }

  @Test
  void quarkusOidcFindsThePublishedSigningKey() {
    PACT.run(JWKS, (url, recorded) -> {
      JsonWebKeySet keys = new JsonWebKeySet(get(url + recorded.path()));
      JsonWebKey key =
          keys.findJsonWebKey(recorded.params().get("kid"), RsaJsonWebKey.KEY_TYPE, "sig", "RS256");
      assertNotNull(key, () -> "an RSA signing key for RS256 named by the state's kid in " + keys.getJsonWebKeys());
      assertNotNull(((RsaJsonWebKey) key).getRsaPublicKey().getModulus());
    });
  }

  @Test
  void theCommittedPactIsWhatTheRowsWrite() {
    PACT.compareOrWritePactFile();
  }

  @Test
  void everyInteractionCarriesBothReferences() {
    PACT.assertEveryInteractionCarriesBothReferences();
    assertEquals(2, PACT.recorded().size(), "both rows recorded: " + PACT.unrecorded());
  }

  private static String get(String url) throws Exception {
    HttpResponse<String> answer = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json").build(),
        HttpResponse.BodyHandlers.ofString());
    assertEquals(200, answer.statusCode(), answer.body());
    return answer.body();
  }
}
