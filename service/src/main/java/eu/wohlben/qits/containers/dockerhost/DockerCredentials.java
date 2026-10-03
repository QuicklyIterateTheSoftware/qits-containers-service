package eu.wohlben.qits.containers.dockerhost;

import eu.wohlben.qits.containers.control.BootSweep;
import eu.wohlben.qits.containers.docker.DockerConfigFile;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Writes this service's own docker CLI credential at start, from the idp client qits-deployments
 * provisions for it (qits-879). {@link DockerConfigFile} carries the format and the reason; this is
 * only the boot step.
 *
 * <p><b>Both halves or nothing.</b> With either the client id or the secret unset — a deployment
 * from before {@code idp:client} was declared, a suite, a {@code quarkus:dev} — no file is written,
 * and since {@code DockerContainersDriver} points a child at the directory only while the file
 * exists, the CLI keeps reading whatever {@code DOCKER_CONFIG} the deployment set.
 *
 * <p><b>A failed write never fails the boot</b>, for {@code SharedResources}' reason: an
 * orchestrator that refused to start could not be redeployed to fix what refused it. The pulls that
 * then fail name the registry's refusal.
 */
@ApplicationScoped
public class DockerCredentials {

  private static final Logger LOG = Logger.getLogger(DockerCredentials.class);

  @ConfigProperty(name = "qits.containers.docker-config.dir")
  String dir;

  @ConfigProperty(name = "qits.containers.docker-config.hosts")
  List<String> hosts;

  /** {@code QITS_RESOURCE_IDP_CLIENT_ID}; empty reads as unset, which MicroProfile calls absent. */
  @ConfigProperty(name = "qits.containers.docker-config.client-id")
  Optional<String> clientId;

  /** {@code QITS_RESOURCE_IDP_CLIENT_SECRET}. Never logged. */
  @ConfigProperty(name = "qits.containers.docker-config.client-secret")
  Optional<String> clientSecret;

  void onStart(@Observes @Priority(BootSweep.DOCKER_CREDENTIALS_PRIORITY) StartupEvent event) {
    if (LaunchMode.current() != LaunchMode.NORMAL) {
      return;
    }
    try {
      writeOnce();
    } catch (RuntimeException e) {
      LOG.warnf(
          e,
          "Could not write this service's docker config under %s; docker keeps the deployment's"
              + " own DOCKER_CONFIG",
          dir);
    }
  }

  /**
   * One pass. Package-private so a suite drives it without a real {@code StartupEvent}.
   *
   * @return the file written, or empty when the client is not configured
   */
  Optional<Path> writeOnce() {
    String id = clientId.map(String::strip).orElse("");
    String secret = clientSecret.orElse("");
    if (id.isEmpty() || secret.isEmpty()) {
      LOG.debugf("No idp client injected; no docker config is written under %s", dir);
      return Optional.empty();
    }
    Path written = DockerConfigFile.write(Path.of(dir), hosts, id, secret);
    LOG.infof("Wrote the docker config %s for idp client %s, hosts %s", written, id, hosts);
    return Optional.of(written);
  }
}
