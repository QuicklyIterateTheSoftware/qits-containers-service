package eu.wohlben.qits.containers.spec;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Validates every untrusted string that reaches a docker argv — the {@code CiIdentifiers} /
 * {@code DeploymentIdentifiers} posture, applied to this service's own vocabulary.
 *
 * <p>Everything here arrives from an owner: another platform module asking for a workload, over
 * HTTP, with its own repository-authored values behind it. So the image, the network, the aliases,
 * the volume names, the mount paths, the environment keys and the label keys are all
 * attacker-reachable by design, and all of them are checked rather than trusted.
 *
 * <p><b>Defence in depth, not the only guard.</b> An argv is assembled element by element for
 * {@link ProcessBuilder}, which never shell-splits. What these checks are really for is the
 * <b>element boundaries a value could forge</b>: a {@code :} inside a mount path would move the
 * boundary between a volume and its path and could append a mode field, a {@code =} inside a label
 * key would move the boundary between a key and its value, and a leading {@code -} would let a
 * positional argument be read as an option. There are two checkpoints — the API layer, later, and
 * {@code DockerArgv}, always — and this class is what both of them call.
 *
 * <p><b>Every refusal names the field and shows the value safely.</b> The offered value is echoed
 * back truncated and with its control characters stripped, because a refusal ends up in a log and in
 * an HTTP body, and a value that could carry a newline could forge a second log line.
 */
public final class ContainersIdentifiers {

  /**
   * The charset a name has to survive being a DNS label under: lowercase alphanumerics and dashes,
   * no leading dash. Docker is looser than this for most of these values; the platform is not,
   * because a name here becomes an address the platform's own containers resolve.
   */
  private static final String DNS_LABEL_CHARS = "[a-z0-9][a-z0-9-]*";

  /** How long an owner or a workload may be. Both are short words a module chose for itself. */
  public static final int OWNER_MAX = 64;

  /**
   * How long a ref may be. It is the owner's own identifier for the thing the workload belongs to —
   * a ci run id, a workspace id — so it is wide enough for a UUID and then some.
   */
  public static final int REF_MAX = 190;

  /** How long a container or volume name may be. Docker's own cap is higher; this is readable. */
  public static final int NAME_MAX = 190;

  /**
   * How long a network alias may be. <b>63, not {@link #NAME_MAX}</b>: an alias is resolved by
   * docker's embedded DNS and a DNS label is 63 octets, so a longer one is a name that would be
   * accepted here and refused by the resolver a container asks.
   */
  public static final int ALIAS_MAX = 63;

  /** The largest keep-storage a prune may be asked for: one petabyte, in bytes. */
  public static final long KEEP_STORAGE_MAX = 1_000_000_000_000_000L;

  /** Environment keys, POSIX-shaped — the charset a shell would accept for a variable name. */
  private static final String ENV_KEY = "[A-Za-z_][A-Za-z0-9_]*";

  /**
   * A docker volume name. <b>Deliberately wider than {@link #DNS_LABEL_CHARS}</b>, and the reason is
   * measured rather than tidy: the platform's three shared volumes are spelled with underscores
   * ({@code qits_shared_dot_claude}, {@code qits_shared_m2}, {@code qits_shared_pnpm}), which a
   * dns-label charset would refuse. A volume name is never an address, so the label charset buys
   * nothing here; what matters is that it cannot carry a {@code :} or a {@code /}, which is what
   * would forge a mount's own field boundaries or turn a named volume into a host bind.
   */
  private static final String VOLUME_NAME = "[a-zA-Z0-9][a-zA-Z0-9_.-]*";

  /** One side of an {@code --add-host} entry: a host name or an address, never punctuation. */
  private static final String HOST_ENTRY = "[A-Za-z0-9][A-Za-z0-9._:-]*";

  /**
   * The user a container runs as — a passwd name or a bare uid. Underscores are in because that is
   * what a system account is usually spelled with; a {@code :} is out because {@code --user}'s own
   * {@code user:group} form would let one value carry a group nobody declared.
   */
  private static final String USER_NAME = "[a-z0-9_][a-z0-9_-]*";

