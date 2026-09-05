package eu.wohlben.qits.containers.dockerhost;

import eu.wohlben.qits.containers.control.BootSweep;
import eu.wohlben.qits.containers.control.ContainersDriver;
import eu.wohlben.qits.containers.control.ContainersTimeouts;
import eu.wohlben.qits.containers.spec.ContainerSpec;
import eu.wohlben.qits.containers.spec.ContainersIdentifiers;
import eu.wohlben.qits.containers.spec.VolumeSpec;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The platform's own buildkitd — the one container this service creates for itself, so that image
 * builds stop being something a step does through the host's docker socket.
 *
 * <p><b>It is platform infrastructure in {@link SharedResources}' sense, and it follows that
 * class's whole argument</b>: ensured at boot, warned about rather than failed on, and claimed by
 * no registry row. A row would put it under the policy sweeps and the owner routes, where "decides
 * nothing about what should run" is the boundary — and this container is precisely the one thing
 * this service <em>does</em> decide to run, the way it decides the shared volumes exist. The image
 * is protected from the collection while the container exists ({@code ImageGc}'s {@code in-use}
 * rule reads every container, running or not), the state volume is a named unlabelled volume the
 * volume collection classes {@code unmanaged} and keeps, and liveness is docker's own
 * {@code --restart unless-stopped}.
 *
 * <p><b>The boot pass converges on the configured pin.</b> A running builder on the configured
 * image is adopted; a stopped one is started; one on another image — the pin was bumped — is
 * replaced, which is the one moment the cache volume's contents carry across (the volume is
 * mounted, never removed). Every step is warn-and-carry-on: docker not answering at boot is the
 * ordinary state of a rebooted host, and the builds will name the missing builder loudly enough.
 *
 * <p><b>{@code handOut} is the address's one exit.</b> A workload that declared the docker socket
 * is a workload that builds, so it is handed {@code BUILDKIT_HOST} beside the socket — unless the
 * caller sent the key itself, and the caller's value wins <em>including an empty one</em>: qits-ci
 * spells "buildkit is switched off" as an empty {@code BUILDKIT_HOST}, the platform's
 * empty-never-absent off value, and this service must not fill a gap that was made on purpose.
 * The address is discovery rather than privilege — the alias resolves for anything on the platform
 * network — so handing it out adds no capability; it removes the reason a build needed the socket.
 */
@ApplicationScoped
public class PlatformBuildkit {

  private static final Logger LOG = Logger.getLogger(PlatformBuildkit.class);

  /** The env key every consumer reads, spelled where both the injection and the docs point. */
  public static final String BUILDKIT_HOST = "BUILDKIT_HOST";

  /** The builder's state volume. Unlabelled on purpose — see the class javadoc. */
  static final String STATE_VOLUME = "qits-buildkitd-state";

  @Inject ContainersDriver driver;

  /** The infrastructure half of the switch; qits-ci carries the fleet-facing one. */
  @ConfigProperty(name = "qits.containers.buildkit.enabled")
  boolean enabled;

  /** The pinned image — the version pin of the whole build plane. */
  @ConfigProperty(name = "qits.containers.buildkit.image")
  String image;

  /** The address handed to socket-holding workloads. */
  @ConfigProperty(name = "qits.containers.buildkit.address")
  String address;

  @ConfigProperty(name = "qits.containers.network")
  String network;

  /** {@code from=to} pairs rendered into buildkitd.toml — see the config file's argument. */
  @ConfigProperty(name = "qits.containers.buildkit.registry-mirrors")
  List<String> registryMirrors;

  /** Registries buildkitd speaks plain HTTP to — the platform's own, on its own network. */
  @ConfigProperty(name = "qits.containers.buildkit.http-registries")
  List<String> httpRegistries;

  /** What the builder's own gc keeps — the standing bound; the orchestrator's sweep is the belt. */
  @ConfigProperty(name = "qits.containers.buildkit.keep-storage-bytes")
  long keepStorageBytes;

  @ConfigProperty(name = "qits.containers.buildkit.pids-limit")
  long pidsLimit;

  @ConfigProperty(name = "qits.containers.buildkit.oom-score-adj")
  int oomScoreAdj;

  void onStart(@Observes @Priority(BootSweep.PLATFORM_BUILDER_PRIORITY) StartupEvent event) {
    if (LaunchMode.current() != LaunchMode.NORMAL || !enabled) {
      return;
    }
    try {
      ensureOnce();
    } catch (RuntimeException e) {
      // SharedResources' stance: docker not being up yet is a rebooted host's ordinary state, and
      // a boot must not fail over bookkeeping. The first build against a missing builder says so.
      LOG.warnf(e, "Could not ensure the platform builder; the service carries on without it");
    }
  }

