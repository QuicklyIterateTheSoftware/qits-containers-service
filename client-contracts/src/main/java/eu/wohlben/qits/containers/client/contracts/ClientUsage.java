package eu.wohlben.qits.containers.client.contracts;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Which {@code ContainersClient} methods a consumer's compiled code calls (ticket qits-1163), read
 * off the constant pools of its class files: every call site and every method reference ({@code
 * containers::status}) names its target there as a {@code Methodref} to {@code
 * eu/wohlben/qits/containers/client/ContainersClient}. No class is loaded and no code runs.
 *
 * @param calls each called method, {@code name + descriptor}, with the classes that call it
 * @param unknown called methods no {@link Feature} covers
 */
public record ClientUsage(Map<String, Set<String>> calls, Set<String> unknown) {

  /** The internal name every reference to the client is spelled with. */
  public static final String OWNER = "eu/wohlben/qits/containers/client/ContainersClient";

  /** Every {@code .class} file under {@code roots}, which must exist. */
  public static ClientUsage scan(List<Path> roots) {
    Map<String, Set<String>> calls = new TreeMap<>();
    for (Path root : roots) {
      if (!Files.isDirectory(root)) {
        throw new IllegalStateException(
            "no compiled classes at " + root.toAbsolutePath()
                + "; compile the consumer first, or name its class directories with scanning(...)");
      }
      try (Stream<Path> files = Files.walk(root)) {
        for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
          read(file, calls);
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    Set<String> unknown = new TreeSet<>();
    calls.keySet().forEach(call -> {
      if (feature(call) == null && !call.startsWith("<init>")) {
        unknown.add(call);
      }
    });
    return new ClientUsage(calls, unknown);
  }

  private static void read(Path file, Map<String, Set<String>> calls) throws IOException {
    var model = ClassFile.of().parse(Files.readAllBytes(file));
    String caller = model.thisClass().asInternalName().replace('/', '.');
    for (PoolEntry entry : model.constantPool()) {
      if (entry instanceof MemberRefEntry ref
          && !(entry instanceof java.lang.classfile.constantpool.FieldRefEntry)
          && ref.owner().asInternalName().equals(OWNER)) {
        String call = ref.name().stringValue() + ref.type().stringValue();
        calls.computeIfAbsent(call, k -> new TreeSet<>()).add(caller);
      }
    }
  }

  /** The features the scanned calls belong to. */
  public Set<Feature> features() {
    Set<Feature> features = new TreeSet<>();
    calls.keySet().forEach(call -> {
      Feature feature = feature(call);
      if (feature != null) {
        features.add(feature);
      }
    });
    return features;
  }

  private static Feature feature(String call) {
    int paren = call.indexOf('(');
    return Feature.forCall(call.substring(0, paren), call.substring(paren)).orElse(null);
  }
}
