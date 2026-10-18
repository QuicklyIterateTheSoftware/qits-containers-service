package eu.wohlben.qits.containers.client.contracts;

import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import java.util.Objects;

/**
 * One row of a {@code ContainersClient} consumer pact and the drive that proves it (ticket
 * qits-1163): the {@link GoldenInteraction} names the provider state, the operation and what the
 * client reads of the answer; the drive calls the REAL {@code ContainersClient} against the pact
 * mock server's URL and asserts what came back.
 *
 * @param feature the {@link Feature} the row belongs to
 * @param interaction the row as {@link ConsumerPact} reads it
 * @param drive the real client making the call
 */
public record ContractRow(Feature feature, GoldenInteraction interaction, ConsumerPact.Call drive) {

  public ContractRow {
    Objects.requireNonNull(feature, "feature");
    Objects.requireNonNull(interaction, "interaction");
    Objects.requireNonNull(drive, "drive");
  }

  /** {@code <description> [<state>]}, the row's name in a failure or a report. */
  public String name() {
    return interaction.description() + " [" + interaction.state() + "]";
  }

  @Override
  public String toString() {
    return name();
  }
}
