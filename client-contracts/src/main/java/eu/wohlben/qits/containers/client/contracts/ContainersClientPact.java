package eu.wohlben.qits.containers.client.contracts;

import eu.wohlben.qits.containers.client.ContainersClient;
import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Via;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;

/**
 * <b>The pact a consumer of {@code qits-containers-client} writes</b> (ticket qits-1163).
 *
 * <p>A call a library makes on a service's behalf is pacted by that service, with only the calls
 * its real use sends. So the consumer is the service ({@code qits-projects-service}, {@code
 * qits-workspaces-service}), the provider is {@code qits-containers-service}, every interaction
 * carries {@code qits-via} naming this client and its version ({@link #VIA}), and — because the
 * client is published from the provider's own repository — the file keeps the two-part name {@code
 * pacts/<consumer>_qits-containers-service.json}.
 *
 * <p>Which calls are "its real use" is read off the consumer's compiled main classes ({@link
 * ClientUsage}): every {@code ContainersClient} method they reference selects that method's {@link
 * Feature}, and the pact holds exactly those features' rows. A consumer adds what a class file
 * cannot show with {@link Builder#uses} (today only {@link Feature#DELETE_WITH_VOLUMES}).
 *
 * <pre>{@code
 * class ContainersPactTest {
 *   static final ContainersClientPact.Builder CONTAINERS =
 *       ContainersClientPact.forConsumer("qits-workspaces-service");
 *
 *   @Test void everyRecordedRowRunsTheRealClient() { CONTAINERS.runRecorded(); }
 *   @Test void theCommittedPactIsWhatTheRowsWrite() { CONTAINERS.pact().compareOrWritePactFile(); }
 * }
 * }</pre>
 */
public final class ContainersClientPact {

  /** The provider's repository, as the pact names it. */
  public static final String PROVIDER = "qits-containers-service";

  /** The client's maven coordinate. */
  public static final String ARTIFACT = "eu.wohlben.qits:qits-containers-client";

  /** This jar's version, which is the client version it was built against. */
  public static final String VERSION = version();

  /** The {@code qits-via} every interaction carries. */
  public static final Via VIA = new Via(PROVIDER, ARTIFACT, VERSION);

  /** The provider's golden masters, from {@code eu.wohlben.qits:qits-containers-golden-masters}. */
  public static final GoldenMasters MASTERS = GoldenMasters.of(PROVIDER, "qits-containers");

  /** Where a consumer's compiled main classes are when surefire runs in that module. */
  public static final Path DEFAULT_CLASSES = Path.of("target", "classes");

  private ContainersClientPact() {}

  /** Every row this jar knows, of every feature. */
  public static List<ContractRow> allRows() {
    return Arrays.stream(Feature.values()).flatMap(f -> f.rows().stream()).toList();
  }

  /**
   * The pact of {@code consumer}, derived from its classes under {@code target/classes} unless
   * {@link Builder#scanning} names others.
   *
   * @param consumer the consumer's repository name ({@code qits-projects-service})
   */
  public static Builder forConsumer(String consumer) {
    return new Builder(consumer, List.of(DEFAULT_CLASSES), EnumSet.noneOf(Feature.class));
  }

  /** One consumer's selection. Immutable; each method returns a new one. */
  public static final class Builder {

    private final String consumer;
    private final List<Path> scan;
    private final Set<Feature> explicit;

    private Builder(String consumer, List<Path> scan, Set<Feature> explicit) {
      this.consumer = Objects.requireNonNull(consumer, "consumer");
      this.scan = List.copyOf(scan);
      this.explicit = explicit.isEmpty() ? EnumSet.noneOf(Feature.class) : EnumSet.copyOf(explicit);
    }

    /** Scans these class directories instead; none at all turns the scan off. */
    public Builder scanning(Path... classDirectories) {
      return new Builder(consumer, List.of(classDirectories), explicit);
    }

    /** Adds {@code features} to what the scan derives. */
    public Builder uses(Feature... features) {
      Set<Feature> all = EnumSet.noneOf(Feature.class);
      all.addAll(explicit);
      all.addAll(List.of(features));
      return new Builder(consumer, scan, all);
    }

    public String consumer() {
      return consumer;
    }