  /** One pass. Package-private so the suite drives it without a real {@code StartupEvent}. */
  void ensureOnce() {
    driver.ensureVolume(new VolumeSpec(STATE_VOLUME), Map.of(), ContainersTimeouts.VOLUME);
    String name = ContainersIdentifiers.PLATFORM_BUILDER;
    Optional<ContainersDriver.Observed> observed = driver.inspect(name, ContainersTimeouts.INSPECT);
    if (observed.isPresent()) {
      String runningImage = driver.imageOf(name, ContainersTimeouts.INSPECT).orElse("");
      if (image.equals(runningImage)) {
        if (!"running".equals(observed.get().status())) {
          ContainersDriver.OpResult started = driver.start(name, ContainersTimeouts.START);
          if (!started.ok()) {
            LOG.warnf("Could not start the platform builder %s: %s", name, started.detail());
          }
        }
        return;
      }
      // The pin moved. The container is replaced and the state volume rides across, which is what
      // makes a version bump cost a restart rather than a cold cache.
      LOG.infof("Replacing the platform builder: it runs %s, the pin says %s", runningImage, image);
      driver.stop(name, ContainersTimeouts.STOP);
      driver.remove(name, ContainersTimeouts.REMOVE);
    }
    ContainersDriver.OpResult pulled =
        driver.pull(image, ContainersTimeouts.PULL, ContainersTimeouts.PULL_MAX_CHARS);
    if (!pulled.ok()) {
      // Run anyway: docker's own run fetches a missing image, and the run's failure is the record.
      LOG.warnf("Could not pull %s for the platform builder; running anyway", image);
    }
    ContainersDriver.Started started =
        driver.runBuildkitd(
            image, network, STATE_VOLUME, buildkitdToml(), pidsLimit, oomScoreAdj,
            ContainersTimeouts.RUN);
    if (!started.started()) {
      LOG.warnf("Could not run the platform builder: %s", started.detail());
    } else {
      LOG.infof("The platform builder %s is up on %s", name, image);
    }
  }

  /**
   * The spec as it goes to docker, with the builder's address filled in where it was left open —
   * the one place {@code BUILDKIT_HOST} enters a workload. See the class javadoc for the
   * caller-wins rule; the empty map copy is what keeps a spec immutable end to end.
   */
  public ContainerSpec handOut(ContainerSpec spec) {
    if (!enabled || !spec.hostDockerSocket() || spec.env().containsKey(BUILDKIT_HOST)) {
      return spec;
    }
    Map<String, String> env = new LinkedHashMap<>(spec.env());
    env.put(BUILDKIT_HOST, address);
    return new ContainerSpec(
        spec.image(),
        spec.entrypoint(),
        spec.args(),
        env,
        spec.extraLabels(),
        spec.network(),
        spec.aliases(),
        spec.addHosts(),
        spec.volumeMounts(),
        spec.sharedMounts(),
        spec.hostDockerSocket(),
        spec.security(),
        spec.pullPolicy(),
        spec.explicitName(),
        spec.user(),
        spec.init());
  }

  /**
   * buildkitd.toml, rendered rather than committed: every value in it is one of this service's own
   * config keys, and a file shipped in an image would be a second place for the registry topology
   * to live. The mirrors are what keep every committed Dockerfile's {@code FROM} lines working —
   * they name edge vhosts only the host's network namespace resolves, and buildkitd is on the
   * platform network, so the rewrite to the in-network aliases happens here and nowhere else.
   */
  String buildkitdToml() {
    StringBuilder toml = new StringBuilder();
    toml.append("[worker.oci]\n  gc = true\n  gckeepstorage = ").append(keepStorageBytes).append("\n");
    for (String mirror : registryMirrors) {
      int split = mirror.indexOf('=');
      if (split <= 0 || split == mirror.length() - 1) {
        LOG.warnf("Ignoring a registry mirror that is not from=to: %s", mirror);
        continue;
      }
      toml.append("[registry.\"").append(mirror, 0, split).append("\"]\n  mirrors = [\"")
          .append(mirror, split + 1, mirror.length()).append("\"]\n");
    }
    for (String registry : httpRegistries) {
      toml.append("[registry.\"").append(registry).append("\"]\n  http = true\n");
    }
    return toml.toString();
  }
}
