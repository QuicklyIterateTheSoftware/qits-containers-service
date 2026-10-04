package eu.wohlben.qits.containers.control;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.ContainersDriver.ImageSummary;
import eu.wohlben.qits.containers.driver.ContainersTimeouts;
import eu.wohlben.qits.containers.driver.gc.ImageKeepRules;
import eu.wohlben.qits.containers.entity.CtContainer;
import eu.wohlben.qits.containers.persistence.CtContainerRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * The host's image store, collected by four keep rules and nothing else.
 *
 * <h4>An image is not a row, and that is why the rules are the safety</h4>
 *
 * <p>Every other removal in this service is licensed by a registry row that named the thing before
 * it existed. An image is named by no row: the platform's builds put it there and the platform's
 * containers run from it, so "unclaimed means somebody else's" has nothing to read. What replaces
 * it is a list of reasons to keep, checked in order, with removal as the fall-through — and a
 * candidate set assembled from the daemon's own listing rather than from a pattern, a label or a
 * {@code prune}.
 *
 * <p>The rules, in order, and each one is a keep:
 *
 * <ol>
 *   <li><b>{@code in-use}</b> — a container references it, running or not. Read from
 *       {@code docker ps -a}, which is a listing that <em>throws</em> rather than degrading: an
 *       empty answer here is what makes an image removable, so a daemon that did not answer must
 *       not be able to give one.
 *   <li><b>{@code live-row}</b> — a live {@code ct_container} row names it. The container may be
 *       gone while the row still wants it, and an ensure of that place is one restart away from
 *       needing the image again.
 *   <li><b>{@code pinned}</b> — a tag the caller named in {@code keep} or matched by
 *       {@code keepPrefixes}. This is where the platform's pin set arrives: the orchestrator reads
 *       every deployment's pinned image once per run and hands it to this call.
 *   <li><b>{@code too-young}</b> — built inside {@code minAge}. <b>It protects an image a CI step
 *       has built and not yet pushed</b>, which nothing else here can see: it is untagged or tagged
 *       only locally, no container holds it, no row names it and no pin knows about it, so the
 *       three rules above would all pass it through while the step that made it is still running.
 * </ol>
 *
 * <p>Everything else is removed, <b>dangling and tagged alike</b>. A dangling image is one no
 * {@code repository:tag} names any more ({@code <none>} in both columns); it is the common case and
 * it is not a separate rule, because an image kept only by a tag nobody pinned is exactly as
 * collectable.
 *
 * <p><b>The decision is qits-containers-driver's {@link ImageKeepRules}</b>, so a runner embedding the
 * driver keeps and removes by exactly these rules. This class reads the host and the rows, hands
 * the live-row images over as {@link ImageKeepRules.Inputs#protectedRefs}, and maps the verdicts
 * onto the reason strings below, which are the {@code /gc} answer's.
 *
 * <p><b>Matching is deliberately generous in the keeping direction.</b> A reference matches an
 * image when it equals one of its tags, when either side is the other with a registry host in
 * front, or when it carries a hex id this image's id starts with. Every one of those tolerances can
 * only move an image from the removed list to the kept one — which is the direction a wrong answer
 * is affordable in.
 *
 * <p><b>Nothing here touches a row</b>, reads included: the row read is the live listing and it is
 * read through {@link ContainerRegistry#read}'s retried bracket, so a database that blinked fails
 * the call rather than answering "no rows name any image" — which would be the whole registry
 * unpinned at once.
 *
 * <p><b>{@code bytesReclaimed} is docker's arithmetic and it over-counts.</b> Image sizes include
 * shared layers, so removing two images that share a base frees less than the sum of their sizes.
 * It is reported as docker reports it, because the alternative is inventing a number; a dry run
 * reports the same sum for what it would have removed.
 */
@ApplicationScoped
public class ImageGc {

  private static final Logger LOG = Logger.getLogger(ImageGc.class);

  /** A container references it. */
  public static final String IN_USE = "in-use";

  /** A live registry row names it. */
  public static final String LIVE_ROW = "live-row";

  /** The caller named it, exactly or by prefix. */
  public static final String PINNED = "pinned";

  /** Built inside {@code minAge} — the CI step's own protection. */
  public static final String TOO_YOUNG = "too-young";

  /** Removed: no {@code repository:tag} names it any more. */
  public static final String DANGLING = "dangling";

  /** Removed: tagged, and nothing above kept it. */
  public static final String UNPINNED = "unpinned";

  /** One image and what was decided about it. */
  public record Outcome(String id, List<String> tags, long sizeBytes, String reason) {}

  /** One image docker refused to remove, with its own words. */
  public record Failure(String id, List<String> tags, String error) {}

  /** The whole run. In a dry run {@code removed} is what the same call without it would remove. */
  public record Result(
      boolean dryRun,
      int examined,
      long bytesReclaimed,
      List<Outcome> removed,
      List<Outcome> kept,
      List<Failure> failed) {}

  @Inject ContainersDriver driver;
  @Inject CtContainerRepository containers;
  @Inject ContainerRegistry registry;
  @Inject Clock clock;

  /**
   * One pass.
   *
   * @param dryRun decide and report, ask docker for nothing
   * @param minAge how young an image is protected for; {@code null} or zero protects none
   * @param keep exact references: a tag, a tag with a registry host in front of it, or an id
   * @param keepPrefixes the same, matched as a prefix of the part after a {@code /}
   */
  public Result sweep(
      boolean dryRun, Duration minAge, List<String> keep, List<String> keepPrefixes) {
    List<String> pins = ImageKeepRules.clean(keep);
    List<String> pinPrefixes = ImageKeepRules.clean(keepPrefixes);
    // The protecting listing FIRST, and its throw is not caught: without it there is no safe
    // candidate set to compute, so a docker that will not answer is a run that does not happen.
    Set<String> inUse = new LinkedHashSet<>(driver.listImageReferencesInUse(ContainersTimeouts.GC_LIST));
    Set<String> rowImages = liveRowImages();
    List<ImageSummary> images = driver.listImages(ContainersTimeouts.GC_LIST);
    Instant youngest =
        minAge == null || minAge.isZero() || minAge.isNegative()
            ? null
            : clock.instant().minus(minAge);
    ImageKeepRules.Inputs inputs =
        new ImageKeepRules.Inputs(inUse, rowImages, pins, pinPrefixes, youngest);

    List<Outcome> removed = new ArrayList<>();
    List<Outcome> kept = new ArrayList<>();
    List<Failure> failed = new ArrayList<>();
    long bytes = 0;

    for (ImageSummary image : images) {
      String keepReason = ImageKeepRules.whyKeep(image, inputs).map(ImageGc::reason).orElse(null);
      if (keepReason != null) {
        kept.add(new Outcome(image.id(), image.tags(), image.sizeBytes(), keepReason));
        continue;
      }
      String reason = reason(ImageKeepRules.removal(image));
      if (dryRun) {
        removed.add(new Outcome(image.id(), image.tags(), image.sizeBytes(), reason));
        bytes += image.sizeBytes();
        continue;
      }
      ContainersDriver.OpResult gone = remove(image);
      if (gone.ok()) {
        removed.add(new Outcome(image.id(), image.tags(), image.sizeBytes(), reason));
        bytes += image.sizeBytes();
      } else {
        failed.add(new Failure(image.id(), image.tags(), Details.brief(gone.detail())));
      }
    }
    LOG.infof(
        "Image collection%s: examined %d, removed %d, kept %d, failed %d",
        dryRun ? " (dry run)" : "", images.size(), removed.size(), kept.size(), failed.size());
    return new Result(
        dryRun,
        images.size(),
        bytes,
        List.copyOf(removed),
        List.copyOf(kept),
        List.copyOf(failed));
  }

  /**
   * The remove, with a daemon that stopped answering reported as this image's own failure.
   *
   * <p><b>A tagged image is removed by its references and only a dangling one by its id</b>, which
   * is docker's arithmetic rather than a preference: an id more than one reference names is refused
   * with {@code must be forced}, and two tags of one repository are two references. Measured on the
   * platform's first real collection run, where it was 20 of 32 candidates. Untagging every
   * reference in one call removes the image on the last one — so the image goes, and no {@code -f}
   * is anywhere near it.
   */
  private ContainersDriver.OpResult remove(ImageSummary image) {
    try {
      return image.tags().isEmpty()
          ? driver.removeImage(image.id(), ContainersTimeouts.IMAGE_REMOVE)
          : driver.removeImageReferences(image.tags(), ContainersTimeouts.IMAGE_REMOVE);
    } catch (RuntimeException e) {
      return new ContainersDriver.OpResult(false, String.valueOf(e.getMessage()));
    }
  }

  /** A keep rule's verdict, as the reason string the {@code /gc} answer carries. */
  private static String reason(ImageKeepRules.Keep keep) {
    return switch (keep) {
      case IN_USE -> ImageGc.IN_USE;
      case PROTECTED -> ImageGc.LIVE_ROW;
      case PINNED -> ImageGc.PINNED;
      case TOO_YOUNG -> ImageGc.TOO_YOUNG;
    };
  }

  /** A removal's verdict, as the reason string the {@code /gc} answer carries. */
  private static String reason(ImageKeepRules.Removal removal) {
    return switch (removal) {
      case DANGLING -> ImageGc.DANGLING;
      case UNPINNED -> ImageGc.UNPINNED;
    };
  }

  /** Every image a live row names. Read in one retried bracket; a failure fails the whole call. */
  private Set<String> liveRowImages() {
    List<String> images =
        registry.read(
            "The image collection's live-row read",
            () ->
                containers.listLive().stream()
                    .map(ImageGc::imageOf)
                    .filter(Objects::nonNull)
                    .toList());
    return new LinkedHashSet<>(images);
  }

  private static String imageOf(CtContainer row) {
    return row.image == null || row.image.isBlank() ? null : row.image.strip();
  }
}