  /** A docker network name — the volume charset, for the same reasons. */
  private static final String NETWORK_NAME = "[a-zA-Z0-9][a-zA-Z0-9_.-]*";

  /**
   * What every buildx builder container is named with, and what its state volume is named for.
   *
   * <p>It lives here rather than in an argv or a sweep because both of them need it and neither may
   * own it: {@code DockerArgv} filters a {@code docker ps} on it, {@code control/VolumeGc} reads a
   * volume name against it, and the two answering differently would be a builder whose cache is
   * collected while it is running.
   */
  public static final String BUILDER_PREFIX = "buildx_buildkit_";

  /**
   * The platform's own buildkitd container — the one builder this service creates and owns, and the
   * address every build on the platform dials ({@code tcp://qits-buildkitd:1234} on the platform
   * network). A constant rather than configuration, for the same reason {@link #BUILDER_PREFIX} is:
   * the name is half of a cross-repo contract (the other half is the {@code BUILDKIT_HOST} every
   * step container is handed), and it guards a {@code docker exec} — a closed set of exec targets
   * has to be spelled where no configuration can widen it.
   */
  public static final String PLATFORM_BUILDER = "qits-buildkitd";

  /**
   * An image id, as {@code docker image ls --no-trunc} prints one: {@code sha256:} and 64 hex, or a
   * bare hex prefix of one. <b>It is not {@link #requireImage}</b>, deliberately — a reference may
   * carry a host, a port and a path, and the garbage collection only ever removes an image by the
   * id it read off the daemon a moment ago. Narrowing the belt to what the daemon prints is what
   * makes {@code image rm} unable to take a name at all.
   */
  private static final String IMAGE_ID = "(sha256:)?[0-9a-f]{12,64}";

  /**
   * A buildx builder's container name. The prefix is part of the pattern because it is part of the
   * claim: the only containers this service ever runs a command <em>inside</em> are the bootstrap
   * builders, and a belt that accepted any container name would make {@code docker exec} a general
   * capability rather than that one.
   */
  private static final String BUILDER_CONTAINER = BUILDER_PREFIX + "[a-zA-Z0-9][a-zA-Z0-9_.-]*";

  /**
   * A label key outside this service's namespace. Dotted segments, the docker convention; no
   * {@code =} and no whitespace, because both are the boundaries a {@code --label k=v} element is
   * split on.
   */
  private static final String LABEL_KEY = "[a-zA-Z0-9][a-zA-Z0-9._-]*";

  /** How much of a rejected value is echoed back. Long enough to recognise, short enough to log. */
  private static final int ECHO_MAX_CHARS = 80;

  private ContainersIdentifiers() {}

  /** The module asking for the workload — {@code qits-ci}, {@code qits-workspaces}. */
  public static String requireOwner(String owner) {
    return requireDnsLabel(owner, OWNER_MAX, "owner");
  }

  /** What kind of thing it is — {@code step}, {@code workspace}, {@code agent}. */
  public static String requireWorkload(String workload) {
    return requireDnsLabel(workload, OWNER_MAX, "workload");
  }

  /** The owner's own identifier for what this workload belongs to. Opaque here. */
  public static String requireRef(String ref) {
    return requireDnsLabel(ref, REF_MAX, "ref");
  }

  /** A container name, whether this service derived it or an owner brought its own. */
  public static String requireContainerName(String name) {
    return requireDnsLabel(name, NAME_MAX, "container name");
  }

  /**
   * A network alias — an address other containers resolve, so it is a real DNS label and is capped
   * at {@link #ALIAS_MAX}.
   */
  public static String requireAlias(String alias) {
    return requireDnsLabel(alias, ALIAS_MAX, "network alias");
  }

  /** A named docker volume, this service's own or one of the platform's shared ones. */
  public static String requireVolumeName(String name) {
    if (name == null || name.length() > NAME_MAX || !name.matches(VOLUME_NAME)) {
      throw refuse("volume name", name);
    }
    return name;
  }

