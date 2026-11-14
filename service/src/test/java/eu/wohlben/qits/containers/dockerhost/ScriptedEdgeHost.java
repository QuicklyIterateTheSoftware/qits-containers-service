package eu.wohlben.qits.containers.dockerhost;

import eu.wohlben.qits.containers.driver.docker.ContainerProcess;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The {@link PrivateEdgeResolver} bean handed a scripted docker CLI and resolv.conf, and given its
 * own back on {@link #close()} — for a suite outside this package (the provider states, the
 * resource tests) that cannot reach the bean's package-private seam.
 *
 * <p>The script answers by the argv's second and third words ({@code "container inspect"}, {@code
 * "network inspect"}, {@code "service ls"}, {@code "service inspect"}) with exit 0 and the scripted
 * line, and anything unscripted with exit 1 — the shape {@code PrivateEdgeEnsureTest} uses.
 */
public final class ScriptedEdgeHost implements AutoCloseable {

  /** The overlay's id on the scripted host. */
  public static final String NETWORK_ID = "4k2x9q7m1n0p3r5s6t8u";

  private final PrivateEdgeResolver resolver;

  private final PrivateEdgeResolver.DockerCommand realDocker;

  private final String realResolvConf;

  private final Path resolvConf;

  private final List<List<String>> calls = new ArrayList<>();

  private ScriptedEdgeHost(
      PrivateEdgeResolver resolver, Map<String, String> answers, String resolvConfText) {
    this.resolver = resolver;
    this.realDocker = resolver.docker;
    this.realResolvConf = resolver.resolvConf;
    try {
      this.resolvConf = Files.createTempFile("qits-2231-resolv", ".conf");
      Files.writeString(resolvConf, resolvConfText);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    resolver.resolvConf = resolvConf.toString();
    resolver.docker =
        (argv, timeout, maxChars) -> {
          synchronized (calls) {
            calls.add(argv);
          }
          String output = answers.get(argv.get(1) + " " + argv.get(2));
          return output == null
              ? new ContainerProcess.Result(1, "unscripted", false, false)
              : new ContainerProcess.Result(0, output + "\n", false, false);
        };
  }

  /** Scripts {@code resolver} until {@link #close()}. */
  public static ScriptedEdgeHost on(
      PrivateEdgeResolver resolver, Map<String, String> answers, String resolvConfText) {
    return new ScriptedEdgeHost(resolver, answers, resolvConfText);
  }

  /**
   * A host whose private edge was recreated onto {@code 10.231.0.212}, and a container run before
   * that, on the overlay, still resolving through {@code 10.231.0.2} then the host's upstream.
   */
  public static ScriptedEdgeHost edgeMoved(PrivateEdgeResolver resolver) {
    return on(
        resolver,
        Map.of(
            "container inspect", "[\"10.231.0.2\",\"127.0.0.53\"] qits-net qits-private ",
            "network inspect", NETWORK_ID,
            "service ls", "y8w2v6b4c1d3",
            "service inspect", "[{\"NetworkID\":\"" + NETWORK_ID + "\",\"Addr\":\"10.231.0.212/20\"}]"),
        "nameserver 127.0.0.11\n# ExtServers: [host(127.0.0.53)]\n");
  }

  /** Every argv the resolver was handed, in order. */
  public List<List<String>> calls() {
    synchronized (calls) {
      return List.copyOf(calls);
    }
  }

  @Override
  public void close() {
    resolver.docker = realDocker;
    resolver.resolvConf = realResolvConf;
    try {
      Files.deleteIfExists(resolvConf);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
