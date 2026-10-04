package eu.wohlben.qits.containers.control;

import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.entity.CtContainer;
import eu.wohlben.qits.containers.entity.CtVolume;
import eu.wohlben.qits.containers.entity.DesiredState;
import eu.wohlben.qits.containers.entity.ObservedState;
import eu.wohlben.qits.containers.entity.VolumeState;
import eu.wohlben.qits.containers.persistence.CtContainerRepository;
import eu.wohlben.qits.containers.persistence.CtVolumeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;

/**
 * What every registry suite here needs: the beans, a wiped registry, and a way to write a row
 * straight into the table.
 *
 * <p><b>The rows are seeded directly rather than through {@code ensure}</b>, and that is the point
 * rather than a shortcut — it is the {@code PdSweepAdoptionTest} stance. A sweep is a statement
 * about the rows a <em>dead process</em> left behind, and the only way to arrange one honestly is to
 * write the state that process would have left. Driving {@code ensure} to get there would arrange it
 * through the very code path the sweep exists because it cannot be trusted to have completed.
 *
 * <p>The tables are wiped before each test rather than between Quarkus restarts, because these
 * suites read whole listings — "the sweep touched exactly these rows" is not assertable against a
 * table another class left rows in.
 */
public abstract class CtTestSupport {

  @Inject FakeContainersDriver driver;
  @Inject CtContainerRepository containers;
  @Inject CtVolumeRepository volumes;
  @Inject ContainerRegistry registry;

  @BeforeEach
  void wipe() {
    driver.reset();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              containers.deleteAll();
              volumes.deleteAll();
            });
  }

  /** The plainest spec that passes every belt: an image and the network docker run takes. */
  protected static ContainerSpec spec(String image) {
    return ContainerSpec.builder(image).network("qits-net").build();
  }

  /** A row as a crashed process would have left it, with a real stored spec on it. */
  protected UUID seed(
      String owner,
      String workload,
      String ref,
      LifecyclePolicy policy,
      DesiredState desired,
      ObservedState observed) {
    return seed(owner, workload, ref, policy, desired, observed, Instant.now(), spec("alpine:3"));
  }

  protected UUID seed(
      String owner,
      String workload,
      String ref,
      LifecyclePolicy policy,
      DesiredState desired,
      ObservedState observed,
      Instant createdAt,
      ContainerSpec spec) {
    UUID id = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CtContainer row = new CtContainer();
              row.id = id;
              row.owner = owner;
              row.workload = workload;
              row.ownerRef = ref;
              row.containerName = ContainerNames.of(owner, workload, ref);
              row.image = spec.image();
              row.specJson = SpecFingerprint.persistedJson(spec);
              row.specHash = SpecFingerprint.hash(spec);
              row.policy = policy.type();
              row.idleAfterS = policy.idleAfter() == null ? null : policy.idleAfter().toSeconds();
              row.maxAgeS = policy.maxAge() == null ? null : policy.maxAge().toSeconds();
              row.desiredState = desired;
              row.observedState = observed;
              row.createdAt = createdAt;
              row.updatedAt = createdAt;
              containers.persist(row);
            });
    return id;
  }

  /** A volume row, for the reconcile's claimed/unclaimed arithmetic. */
  protected void seedVolume(String owner, String name, VolumeState desired) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CtVolume row = new CtVolume();
              row.id = UUID.randomUUID();
              row.owner = owner;
              row.name = name;
              row.desiredState = desired;
              row.createdAt = Instant.now();
              volumes.persist(row);
            });
  }

  /** One row, read back detached. */
  protected CtContainer row(UUID id) {
    return QuarkusTransaction.requiringNew().call(() -> containers.findById(id));
  }

  /** The derived name of a place, so a test can script the fake for a container it has not run. */
  protected static String nameOf(String owner, String workload, String ref) {
    return ContainerNames.of(owner, workload, ref);
  }

  protected void touchAt(UUID id, Instant when) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CtContainer row = containers.findById(id);
              row.lastTouchedAt = when;
            });
  }
}