  /**
   * The user the container's first process runs as — {@code docker run --user}.
   *
   * <p>A name or a uid, and the image has to back it: docker takes an unknown uid happily, but a
   * process running as one has no passwd entry, and anything that calls {@code getpwuid} — zonky's
   * {@code initdb} is the platform's measured case — fails on a user that exists only as a number.
   * That is the image's job to provide; what this belt is for is the argv element, which must not
   * be able to open with a {@code -} or carry a {@code :}.
   */
  public static String requireUser(String user) {
    if (user == null || user.isEmpty() || user.length() > OWNER_MAX || !user.matches(USER_NAME)) {
      throw refuse("user", user);
    }
    return user;
  }

  /**
   * A supplementary group the container joins — {@code docker run --group-add}.
   *
   * <p>Same shape as {@link #requireUser} and same belt, for the same argv reason. <b>No caller
   * supplies one</b>: the only group this service ever renders is the docker socket's own, read off
   * the socket by the deployment, so this belt guards a host fact rather than a request. That is
   * the whole difference between a privilege that was granted and one that was assembled — see
   * {@link ContainerSpec#hostDockerSocket()}.
   */
  public static String requireGroup(String group) {
    if (group == null || group.isEmpty() || group.length() > OWNER_MAX || !group.matches(USER_NAME)) {
      throw refuse("group", group);
    }
    return group;
  }

  /** The network a container is started on. One only — docker takes one at run time. */
  public static String requireNetwork(String network) {
    if (network == null || network.length() > NAME_MAX || !network.matches(NETWORK_NAME)) {
      throw refuse("network", network);
    }
    return network;
  }

  /**
   * The image reference, as the owner declared it.
   *
   * <p>Deliberately loose: a reference can carry a registry host, a port, a path, a tag and a
   * digest, and deciding which of those resolve is the registry's business. What it must not be is
   * blank, whitespace-carrying, or something the CLI could read as an option — it is a positional
   * argument, and "the argument parser will surely never take this for a flag" is not a claim worth
   * re-defending.
   */
  public static String requireImage(String image) {
    if (image == null
        || image.isBlank()
        || image.startsWith("-")
        || image.chars().anyMatch(Character::isWhitespace)
        || image.chars().anyMatch(Character::isISOControl)) {
      throw refuse("image", image);
    }
    return image;
  }

  /** An environment variable name, POSIX-shaped. The value beside it is the owner's business. */
  public static String requireEnvKey(String key) {
    if (key == null || key.isEmpty() || key.length() > NAME_MAX || !key.matches(ENV_KEY)) {
      throw refuse("environment key", key);
    }
    return key;
  }

  /**
   * A label key an owner supplied for its own bookkeeping.
   *
   * <p><b>The {@value ContainerLabels#NAMESPACE} prefix is refused, and that is the belt this whole
   * method exists for.</b> The labels in that namespace are what a sweep reads to decide whether a
   * container is this service's, so an owner that could write one could label somebody else's
   * workload as its own — or label its own as another owner's.
   */
  public static String requireExtraLabelKey(String key) {
    if (key == null || key.isEmpty() || key.length() > NAME_MAX || !key.matches(LABEL_KEY)) {
      throw refuse("label key", key);
    }
    if (key.toLowerCase(Locale.ROOT).startsWith(ContainerLabels.NAMESPACE)) {
      throw refuse("label key (the " + ContainerLabels.NAMESPACE + "namespace is this service's)", key);
    }
    return key;
  }

  /**
   * The same belt over a whole map, answering a sorted immutable copy — so a rendered argv is the
   * same list every time it is rendered, and a caller's mutable map cannot change under it.
   */
  public static Map<String, String> requireExtraLabels(Map<String, String> labels) {
    if (labels == null || labels.isEmpty()) {
      return Map.of();
    }
    Map<String, String> checked = new TreeMap<>();
    labels.forEach((k, v) -> checked.put(requireExtraLabelKey(k), v == null ? "" : v));
    return Collections.unmodifiableMap(checked);
  }

