package eu.wohlben.qits.containers.dockerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.control.ContainersDriver;
import eu.wohlben.qits.containers.control.FakeContainersDriver;
import eu.wohlben.qits.containers.spec.ContainerSpec;
import eu.wohlben.qits.containers.spec.ContainersIdentifiers;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The boot pass converging on the pin, and the address's one exit. Plain JUnit against the scripted
 * fake — the boot pass is {@code LaunchMode}-guarded, so a {@code @QuarkusTest} never runs it and a
 * suite that wants a claim about it drives {@code ensureOnce()} itself, exactly as the boot sweep's
 * does.
 */
public class PlatformBuildkitTest {

  private static final String PIN = "moby/buildkit:v0.33.0";

  private static final String NAME = ContainersIdentifiers.PLATFORM_BUILDER;

  private FakeContainersDriver driver;

  private PlatformBuildkit buildkit;

  @BeforeEach
  public void wire() {
    driver = new FakeContainersDriver();
    buildkit = new PlatformBuildkit();
    buildkit.driver = driver;
    buildkit.enabled = true;
    buildkit.image = PIN;
    buildkit.address = "tcp://qits-buildkitd:1234";
    buildkit.network = "qits-net";
    buildkit.registryMirrors =
        List.of(
            "registry.dev.localhost:8080=qits-platform-artifacts:8080",
            "mirror.dev.localhost:8080=qits-platform-mirror:8080");
    buildkit.httpRegistries = List.of("qits-platform-artifacts:8080", "qits-platform-mirror:8080");
    buildkit.keepStorageBytes = 20_000_000_000L;
    buildkit.pidsLimit = 4096;
    buildkit.oomScoreAdj = 500;
  }

  @Test
  public void aHostWithoutTheBuilderGetsOneVolumeFirst() {
    buildkit.ensureOnce();

    assertEquals(
        List.of(
            "ensureVolume:" + PlatformBuildkit.STATE_VOLUME,
            "inspect:" + NAME,
            "pull:" + PIN,
            "runBuildkitd:" + PIN),
        driver.calls());
  }

  @Test
  public void aRunningBuilderOnThePinIsAdoptedUntouched() {
    driver.seedContainer(
        NAME, new ContainersDriver.Observed("id-1", "running", "none", Instant.EPOCH), PIN);

    buildkit.ensureOnce();

    assertFalse(driver.calls().stream().anyMatch(c -> c.startsWith("runBuildkitd")));
    assertFalse(driver.calls().stream().anyMatch(c -> c.startsWith("remove")));
    assertFalse(driver.calls().stream().anyMatch(c -> c.startsWith("start")));
  }

  @Test
  public void aStoppedBuilderOnThePinIsStartedNotReplaced() {
    driver.seedContainer(
        NAME, new ContainersDriver.Observed("id-1", "exited", "none", Instant.EPOCH), PIN);

    buildkit.ensureOnce();

    assertTrue(driver.calls().contains("start:" + NAME));
    assertFalse(driver.calls().stream().anyMatch(c -> c.startsWith("runBuildkitd")));
  }

  @Test
  public void aBuilderOnAnotherImageIsReplacedAndTheStateVolumeRidesAcross() {
    driver.seedContainer(
        NAME,
        new ContainersDriver.Observed("id-1", "running", "none", Instant.EPOCH),
        "moby/buildkit:v0.32.0");

    buildkit.ensureOnce();

    assertEquals(
        List.of(
            "ensureVolume:" + PlatformBuildkit.STATE_VOLUME,
            "inspect:" + NAME,
            "imageOf:" + NAME,
            "stop:" + NAME,
            "remove:" + NAME,
            "pull:" + PIN,
            "runBuildkitd:" + PIN),
        driver.calls());
    // The volume was only ever ensured, never removed — the cache is the point of the replace
    // being a replace rather than a reset.
    assertFalse(driver.calls().stream().anyMatch(c -> c.startsWith("removeVolume")));
  }

  @Test
  public void aSocketHoldingSpecIsHandedTheBuildersAddress() {
    ContainerSpec spec =
        ContainerSpec.builder("qits/build-images/ci-base:latest")
            .network("qits-net")
            .hostDockerSocket(true)
            .env("QITS_CI_SHA", "cafebabe")
            .build();

    ContainerSpec handed = buildkit.handOut(spec);

    assertEquals("tcp://qits-buildkitd:1234", handed.env().get("BUILDKIT_HOST"));
    assertEquals("cafebabe", handed.env().get("QITS_CI_SHA"), "everything else rides unchanged");
  }

  @Test
  public void aCallerSentValueWinsAndEmptyIsAValue() {
    // Empty is qits-ci's off value — the kill switch's reach across the seam — and filling it in
    // here would be this service overriding a decision the caller made on purpose.
    ContainerSpec spec =
        ContainerSpec.builder("img")
            .network("qits-net")
            .hostDockerSocket(true)
            .env("BUILDKIT_HOST", "")
            .build();

    assertSame(spec, buildkit.handOut(spec));
  }

  @Test
  public void aSpecWithoutTheSocketIsNotABuildAndGetsNoAddress() {
    ContainerSpec spec = ContainerSpec.builder("img").network("qits-net").build();

    assertSame(spec, buildkit.handOut(spec));
  }

  @Test
  public void switchedOffNothingIsHandedOut() {
    buildkit.enabled = false;
    ContainerSpec spec =
        ContainerSpec.builder("img").network("qits-net").hostDockerSocket(true).build();

    assertSame(spec, buildkit.handOut(spec));
  }

  @Test
  public void theTomlCarriesTheBoundTheMirrorsAndThePlainHttpGrants() {
    assertEquals(
        """
        [worker.oci]
          gc = true
          gckeepstorage = 20000000000
        [registry."registry.dev.localhost:8080"]
          mirrors = ["qits-platform-artifacts:8080"]
        [registry."mirror.dev.localhost:8080"]
          mirrors = ["qits-platform-mirror:8080"]
        [registry."qits-platform-artifacts:8080"]
          http = true
        [registry."qits-platform-mirror:8080"]
          http = true
        """,
        buildkit.buildkitdToml());
  }
}
