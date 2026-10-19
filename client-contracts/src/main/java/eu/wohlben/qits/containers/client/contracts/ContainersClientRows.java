package eu.wohlben.qits.containers.client.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.client.ContainersAnswer;
import eu.wohlben.qits.containers.client.ContainersClient;
import eu.wohlben.qits.containers.client.ContainersWire.DeleteOutcome;
import eu.wohlben.qits.containers.client.ContainersWire.Destroyed;
import eu.wohlben.qits.containers.client.ContainersWire.EnsureRequest;
import eu.wohlben.qits.containers.client.ContainersWire.Envelope;
import eu.wohlben.qits.containers.client.ContainersWire.LogTail;
import eu.wohlben.qits.containers.client.ContainersWire.Policy;
import eu.wohlben.qits.containers.client.ContainersWire.Spec;
import eu.wohlben.qits.containers.client.ContainersWire.VolumeEnvelope;
import eu.wohlben.qits.containers.client.ContainersWire.VolumeState;
import eu.wohlben.qits.containers.client.TokenSource;
import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters.Operation;
import eu.wohlben.qits.pact.consumer.Trigger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Every row this jar knows, by {@link Feature}, with its drive (ticket qits-1163).
 *
 * <p>The states and operationIds are the ones {@code qits-containers-golden-masters} records. A row
 * whose state is NOT recorded is still listed: {@link ConsumerPact} leaves it out of the pact file
 * and {@code run} refuses it with {@code needs provider state '<state>' for <operationId> in
 * qits-containers-service}, so it is the request to the provider for that recording, and it joins
 * the consumer's pact on its own the day the recording is released.
 *
 * <p>What a row consumes is what the two consumers read of the answer — {@code containerName} and
 * {@code state.observed} of an envelope, the presence of a volume — plus, where the client binds a
 * body, one field of it: a 2xx whose body does not bind is a {@code Refused UNREADABLE} to {@code
 * ContainersClient}, so a pact that answered with no body would describe a call the client fails.
 */
final class ContainersClientRows {

  private ContainersClientRows() {}

  static final String RUNNING = "a running container";
  static final String NO_CONTAINER = "no container";
  static final String CLAIMED_VOLUME = "a claimed volume";
  static final String NO_VOLUME = "no volume";

  /** The delete door with {@code volumes=true}: the same seed as {@link #RUNNING}, deleted with its volumes. */
  static final String RUNNING_WITH_VOLUME = "a running container with its volumes";

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  /** {@code ContainersClient.<method>}: the call the consumer makes is what triggers the request. */
  static Trigger call(String method) {
    return Trigger.of("call", "method", "ContainersClient." + method);
  }

  private static ContractRow row(
      Feature feature, String method, String state, String operationId, ConsumerPact.Call drive,
      String... consumes) {
    return new ContractRow(
        feature, GoldenInteraction.of(call(method), state, operationId).consumes(consumes), drive);
  }

  static List<ContractRow> of(Feature feature) {
    return switch (feature) {
      case ENSURE ->
          List.of(
              row(feature, "ensure", NO_CONTAINER, "ensureContainer",
                  (url, op) -> assertEnvelope(op, ensure(url, op), true),
                  "containerName", "state.observed"),
              row(feature, "ensure", RUNNING, "ensureContainer",
                  (url, op) -> assertEnvelope(op, ensure(url, op), false),
                  "containerName", "state.observed"));
      case STATUS ->
          List.of(
              row(feature, "status", RUNNING, "getContainer",
                  (url, op) -> assertEnvelope(op, client(url).status(owner(op), workload(op), ref(op), TIMEOUT), false),
                  "containerName", "state.observed"),
              row(feature, "status", NO_CONTAINER, "getContainer",
                  (url, op) -> assertRefused(op, client(url).status(owner(op), workload(op), ref(op), TIMEOUT))));
      case LIST_WORKLOAD ->
          List.of(
              row(feature, "list", RUNNING, "listWorkloadContainers",
                  (url, op) -> assertListing(client(url).list(owner(op), workload(op), TIMEOUT)),
                  "containers[].containerName", "containers[].state.observed"));
      case LIST_OWNER ->
          List.of(
              row(feature, "list", RUNNING, "listOwnerContainers",
                  (url, op) -> assertListing(client(url).list(owner(op))),
                  "containers[].containerName", "containers[].state.observed"));
      case LOGS ->
          List.of(
              row(feature, "logs", RUNNING, "getContainerLogs",
                  (url, op) -> {
                    int tail = Integer.parseInt(op.query().get("tail").getFirst());
                    ContainersAnswer<LogTail> answer =
                        client(url).logs(owner(op), workload(op), ref(op), tail, TIMEOUT);
                    assertTrue(answer.succeeded(), answer.detail());
                    assertNotNull(answer.value().text());
                  },
                  "text", "truncated"));
      case STOP ->
          List.of(
              row(feature, "stop", RUNNING, "stopContainer",
                  (url, op) -> {
                    ContainersAnswer<Envelope> answer =
                        client(url).stop(owner(op), workload(op), ref(op), TIMEOUT);
                    assertTrue(answer.succeeded(), answer.detail());
                    assertNotNull(answer.value().state().observed());
                  },
                  "containerName", "state.observed"));
      case TOUCH ->
          List.of(
              row(feature, "touch", RUNNING, "touchContainer",
                  (url, op) -> {
                    ContainersAnswer<Void> answer =
                        client(url).touch(owner(op), workload(op), ref(op), TIMEOUT);
                    assertTrue(answer.succeeded(), answer.detail());
                  }));
      case DELETE ->
          List.of(
              row(feature, "delete", RUNNING, "deleteContainer",
                  (url, op) -> assertDeleted(url, op, false, true),
                  "existed"),
              row(feature, "delete", NO_CONTAINER, "deleteContainer",
                  (url, op) -> assertDeleted(url, op, false, false),
                  "existed"));
      case DELETE_WITH_VOLUMES ->
          List.of(
              row(feature, "delete", RUNNING_WITH_VOLUME, "deleteContainer",
                  (url, op) -> assertDeleted(url, op, true, true),
                  "existed"));
      case DESTROY_ALL ->
          List.of(
              row(feature, "destroyAll", RUNNING, "destroyWorkloadContainers",
                  (url, op) -> {
                    Instant before = Instant.parse(op.query().get("createdBefore").getFirst());
                    ContainersAnswer<List<Destroyed>> answer =
                        client(url).destroyAll(owner(op), workload(op), before, TIMEOUT);
                    assertTrue(answer.succeeded(), answer.detail());
                    assertFalse(answer.value().isEmpty(), "destroyed");
                    assertNotNull(answer.value().getFirst().ref());
                  },
                  "destroyed[].ref", "destroyed[].removed"));
      case VOLUME_ENSURE ->
          List.of(
              row(feature, "ensureVolume", NO_VOLUME, "ensureVolume",
                  (url, op) -> assertVolume(client(url).ensureVolume(owner(op), name(op), TIMEOUT), VolumeState.PRESENT),
                  "name", "desired"),
              row(feature, "ensureVolume", CLAIMED_VOLUME, "ensureVolume",
                  (url, op) -> assertVolume(client(url).ensureVolume(owner(op), name(op), TIMEOUT), VolumeState.PRESENT),
                  "name", "desired"));
      case VOLUME_GET ->
          List.of(
              row(feature, "volume", CLAIMED_VOLUME, "getVolume",
                  (url, op) -> assertVolume(client(url).volume(owner(op), name(op), TIMEOUT), VolumeState.PRESENT),
                  "name", "desired"),
              row(feature, "volume", NO_VOLUME, "getVolume",
                  (url, op) -> assertRefused(op, client(url).volume(owner(op), name(op), TIMEOUT))));
      case VOLUME_DELETE ->
          List.of(
              row(feature, "deleteVolume", CLAIMED_VOLUME, "deleteVolume",
                  (url, op) -> assertVolume(client(url).deleteVolume(owner(op), name(op), TIMEOUT), VolumeState.ABSENT),
                  "name", "desired"),
              row(feature, "deleteVolume", NO_VOLUME, "deleteVolume",
                  (url, op) -> assertVolume(client(url).deleteVolume(owner(op), name(op), TIMEOUT), VolumeState.ABSENT),
                  "name", "desired"));
    };
  }

