package eu.wohlben.qits.containers.contracts;

import eu.wohlben.qits.containers.control.ContainerNames;
import eu.wohlben.qits.containers.control.ContainerRegistry;
import eu.wohlben.qits.containers.control.FakeContainersDriver;
import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.persistence.CtContainerRepository;
import eu.wohlben.qits.containers.persistence.CtVolumeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * <b>qits-containers' provider states</b> (qits-1149): each seeds the rows and the scripted docker
 * host one consumer situation needs, and hands back the names a consumer puts in its path.
 *
 * <p>Two callers. {@link GoldenMasterRecordingTest} runs a state before recording each operation
 * that names it, and {@link ConsumerPactVerificationTest}'s {@code @State} methods delegate here.
 * Both call {@link #cleanUp()} afterwards, which wipes every row and resets the fake driver.
 *
 * <p>Both run under {@code FakeDriverProfile}, so docker is {@link FakeContainersDriver}. It is
 * injected through {@link Instance}: this bean exists in every profile, and the fake is an
 * alternative only that profile enables.
 */
@ApplicationScoped
public class ProviderStates {

  public static final String A_RUNNING_CONTAINER = "a running container";
  public static final String NO_CONTAINER = "no container";
  public static final String A_CLAIMED_VOLUME = "a claimed volume";
  public static final String NO_VOLUME = "no volume";
  public static final String A_HOST_WITH_RECLAIMABLE_STORAGE = "a host with reclaimable storage";

  static final String OWNER = "qits-workspaces";
  static final String WORKLOAD = "editor";
  static final String REF = "contract-1";
  static final String VOLUME = "contract-home";

  /** An image no container uses and no tag names, a week old. */
  static final String DANGLING_IMAGE =
      "sha256:3333333333333333333333333333333333333333333333333333333333333333";

  /** What a state hands back: its parameters, keys sorted, and its unique tokens (none here). */
  public record Setup(Map<String, String> params, List<String> uniqueTokens) {}

  @Inject Instance<FakeContainersDriver> driver;

  @Inject ContainerRegistry registry;

  @Inject CtContainerRepository containers;

  @Inject CtVolumeRepository volumes;

  private final Map<String, Supplier<Setup>> states = new LinkedHashMap<>();

  public ProviderStates() {
    states.put(A_RUNNING_CONTAINER, this::aRunningContainer);
    states.put(NO_CONTAINER, this::noContainer);
    states.put(A_CLAIMED_VOLUME, this::aClaimedVolume);
    states.put(NO_VOLUME, this::noVolume);
    states.put(A_HOST_WITH_RECLAIMABLE_STORAGE, this::aHostWithReclaimableStorage);
  }

  /** Every state name this provider answers for. */
  public Set<String> names() {
    return Collections.unmodifiableSet(states.keySet());
  }

  /** Runs the named state; an unknown name is a programming error, not an empty state. */
  public Setup setUp(String state) {
    Supplier<Setup> setup = states.get(state);
    if (setup == null) {
      throw new IllegalArgumentException(
          "No provider state '" + state + "' — this provider answers for " + states.keySet());
    }
    cleanUp();
    return setup.get();
  }

  /** {@link #setUp} for a pact {@code @State} method, which returns only the params. */
  public Map<String, String> params(String state) {
    return setUp(state).params();
  }

  /** Deletes every row and forgets everything the fake docker host was told. */
  public void cleanUp() {
    driver.get().reset();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              containers.deleteAll();
              volumes.deleteAll();
            });
  }

  /** The state's slug: lower-cased, every run of non-alphanumerics replaced by {@code -}. */
  public static String slug(String state) {
    return state.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
  }

  // --- the states ------------------------------------------------------------------------------

  /** One running container at {@code qits-workspaces/editor/contract-1}, with a line of logs. */
  private Setup aRunningContainer() {
    registry.ensure(
        OWNER,
        WORKLOAD,
        REF,
        ContainerSpec.builder("alpine:3").network("qits-net").build(),
        LifecyclePolicy.explicitLifetime(),
        false);
    driver.get().scriptLogs(ContainerNames.of(OWNER, WORKLOAD, REF), "listening on :8080\n");
    return place();
  }

  /** Nothing at {@code qits-workspaces/editor/contract-1}: the place an ensure creates. */
  private Setup noContainer() {
    return place();
  }

  /** {@code qits-workspaces} claims the volume {@code contract-home}. */
  private Setup aClaimedVolume() {
    registry.ensureVolume(OWNER, VOLUME);
    return volume();
  }

  /** {@code qits-workspaces} claims no volume: the one an ensure claims. */
  private Setup noVolume() {
    return volume();
  }

  /**
   * A docker host with something to collect: its usage, one dangling image a week old, and the
   * fake's default host build cache.
   */
  private Setup aHostWithReclaimableStorage() {
    FakeContainersDriver fake = driver.get();
    fake.scriptDiskUsage(
        new ContainersDriver.DiskUsage(
            new ContainersDriver.UsageLine(12, 4, 9_000_000_000L, 3_000_000_000L),
            new ContainersDriver.UsageLine(5, 4, 30_000_000L, 1_000_000L),
            new ContainersDriver.UsageLine(6, 3, 2_000_000_000L, 500_000_000L),
            new ContainersDriver.UsageLine(40, 0, 7_000_000_000L, 2_000_000_000L)));
    fake.scriptImage(
        DANGLING_IMAGE, List.of(), 90_000_000L, Instant.now().minus(Duration.ofDays(7)));
    return new Setup(Map.of(), List.of());
  }

  private static Setup place() {
    Map<String, String> params = new TreeMap<>();
    params.put("owner", OWNER);
    params.put("ref", REF);
    params.put("workload", WORKLOAD);
    return new Setup(params, List.of());
  }

  private static Setup volume() {
    Map<String, String> params = new TreeMap<>();
    params.put("name", VOLUME);
    params.put("owner", OWNER);
    return new Setup(params, List.of());
  }
}
