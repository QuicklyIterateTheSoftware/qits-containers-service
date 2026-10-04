package eu.wohlben.qits.containers.control;

import eu.wohlben.qits.containers.driver.spec.ContainersIdentifiers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The container name this service derives when an owner does not bring its own.
 *
 * <p><b>The name is chosen before the container exists and is written to the row first</b>, so it is
 * the address every later inspect, stop and remove uses. It is derived rather than random for one
 * reason: a person reading {@code docker ps} on the host has to be able to tell whose workload a
 * container is without asking this service anything.
 *
 * <p>{@code qits-ct-<owner>-<workload>-<ref>}, the {@code qits-pd-} shape from
 * qits-platform-deployments. The prefix is short for the same reason that one is: docker's name
 * charset has no dot, and spelling the service out would spend fifteen characters before the words a
 * person reads.
 *
 * <p><b>A ref that would overflow the name is replaced by a digest of itself</b>, not truncated.
 * Truncation would map two different places onto one name, and a name is unique among live rows
 * (V3's index) — so the second workload would be refused rather than started. Twelve hex characters
 * of sha256 is 48 bits, which for the number of live containers one host runs is collision-free in
 * practice and, unlike a truncation, is at least detectable as a name rather than as a ref.
 *
 * <p><b>Deterministic per place, deliberately, and that is why the uniqueness is partial.</b> The
 * same place asked for twice derives the same name, so a place this service deleted and is asked for
 * again wants back the name its own settled row still records. V1 declared the column unique
 * table-wide and refused exactly that for as long as the row prune took — see V3's header.
 */
public final class ContainerNames {

  /** What every derived name starts with. See the class javadoc for why it is not spelled out. */
  public static final String PREFIX = "qits-ct-";

  /** How much of a digest stands in for a ref too long to spell. */
  private static final int DIGEST_CHARS = 12;

  private ContainerNames() {}

  /**
   * The derived name for one place. Every input is belt-checked by
   * {@link ContainersIdentifiers} before it arrives here, and the answer is checked again on the way
   * out — the name is what reaches an argv, and this is the last line before it does.
   */
  public static String of(String owner, String workload, String ownerRef) {
    ContainersIdentifiers.requireOwner(owner);
    ContainersIdentifiers.requireWorkload(workload);
    ContainersIdentifiers.requireRef(ownerRef);
    String full = PREFIX + owner + "-" + workload + "-" + ownerRef;
    String name =
        full.length() <= ContainersIdentifiers.NAME_MAX
            ? full
            : PREFIX + owner + "-" + workload + "-" + digest(ownerRef);
    return ContainersIdentifiers.requireContainerName(name);
  }

  private static String digest(String value) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash).substring(0, DIGEST_CHARS);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is missing from this JVM", e);
    }
  }
}
