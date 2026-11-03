package eu.wohlben.qits.containers.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * THE SHIPPED BUILDKIT ENDPOINTS, ASSERTED AS THE CONFIG RESOLVES THEM.
 *
 * <p>Written because {@code PlatformBuildkitTest} cannot do this job and reads as though it can: it
 * assigns {@code registryMirrors} and {@code httpRegistries} as fixtures on a hand-built {@code
 * PlatformBuildkit}, so it exercises the rendering of whatever it was handed and says nothing at all
 * about what {@code META-INF/microprofile-config.properties} actually ships. A bare alias could be
 * restored to that file and every assertion in that suite would still pass.
 *
 * <p><b>What is being guarded</b> (qits-1409, feature qits-1406). The builder dials the edge like
 * every target's builder: the platform's registry and mirror at {@code registry.qits.<domain>} and
 * {@code mirror.qits.<domain>} over TLS, and the three bare upstream registries mapped onto the
 * mirror's edge namespaces. No endpoint names a wire alias, none carries {@code QITS_ENVIRONMENT},
 * and none is granted plain HTTP. A restored {@code <env>-qits-mirror:8080} rewrite fails here.
 *
 * <p>The assertions are on the RESOLVED strings, and the last one on the raw text, because a
 * domain typed in rather than derived would resolve identically on this suite.
 */
class BuildkitShippedConfigTest {

  private static final String MIRRORS = "qits.containers.buildkit.registry-mirrors";
  private static final String REGISTRIES = "qits.containers.buildkit.http-registries";

  /** QITS_DOMAIN carries no default in the shipped file; the suite supplies one — see pom.xml. */
  private static final String DOMAIN = "wohlben.eu";

  private static Config config() {
    return ConfigProvider.getConfig();
  }

  @Test
  void everyRegistryMirrorNamesTheMirrorsEdgeHost() {
    List<String> mirrors = config().getValues(MIRRORS, String.class);

    assertEquals(
        List.of(
            "quay.io=mirror.qits." + DOMAIN + "/quay",
            "registry.access.redhat.com=mirror.qits." + DOMAIN + "/redhat",
            "docker.io=mirror.qits." + DOMAIN + "/hub"),
        mirrors,
        "every upstream maps onto the mirror's edge host; the platform's own two names need no"
            + " entry, they are pulled from directly");
  }

  @Test
  void noPlainHttpRegistryIsShipped() {
    assertTrue(
        config().getOptionalValues(REGISTRIES, String.class).isEmpty(),
        "the edge speaks TLS: a plain-HTTP grant could only name an in-network alias again");
  }

  @Test
  void theDomainIsDERIVEDAndNoAliasOrTierIsWrittenDown() {
    String raw = rawProperty(MIRRORS);
    assertTrue(
        raw.contains("mirror.qits.${QITS_DOMAIN}/"), "the domain must be derived, not typed: " + raw);
    assertFalse(raw.contains("QITS_ENVIRONMENT"), "no peer address carries the tier: " + raw);
    assertFalse(raw.contains(":8080"), "no wire alias: " + raw);
  }

  /** One property as the shipped file spells it, unexpanded. */
  private static String rawProperty(String key) {
    // Every copy on the classpath, not the first: a dependency ships one of these files too, and
    // reading whichever happens to come first is how this test would silently start asserting about
    // somebody else's configuration.
    try {
      var urls =
          BuildkitShippedConfigTest.class
              .getClassLoader()
              .getResources("META-INF/microprofile-config.properties");
      while (urls.hasMoreElements()) {
        try (var stream = urls.nextElement().openStream()) {
          var line =
              new String(stream.readAllBytes())
                  .lines()
                  .filter(candidate -> candidate.startsWith(key + "="))
                  .findFirst();
          if (line.isPresent()) {
            return line.get();
          }
        }
      }
      throw new IllegalStateException("no shipped microprofile-config.properties defines " + key);
    } catch (java.io.IOException failure) {
      throw new IllegalStateException(failure);
    }
  }
}
