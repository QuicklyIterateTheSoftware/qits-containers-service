package eu.wohlben.qits.containers.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * <p><b>What is being guarded.</b> Every one of these endpoints carries the environment, derived
 * from {@code QITS_ENVIRONMENT} rather than written down. The mirror used to be bare because a
 * platform-tier service was one process for the whole estate; the plane is deleted and it is an
 * ordinary environment application now. The registry never had that excuse — it was a hardcoded
 * {@code dev-}. Both are derived, and if either stops being so, this fails.
 *
 * <p>QITS_ENVIRONMENT is unset under test, so the {@code :dev} fallback is what resolves here. The
 * assertions are on the RESOLVED strings on purpose: a default that stopped deriving and was
 * hardcoded back to {@code dev-} would read identically on this suite, which is why the last test
 * asserts against the raw property text instead.
 */
class BuildkitShippedConfigTest {

  private static final String MIRRORS = "qits.containers.buildkit.registry-mirrors";
  private static final String REGISTRIES = "qits.containers.buildkit.http-registries";

  private static Config config() {
    return ConfigProvider.getConfig();
  }

  @Test
  void everyRegistryMirrorNamesATierQualifiedUpstream() {
    List<String> mirrors = config().getValues(MIRRORS, String.class);

    assertEquals(
        List.of(
            "registry.dev.localhost:8080=dev-qits-artifacts:8080",
            "mirror.dev.localhost:8080=dev-qits-platform-mirror:8080",
            "localhost:8081=dev-qits-artifacts:8080",
            "localhost:8082=dev-qits-platform-mirror:8080",
            "quay.io=dev-qits-platform-mirror:8080/quay",
            "registry.access.redhat.com=dev-qits-platform-mirror:8080/redhat",
            "docker.io=dev-qits-platform-mirror:8080/hub"),
        mirrors,
        "every upstream is <env>-<application>; a bare one resolves nowhere on qits-net and fails"
            + " every base pull with a DNS error");
  }

  @Test
  void theHttpRegistriesAreTheSameTwoEndpointsAndTierQualifiedToo() {
    assertEquals(
        List.of("dev-qits-artifacts:8080", "dev-qits-platform-mirror:8080"),
        config().getValues(REGISTRIES, String.class),
        "a plain-HTTP endpoint the builder is not told about is a TLS attempt against a listener"
            + " that speaks none");
  }

  @Test
  void theTierIsDERIVEDFromTheEnvironmentAndNotWrittenDown() {
    // The raw text, before expansion. This is the assertion the two above cannot make: they would
    // both pass against a file that had `dev-` typed into it, which is exactly the regression worth
    // catching — it works on this platform and names the wrong tier on every other one.
    String raw = rawProperty(MIRRORS);
    assertTrue(
        raw.contains("${QITS_ENVIRONMENT:dev}-qits-platform-mirror"),
        "the mirror's tier must be derived, not typed: " + raw);
    assertTrue(
        raw.contains("${QITS_ENVIRONMENT:dev}-qits-artifacts"),
        "and so must the registry's: " + raw);
    assertTrue(
        rawProperty(REGISTRIES).contains("${QITS_ENVIRONMENT:dev}-"),
        "including the http-registries list, which is the same two endpoints restated");
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
