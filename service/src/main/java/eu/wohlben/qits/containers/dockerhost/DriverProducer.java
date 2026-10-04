package eu.wohlben.qits.containers.dockerhost;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.DockerContainersDriver;
import eu.wohlben.qits.containers.driver.DockerSocketGroup;
import eu.wohlben.qits.containers.spec.ContainerLabels;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Where this service's configuration becomes the real docker driver.
 *
 * <p><b>The driver lives in qits-containers-driver</b> and is constructed, never injected: plain
 * Java with no CDI and no config file, so a runner holding a docker socket embeds the same code.
 * This class is the one place this service reads the three settings the driver takes and hands them
 * over:
 *
 * <ul>
 *   <li>{@code qits.containers.container-runtime} — the binary shelled out to;
 *   <li>{@link ContainerLabels#NS} — {@code qits.containers.}, the namespace every label and the
 *       builder stamp is written under and the one an owner label is refused inside;
 *   <li>{@code qits.containers.docker-socket-group} — the {@link DockerSocketGroup} override, read
 *       once and cached by the group itself;
 *   <li>{@code qits.containers.docker-config.dir} — where {@link DockerCredentials} writes this
 *       service's own {@code config.json}; a blank value is no directory, and the driver points a
 *       docker child's {@code DOCKER_CONFIG} at it only while the file exists (qits-879).
 * </ul>
 *
 * <p><b>It replaces {@code UnwiredContainersDriver} by ordinary CDI precedence.</b> That one is a
 * {@code @DefaultBean}, which means "unless something else provides this", so the produced driver
 * needs no alternative, no priority and no profile — and a test profile that enables the fake
 * driver's {@code @Alternative} still takes the seam from both.
 */
public class DriverProducer {

  @Produces
  @Singleton
  DockerSocketGroup socketGroup(
      @ConfigProperty(name = "qits.containers.docker-socket-group") Optional<String> configured) {
    return new DockerSocketGroup(configured);
  }

  @Produces
  @Singleton
  ContainersDriver driver(
      @ConfigProperty(name = "qits.containers.container-runtime") String runtime,
      @ConfigProperty(name = "qits.containers.docker-config.dir") String dockerConfigDir,
      DockerSocketGroup socketGroup) {
    return driver(runtime, socketGroup::value, dockerConfigDir);
  }

  /**
   * The driver, from the three settings as configuration spells them. An unset or blank {@code
   * dockerConfigDir} — a driver built by hand, a config that names none — means no {@code
   * DOCKER_CONFIG}.
   */
  static DockerContainersDriver driver(
      String runtime, Supplier<String> socketGroup, String dockerConfigDir) {
    Path dir =
        dockerConfigDir == null || dockerConfigDir.isBlank() ? null : Path.of(dockerConfigDir);
    return new DockerContainersDriver(runtime, ContainerLabels.NS, socketGroup, dir);
  }
}
