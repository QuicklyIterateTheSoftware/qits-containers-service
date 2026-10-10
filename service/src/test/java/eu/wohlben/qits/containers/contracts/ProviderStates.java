package eu.wohlben.qits.containers.contracts;

import eu.wohlben.qits.containers.control.ContainerNames;
import eu.wohlben.qits.containers.control.ContainerRegistry;
import eu.wohlben.qits.containers.control.FakeContainersDriver;
import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.persistence.CtContainerRepository;
import eu.wohlben.qits.containers.persistence.CtVolumeRepository;
import eu.wohlben.qits.containers.spec.ContainerLabels;
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

  /**
   * The machine gate is on: a bearer no idp issued answers 401. Only {@code MachineGuardProfile}
   * runs the service gated, so only {@link GatedGoldenMasterRecordingTest} and {@link
   * GatedConsumerPactVerificationTest} use this state. It seeds nothing.
   */
  public static final String THE_MACHINE_GATE_IS_ON = "the machine gate is on";

  /** States only a gated application can answer for. */
  public static final Set<String> GATED = Set.of(THE_MACHINE_GATE_IS_ON);

  /** A {@code @PactFilter} regex matching every state except the gated ones. */
  public static final String UNGATED_STATES = "^(?!" + THE_MACHINE_GATE_IS_ON + "$).*";

  static final String OWNER = "qits-workspaces";
  static final String WORKLOAD = "editor";
  static final String REF = "contract-1";
  static final String VOLUME = "contract-home";

  /** The owner the bootstrap's auth-plane probe names. */
  static final String GATED_OWNER = "dev-qits-ci";

  /** An image no container uses and no tag names, a week old. */
  static final String DANGLING_IMAGE =
      "sha256:3333333333333333333333333333333333333333333333333333333333333333";

  /** A tagged image no pin names, a week old: collected as unpinned. */
  static final String UNPINNED_IMAGE =
      "sha256:4444444444444444444444444444444444444444444444444444444444444444";

  /** A tagged image the request's keep-set names: kept as pinned. */
  static final String PINNED_IMAGE =
      "sha256:5555555555555555555555555555555555555555555555555555555555555555";

  /** The tag of {@link #PINNED_IMAGE}, as the orchestrator's keep-set spells it. */
  static final String PINNED_TAG = "qits/qits-ci:2026.1001.120000";

  /** A managed volume no row claims, a week old: collected as managed-no-row. */
  static final String ORPHAN_VOLUME = "ws-data-orphan";

  /** A volume nothing here accounts for: kept as unmanaged. */
  static final String FOREIGN_VOLUME = "somebody-elses";

  /** A bootstrap builder container, pruned beside the platform's own buildkitd. */
  static final String BOOTSTRAP_BUILDER = "buildx_buildkit_qits-bootstrap-builder-v40";

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
    states.put(THE_MACHINE_GATE_IS_ON, this::theMachineGateIsOn);
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
   * A docker host with something to collect and something to keep in every store: its usage; a
   * dangling and an unpinned image (removed) beside a pinned one (kept); an orphan managed volume
   * (removed) beside a foreign one (kept); and a host build cache and one bootstrap builder, each
   * with bytes to reclaim. Everything is a week old, older than any {@code minAge} a consumer sends.
   */
  private Setup aHostWithReclaimableStorage() {
    FakeContainersDriver fake = driver.get();
    Instant weekOld = Instant.now().minus(Duration.ofDays(7));
    fake.scriptDiskUsage(
        new ContainersDriver.DiskUsage(
            new ContainersDriver.UsageLine(12, 4, 9_000_000_000L, 3_000_000_000L),
            new ContainersDriver.UsageLine(5, 4, 30_000_000L, 1_000_000L),
            new ContainersDriver.UsageLine(6, 3, 2_000_000_000L, 500_000_000L),
            new ContainersDriver.UsageLine(40, 0, 7_000_000_000L, 2_000_000_000L)));
    fake.scriptImage(DANGLING_IMAGE, List.of(), 90_000_000L, weekOld);
    fake.scriptImage(UNPINNED_IMAGE, List.of("qits/qits-ci:2026.901.120000"), 400_000_000L, weekOld);
    fake.scriptImage(PINNED_IMAGE, List.of(PINNED_TAG), 410_000_000L, weekOld);
    fake.scriptDanglingVolumes(List.of(ORPHAN_VOLUME, FOREIGN_VOLUME));
    fake.scriptVolumeDetail(
        ORPHAN_VOLUME, Map.of(ContainerLabels.MANAGED, ContainerLabels.MANAGED_VOLUME), weekOld);
    fake.scriptVolumeDetail(FOREIGN_VOLUME, Map.of(), weekOld);
    fake.scriptBuilders(List.of(BOOTSTRAP_BUILDER));
    fake.scriptHostCache(new ContainersDriver.CacheResult(true, 2_000_000_000L, "Total: 2GB"));
    fake.scriptBuilderCache(
        BOOTSTRAP_BUILDER, new ContainersDriver.CacheResult(true, 1_500_000_000L, "Total: 1.5GB"));
    return new Setup(Map.of(), List.of());
  }

  /** Nothing seeded: what this state stands for is the profile, not a row. */
  private Setup theMachineGateIsOn() {
    return new Setup(Map.of("owner", GATED_OWNER), List.of());
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
