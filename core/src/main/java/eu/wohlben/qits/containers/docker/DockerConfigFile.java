package eu.wohlben.qits.containers.docker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * This service's own docker CLI credential: a {@code config.json} written at start from the idp
 * client qits-deployments provisions for it ({@code idp:client} in {@code .config/qits/deployments.yml}),
 * and the one decision about whether a docker child is pointed at it.
 *
 * <p><b>Why it exists (qits-879).</b> The CLI used to read {@code $DOCKER_CONFIG/config.json} from
 * the {@code /work/config} volume the bootstrap wrote, holding a bootstrap-era {@code id:secret}.
 * That client is retired (qits-878 collects it); the pull credential is now this application's
 * own client, injected as {@code QITS_RESOURCE_IDP_CLIENT_ID}/{@code _SECRET}, so a pull the
 * registry refuses names the service that was refused.
 *
 * <p><b>The format is the bootstrap's {@code SeedPhases.dockerConfigJson} byte for byte</b>: one
 * {@code auths} entry per host, each base64 of {@code <id>:<secret>} — what {@code docker login}
 * would store. The CLI matches an entry by host and nothing else, so listing the mirror beside the
 * registry widens nothing.
 *
 * <p><b>The file decides, not the configuration.</b> {@link #environment} adds {@code DOCKER_CONFIG}
 * only when the file is really there; otherwise the child keeps whatever this process was deployed
 * with, so the older spec that mounts {@code /work/config} — a rollback — keeps pulling.
 */
public final class DockerConfigFile {

  /** The CLI's own file name inside {@code $DOCKER_CONFIG}. */
  public static final String FILE_NAME = "config.json";

  private DockerConfigFile() {}

  /** The {@code config.json} body: an {@code auths} entry per host. */
  public static String json(List<String> hosts, String clientId, String secret) {
    String auth =
        Base64.getEncoder()
            .encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
    return hosts.stream()
        .map(
            host ->
                "\""
                    + host.replace("\\", "\\\\").replace("\"", "\\\"")
                    + "\":{\"auth\":\""
                    + auth
                    + "\"}")
        .collect(Collectors.joining(",", "{\"auths\":{", "}}\n"));
  }

  /**
   * Write {@code dir/config.json}, the directory 0700 and the file 0600, replacing any earlier one.
   * The file is created 0600 before a byte of the secret is in it and moved into place, so there is
   * no moment a wider mode holds it.
   *
   * @return the file written
   */
  public static Path write(Path dir, List<String> hosts, String clientId, String secret) {
    try {
      Files.createDirectories(dir);
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
      Path target = dir.resolve(FILE_NAME);
      Path tmp = dir.resolve(FILE_NAME + ".tmp");
      Files.deleteIfExists(tmp);
      Files.createFile(
          tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      Files.writeString(
          tmp,
          json(hosts, clientId, secret),
          StandardCharsets.UTF_8,
          StandardOpenOption.TRUNCATE_EXISTING);
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      return target;
    } catch (IOException e) {
      throw new UncheckedIOException("could not write the docker config under " + dir, e);
    }
  }

  /**
   * The environment a docker child is spawned with: {@code extra} plus {@code DOCKER_CONFIG=dir}
   * when {@code dir/config.json} exists, and {@code extra} untouched otherwise.
   */
  public static Map<String, String> environment(Map<String, String> extra, Path dir) {
    if (dir == null || !Files.isRegularFile(dir.resolve(FILE_NAME))) {
      return extra == null ? Map.of() : extra;
    }
    Map<String, String> env = new HashMap<>(extra == null ? Map.of() : extra);
    env.put("DOCKER_CONFIG", dir.toString());
    return Map.copyOf(env);
  }
}
