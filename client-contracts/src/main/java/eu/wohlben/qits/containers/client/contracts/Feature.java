package eu.wohlben.qits.containers.client.contracts;

import java.lang.constant.MethodTypeDesc;
import java.util.List;
import java.util.Optional;

/**
 * One group of {@code ContainersClient} calls (ticket qits-1163): a consumer's pact holds the rows
 * of exactly the features its real use calls. Every feature but {@link #DELETE_WITH_VOLUMES} is
 * derived from a method of the client, so {@link ClientUsage} can read the feature set off a
 * consumer's compiled classes; that one differs from {@link #DELETE} only in an argument, which a
 * class file does not show, so a consumer that passes {@code withVolumes=true} adds it with {@link
 * ContainersClientPact.Builder#uses}.
 */
public enum Feature {
  /** {@code ensure(...)}: {@code PUT /containers/{owner}/{workload}/{ref}}. */
  ENSURE,
  /** {@code status(...)}: {@code GET /containers/{owner}/{workload}/{ref}}, found and not. */
  STATUS,
  /** {@code list(owner, workload[, deadline])}: {@code GET /containers/{owner}/{workload}}. */
  LIST_WORKLOAD,
  /** {@code list(owner)}: {@code GET /containers/{owner}}. */
  LIST_OWNER,
  /** {@code logs(...)}: {@code GET /containers/{owner}/{workload}/{ref}/logs}. */
  LOGS,
  /** {@code stop(...)}: {@code POST …/stop}. */
  STOP,
  /** {@code touch(...)}: {@code POST …/touch}. */
  TOUCH,
  /** {@code delete(..., false, false[, deadline])}: {@code DELETE …?volumes=false&logs=false}. */
  DELETE,
  /** {@code delete(..., true, …)}: the same door with {@code volumes=true}. Explicit only. */
  DELETE_WITH_VOLUMES,
  /** {@code destroyAll(...)}: {@code DELETE /containers/{owner}/{workload}?createdBefore=…}. */
  DESTROY_ALL,
  /** {@code ensureVolume(...)}: {@code PUT /volumes/{owner}/{name}}. */
  VOLUME_ENSURE,
  /** {@code volume(...)}: {@code GET /volumes/{owner}/{name}}, found and not. */
  VOLUME_GET,
  /** {@code deleteVolume(...)}: {@code DELETE /volumes/{owner}/{name}}. */
  VOLUME_DELETE;

  /** This feature's rows, recorded or not, in a fixed order. */
  public List<ContractRow> rows() {
    return ContainersClientRows.of(this);
  }

  /**
   * The feature a call to {@code ContainersClient.<name><descriptor>} belongs to; empty for a
   * method no feature covers (a constructor, or a method this jar does not know yet — which {@link
   * ClientUsage} reports rather than skips).
   *
   * @param descriptor the JVM method descriptor, {@code (Ljava/lang/String;)L…;}
   */
  public static Optional<Feature> forCall(String name, String descriptor) {
    return Optional.ofNullable(
        switch (name) {
          case "ensure" -> ENSURE;
          case "status" -> STATUS;
          case "list" ->
              MethodTypeDesc.ofDescriptor(descriptor).parameterCount() == 1
                  ? LIST_OWNER
                  : LIST_WORKLOAD;
          case "logs" -> LOGS;
          case "stop" -> STOP;
          case "touch" -> TOUCH;
          case "delete" -> DELETE;
          case "destroyAll" -> DESTROY_ALL;
          case "ensureVolume" -> VOLUME_ENSURE;
          case "volume" -> VOLUME_GET;
          case "deleteVolume" -> VOLUME_DELETE;
          default -> null;
        });
  }
}
