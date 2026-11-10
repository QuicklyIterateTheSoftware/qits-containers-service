package eu.wohlben.qits.containers.dockerhost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.containers.control.ContainerRegistry;
import eu.wohlben.qits.containers.driver.ContainersTimeouts;
import eu.wohlben.qits.containers.driver.docker.ContainerProcess;
import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.driver.spec.ContainersIdentifiers;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * What {@code privateEdge} on the wire becomes at the moment a container is run (qits-1991): a
 * membership of the {@code qits-private} overlay, and the private edge's VIP on it as the first
 * resolver, followed by the host's own.
 *
 * <p><b>Why this service and not the caller.</b> qits-workspaces knows that a DIRECT admin
 * workspace wants the private edge; it does not know the overlay's id, which address swarm gave the
 * edge's service on it, or which resolvers the host hands its containers. All three are facts of
 * the docker daemon, and this is the service holding its socket — so the caller sends one flag and
 * this class asks the daemon, at every run, and hands the driver a spec with {@link
 * ContainerSpec#extraNetworks} and {@link ContainerSpec#dns} filled in. The result is never stored
 * and never hashed ({@link ContainerRegistry.AtRun}): an edge redeployed onto a new VIP is picked up
 * by the next run of each container without a single row reading as changed.
 *
 * <p><b>Three questions, each with a fallback that is today's behaviour, never a refusal.</b>
 *
 * <ol>
 *   <li><b>Is the network there?</b> {@code docker network inspect --format {{.Id}}}. Absent, or
 *       docker not answering, adds <em>nothing</em> — no join and no resolver. That is not caution
 *       for its own sake: the driver unwinds a run whose join failed, removing the container and
 *       reporting the run failed, so asking it to join a network that is not there would turn a
 *       missing overlay into a workspace that cannot start at all. A host without the overlay (a
 *       dev box, an estate before qits-deployments created it) runs the container exactly as if the
 *       flag were off. A spec already on the network as its primary is not joined to it again.
 *   <li><b>Where is the edge on it?</b> {@code docker service ls -q --filter
 *       label=qits.platform.deployments.app-name=<application>} — qits-deployments' {@code
 *       DeploymentDriver.APP_NAME_LABEL}, spelled here because that jar is not on this classpath —
 *       then {@code docker service inspect --format {{json .Endpoint.VirtualIPs}}} and the entry
 *       whose {@code NetworkID} is the overlay's id, its {@code /prefix} stripped. Exactly one
 *       service is expected; none or several is no resolver, with the join kept, because a
 *       container on the overlay with the ordinary resolvers is strictly better than one without.
 *       The {@code service} verbs are this class's and not the driver's: they read swarm state the
 *       driver's container vocabulary has no reason to know, and the answers are read-only.
 *   <li><b>What comes after it?</b> The {@code # ExtServers: [...]} comment docker writes into this
 *       container's own {@code /etc/resolv.conf}, which is the upstream list docker's embedded
 *       resolver forwards to here and will forward to in a sibling — {@code host(x)} is the
 *       loopback spelling of a server reached from the host's namespace, kept as {@code x}. This is
 *       what makes the VIP safe to put first: {@code --dns} REPLACES the upstream rather than
 *       prepending to it, so a list holding only the VIP would have the embedded resolver answer
 *       every public name with whatever the edge answers for names it does not own — REFUSED — and
 *       a workspace that cannot resolve github.com. Docker moves on to the next server on REFUSED
 *       or SERVFAIL, so {@code [vip, upstream...]} asks the edge first and the world after it.
 * </ol>
 *
 * <p><b>The resolver list is all or nothing</b>: set only when the VIP <em>and</em> at least one
 * upstream were found, and otherwise left empty — the host's default, today's behaviour — with a
 * warning that says which half was missing. A VIP-only list is never written, for the reason above.
 *
 * <p>Every call carries {@link ContainersTimeouts#INSPECT} and {@link
 * ContainersTimeouts#SHORT_MAX_CHARS}, the repository's second invariant. Nothing here throws:
 * every failure degrades to less resolution and a warning naming why, once per run.
 *
 * <p>{@code @Singleton} rather than {@code @ApplicationScoped}: there is no state to proxy and no
 * lazy start to want, and an unproxied bean is one a {@code @QuarkusTest} in this package can hand
 * a scripted {@link DockerCommand} to.
 */
@Singleton
public class PrivateEdgeResolver {

  private static final Logger LOG = Logger.getLogger(PrivateEdgeResolver.class);

  /**
   * qits-deployments' {@code DeploymentDriver.APP_NAME_LABEL}: the label every swarm service it
   * deploys carries, holding the application's name. Restated rather than imported — that jar is
   * qits-deployments' own and none of its code belongs on this classpath.
   */
  static final String APP_NAME_LABEL = "qits.platform.deployments.app-name";

  /** The comment line docker writes into a container's resolv.conf naming its upstream servers. */
  static final String EXT_SERVERS = "# ExtServers:";

  /** A docker object id as {@code network inspect} and {@code service ls -q} print one. */
  private static final Pattern DOCKER_ID = Pattern.compile("[0-9a-z]{1,128}");

  /** The {@code host(x)} spelling docker uses for a server reached from the host's namespace. */
  private static final Pattern HOST_FORM = Pattern.compile("host\\((.*)\\)");

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * One docker CLI call — {@link ContainerProcess#run} in production, a script in the suite. A seam
   * of this class's own because the questions it asks are not in the driver's vocabulary, and both
   * guards are in its signature so the fake cannot be called without them either.
   */
  @FunctionalInterface
  interface DockerCommand {
    ContainerProcess.Result run(List<String> argv, Duration timeout, int maxChars);
  }

  /** The binary the driver shells out to; the same one, so the two never disagree on a host. */
  @ConfigProperty(name = "qits.containers.container-runtime")
  String runtime;

  /** The attachable overlay a flagged container joins. qits-deployments creates it. */
  @ConfigProperty(name = "qits.containers.private-edge.network")
  String network;

  /** The application name of the private edge, as its swarm service's app-name label holds it. */
  @ConfigProperty(name = "qits.containers.private-edge.application")
  String application;

  /** Where this container's own resolv.conf is read from — configurable for the suite only. */
  @ConfigProperty(name = "qits.containers.private-edge.resolv-conf")
  String resolvConf;

  DockerCommand docker = (argv, timeout, maxChars) -> ContainerProcess.run(null, argv, timeout, maxChars);

  PrivateEdgeResolver() {}

  /** Everything by hand, for the suite. */
  PrivateEdgeResolver(
      String runtime, String network, String application, Path resolvConf, DockerCommand docker) {
    this.runtime = runtime;
    this.network = network;
    this.application = application;
    this.resolvConf = resolvConf.toString();
    this.docker = docker;
  }

  /**
   * The spec {@code docker run} is handed for a container that asked for the private edge — see
   * the class javadoc. Never throws; a spec nothing could be resolved for comes back as it went in.
   */
  public ContainerSpec resolve(ContainerSpec spec, String containerName) {
    Optional<String> networkId = networkId();
    if (networkId.isEmpty()) {
      return spec;
    }
    List<String> extraNetworks = new ArrayList<>(spec.extraNetworks());
    if (!network.equals(spec.network()) && !extraNetworks.contains(network)) {
      extraNetworks.add(network);
    }

    List<String> dns = spec.dns();
    if (dns.isEmpty()) {
      Optional<String> vip = edgeVip(networkId.get(), containerName);
      List<String> upstreams = upstreams();
      if (vip.isPresent() && !upstreams.isEmpty()) {
        dns = new ArrayList<>();
        dns.add(vip.get());
        upstreams.stream().filter(u -> !u.equals(vip.get())).forEach(dns::add);
      } else if (vip.isPresent()) {
        LOG.warnf(
            "%s joins %s but keeps the host's resolvers: %s names no upstream server (no '%s' line),"
                + " and a resolver list holding only the private edge's VIP would answer every"
                + " public name REFUSED",
            containerName, network, resolvConf, EXT_SERVERS);
      }
    }

    ContainerSpec resolved;
    try {
      resolved = with(spec, dns, extraNetworks);
    } catch (IllegalArgumentException e) {
      LOG.warnf(
          "Could not put %s on the private edge, so it runs as asked: %s", containerName, e.getMessage());
      return spec;
    }
    LOG.infof(
        "%s is on the private edge: joins %s, resolvers %s",
        containerName,
        resolved.extraNetworks().contains(network) ? network : network + " (its primary network)",
        resolved.dns().isEmpty() ? "the host's own" : resolved.dns());
    return resolved;
  }

  /** The overlay's id, or empty — with the warning — when the host has none or did not answer. */
  private Optional<String> networkId() {
    ContainerProcess.Result result =
        call(List.of(runtime, "network", "inspect", "--format", "{{.Id}}", network));
    String id = result.exitCode() == 0 ? lastLine(result.output()) : "";
    if (!DOCKER_ID.matcher(id).matches()) {
      LOG.warnf(
          "A container asked for the private edge, but the network %s does not answer an inspect"
              + " (%s), so it is neither joined to it nor given its resolver: a join to a network"
              + " that is not there would fail the run itself. qits-deployments creates it.",
          network, brief(result));
      return Optional.empty();
    }
    return Optional.of(id);
  }

  /** The private edge's address on the overlay, or empty with a warning naming why. */
  private Optional<String> edgeVip(String networkId, String containerName) {
    ContainerProcess.Result listed =
        call(
            List.of(
                runtime, "service", "ls", "-q", "--filter", "label=" + APP_NAME_LABEL + "=" + application));
    List<String> services =
        listed.exitCode() == 0
            ? listed.output().lines().map(String::strip).filter(l -> !l.isEmpty()).toList()
            : List.of();
    if (services.size() != 1 || !DOCKER_ID.matcher(services.getFirst()).matches()) {
      LOG.warnf(
          "%s joins %s but keeps the host's resolvers: expected exactly one swarm service labelled"
              + " %s=%s and found %s",
          containerName,
          network,
          APP_NAME_LABEL,
          application,
          listed.exitCode() == 0 ? services.size() + " (" + String.join(", ", services) + ")" : brief(listed));
      return Optional.empty();
    }
    ContainerProcess.Result inspected =
        call(
            List.of(
                runtime,
                "service",
                "inspect",
                "--format",
                "{{json .Endpoint.VirtualIPs}}",
                services.getFirst()));
    Optional<String> vip =
        inspected.exitCode() == 0 ? vipOn(lastLine(inspected.output()), networkId) : Optional.empty();
    if (vip.isEmpty()) {
      LOG.warnf(
          "%s joins %s but keeps the host's resolvers: the service %s (%s) has no virtual IP on it"
              + " (%s)",
          containerName, network, services.getFirst(), application, brief(inspected));
    }
    return vip;
  }

  /** The {@code Addr} of the entry whose {@code NetworkID} is the overlay's, without its prefix. */
  static Optional<String> vipOn(String virtualIpsJson, String networkId) {
    try {
      JsonNode entries = JSON.readTree(virtualIpsJson);
      if (entries == null || !entries.isArray()) {
        return Optional.empty();
      }
      for (JsonNode entry : entries) {
        if (networkId.equals(entry.path("NetworkID").asText())) {
          String addr = entry.path("Addr").asText();
          int slash = addr.indexOf('/');
          return dnsServer(slash < 0 ? addr : addr.substring(0, slash));
        }
      }
    } catch (Exception e) {
      LOG.debugf("Could not read the virtual IPs %s: %s", virtualIpsJson, e.getMessage());
    }
    return Optional.empty();
  }

  /** The upstream servers docker named in this container's resolv.conf, in order; empty if none. */
  private List<String> upstreams() {
    try {
      return extServers(Files.readString(Path.of(resolvConf), StandardCharsets.UTF_8));
    } catch (Exception e) {
      LOG.debugf("Could not read %s: %s", resolvConf, e.getMessage());
      return List.of();
    }
  }

  /**
   * The servers of the first {@code # ExtServers: [a b host(c)]} line — {@code [a, b, c]}. An entry
   * that is not an address literal is skipped rather than failing the list: it would fail the
   * spec's own belt, and one bad token is no reason to lose the good ones beside it.
   */
  static List<String> extServers(String resolvConfText) {
    for (String line : resolvConfText.lines().toList()) {
      String stripped = line.strip();
      if (!stripped.startsWith(EXT_SERVERS)) {
        continue;
      }
      String rest = stripped.substring(EXT_SERVERS.length()).strip();
      if (rest.startsWith("[")) {
        rest = rest.substring(1);
      }
      int close = rest.lastIndexOf(']');
      if (close >= 0) {
        rest = rest.substring(0, close);
      }
      List<String> servers = new ArrayList<>();
      for (String token : rest.strip().split("\\s+")) {
        var host = HOST_FORM.matcher(token);
        dnsServer(host.matches() ? host.group(1) : token)
            .filter(s -> !servers.contains(s))
            .ifPresent(servers::add);
      }
      return List.copyOf(servers);
    }
    return List.of();
  }

  private static Optional<String> dnsServer(String candidate) {
    try {
      return Optional.of(ContainersIdentifiers.requireDnsServer(candidate));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** The spec with these two components replaced and every other one as it was. */
  private static ContainerSpec with(
      ContainerSpec spec, List<String> dns, List<String> extraNetworks) {
    return new ContainerSpec(
        spec.image(),
        spec.entrypoint(),
        spec.args(),
        spec.env(),
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
        spec.init(),
        dns,
        extraNetworks);
  }

  private ContainerProcess.Result call(List<String> argv) {
    return docker.run(argv, ContainersTimeouts.INSPECT, ContainersTimeouts.SHORT_MAX_CHARS);
  }

  private static String lastLine(String output) {
    if (output == null) {
      return "";
    }
    List<String> lines = output.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
    return lines.isEmpty() ? "" : lines.getLast();
  }

  private static String brief(ContainerProcess.Result result) {
    if (result.timedOut()) {
      return "docker did not answer within " + ContainersTimeouts.INSPECT.toSeconds() + "s";
    }
    String text = result.output() == null ? "" : result.output().strip();
    if (text.length() > 200) {
      text = text.substring(0, 200) + "…";
    }
    return "exit " + result.exitCode() + (text.isEmpty() ? "" : ": " + text);
  }
}
