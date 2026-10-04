package eu.wohlben.qits.containers.control;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.ContainersTimeouts;
import eu.wohlben.qits.containers.entity.CtVolume;
import eu.wohlben.qits.containers.entity.VolumeState;
import eu.wohlben.qits.containers.persistence.CtVolumeRepository;
import eu.wohlben.qits.containers.spec.ContainerLabels;
import eu.wohlben.qits.db.DbRetry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Compares the volumes docker has against the volumes this registry claims.
 *
 * <p><b>The row decides, and the row decides in one direction only.</b> A listed volume whose
 * {@link CtVolume} row says {@code ABSENT} is a delete that did not finish: removing it is replaying
 * that delete, and the volume is named by a row, so the invariant holds. A listed volume with
 * <b>no row at all</b> is somebody else's — a compose original, a bootstrap seed, another instance's
 * — and is reported and never removed, exactly as an unclaimed container is left alone.
 *
 * <h4>Two deviations from the sketched design, both measured against WP2's label vocabulary</h4>
 *
 * <p><b>The listing filter is {@code qits.containers.managed=volume}, not
 * {@code qits.containers.instance=<self>}.</b> {@code ContainerLabels.forVolume} writes no instance
 * label, deliberately: a volume outlives the process that made it, so stamping it with one run of
 * this service would record something that stops being true the first time the service restarts. The
 * managed label is the only namespace label every volume of ours carries, so it is what narrows the
 * listing. Narrowing costs nothing here, because the listing is not what decides anything — the rows
 * are, and a volume with no row is untouchable however it was listed.
 *
 * <p><b>There is no age-based collection, and {@code qits.containers.volume-gc-grace} is therefore
 * declared and not read.</b> The sketch was "remove an orphan once it is older than the grace" —
 * which needs a creation time for a volume this service did not record, and docker volume labels
 * carry none. The two ways to invent one are both worse: an in-memory first-seen map dies with every
 * restart, so a service that restarts more often than the grace would never collect anything; and a
 * row written for an orphan would be this service claiming a volume it did not create, which is the
 * unclaimed-means-untouchable rule inverted. So the narrowing above is what ships — deterministic,
 * clock-free, and unable to remove anything nobody asked to be removed. The key stays in the config
 * file for the day a creation time exists to compare against.
 *
 * <p>It runs on {@code ct-worker}, enqueued by {@link ContainerObserver}.
 */
@ApplicationScoped
public class VolumeReconcile {

  private static final Logger LOG = Logger.getLogger(VolumeReconcile.class);

  /** What narrows the listing. See the class javadoc for why it is not the instance label. */
  private static final Map<String, String> MANAGED_VOLUMES =
      Map.of(ContainerLabels.MANAGED, ContainerLabels.MANAGED_VOLUME);

  @Inject CtVolumeRepository volumes;
  @Inject ContainersDriver driver;
  @Inject ContainerRegistry registry;

  /**
   * One pass.
   *
   * @return how many volumes it removed
   */
  int reconcileOnce() {
    List<String> listed = driver.listVolumesByLabels(MANAGED_VOLUMES, ContainersTimeouts.VOLUME);
    if (listed.isEmpty()) {
      return 0;
    }
    List<Claim> claims = claimsFor(listed);

    int removed = 0;
    for (Claim claim : claims) {
      if (claim.owner() == null) {
        LOG.warnf(
            "The volume %s carries this service's label and no row names it, so it is left alone."
                + " Unclaimed means somebody else's.",
            claim.name());
        continue;
      }
      if (claim.desired() != VolumeState.ABSENT) {
        continue; // claimed and wanted; nothing to do
      }
      ContainersDriver.OpResult dropped =
          driver.removeVolume(claim.name(), ContainersTimeouts.VOLUME);
      if (dropped.ok()) {
        forget(claim.owner(), claim.name());
        removed++;
        LOG.infof("Removed %s: its row asked for it and the delete had not finished", claim.name());
      } else {
        LOG.warnf(
            "Could not remove the volume %s its row marks absent: %s",
            claim.name(), Details.brief(dropped.detail()));
      }
    }
    return removed;
  }

  /** The row goes with the volume: a delete that finished has nothing left to describe. */
  private void forget(String owner, String name) {
    DbRetry.runInNewTx(
        "The volume row delete of " + owner + "/" + name,
        () -> {
          CtVolume row = volumes.findByOwnerAndName(owner, name);
          if (row != null) {
            volumes.delete(row);
          }
          volumes.flush();
        },
        ContainerRegistry.CUTOVER_BUDGET);
  }

  /**
   * What the rows say about these volume names, read in one bracket.
   *
   * <p><b>It is the one row lookup a docker volume name has, and it is shared on purpose.</b>
   * {@link VolumeGc} asks the same question of a different candidate set — dangling volumes rather
   * than labelled ones — and a second copy of the lookup would be a second answer to "is this
   * volume claimed", which is the question the whole untouchable rule turns on. The key is the name
   * alone because that is all a listing carries: {@code ContainerLabels.forVolume} writes no owner
   * and no row id, deliberately, since a volume outlives both.
   *
   * <p>The read is {@link ContainerRegistry#read}'s retried bracket, so a database that blinked
   * throws rather than answering "nothing is claimed" — which would be every volume on the host
   * unclaimed at once, and unclaimed is the half of the rule that permits nothing.
   */
  List<Claim> claimsFor(List<String> names) {
    return registry.read(
        "The volume row read",
        () -> {
          List<Claim> out = new java.util.ArrayList<>();
          for (String name : names) {
            CtVolume row = volumes.find("name", name).firstResult();
            out.add(
                new Claim(
                    name, row == null ? null : row.owner, row == null ? null : row.desiredState));
          }
          return List.copyOf(out);
        });
  }

  /** One volume name and the row that claims it, or nulls for one no row names. */
  record Claim(String name, String owner, VolumeState desired) {}
}
