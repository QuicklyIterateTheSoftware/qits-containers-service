package eu.wohlben.qits.containers.contracts;

import au.com.dius.pact.core.model.Interaction;
import au.com.dius.pact.core.model.ProviderState;
import au.com.dius.pact.provider.junitsupport.filter.InteractionFilter;
import java.util.Set;
import java.util.function.Predicate;

/**
 * <b>A {@code @PactFilter} that drops the interactions naming one of the given states</b>, and keeps
 * every other interaction — also one that names no state at all.
 *
 * <p>pact-jvm's default filter keeps only interactions whose state matches, so an interaction with
 * no state passes neither verifier's filter and nobody verifies it. With this filter the main
 * verifier receives such an interaction, and {@link ConsumerPactVerificationTest} fails it.
 */
public class ExcludeStatesFilter<I extends Interaction> implements InteractionFilter<I> {

  @Override
  public Predicate<I> buildPredicate(String[] excluded) {
    Set<String> names = Set.of(excluded);
    return interaction ->
        interaction.getProviderStates().stream()
            .map(ProviderState::getName)
            .noneMatch(names::contains);
  }
}