  // --- the drives ----------------------------------------------------------------------------------

  /** The real client, as a consumer constructs it, pointed at the mock server. */
  static ContainersClient client(String url) {
    return new ContainersClient(url, TIMEOUT, TIMEOUT, TokenSource.none());
  }

  /**
   * The recorded ensure request, built with the client's own wire types: the image and network of
   * the recorded spec, an EXPLICIT lifetime, {@code recreate=never}.
   */
  private static ContainersAnswer<Envelope> ensure(String url, Operation op) {
    var spec = op.body().path("spec");
    EnsureRequest request =
        EnsureRequest.of(
            Spec.of(spec.path("image").asText(), spec.path("network").asText()),
            Policy.explicitLifetime());
    return client(url).ensure(owner(op), workload(op), ref(op), request, TIMEOUT);
  }

  private static void assertDeleted(String url, Operation op, boolean withVolumes, boolean existed) {
    ContainersAnswer<DeleteOutcome> answer =
        client(url).delete(owner(op), workload(op), ref(op), withVolumes, false, TIMEOUT);
    assertTrue(answer.succeeded(), answer.detail());
    assertEquals(existed, answer.value().existed(), "existed");
  }

  private static void assertEnvelope(Operation op, ContainersAnswer<Envelope> answer, boolean created) {
    assertTrue(answer.succeeded(), answer.detail());
    assertEquals(created, answer.created(), "created: " + answer.detail());
    assertNotNull(answer.value().containerName(), "containerName");
    assertNotNull(answer.value().state().observed(), "state.observed");
  }

  private static void assertListing(ContainersAnswer<List<Envelope>> answer) {
    assertTrue(answer.succeeded(), answer.detail());
    assertFalse(answer.value().isEmpty(), "containers");
    assertNotNull(answer.value().getFirst().containerName(), "containerName");
    assertNotNull(answer.value().getFirst().state().observed(), "state.observed");
  }

  private static void assertRefused(Operation op, ContainersAnswer<?> answer) {
    var refused = assertInstanceOf(ContainersAnswer.Refused.class, answer, answer.detail());
    assertEquals(op.status(), refused.status(), answer.detail());
  }

  private static void assertVolume(ContainersAnswer<VolumeEnvelope> answer, VolumeState desired) {
    assertTrue(answer.succeeded(), answer.detail());
    assertNotNull(answer.value(), "a volume");
    assertEquals(desired, answer.value().desired(), "desired");
  }

  private static String owner(Operation op) {
    return param(op, "owner");
  }

  private static String workload(Operation op) {
    return param(op, "workload");
  }

  private static String ref(Operation op) {
    return param(op, "ref");
  }

  private static String name(Operation op) {
    return param(op, "name");
  }

  private static String param(Operation op, String name) {
    Map<String, String> params = op.params();
    String value = params.get(name);
    assertNotNull(value, () -> op.state() + " has no param '" + name + "': " + params);
    return value;
  }
}
