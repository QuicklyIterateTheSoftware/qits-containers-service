package eu.wohlben.qits.containers.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.spec.SpecReflection;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Guards the <em>completeness</em> of {@link SpecReflection}, which is the only part of it a JVM
 * suite can reach.
 *
 * <p><b>Say plainly what this cannot prove.</b> On a JVM every class reflects whether anyone
 * registered it or not, so nothing here would have failed on the build that shipped the binary
 * answering 500 to every {@code ensure}, and nothing here would fail if the annotation were deleted
 * tomorrow — except the assertion that reads the annotation itself. Only the native artifact,
 * running, proves the registration does its job. What is checkable is that the registered list still
 * covers every type the spec's JSON names, and that is what the first test below is: it walks {@link
 * ContainerSpec} the way Jackson does and compares what it finds against the list. A record
 * component added to the spec and forgotten in the holder fails here rather than in production.
 *
 * <p>It lives in {@code control} rather than beside the holder because {@link SpecFingerprint} is
 * package-private, and the round trip below is what ties the walk to the real serialization: the
 * types it reaches are the types that mapper touches.
 */
public class SpecReflectionCoverageTest {

  /** A spec with every component set, so nothing is reached only by a default. */
  private static ContainerSpec populated() {
    return ContainerSpec.builder("registry.example/qits/step:1.2.3")
        .entrypoint("/bin/sh")
        .args("-c", "echo hello")
        .env("QITS_TOKEN", "a-secret-that-must-not-be-stored")
        .label("qits.ci.run", "run-1")
        .network("qits-net")
        .alias("step-1")
        .addHost("host.docker.internal:host-gateway")
        .mount("qits-step-1-work", "/work")
        .shared("qits-maven-repo", "/root/.m2")
        .hostDockerSocket(true)
        .security(new ContainerSpec.SecurityPosture(true, true, "512m", "512m", 256L, "1.5", 1000))
        .pullPolicy(ContainerSpec.PullPolicy.ALWAYS)
        .name("qits-ci-step-1")
        .user("build")
        .init(true)
        .build();
  }

  @Test
  public void everyTypeTheSpecsJsonNamesIsRegistered() {
    RegisterForReflection registration =
        SpecReflection.class.getAnnotation(RegisterForReflection.class);
    assertNotNull(registration, "the annotation IS the class; without it that file is a no-op");
    assertEquals(
        reachable(ContainerSpec.class),
        Set.of(registration.targets()),
        "a record or enum reachable from ContainerSpec is missing from SpecReflection, which is a"
            + " native binary that answers 500 to every ensure");
  }

  /**
   * The walk tied to the real mapper: the same spec through the same calls the registry makes, both
   * ways, so the types the test above enumerates are the types that are really serialized rather
   * than the ones a reading of the record suggests.
   *
   * <p>What the persisted form leaves out is {@code CtEnsureTest}'s claim and is not restated here.
   */
  @Test
  public void theCanonicalFormNamesThoseTypesAndReadsBack() {
    ContainerSpec spec = populated();
    String json = SpecFingerprint.persistedJson(spec);

    for (String named :
        Set.of(
            "qits-step-1-work", // a VolumeMount
            "qits-maven-repo", // a SharedMount
            "512m", // a SecurityPosture field
            "build", // the user — a plain String component, so it needs no registration of its own
            "ALWAYS")) { // the PullPolicy enum
      assertTrue(json.contains(named), json + " does not name " + named);
    }

    ContainerSpec back = SpecFingerprint.fromPersistedJson(json);
    assertEquals(spec.volumeMounts(), back.volumeMounts());
    assertEquals(spec.sharedMounts(), back.sharedMounts());
    assertEquals(spec.security(), back.security());
    assertEquals(spec.pullPolicy(), back.pullPolicy());
    assertEquals(spec.user(), back.user());
    assertEquals(spec.init(), back.init());
    // hash() is the second path through the same records, over the form that keeps env.
    assertEquals(64, SpecFingerprint.hash(spec).length(), "sha256, hex");
  }

  /** Every record and enum Jackson can reach from a type, the way it reaches them. */
  private static Set<Class<?>> reachable(Class<?> root) {
    Set<Class<?>> found = new LinkedHashSet<>();
    walk(root, found);
    return found;
  }

  private static void walk(Type type, Set<Class<?>> found) {
    if (type instanceof ParameterizedType parameterized) {
      walk(parameterized.getRawType(), found);
      for (Type argument : parameterized.getActualTypeArguments()) {
        walk(argument, found);
      }
      return;
    }
    if (!(type instanceof Class<?> raw)) {
      return; // a wildcard or a type variable names no class to register
    }
    if (raw.isArray()) {
      walk(raw.getComponentType(), found);
      return;
    }
    // Primitives and the JDK's own types are what the platform's substitutions already cover; only
    // this repository's records and enums need declaring.
    if (raw.isPrimitive() || raw.getName().startsWith("java.") || !found.add(raw)) {
      return;
    }
    if (raw.isRecord()) {
      for (RecordComponent component : raw.getRecordComponents()) {
        walk(component.getGenericType(), found);
      }
    }
  }
}
