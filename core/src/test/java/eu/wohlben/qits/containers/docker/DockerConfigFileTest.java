package eu.wohlben.qits.containers.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** This service's own docker {@code config.json} (qits-879): its bytes, its modes, and the switch. */
public class DockerConfigFileTest {

  private static final List<String> HOSTS =
      List.of("registry.dev.localhost:8080", "mirror.dev.localhost:8080");

  @Test
  public void oneAuthsEntryPerHostEachTheBase64OfIdColonSecret() {
    String basic =
        Base64.getEncoder()
            .encodeToString("dev-qits-containers:s3cret".getBytes(StandardCharsets.UTF_8));

    // The bootstrap's dockerConfigJson, byte for byte: the same file docker login would write.
    assertEquals(
        "{\"auths\":{\"registry.dev.localhost:8080\":{\"auth\":\""
            + basic
            + "\"},\"mirror.dev.localhost:8080\":{\"auth\":\""
            + basic
            + "\"}}}\n",
        DockerConfigFile.json(HOSTS, "dev-qits-containers", "s3cret"));
  }

  @Test
  public void theFileIs0600InA0700Directory(@TempDir Path tmp) throws Exception {
    Path dir = tmp.resolve("qits-docker");

    Path written = DockerConfigFile.write(dir, HOSTS, "dev-qits-containers", "s3cret");

    assertEquals(dir.resolve("config.json"), written);
    assertEquals(
        DockerConfigFile.json(HOSTS, "dev-qits-containers", "s3cret"),
        Files.readString(written));
    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(written)));
    assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
    assertFalse(Files.exists(dir.resolve("config.json.tmp")), "no staging file is left behind");
  }

  @Test
  public void aSecondWriteReplacesTheFirst(@TempDir Path tmp) throws Exception {
    DockerConfigFile.write(tmp, HOSTS, "old", "one");
    Path written = DockerConfigFile.write(tmp, HOSTS, "new", "two");

    assertEquals(DockerConfigFile.json(HOSTS, "new", "two"), Files.readString(written));
    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(written)));
  }

  @Test
  public void dockerConfigIsAddedWhenTheFileExists(@TempDir Path tmp) {
    DockerConfigFile.write(tmp, HOSTS, "id", "secret");

    Map<String, String> env =
        DockerConfigFile.environment(Map.of("BUILDX_CONFIG", "/tmp/qits-buildx"), tmp);

    assertEquals(
        Map.of("BUILDX_CONFIG", "/tmp/qits-buildx", "DOCKER_CONFIG", tmp.toString()), env);
  }

  @Test
  public void theEnvironmentIsUntouchedWhenThereIsNoFile(@TempDir Path tmp) {
    // A rollback: the old spec mounts /work/config and sets DOCKER_CONFIG itself — ours to keep.
    Map<String, String> extra = Map.of("BUILDX_CONFIG", "/tmp/qits-buildx");

    assertEquals(extra, DockerConfigFile.environment(extra, tmp));
    assertEquals(Map.of(), DockerConfigFile.environment(Map.of(), tmp.resolve("absent")));
    assertTrue(DockerConfigFile.environment(null, tmp).isEmpty());
  }
}
