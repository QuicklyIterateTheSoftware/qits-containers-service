package eu.wohlben.qits.containers.contracts.consumer;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * <b>The completeness guard for ticket qits-1149</b>: a client call against a qits service with no
 * pact row must fail a build, and the set of calls comes from the code, not from a literal.
 *
 * <p>qits-containers-service builds no client of a qits service. Its one outbound qits call is the
 * one the framework makes: the quarkus-oidc tenant's discovery read and the JWKS read its {@code
 * jwks_uri} names, both rows of {@link IdpPactTest#PACT}. This class pins that claim to the build:
 *
 * <ul>
 *   <li>no production class reaches a way to dial out — {@code java.net.http.HttpClient}, {@code
 *       HttpURLConnection}, a Vert.x {@code WebClient} or {@code HttpClient}, a MicroProfile REST
 *       client, or {@code quarkus-oidc-client}'s {@code OidcClient} — outside the named exclusions;
 *   <li>there is no client-credentials mint ({@code quarkus-oidc-client} is not on the classpath);
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
  void thereIsNoClientCredentialsMintToPact() {
    assertThrows(
        ClassNotFoundException.class,
        () -> Class.forName("io.quarkus.oidc.client.OidcClient"),
        "quarkus-oidc-client is on the classpath: the POST /idp/token mint needs a row against"
            + " qits-idp's 'a service client calling its peers'");
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
    Set<String> pacted =
        IdpPactTest.PACT.recorded().stream()
            .map(GoldenInteraction::operationId)
            .collect(Collectors.toSet());
    boolean discovery =
        Boolean.parseBoolean(properties.getProperty("quarkus.oidc.discovery-enabled", "true"));
    assertEquals(
        discovery ? Set.of("getOpenIdConfiguration", "getJwks") : Set.of("getJwks"),
        pacted,
        "the oidc tenant's reads of " + server + " against the recorded idp rows");
  }
}