    /**
     * What the scan found. Fails when the scanned classes call a {@code ContainersClient} method
     * no feature covers — a client newer than this jar, or a feature this jar forgot — and when a
     * scan was asked for and found no call at all, which is a wrong directory far more often than
     * a consumer that stopped calling the client.
     */
    public ClientUsage usage() {
      if (scan.isEmpty()) {
        return new ClientUsage(java.util.Map.of(), Set.of());
      }
      ClientUsage usage = ClientUsage.scan(scan);
      Assertions.assertTrue(usage.unknown().isEmpty(), () -> consumer
          + " calls ContainersClient methods no Feature covers: " + usage.unknown()
          + "; qits-containers-client-contracts " + VERSION + " needs rows for them");
      Assertions.assertFalse(usage.calls().isEmpty(), () -> "no call to " + ClientUsage.OWNER
          + " under " + scan.stream().map(p -> p.toAbsolutePath().toString()).toList()
          + "; name the consumer's class directories with scanning(...)");
      return usage;
    }

    /** The derived features plus the explicit ones. */
    public Set<Feature> features() {
      Set<Feature> all = EnumSet.noneOf(Feature.class);
      all.addAll(usage().features());
      all.addAll(explicit);
      return all;
    }

    /** The rows of {@link #features()}, recorded or not. */
    public List<ContractRow> rows() {
      List<ContractRow> rows = new ArrayList<>();
      features().forEach(f -> rows.addAll(f.rows()));
      return rows;
    }

    /**
     * The consumer pact, with {@link #VIA}: {@code compareOrWritePactFile()} on it writes {@code
     * pacts/<consumer>_qits-containers-service.json}. Fails when the client on the classpath is
     * not the version this jar names, since the via would then name a version the consumer does
     * not run.
     */
    public ConsumerPact pact() {
      assertClientVersion();
      return ConsumerPact.of(consumer, MASTERS, rows().stream().map(ContractRow::interaction).toList())
          .via(VIA);
    }

    /** Runs one row's drive against a pact mock server serving that row alone. */
    public void run(ContractRow row) {
      pact().run(row.interaction(), row.drive());
    }

    /**
     * Runs every recorded row of this consumer through its drive, collecting the failures, and
     * fails naming each. The unrecorded rows are skipped here; {@link #unrecorded()} names them.
     */
    public void runRecorded() {
      ConsumerPact pact = pact();
      Set<GoldenInteraction> recorded = Set.copyOf(pact.recorded());
      List<String> failures = new ArrayList<>();
      for (ContractRow row : rows()) {
        if (!recorded.contains(row.interaction())) {
          continue;
        }
        try {
          pact.run(row.interaction(), row.drive());
        } catch (AssertionError | RuntimeException e) {
          failures.add(row.name() + ": " + e.getMessage());
        }
      }
      Assertions.assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    /** The rows still waiting for a provider state, each with the sentence that says so. */
    public List<String> unrecorded() {
      ConsumerPact pact = pact();
      return pact.unrecorded().stream().map(pact::needs).toList();
    }

    @Override
    public String toString() {
      return consumer + " " + features().stream().map(Enum::name).collect(Collectors.joining(",", "[", "]"));
    }
  }

  /**
   * The client on the classpath against {@link #VERSION}, read from the jar's own {@code
   * pom.properties}. Skipped when there is none, which is this reactor's own build (the client is
   * a class directory there, at the same version by construction).
   */
  static void assertClientVersion() {
    String resource = "META-INF/maven/eu.wohlben.qits/qits-containers-client/pom.properties";
    try (InputStream in = ContainersClient.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        return;
      }
      Properties props = new Properties();
      props.load(in);
      String onClasspath = props.getProperty("version");
      Assertions.assertEquals(VERSION, onClasspath, () -> "qits-containers-client " + onClasspath
          + " is on the classpath but qits-containers-client-contracts is " + VERSION
          + "; pin both at the same version");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String version() {
    try (InputStream in = ContainersClientPact.class.getResourceAsStream("version.properties")) {
      if (in == null) {
        throw new IllegalStateException("version.properties is missing from this jar");
      }
      Properties props = new Properties();
      props.load(in);
      String version = props.getProperty("version");
      if (version == null || version.isBlank() || version.contains("${")) {
        throw new IllegalStateException("version.properties was not filtered: " + version);
      }
      return version;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
