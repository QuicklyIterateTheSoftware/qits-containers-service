package eu.wohlben.qits.containers.control;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * The two forms a spec takes on a registry row: <b>what is stored</b> and <b>what is compared</b>.
 * They are deliberately different, and the difference is the whole security property of this table.
 *
 * <ul>
 *   <li>{@link #persistedJson} is the spec <b>with its environment removed</b>. Env is where a
 *       credential rides — a registry token, a database password, a machine token minted for one
 *       step — and a table that stored it would be the platform's largest collection of secrets.
 *       V1's header states the rule; this method is where it is enforced.
 *   <li>{@link #hash} is sha256 over a canonical form that <b>includes</b> env. Change detection has
 *       to see an env change: a workload whose token was rotated is a different workload, and a hash
 *       blind to that would leave a container running with a credential nobody uses any more. Hash
 *       yes, plaintext no — a digest is not reversible into the value it covers.
 * </ul>
 *
 * <p><b>The mapper is this class's own, never the CDI one</b>, for the reason qits-eventstream's
 * {@code CanonicalJson} gives: a consuming application's {@code ObjectMapperCustomizer}s must not be
 * able to reach a form that is compared byte for byte. Two knobs are set explicitly, and both are
 * what makes the comparison meaningful rather than incidental — map entries ordered by key, so an
 * env or label map built in a different order hashes the same; and properties sorted
 * alphabetically, so a record component added in the middle of {@link ContainerSpec} does not
 * reorder everything before it.
 *
 * <p>The same mapper produces the stored JSON, so {@code spec_json} is deterministic too. That costs
 * nothing and buys a diff between two rows that reads.
 *
 * <p><b>Every type this class serializes must be named in {@code spec/SpecReflection}.</b> That is
 * the price of the mapper being this class's own: a hand-built mapper is invisible to the build step
 * that decides what a native image may reflect over, and Quarkus registers only the types it finds
 * on a REST resource signature — which none of these are. A missing entry is green on the JVM, green
 * in this suite, and a 500 on every {@code ensure} the deployed binary answers. Measured
 * 2026-08-11. So a record component added to {@link ContainerSpec}, and any nested record or enum it
 * brings with it, is a change to that list too; {@code SpecReflectionCoverageTest} fails the build
 * when the two drift apart.
 */
final class SpecFingerprint {

  private static final ObjectMapper CANONICAL =
      JsonMapper.builder()
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
          .disable(SerializationFeature.INDENT_OUTPUT)
          .build();

  private SpecFingerprint() {}

  /** The spec as it goes on the row: everything except {@link ContainerSpec#env()}. */
  static String persistedJson(ContainerSpec spec) {
    return write(withoutEnv(spec));
  }

  /** A stored form back into a spec. Its env is empty, which is what was stored. */
  static ContainerSpec fromPersistedJson(String json) {
    try {
      return CANONICAL.readValue(json, ContainerSpec.class);
    } catch (Exception e) {
      throw new IllegalStateException("could not read a stored container spec", e);
    }
  }

  /** sha256, hex, over the canonical spec <b>including</b> env. */
  static String hash(ContainerSpec spec) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(write(spec).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is missing from this JVM", e);
    }
  }

  /** Any small value as canonical JSON — the volume row's label set uses it. */
  static String write(Object value) {
    try {
      return CANONICAL.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("could not serialize " + value.getClass().getName(), e);
    }
  }

  /**
   * The spec with an empty environment. Built through the canonical constructor rather than by
   * editing JSON, so the removal cannot be defeated by a field that is added later and forgotten
   * here: a new component arrives in both forms and only {@code env} is ever dropped.
   */
  private static ContainerSpec withoutEnv(ContainerSpec spec) {
    return new ContainerSpec(
        spec.image(),
        spec.entrypoint(),
        spec.args(),
        Map.of(),
        spec.extraLabels(),
        spec.network(),
        spec.aliases(),
        spec.addHosts(),
        spec.volumeMounts(),
        spec.sharedMounts(),
        spec.hostDockerSocket(),
        spec.security(),
        spec.pullPolicy(),
        spec.explicitName(),
        spec.user(),
        spec.init());
  }
}
