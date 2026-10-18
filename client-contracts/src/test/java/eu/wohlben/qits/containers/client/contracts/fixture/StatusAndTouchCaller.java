package eu.wohlben.qits.containers.client.contracts.fixture;

import eu.wohlben.qits.containers.client.ContainersClient;
import java.util.function.BiFunction;

/** A consumer that calls {@code status} directly and {@code list(owner)} by method reference. */
public final class StatusAndTouchCaller {

  private StatusAndTouchCaller() {}

  public static boolean probe(ContainersClient client) {
    client.touch("o", "w", "r");
    BiFunction<String, String, ?> list = client::list;
    return client.status("o", "w", "r").succeeded() && list != null;
  }
}