  /**
   * Where a mount lands inside the container. Absolute, and carrying no {@code :} — a colon there
   * would move the boundary between the volume and its path and could append a third field docker
   * reads as a mode.
   */
  public static String requireContainerPath(String path) {
    if (path == null
        || !path.startsWith("/")
        || path.length() > NAME_MAX * 2
        || path.contains(":")
        || path.chars().anyMatch(Character::isISOControl)) {
      throw refuse("container path", path);
    }
    return path;
  }

  /**
   * One {@code --add-host} entry, {@code name:target}. Both halves are checked, because the entry is
   * one argv element and either half could otherwise carry the separator that splits it.
   */
  public static String requireAddHost(String entry) {
    if (entry == null) {
      throw refuse("add-host", null);
    }
    int split = entry.indexOf(':');
    if (split <= 0 || split == entry.length() - 1) {
      throw refuse("add-host (expected name:target)", entry);
    }
    String name = entry.substring(0, split);
    String target = entry.substring(split + 1);
    if (name.length() > NAME_MAX || !name.matches(HOST_ENTRY) || name.contains(":")) {
      throw refuse("add-host name", entry);
    }
    if (target.length() > NAME_MAX || !target.matches(HOST_ENTRY)) {
      throw refuse("add-host target", entry);
    }
    return entry;
  }

  /**
   * An image id read off the daemon — see {@link #IMAGE_ID}.
   *
   * <p><b>Nothing removes an image by name here.</b> The collection lists, decides, and then removes
   * what it decided about by id; a belt that took a reference would let a tag an owner chose reach
   * an {@code image rm}, and the whole safety of the image sweep is that the keep rules run over
   * the tags rather than the tags reaching docker.
   */
  public static String requireImageId(String id) {
    if (id == null || !id.matches(IMAGE_ID)) {
      throw refuse("image id", id);
    }
    return id;
  }

  /**
   * The container name of a buildx builder — see {@link #BUILDER_CONTAINER}.
   *
   * <p>It is the one belt that guards a {@code docker exec}. No caller supplies the value: it comes
   * from a {@code docker ps} filtered on the same prefix, and this re-checks it, because a listing
   * is evidence about a host and not about what may be run inside one.
   */
  public static String requireBuilderContainer(String name) {
    if (name == null
        || name.length() > NAME_MAX
        || !(name.matches(BUILDER_CONTAINER) || PLATFORM_BUILDER.equals(name))) {
      throw refuse("builder container", name);
    }
    return name;
  }

  /**
   * How much build cache a prune may keep. Non-negative and no larger than a petabyte — the second
   * half is not tidiness: the number is rendered into an argv, and a value that overflowed on the
   * far side would be a prune keeping nothing.
   */
  public static long requireKeepStorageBytes(long bytes) {
    if (bytes < 0 || bytes > KEEP_STORAGE_MAX) {
      throw refuse("keep-storage bytes", String.valueOf(bytes));
    }
    return bytes;
  }

  private static String requireDnsLabel(String value, int max, String what) {
    if (value == null || value.isEmpty() || value.length() > max || !value.matches(DNS_LABEL_CHARS)) {
      throw refuse(what, value);
    }
    return value;
  }

  /**
   * The one refusal shape. It names the field and echoes the value, which is what makes a rejection
   * actionable — and it echoes it {@link #safely safely}, because the message is logged.
   */
  private static IllegalArgumentException refuse(String what, String value) {
    return new IllegalArgumentException("Invalid " + what + ": " + safely(value));
  }

  /**
   * A hostile value, rendered for a log line: control characters replaced, length capped. A newline
   * left in would let a refused value forge a second log entry, and an unbounded echo would let it
   * choose how much of the log it occupies.
   */
  static String safely(String value) {
    if (value == null) {
      return "(null)";
    }
    StringBuilder out = new StringBuilder(Math.min(value.length(), ECHO_MAX_CHARS));
    value
        .codePoints()
        .limit(ECHO_MAX_CHARS)
        .forEach(cp -> out.appendCodePoint(Character.isISOControl(cp) ? '?' : cp));
    return "'" + out + (value.length() > ECHO_MAX_CHARS ? "'..." : "'");
  }
}
