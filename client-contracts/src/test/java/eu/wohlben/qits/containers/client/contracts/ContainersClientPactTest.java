package eu.wohlben.qits.containers.client.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.ProviderStateMissing;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Every row this jar ships, run through its drive — the real {@code ContainersClient} — against a
 * pact mock server, under a dummy consumer (ticket qits-1163). Nothing here writes a pact file:
 * the pact belongs to each consuming service.
 */
class ContainersClientPactTest {

  static final String DUMMY = "qits-containers-client-contracts-test";

  /** Every feature, no scan: the whole surface. */
  static final ContainersClientPact.Builder ALL =
      ContainersClientPact.forConsumer(DUMMY).scanning().uses(Feature.values());

  @TestFactory
  Stream<DynamicTest> everyRecordedRowDrivesTheRealClient() {
    ConsumerPact pact = ALL.pact();
    return ALL.rows().stream()
        .filter(row -> pact.recorded().contains(row.interaction()))
        .map(row -> DynamicTest.dynamicTest(row.name(), () -> ALL.run(row)));
  }

  @Test
  void runRecordedPassesForTheWholeSurface() {
    ALL.runRecorded();
  }

  /** The rows whose provider state is not recorded yet: the list a provider recording closes. */
  @Test
  void theUnrecordedRowsAreTheOnesListed() {
    assertEquals(
        List.of(
            "needs provider state 'a running container' for ensureContainer in qits-containers-service",
            "needs provider state 'a running container with a named volume' for deleteContainer in qits-containers-service",
            "needs provider state 'a claimed volume' for ensureVolume in qits-containers-service"),
        ALL.unrecorded());
    ContractRow unrecorded = Feature.DELETE_WITH_VOLUMES.rows().getFirst();
    assertThrows(ProviderStateMissing.class, () -> ALL.run(unrecorded));
  }

  @Test
  void thePactNamesTheClientAsViaAndKeepsTheTwoPartFile() {
    ConsumerPact pact = ALL.pact();
    assertEquals(DUMMY + "_qits-containers-service.json", pact.file());
    assertEquals(ContainersClientPact.VIA, pact.via().orElseThrow());
    assertEquals("qits-containers-service", ContainersClientPact.VIA.repository());
    assertEquals("eu.wohlben.qits:qits-containers-client", ContainersClientPact.VIA.artifact());
    assertFalse(ContainersClientPact.VERSION.contains("$"), ContainersClientPact.VERSION);
    pact.assertEveryInteractionCarriesBothReferences();
  }

  /** This module's own drives reference every method, so the scan of them derives every feature. */
  @Test
  void theScanOfTheDrivesDerivesEveryMethodFeature() {
    Set<Feature> derived =
        ContainersClientPact.forConsumer(DUMMY).scanning(Path.of("target", "classes")).features();
    Set<Feature> expected = EnumSet.allOf(Feature.class);
    expected.remove(Feature.DELETE_WITH_VOLUMES);
    assertEquals(expected, derived);
  }

  @Test
  void theScanOfAConsumerDerivesOnlyWhatItCalls() {
    ContainersClientPact.Builder fixture =
        ContainersClientPact.forConsumer(DUMMY)
            .scanning(Path.of("target", "test-classes", "eu", "wohlben", "qits", "containers",
                "client", "contracts", "fixture"));
    assertEquals(EnumSet.of(Feature.STATUS, Feature.TOUCH, Feature.LIST_WORKLOAD), fixture.features());
    assertEquals(
        EnumSet.of(Feature.STATUS, Feature.TOUCH, Feature.LIST_WORKLOAD, Feature.DELETE_WITH_VOLUMES),
        fixture.uses(Feature.DELETE_WITH_VOLUMES).features());
    assertTrue(fixture.rows().stream().allMatch(r -> fixture.features().contains(r.feature())));
  }

  @Test
  void aScanThatFindsNoCallFailsInsteadOfWritingAnEmptyPact() {
    Path empty = Path.of("target", "test-classes", "nothing-here");
    empty.toFile().mkdirs();
    assertThrows(AssertionError.class,
        () -> ContainersClientPact.forConsumer(DUMMY).scanning(empty).features());
    assertThrows(IllegalStateException.class,
        () -> ContainersClientPact.forConsumer(DUMMY).scanning(Path.of("target", "no-such")).features());
  }

  @Test
  void everyRowIsUniqueAndNamesItsFeaturesMethod() {
    List<ContractRow> rows = ContainersClientPact.allRows();
    assertEquals(rows.size(), rows.stream().map(ContractRow::name).distinct().count());
    for (ContractRow row : rows) {
      assertTrue(row.interaction().description().startsWith("ContainersClient."), row.name());
    }
  }
}
