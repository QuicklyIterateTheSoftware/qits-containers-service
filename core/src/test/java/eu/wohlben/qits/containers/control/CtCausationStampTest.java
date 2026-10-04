package eu.wohlben.qits.containers.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.entity.CtContainer;
import eu.wohlben.qits.containers.entity.DesiredState;
import eu.wohlben.qits.containers.entity.ObservedState;
import eu.wohlben.qits.eventstream.CausationScope;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The measurement WP1 asked for: <b>does the causation stamp actually fire on this schema?</b>
 *
 * <p>WP1 recorded a warning from Hibernate's augmentation — {@code CausationStamp} is claimed by no
 * named persistence unit, because it lives in the eventstream jar's root package while the
 * {@code containers} unit claims {@code eu.wohlben.qits.containers.entity}. A listener that silently
 * did nothing would leave every row rootless with nothing to say so, which is the failure shape this
 * platform has already paid for once (qits-ci's live event runs, a full {@code trigger_event_id}
 * beside an empty {@code causation_id}).
 *
 * <p><b>Measured 2026-08-11: the stamp fires.</b> The listener is resolved from the entity's
 * {@code @EntityListeners} annotation rather than from a package claim, so the warning is about
 * where the class lives and not about whether it runs. That is why {@code ContainerRegistry} sets no
 * cause explicitly: its one insert happens on the caller's own thread, where {@code @PrePersist}
 * reads a scope that is still standing. Should this test ever go red, the house fallback is the
 * qits-ci one — set the cause as data through {@code CausedRow.causationId(UUID)} in
 * {@code ContainerRegistry.upsert} — and never a stamp that writes nothing.
 *
 * <p>The row is persisted directly through the repository rather than through {@code ensure}, on
 * purpose: what is under test is the listener, and routing through the registry would let registry
 * code be the reason a green assertion is green.
 */
@QuarkusTest
public class CtCausationStampTest extends CtTestSupport {

  @Test
  public void aRowPersistedInsideAScopeRecordsTheEventThatCausedIt() {
    UUID cause = UUID.randomUUID();

    AtomicReference<UUID> rowId = new AtomicReference<>();
    CausationScope.with(cause, () -> rowId.set(persist("qits-ci", "step", "run-caused")));

    assertEquals(
        cause,
        row(rowId.get()).causationId,
        "the @EntityListeners stamp fires despite the augmentation warning — see the class javadoc");
  }

  @Test
  public void aRowPersistedOutsideAnyScopeIsRootless() {
    UUID rowId = persist("qits-ci", "step", "run-rootless");

    assertNull(
        row(rowId).causationId,
        "no scope is no cause, exactly as an event published outside one is a chain root");
  }

  /** A bare insert through the repository — no registry code between the scope and the persist. */
  private UUID persist(String owner, String workload, String ref) {
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
              row.image = "alpine:3";
              row.specJson = SpecFingerprint.persistedJson(spec("alpine:3"));
              row.specHash = SpecFingerprint.hash(spec("alpine:3"));
              row.policy = LifecyclePolicy.Type.EPHEMERAL;
              row.desiredState = DesiredState.RUNNING;
              row.observedState = ObservedState.PENDING;
              row.createdAt = Instant.now();
              row.updatedAt = row.createdAt;
              containers.persist(row);
            });
    return id;
  }
}
