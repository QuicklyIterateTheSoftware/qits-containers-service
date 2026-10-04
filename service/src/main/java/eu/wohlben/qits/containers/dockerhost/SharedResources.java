package eu.wohlben.qits.containers.dockerhost;

import eu.wohlben.qits.containers.control.BootSweep;
import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.ContainersTimeouts;
import eu.wohlben.qits.containers.driver.spec.VolumeSpec;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The two things every workload this service starts expects to already be on the host: the
 * platform's shared volumes, and the network its containers are addressed on.
 *
 * <p><b>The volumes are made and the network is only asked about.</b> That asymmetry is the whole
 * class. {@code docker volume create} is idempotent and a volume nobody mounts costs nothing, so
 * making them is safe and saves every caller from carrying the same three names. A network is the
 * opposite: one this service invented would be a network no other module's containers are on, and
 * on a swarm-initialized host a bridge cannot be created at all — so a missing one is a warning
 * that names it, and the deployment answers it.
 *
 * <p><b>The shared volumes get no rows and no labels.</b> A row would claim them, and a claim is
 * precisely what makes a volume removable ({@code VolumeReconcile} removes what a row marks
 * absent); a namespace label would put them in that reconcile's listing, where every pass would
 * report three unclaimed volumes forever. They belong to the platform rather than to this service —
 * {@code ContainerSpec.sharedMounts} is a separate type from {@code volumeMounts} for the same
 * reason — so this service makes sure they exist and never touches them again.
 *
 * <p>Deployments only. A {@code @QuarkusTest} and a {@code quarkus:dev} have no daemon to promise
 * anything about, and a suite that made three real volumes on the developer's host would be one
 * that changed the machine it ran on.
 */
@ApplicationScoped
public class SharedResources {

  private static final Logger LOG = Logger.getLogger(SharedResources.class);

  @Inject ContainersDriver driver;

  /**
   * The platform's shared volumes — the coding agent's home, the maven repository, the pnpm store.
   * Spelled with underscores because that is what they are called on the host, which is why
   * {@code ContainersIdentifiers} allows a volume name a wider charset than a container name.
   */
  @ConfigProperty(name = "qits.containers.shared-volumes")
  List<String> sharedVolumes;

  /** The network this service's workloads are addressed on. Asked about, never created. */
  @ConfigProperty(name = "qits.containers.network")
  String network;

  void onStart(@Observes @Priority(BootSweep.SHARED_RESOURCES_PRIORITY) StartupEvent event) {
    if (LaunchMode.current() != LaunchMode.NORMAL) {
      return;
    }
    try {
      ensureOnce();
    } catch (RuntimeException e) {
      // Docker not being up yet is the ordinary state of a host that has just rebooted, and this
      // is bookkeeping rather than a gate: the first ensure that needs a volume will make it.
      LOG.warnf(e, "Could not prepare the shared resources; the service carries on without them");
    }
  }

  /** One pass. Package-private so a suite drives it without a real {@code StartupEvent}. */
  void ensureOnce() {
    for (String volume : sharedVolumes) {
      ContainersDriver.OpResult made =
          driver.ensureVolume(new VolumeSpec(volume), Map.of(), ContainersTimeouts.VOLUME);
      if (!made.ok()) {
        LOG.warnf(
            "Could not make sure the shared volume %s exists: a workload that mounts it will fail"
                + " to start until it does",
            volume);
      }
    }
    if (!driver.networkPresent(network, ContainersTimeouts.VOLUME)) {
      LOG.warnf(
          "The network %s is not on this host, and this service does not create one: every"
              + " container it starts names a network, and one invented here would be a network no"
              + " other module's containers are on. Create it where the platform's own topology is"
              + " declared.",
          network);
    }
  }
}
