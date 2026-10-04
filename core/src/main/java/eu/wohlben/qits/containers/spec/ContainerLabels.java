package eu.wohlben.qits.containers.spec;

import eu.wohlben.qits.containers.driver.spec.LabelNamespace;
import java.util.Map;

/**
 * The one label namespace this service reads and writes, and the only vocabulary it understands.
 *
 * <p><b>Legacy vocabularies are never read.</b> {@code qits.ci.run}, {@code qits.managed},
 * {@code qits.workspace.*} and {@code qits.platform.deployments.*} exist on this host and none of
 * them is consulted here, ever. The <b>absence</b> of a {@value #NAMESPACE} label is itself the
 * statement: an unlabelled container is somebody else's — a compose original, a bootstrap seed, a
 * container from before this service existed — and foreign means untouchable. Reading a legacy
 * label would turn "not mine" into "mine, under an old spelling", which is exactly the reap this
 * repository was built to remove.
 *
 * <p><b>And a label is never the registry.</b> The rows are. These labels are how a container
 * describes itself to a person reading {@code docker ps} and how a listing narrows before the rows
 * decide; no code path may remove a container because it carries one. That is the first invariant in
 * AGENTS.md, and it is why {@link #MANAGED} is a statement of kind rather than a licence.
 *
 * <p><b>The vocabulary itself lives in qits-containers-driver</b>, as a {@link LabelNamespace} the
 * embedder chooses. This class is this service's choice, {@link #NS} = {@value #NAMESPACE}, and the
 * facade every caller here reads: each constant and each {@code for*} map is computed from {@link
 * #NS}, so the values are exactly the literals they always were. The driver is constructed with the
 * same {@link #NS}, which is what makes it refuse an owner label inside it and stamp the builder
 * under it. A runner embedding the driver beside this service passes its own namespace and never
 * this one: a volume labelled under {@value #NAMESPACE} is one {@code VolumeGc} may remove.
 */
public final class ContainerLabels {

  /** The prefix every label here shares. An owner may not write inside it — see the belts. */
  public static final String NAMESPACE = "qits.containers.";

  /** This service's label namespace — what the driver is constructed with. */
  public static final LabelNamespace NS = new LabelNamespace(NAMESPACE);

  /** {@value #MANAGED_CONTAINER} or {@value #MANAGED_VOLUME} — what kind of thing this is. */
  public static final String MANAGED = NS.managed();

  public static final String MANAGED_CONTAINER = LabelNamespace.MANAGED_CONTAINER;
  public static final String MANAGED_VOLUME = LabelNamespace.MANAGED_VOLUME;

  /** Which platform module asked for it. */
  public static final String OWNER = NS.owner();

  /** What kind of workload it is, in the owner's own words. */
  public static final String WORKLOAD = NS.workload();

  /** The owner's identifier for the thing this workload belongs to. Opaque to this service. */
  public static final String REF = NS.ref();

  /** The registry row that named this container <b>before</b> it was started. */
  public static final String ROW = NS.row();

  /**
   * Which run of this service started it. It is what tells a container this process started from one
   * a previous life did — and it is a diagnostic, never a filter a sweep acts on: adopting is the
   * rule, so "started by an earlier instance" must not be readable as "removable".
   */
  public static final String INSTANCE = NS.instance();

  private ContainerLabels() {}

  /**
   * The full label set of one container, with every value belt-checked. Insertion order is the
   * declaration order; {@code DockerArgv} sorts before rendering, so nothing downstream depends on
   * it.
   */
  public static Map<String, String> forContainer(
      String owner, String workload, String ref, String rowId, String instanceId) {
    return NS.forContainer(owner, workload, ref, rowId, instanceId);
  }

  /**
   * The label set of a volume an owner asked for <b>by name</b>, with no workload behind it.
   *
   * <p>Two labels rather than four, because there is no third and fourth to write: nothing mounts
   * it yet and no ref names it. {@code VolumeReconcile} narrows its listing on {@link #MANAGED}
   * alone, so a volume labelled this way is found exactly as a workload's own is — and the row is
   * what decides either way.
   */
  public static Map<String, String> forOwnerVolume(String owner) {
    return NS.forOwnerVolume(owner);
  }

  /**
   * The label set of one named volume. No row and no instance: a volume outlives the container that
   * mounted it and the process that made it, which is the whole reason it is a volume.
   */
  public static Map<String, String> forVolume(String owner, String workload, String ref) {
    return NS.forVolume(owner, workload, ref);
  }
}
