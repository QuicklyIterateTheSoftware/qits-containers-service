package eu.wohlben.qits.containers.dockerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.docker.DockerConfigFile;
import eu.wohlben.qits.containers.driver.DockerContainersDriver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The boot step that writes this service's own docker credential, and the driver seam that points
 * every docker child at it (qits-879). Plain unit tests: no profile, no application, no daemon —
 * the driver's runtime is a script that prints the {@code DOCKER_CONFIG} it was spawned with.
 */
public class DockerCredentialsTest {

  private static final List<String> HOSTS =
      List.of("registry.dev.localhost:8080", "mirror.dev.localhost:8080");

  private static DockerCredentials credentials(Path dir, String id, String secret) {
    DockerCredentials bean = new DockerCredentials();
    bean.dir = dir.toString();
    bean.hosts = HOSTS;
    bean.clientId = Optional.ofNullable(id);
    bean.clientSecret = Optional.ofNullable(secret);
    return bean;
  }

  @Test
  public void bothHalvesSetWritesTheFile(@TempDir Path tmp) throws Exception {
    Path dir = tmp.resolve("qits-docker");

    Optional<Path> written = credentials(dir, "dev-qits-containers", "s3cret").writeOnce();

    assertEquals(Optional.of(dir.resolve("config.json")), written);
    assertEquals(
        DockerConfigFile.json(HOSTS, "dev-qits-containers", "s3cret"),
        Files.readString(written.orElseThrow()));
    assertEquals(
        "rw-------",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(written.orElseThrow())));
  }

  @Test
  public void eitherHalfUnsetWritesNothing(@TempDir Path tmp) {
    Path dir = tmp.resolve("qits-docker");

    assertTrue(credentials(dir, null, null).writeOnce().isEmpty());
    assertTrue(credentials(dir, "dev-qits-containers", null).writeOnce().isEmpty());
    assertTrue(credentials(dir, null, "s3cret").writeOnce().isEmpty());
    assertTrue(credentials(dir, " ", "s3cret").writeOnce().isEmpty());
    assertFalse(Files.exists(dir), "nothing at all is created");
  }

  @Test
  public void aDriverWithNoDirConfiguredSpawnsWithTheInheritedEnvironment(@TempDir Path tmp)
      throws Exception {
    // A driver built by hand (DockerBuildCacheCallTest's shape) never set the dir: that is "no
    // DOCKER_CONFIG", never a NullPointerException on every docker call.
    Path script = tmp.resolve("fake-docker");
    Files.writeString(script, "#!/bin/sh\necho \"cfg=${DOCKER_CONFIG:-unset}\"\n");
    script.toFile().setExecutable(true);
    DockerContainersDriver driver = DriverProducer.driver(script.toString(), () -> "", null);

    String inherited = Optional.ofNullable(System.getenv("DOCKER_CONFIG")).orElse("unset");
    assertEquals(
        List.of("cfg=" + inherited), driver.listBuildxBuilders(Duration.ofSeconds(30)));
    driver = DriverProducer.driver(script.toString(), () -> "", " ");
    assertEquals(
        List.of("cfg=" + inherited), driver.listBuildxBuilders(Duration.ofSeconds(30)));
  }

  @Test
  public void aDockerChildIsPointedAtTheFileOnlyWhileItExists(@TempDir Path tmp) throws Exception {
    Path script = tmp.resolve("fake-docker");
    Files.writeString(script, "#!/bin/sh\necho \"cfg=${DOCKER_CONFIG:-unset}\"\n");
    script.toFile().setExecutable(true);
    Path dir = tmp.resolve("qits-docker");

    DockerContainersDriver driver =
        DriverProducer.driver(script.toString(), () -> "", dir.toString());

    // No file: the deployment's own environment is the child's — the rollback onto /work/config.
    String inherited = Optional.ofNullable(System.getenv("DOCKER_CONFIG")).orElse("unset");
    assertEquals(
        List.of("cfg=" + inherited), driver.listBuildxBuilders(Duration.ofSeconds(30)));

    credentials(dir, "dev-qits-containers", "s3cret").writeOnce();

    assertEquals(List.of("cfg=" + dir), driver.listBuildxBuilders(Duration.ofSeconds(30)));
    // The buildx calls carry their own environment too; the credential rides beside it.
    assertTrue(
        driver.describeBuildCache(Duration.ofSeconds(30)).detail().contains("cfg=" + dir),
        "the build-cache call is pointed at the file as well");
  }
}
