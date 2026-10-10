package eu.wohlben.qits.containers.contracts;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import au.com.dius.pact.core.model.Interaction;
import au.com.dius.pact.core.model.ProviderState;
import au.com.dius.pact.core.model.RequestResponseInteraction;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** The main verifier's filter: gated states out, every other interaction in — stateless too. */
class ExcludeStatesFilterTest {

  private final Predicate<Interaction> mainVerifier =
      new ExcludeStatesFilter<>()
          .buildPredicate(new String[] {ProviderStates.THE_MACHINE_GATE_IS_ON});

  @Test
  void dropsAGatedInteraction() {
    assertFalse(mainVerifier.test(interaction(ProviderStates.THE_MACHINE_GATE_IS_ON)));
  }

  @Test
  void keepsAnUngatedInteraction() {
    assertTrue(mainVerifier.test(interaction(ProviderStates.A_RUNNING_CONTAINER)));
  }

  @Test
  void keepsAnInteractionWithNoState() {
    assertTrue(mainVerifier.test(interaction()));
  }

  private static Interaction interaction(String... states) {
    return new RequestResponseInteraction(
        "an interaction", List.of(states).stream().map(ProviderState::new).toList());
  }
}
