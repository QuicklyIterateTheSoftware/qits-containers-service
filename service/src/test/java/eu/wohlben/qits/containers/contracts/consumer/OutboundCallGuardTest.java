package eu.wohlben.qits.containers.contracts.consumer;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * <b>The completeness guard for ticket qits-1149</b>: a client call against a qits service with no
 * pact row must fail a build, and the set of calls comes from the code, not from a literal.
 *
 * <p>qits-containers-service builds no client of a qits service. Its outbound qits calls are the
 * ones the frameworks make: the quarkus-oidc tenant's discovery read and the JWKS read its {@code
 * jwks_uri} names, plus the named {@code quarkus-oidc-client} {@code qits}'s client-credentials
 * mint (qits-543/qits-1330) — the token this service presents to its peers, spent by the
 * qits-eventstream library rather than by any class of this source tree. All three are rows of
 * {@link IdpPactTest#PACT}. This class pins that claim to the build:
 *
 * <ul>
 *   <li>no production class reaches a way to dial out — {@code java.net.http.HttpClient}, {@code
 *       HttpURLConnection}, a Vert.x {@code WebClient} or {@code HttpClient}, a MicroProfile REST
 *       client, or {@code quarkus-oidc-client}'s {@code OidcClient} — outside the named exclusions;
 *   <li>the named client {@code qits} is the only client-credentials mint, and its mint is rowed;
 *   <li>the tenant {@code application.properties} configures has its discovery and JWKS rows
 *       recorded in the idp pact.
 * </ul>
 *
 * <p><b>Named exclusions</b>, none of them a service-to-service REST call of this repository:
 *
 * <ul>
 *   <li>{@code proxy.ContainerTunnels} — the Vert.x {@code HttpClient} that proxies a request into a
 *       workload container over that container's own tunnel: generic proxying, not a qits API;
 *   <li>{@code eu.wohlben.qits.containers.client} — the published {@code ContainersClient}, the
 *       CALLERS' client of this service (test scope here); its rows live in qits-projects-service
 *       and qits-workspaces-service, verified by this provider;
 *   <li>the eventstream catch-up's reads of qits-events (pacted by qits-eventstream-javalib) and the
 *       OTLP telemetry export to qits-observability (an exporter, not a REST client).
 * </ul>
 */
class OutboundCallGuardTest {

  private static final Set<String> DIAL_OUT =
      Set.of(
          "java.net.http.HttpClient",
          "java.net.HttpURLConnection",
          "io.vertx.ext.web.client.WebClient",
          "io.vertx.core.http.HttpClient",
          "io.quarkus.oidc.client.OidcClient",
          "io.quarkus.oidc.client.OidcClients");

  /** The one production class allowed a Vert.x HttpClient: the tunnel proxy into a workload. */
  private static final String TUNNEL_PROXY = "eu.wohlben.qits.containers.proxy.ContainerTunnels";

  private static final String REGISTER_REST_CLIENT =
      "org.eclipse.microprofile.rest.client.inject.RegisterRestClient";

  private static final JavaClasses PRODUCTION =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages("eu.wohlben.qits.containers");

  @Test
  void noProductionClassDialsAnotherServiceWithoutAPactRow() {
    noClasses()
        .that()
        .resideOutsideOfPackage("eu.wohlben.qits.containers.client..")
        .and()
        .haveNameNotMatching(TUNNEL_PROXY.replace(".", "\\.") + "(\\$.*)?")
        .should()
        .dependOnClassesThat()
        .haveNameMatching(
            DIAL_OUT.stream().map(name -> name.replace(".", "\\.")).collect(Collectors.joining("|")))
        .orShould()
        .beAnnotatedWith(REGISTER_REST_CLIENT)
        .because(
            "an outbound call to another qits service needs a pact row against that provider's"
                + " golden masters (qits-1149) before this guard may let it through")
        .check(PRODUCTION);
  }

  @Test
  void theConfiguredOidcTenantsReadsArePacted() throws IOException {
    Properties properties = new Properties();
    // The shipped file, not the classpath's: a test resource of the same name shadows it there.
    try (InputStream in = Files.newInputStream(Path.of("src/main/resources/application.properties"))) {
      properties.load(in);
    }
    String server = properties.getProperty("quarkus.oidc.auth-server-url", "");
    assertTrue(server.contains("qits-idp"), "the tenant reads qits-idp: " + server);
    Set<String> rowed =
        IdpPactTest.PACT.rows().stream()
            .map(GoldenInteraction::operationId)
            .collect(Collectors.toSet());
    assertTrue(rowed.contains("getJwks"), "the tenant reads the JWKS: row getJwks");
    boolean discovery =
        Boolean.parseBoolean(properties.getProperty("quarkus.oidc.discovery-enabled", "true"));
    if (discovery) {
      assertTrue(
          rowed.contains("getOpenIdConfiguration"),
          "the tenant discovers: row getOpenIdConfiguration in IdpPactTest");
    } else {
      assertFalse(
          rowed.contains("getOpenIdConfiguration"),
          "discovery is off, so the tenant never reads the discovery document: drop its row");
    }
  }

  /**
   * The one token mint this service makes is the named client {@code qits}
   * (qits-543/qits-1330), and it is rowed: {@code issueToken} in {@link IdpPactTest}, run through
   * the real client by {@code IdpTokenMintPactTest}. Any other client name would be a second
   * identity nobody pacted.
   */
  @Test
  void theQitsClientIsTheOnlyMintAndItIsRowed() throws Exception {
    Properties properties = new Properties();
    try (InputStream in = Files.newInputStream(Path.of("src/main/resources/application.properties"))) {
      properties.load(in);
    }
    Class.forName("io.quarkus.oidc.client.OidcClient");
    Set<String> clientKeys =
        properties.stringPropertyNames().stream()
            .filter(name -> name.startsWith("quarkus.oidc-client."))
            .collect(Collectors.toCollection(TreeSet::new));
    assertFalse(clientKeys.isEmpty(), "the qits client is configured in application.properties");
    assertEquals(
        Set.of(),
        clientKeys.stream()
            .filter(name -> !name.startsWith("quarkus.oidc-client.qits."))
            .collect(Collectors.toSet()),
        "only the named client `qits` mints tokens here");
    assertEquals("client", properties.getProperty("quarkus.oidc-client.qits.grant.type"));
    assertEquals(
        "qits-platform",
        properties.getProperty("quarkus.oidc-client.qits.grant-options.client.audience"));
    assertEquals("token", properties.getProperty("quarkus.oidc-client.qits.token-path"));
    assertTrue(
        IdpPactTest.PACT.rows().stream().anyMatch(row -> row.operationId().equals("issueToken")),
        "the qits client mints at qits-idp: row POST /idp/token (issueToken) in IdpPactTest");
  }
}
