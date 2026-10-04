package eu.wohlben.qits.containers.control;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.ContainersTimeouts;
import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.driver.spec.ContainersIdentifiers;
import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.driver.spec.VolumeSpec;
import eu.wohlben.qits.containers.entity.CtContainer;
import eu.wohlben.qits.containers.entity.CtVolume;
import eu.wohlben.qits.containers.entity.DesiredState;
import eu.wohlben.qits.containers.entity.ObservedState;
import eu.wohlben.qits.containers.entity.VolumeState;
import eu.wohlben.qits.containers.persistence.CtContainerRepository;
import eu.wohlben.qits.containers.persistence.CtVolumeRepository;
import eu.wohlben.qits.containers.spec.ContainerLabels;
import eu.wohlben.qits.db.DbRetry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The registry: the state machine that turns an owner's ask into a container, and a durable row into
 * the only record of which containers may exist.
 *
 * <p><b>The row is written before the container is started, and that ordering is the repository.</b>
 * {@link #ensure} commits a row carrying the chosen {@code container_name} and {@code PENDING}
 * before it calls {@code docker run}; a crash anywhere after that leaves a container the registry
 * can still name. Everything else here — the boot sweep, the observer, the policy sweeps — is
 * downstream of that one guarantee, and none of them may remove a container that no row names.
 *
 * <p><b>No transaction ever spans a docker call.</b> A docker call is a child process on the far
 * side of a socket, so each state transition sits in its own bracket and the driver is called
 * between brackets, on plain values copied out of the entities. That is the {@code DeployService}
 * shape and it is not negotiable: a transaction held across a daemon that stopped answering is a
 * connection held for the length of a timeout.
 *
 * <p><b>Which spelling of {@link DbRetry} each bracket takes is decided by who owns the
 * transaction.</b> A read brackets itself and is wrapped in {@link DbRetry#call}; a state transition
 * <em>is</em> a {@link DbRetry#inNewTx}, because owning the boundary is what lets the retry tell "the
 * body threw it, so it certainly never committed" from "the transaction manager reported it" — and
 * every one of those bodies ends in a {@code flush()}, which is what moves the write into the
 * statement phase where that classification is certain. Without the flush the wrap reports rather
 * than helps.
 *
 * <p><b>The budget is {@link #CUTOVER_BUDGET} rather than the library default</b>, for the reason
 * qits-platform-deployments spends thirty seconds: this service will one day be reconciling rows
 * while the platform cuts its own postgres over, and the bookkeeping that runs <em>after</em> a
 * container is running is exactly the work whose loss leaves a live container with no row that
 * admits it.
 *
 * <p>Every operation is owner-scoped by its parameters. There is no ambient identity here and no
 * authorization: WP4's REST layer derives the owner from the caller's machine token and passes it
 * in, which is what keeps this class testable without one.
 */
@ApplicationScoped
public class ContainerRegistry {

  private static final Logger LOG = Logger.getLogger(ContainerRegistry.class);

  /**
   * How long a self-inflicted database blip may last before it is a failure worth reporting — longer
   * than {@link DbRetry#DEFAULT_DEADLINE}, and stated at every call site here rather than taken from
   * the library. Package-private because the sweeps and the observer wrap their brackets for the
   * same reason and one budget spelled twice would drift.
   */
  static final Duration CUTOVER_BUDGET = Duration.ofSeconds(30);

  /** Docker statuses that mean the container is not coming back on its own. */
  private static final List<String> TERMINAL_STATUSES = List.of("exited", "dead", "removing");

  private final ContainersDriver driver;
  private final CtContainerRepository containers;
  private final CtVolumeRepository volumes;

  /**
   * The clock every state transition reads. Injected rather than called statically, because half of
   * what this service decides is about elapsed time and the only way to test that without sleeping
   * through it is to move the clock.
   *
   * <p><b>There is no producer for it here</b>, and that is deliberate: the qits-eventstream jar
   * ships a {@code @DefaultBean} {@code Clock} for the whole platform, and a second default producer
   * of the same type is an ambiguous resolution that fails the build — measured, 2026-08-11. Nothing
   * in {@code core} imports an eventstream type for it: {@link Clock} is a JDK type, so this is one
   * bean the platform already has rather than a widening of the narrow dependency AGENTS.md allows.
   */
  private final Clock clock;

  /**
   * Which run of this service started a container. A diagnostic label and never a filter a sweep
   * acts on — {@code ContainerLabels#INSTANCE} says why: adopting is the rule, so "started by an
   * earlier instance" must not be readable as "removable".
   */
  @ConfigProperty(name = "qits.containers.instance")
  String instanceId;

  @Inject
  public ContainerRegistry(
      ContainersDriver driver,
      CtContainerRepository containers,
      CtVolumeRepository volumes,
      Clock clock) {
    this.driver = driver;
    this.containers = containers;
    this.volumes = volumes;
    this.clock = clock;
  }

  // ---------------------------------------------------------------------------------------------
  // What an operation answers with. Plain records: nothing hands an entity across a bracket.
  // ---------------------------------------------------------------------------------------------

  /**
   * What {@link #ensure} did, and where the place stands now.
   *
   * <p>{@code specHash} is the hash the row now carries — the one a later {@code ensure} is
   * compared against. It is on the answer so a caller can tell "this is the spec I sent" from "this
   * is what was already here" without reading the row back.
   */
  public record Ensured(
      UUID rowId,
      String containerName,
      DesiredState desired,
      ObservedState observed,
      String specHash,
      boolean created,
      String detail) {}

  /** What {@link #stop} did. */
  public record Stopped(
      UUID rowId, String containerName, ObservedState observed, boolean ok, String detail) {}

  /**
   * What {@link #delete} did. {@code existed} is false for a place that was already absent, which is
   * a <b>success</b>: a delete is idempotent, and "there was nothing to remove" is the state the
   * caller asked for.
   */
  public record Deleted(
      UUID rowId, String containerName, boolean existed, String logs, String detail) {}

  /** One place's outcome inside a {@link #destroyAll}. */
  public record Destroyed(String ownerRef, String containerName, boolean removed, String detail) {}

  /**
   * One place as a reader sees it: the row, plus the two addresses its <b>stored</b> spec names.
   *
   * <p>The network and the alias come from {@code spec_json} rather than from a second table,
   * because they are what the spec asked for and the spec is what the row keeps. A stored spec that
   * cannot be read leaves them at their fallbacks rather than failing the read — a listing is a
   * diagnosis, and a row nobody can render is the one a reader most needs to see.
   */
  public record Place(
      UUID rowId,
      String owner,
      String workload,
      String ownerRef,
      String containerName,
      String image,
      LifecyclePolicy.Type policy,
      DesiredState desired,
      ObservedState observed,
      String specHash,
      String network,
      String alias,
      String detail,
      Instant createdAt,
      Instant updatedAt,
      Instant lastObservedAt,
      Instant lastTouchedAt) {}

  /** One volume row as a reader sees it. */
  public record Volume(UUID rowId, String owner, String name, VolumeState desired, Instant createdAt) {}

  /** What a volume ask did. {@code existed} is false for one that was already absent. */
  public record VolumeOutcome(
      UUID rowId, String name, boolean existed, boolean ok, String detail) {}

  /** What the upsert decided, as plain values the docker phase can act on. */
  private enum Step {
    /** A new row and a first container. */
    START,
    /** The spec changed and the policy allows a replacement: stop, remove, run the same name. */
    REPLACE,
    /** The same spec, nothing running: a {@code docker start} of the container that is still there. */
    RESTART,
    /** Already running the spec that was asked for. Nothing to do and nothing to say. */
    ADOPTED,
    /** Something differs and the policy refuses to act on it. The row is left exactly as it was. */
    KEEP
  }

  private record Plan(
      UUID rowId,
      String containerName,
      Step step,
      List<String> ownVolumes,
      DesiredState desired,
      ObservedState observed,
      String specHash,
      boolean created) {}

  // ---------------------------------------------------------------------------------------------
  // ensure
  // ---------------------------------------------------------------------------------------------

  /**
   * Put a container at this place, or confirm the one already there.
   *
   * <p>Three phases, and the boundaries between them are the design:
   *
   * <ol>
   *   <li><b>One transaction upserts the row</b> with the chosen name, the persisted spec and its
   *       hash, {@code desired=RUNNING} and {@code observed=PENDING}, plus a {@link CtVolume} row
   *       per volume this workload owns. It commits before anything docker-side happens.
   *   <li><b>Outside every transaction</b>, the volumes are created, the image is pulled when the
   *       policy demands it, and the container is run.
   *   <li><b>Two further transactions settle the row</b>: {@code STARTING} once docker accepted the
   *       run, then whatever an inspect confirms.
   * </ol>
   *
   * <p><b>A place whose spec has not changed and whose container is merely stopped is started, not
   * run again</b> — see {@link #restart}. It is the same three phases with a {@code docker start}
   * where the run is, and it is what keeps a stopped workload's container, its id and its writable
   * layer across an {@code ensure}.
   *
   * <p><b>The pull policy is answered by one call and one fallback, and the choice is recorded
   * here.</b> {@code ALWAYS} is an explicit {@code docker pull} before the run, so "the registry has
   * no such image" is its own recorded outcome rather than a failed run. {@code MISSING} and
   * {@code NEVER} pull nothing: docker's own {@code run} fetches an image the host does not have,
   * which is exactly {@code MISSING}'s meaning, and {@code NEVER} is honoured by the argv the driver
   * renders. Asking the daemon whether an image is present, to decide whether to ask it to fetch
   * one, would be a second round trip that answers a question the run already answers.
   *
   * <p><b>A run docker refuses is re-inspected before it is recorded as a failure</b>, and that is
   * the crash-retry idempotency this whole ordering exists for. The row named the container before
   * the run, so a container carrying that exact name <em>is</em> ours — a previous attempt that
   * started it and died before recording the fact. It is adopted, not replaced. The check is a
   * re-inspect rather than a match against docker's refusal text, because the wording of a
   * duplicate-name refusal is the CLI's to change and the name is ours.
   *
   * @param recreateIfChanged whether a spec change may replace the running container. False leaves
   *     what is running alone and reports it, which is what an owner that only wants the place
   *     occupied asks for.
   * <p><b>A place this service deleted can be ensured again, under the same name.</b> The name is
   * derived from the place, so it is the same one every time; V3 made it unique among live rows
   * only, which is what lets a settled row keep its history without holding the name a new
   * {@link Step#START} needs. The name is refused only while a live container of another place
   * answers to it — see {@link #requireNameFree}.
   *
   * @throws SpecConflictException when a replacement is asked for and the policy forbids one
   * @throws NameTakenException when the name this place would claim is held by a live container of
   *     a different place
   */
  public Ensured ensure(
      String owner,
      String workload,
      String ownerRef,
      ContainerSpec spec,
      LifecyclePolicy policy,
      boolean recreateIfChanged) {
    ContainersIdentifiers.requireOwner(owner);
    ContainersIdentifiers.requireWorkload(workload);
    ContainersIdentifiers.requireRef(ownerRef);
    String place = place(owner, workload, ownerRef);

    Plan plan =
        DbRetry.inNewTx(
            "The registry upsert of " + place,
            () -> upsert(owner, workload, ownerRef, spec, policy, recreateIfChanged),
            CUTOVER_BUDGET);

    if (plan.step() == Step.ADOPTED || plan.step() == Step.KEEP) {
      LOG.debugf("%s is already what was asked for (%s); nothing docker-side to do", place, plan.step());
      return new Ensured(
          plan.rowId(),
          plan.containerName(),
          plan.desired(),
          plan.observed(),
          plan.specHash(),
          false,
          plan.step() == Step.KEEP ? "[left as it is: the running spec differs and no recreate was asked for]" : null);
    }

    String name = plan.containerName();

    // From here to the end of the method, no transaction is open. Every call below is a child
    // process on the far side of a socket.
    if (plan.step() == Step.REPLACE) {
      driver.stop(name, ContainersTimeouts.STOP);
      driver.remove(name, ContainersTimeouts.REMOVE);
    }
    for (String volume : plan.ownVolumes()) {
      driver.ensureVolume(
          new VolumeSpec(volume),
          ContainerLabels.forVolume(owner, workload, ownerRef),
          ContainersTimeouts.VOLUME);
    }
    if (plan.step() == Step.RESTART) {
      // Above the pull and below the volumes on purpose. A start fetches nothing — the container
      // exists, so its image is already resolved into it, and a pull here would change nothing
      // about what comes back up — while a named volume docker no longer has would be recreated
      // unlabelled by the start itself, which is a volume the reconcile could never claim.
      return restart(plan, place);
    }
    if (spec.pullPolicy() == ContainerSpec.PullPolicy.ALWAYS) {
      ContainersDriver.OpResult pulled =
          driver.pull(spec.image(), ContainersTimeouts.PULL, ContainersTimeouts.PULL_MAX_CHARS);
      if (!pulled.ok()) {
        LOG.warnf(
            "Could not pull %s for %s: %s — running anyway, so the run's own failure is the record",
            spec.image(), place, Details.brief(pulled.detail()));
      }
    }

    Map<String, String> labels =
        ContainerLabels.forContainer(owner, workload, ownerRef, plan.rowId().toString(), instanceId);
    ContainersDriver.Started started = driver.run(spec, name, labels, policy, ContainersTimeouts.RUN);

    if (!started.started()) {
      Optional<ContainersDriver.Observed> existing =
          driver.inspect(name, ContainersTimeouts.INSPECT);
      if (existing.isPresent() && running(existing.get())) {
        settle(
            plan.rowId(),
            ObservedState.RUNNING,
            "[adopted after a refused run: " + name + " is already running and this row names it]");
        LOG.infof("Adopted %s: docker refused a second run of a container this row already names", name);
        return new Ensured(
            plan.rowId(),
            name,
            DesiredState.RUNNING,
            ObservedState.RUNNING,
            plan.specHash(),
            plan.created(),
            null);
      }
      String detail = "[docker refused to start " + name + ": " + Details.brief(started.detail()) + "]";
      settle(plan.rowId(), ObservedState.MISSING, detail);
      LOG.warnf("Could not start %s for %s: %s", name, place, Details.brief(started.detail()));
      return new Ensured(
          plan.rowId(),
          name,
          DesiredState.RUNNING,
          ObservedState.MISSING,
          plan.specHash(),
          plan.created(),
          detail);
    }

    settle(plan.rowId(), ObservedState.STARTING, null);
    ObservedState observed = observedOf(driver.inspect(name, ContainersTimeouts.INSPECT));
    settle(plan.rowId(), observed, null);
    return new Ensured(
        plan.rowId(), name, DesiredState.RUNNING, observed, plan.specHash(), plan.created(), null);
  }

  /**
   * The {@link Step#RESTART} phase: the container this row already names, started where it stands.
   *
   * <p><b>A start and never a second run</b>, which is the difference between a workload coming back
   * and a workload being replaced. The stopped container still holds the name, so a run against it
   * is refused by a real daemon — and a run that was <em>not</em> refused would be worse: a second
   * container with a new id and none of the state the stopped one was carrying, which is exactly
   * what {@code IDLE_STOP} stopping rather than removing exists to preserve.
   *
   * <p><b>A start docker could not perform is recorded rather than thrown</b>, the way a refused run
   * is. The container vanished between the row that named it and this call; the row says so, and the
   * observer carries it from there.
   */
  private Ensured restart(Plan plan, String place) {
    String name = plan.containerName();
    ContainersDriver.OpResult started = driver.start(name, ContainersTimeouts.START);
    String detail = null;
    if (started.ok()) {
      settle(plan.rowId(), ObservedState.STARTING, null);
    } else {
      detail = "[docker could not start " + name + ": " + Details.brief(started.detail()) + "]";
      LOG.warnf("Could not start %s for %s: %s", name, place, Details.brief(started.detail()));
    }
    // The same inspect every other outcome is settled on, the failure included: what the row records
    // is what the host says. A start that failed against a container still sitting there is EXITED,
    // and only one that is really gone is MISSING.
    ObservedState observed = observedOf(driver.inspect(name, ContainersTimeouts.INSPECT));
    settle(plan.rowId(), observed, detail);
    return new Ensured(
        plan.rowId(), name, DesiredState.RUNNING, observed, plan.specHash(), plan.created(), detail);
  }

  /**
   * The whole decision, inside one transaction. It reads the live row of the place, works out which
   * {@link Step} the ask amounts to, writes the row that {@code docker run} will be measured against
   * and returns plain values.
   */
  private Plan upsert(
      String owner,
      String workload,
      String ownerRef,
      ContainerSpec spec,
      LifecyclePolicy policy,
      boolean recreateIfChanged) {
    Instant now = clock.instant();
    String json = SpecFingerprint.persistedJson(spec);
    String hash = SpecFingerprint.hash(spec);
    List<String> ownVolumes =
        spec.volumeMounts().stream().map(ContainerSpec.VolumeMount::volumeName).distinct().toList();

    CtContainer row = containers.findLive(owner, workload, ownerRef);
    boolean created = row == null;
    Step step;
    if (created) {
      row = new CtContainer();
      row.id = UUID.randomUUID();
      row.owner = owner;
      row.workload = workload;
      row.ownerRef = ownerRef;
      row.containerName =
          spec.explicitName().isEmpty()
              ? ContainerNames.of(owner, workload, ownerRef)
              : spec.explicitName();
      requireNameFree(row.containerName, owner, workload, ownerRef);
      row.createdAt = now;
      step = Step.START;
    } else if (hash.equals(row.specHash)) {
      // The same workload. Whether anything has to happen depends only on what is running — a row
      // that was stopped and is asked for again is asked to run again, which is what ensure means.
      if (row.observedState == ObservedState.RUNNING || row.observedState.inFlight()) {
        step = Step.ADOPTED;
      } else if (policy.recreatable()) {
        step = Step.RESTART;
      } else {
        // An EPHEMERAL workload that already ran. Its exit IS the success path, and a second
        // container would do the work twice.
        step = Step.KEEP;
      }
    } else if (!recreateIfChanged) {
      step = Step.KEEP;
    } else if (!policy.recreatable()) {
      throw new SpecConflictException(
          "Cannot recreate "
              + place(owner, workload, ownerRef)
              + ": its lifecycle policy is "
              + policy.type()
              + ", which runs once and exits — a replacement would do the work a second time");
    } else {
      step = Step.REPLACE;
    }

    if (step == Step.START || step == Step.REPLACE || step == Step.RESTART) {
      row.image = spec.image();
      row.specJson = json;
      row.specHash = hash;
      row.policy = policy.type();
      row.idleAfterS = policy.idleAfter() == null ? null : policy.idleAfter().toSeconds();
      row.maxAgeS = policy.maxAge() == null ? null : policy.maxAge().toSeconds();
      row.desiredState = DesiredState.RUNNING;
      row.observedState = ObservedState.PENDING;
    }
    row.updatedAt = now;
    if (created) {
      containers.persist(row);
    }
    if (step != Step.KEEP && step != Step.ADOPTED) {
      for (String volume : ownVolumes) {
        upsertVolumeRow(owner, workload, ownerRef, volume, now);
      }
    }
    // Flushed rather than left to the commit: an ORM flushes at commit by default, which would put
    // every statement on the far side of the one round trip nothing can place. Flushed, a lost
    // connection is a body failure — certainly not committed, so safe to run again.
    try {
      containers.flush();
    } catch (RuntimeException e) {
      // The one collision the check above cannot see: a concurrent ensure of ANOTHER place claimed
      // the same name and has not committed yet, so the index is what decides. Translated here so
      // that the answer is the same word either way — a caller must not have to tell a lost race
      // from a squatter it can read about.
      if (nameCollision(e)) {
        throw new NameTakenException(
            "Cannot start "
                + place(owner, workload, ownerRef)
                + " as "
                + row.containerName
                + ": another container claimed that name while this one was being written");
      }
      throw e;
    }
    return new Plan(
        row.id,
        row.containerName,
        step,
        ownVolumes,
        row.desiredState,
        row.observedState,
        row.specHash,
        created);
  }

  /**
   * Refuse a new place whose name a live container of a different place already holds.
   *
   * <p><b>Only a live row counts</b>, which is the whole of V3: a place that was deleted keeps its
   * recorded name in history and gives the name up with the place, so re-ensuring a place this
   * service deleted an hour ago is a plain {@link Step#START} under the same derived name. What is
   * still refused is a name another running container answers to — docker's name space is flat, and
   * a second row claiming it would be a contradiction the sweeps could not resolve.
   *
   * <p>Before the insert rather than after the database's refusal, so the answer names both places
   * and arrives as a code. {@link #nameCollision} covers the narrow race this check cannot see.
   */
  private void requireNameFree(String name, String owner, String workload, String ownerRef) {
    CtContainer holder = containers.findLiveByContainerName(name);
    if (holder != null) {
      throw new NameTakenException(
          "Cannot start "
              + place(owner, workload, ownerRef)
              + " as "
              + name
              + ": that container name is held by "
              + place(holder.owner, holder.workload, holder.ownerRef)
              + ", which is still live");
    }
  }

  /**
   * Whether a write failed on the live-name index and nothing else.
   *
   * <p>Read off the SQL state and the index name rather than off an ORM exception type: the state is
   * postgres' own word for a unique violation and {@link CtContainerRepository#NAME_INDEX} is the
   * only index this translation may speak for. A violation of the <em>place</em> index is a
   * different statement — two ensures of one place, which the row read already resolves — and must
   * keep travelling as itself.
   */
  private static boolean nameCollision(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql
          && "23505".equals(sql.getSQLState())
          && String.valueOf(sql.getMessage()).contains(CtContainerRepository.NAME_INDEX)) {
        return true;
      }
      if (cause.getCause() == cause) {
        return false;
      }
    }
    return false;
  }

  /** The converging volume row. Written before the volume exists, exactly as the container's is. */
  private void upsertVolumeRow(
      String owner, String workload, String ownerRef, String name, Instant now) {
    CtVolume row = volumes.findByOwnerAndName(owner, name);
    boolean fresh = row == null;
    if (fresh) {
      row = new CtVolume();
      row.id = UUID.randomUUID();
      row.owner = owner;
      row.name = name;
      row.createdAt = now;
    }
    row.labelsJson = SpecFingerprint.write(ContainerLabels.forVolume(owner, workload, ownerRef));
    row.desiredState = VolumeState.PRESENT;
    if (fresh) {
      // Persisted only once every column is set: this entity's id is assigned rather than
      // generated, so Hibernate is free to insert at persist() time and a field set afterwards
      // would be a not-null violation on a row that reads perfectly well in the debugger.
      volumes.persist(row);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // stop / touch / logs
  // ---------------------------------------------------------------------------------------------

  /**
   * Stop what is here, leaving it restartable. The desired state is written first, so a crash
   * between the row and the docker call leaves a row that says what was asked for.
   */
  public Stopped stop(String owner, String workload, String ownerRef) {
    String place = place(owner, workload, ownerRef);
    String name =
        DbRetry.inNewTx(
            "The stop of " + place,
            () -> {
              CtContainer row = containers.findLive(owner, workload, ownerRef);
              if (row == null) {
                return null;
              }
              row.desiredState = DesiredState.STOPPED;
              row.updatedAt = clock.instant();
              containers.flush();
              return row.containerName;
            },
            CUTOVER_BUDGET);
    if (name == null) {
      return new Stopped(null, "", null, true, "[nothing here to stop]");
    }

    ContainersDriver.OpResult result = driver.stop(name, ContainersTimeouts.STOP);
    if (!result.ok()) {
      String detail = "[docker could not stop " + name + ": " + Details.brief(result.detail()) + "]";
      UUID id = settleByName(name, null, detail);
      LOG.warnf("Could not stop %s for %s: %s", name, place, Details.brief(result.detail()));
      return new Stopped(id, name, null, false, detail);
    }
    UUID id = settleByName(name, ObservedState.EXITED, null);
    return new Stopped(id, name, ObservedState.EXITED, true, null);
  }

  /**
   * Record that the owner still wants this workload. One column, no docker call — an idle sweep
   * reads it and nothing else does.
   *
   * @return whether there was a live row to touch
   */
  public boolean touch(String owner, String workload, String ownerRef) {
    return DbRetry.inNewTx(
        "The touch of " + place(owner, workload, ownerRef),
        () -> {
          CtContainer row = containers.findLive(owner, workload, ownerRef);
          if (row == null) {
            return false;
          }
          Instant now = clock.instant();
          row.lastTouchedAt = now;
          row.updatedAt = now;
          containers.flush();
          return true;
        },
        CUTOVER_BUDGET);
  }

  /**
   * The tail of what the container printed. It works while the container is {@code EXITED}, which is
   * the case that matters: a workload that died on its first breath has nothing else to offer, and
   * that is the whole reason no argv here carries {@code --rm}.
   *
   * @param lines how many lines to ask for; anything at or below zero takes the default
   */
  public ContainersDriver.LogTail logs(String owner, String workload, String ownerRef, int lines) {
    String name = readContainerName(owner, workload, ownerRef);
    if (name == null) {
      return new ContainersDriver.LogTail("", false);
    }
    return driver.logsTail(
        name,
        lines <= 0 ? ContainersTimeouts.LOGS_DEFAULT_LINES : lines,
        ContainersTimeouts.LOGS,
        ContainersTimeouts.LOGS_MAX_CHARS);
  }

  // ---------------------------------------------------------------------------------------------
  // delete / destroyAll
  // ---------------------------------------------------------------------------------------------

  /**
   * Remove what is here.
   *
   * <p><b>Idempotent by construction.</b> A place with no live row is a success with
   * {@code existed=false}: the caller asked for nothing to be there and nothing is.
   *
   * <p><b>Logs are captured before the removal or they are lost with it.</b> That is the whole
   * ordering of the method, and it is what qits-ci's boot reap does today by hand.
   *
   * <p><b>The row is settled {@code GONE} only when the container is really gone</b> — the remove
   * reported success, or a follow-up inspect finds nothing. Anything else leaves an {@code ABSENT}
   * row that is not yet {@code GONE}, which is precisely the state {@link BootSweep} replays. A
   * delete that settled optimistically would abandon a container nothing would ever look at again.
   *
   * @param withVolumes remove the volumes this workload owns. Honoured only for policies that own
   *     them — an {@code IDLE_STOP} workload is stopped and never removed, so its volume is the
   *     thing it comes back to. Shared volumes are never touched: they are not this workload's.
   * @param withLogs capture a bounded tail before removing, and return it
   */
  public Deleted delete(
      String owner, String workload, String ownerRef, boolean withVolumes, boolean withLogs) {
    String place = place(owner, workload, ownerRef);
    Doomed doomed =
        DbRetry.inNewTx(
            "The delete of " + place,
            () -> {
              CtContainer row = containers.findLive(owner, workload, ownerRef);
              if (row == null) {
                return null;
              }
              row.desiredState = DesiredState.ABSENT;
              row.updatedAt = clock.instant();
              containers.flush();
              return new Doomed(row.id, row.containerName, row.policy, ownedVolumesOf(row));
            },
            CUTOVER_BUDGET);
    if (doomed == null) {
      return new Deleted(null, "", false, "", "[already absent]");
    }

    String logs = "";
    if (withLogs) {
      ContainersDriver.LogTail tail =
          driver.logsTail(
              doomed.containerName(),
              ContainersTimeouts.LOGS_DEFAULT_LINES,
              ContainersTimeouts.LOGS,
              ContainersTimeouts.LOGS_MAX_CHARS);
      logs = tail.text();
    }

    ContainersDriver.OpResult removed =
        driver.remove(doomed.containerName(), ContainersTimeouts.REMOVE);
    boolean gone =
        removed.ok() || driver.inspect(doomed.containerName(), ContainersTimeouts.INSPECT).isEmpty();

    if (withVolumes && ownsVolumes(doomed.policy())) {
      for (String volume : doomed.ownVolumes()) {
        ContainersDriver.OpResult dropped = driver.removeVolume(volume, ContainersTimeouts.VOLUME);
        if (dropped.ok()) {
          markVolumeAbsent(owner, volume);
        } else {
          LOG.warnf(
              "Could not remove the volume %s of %s: %s",
              volume, place, Details.brief(dropped.detail()));
        }
      }
    }

    String detail =
        gone ? null : "[docker could not remove " + doomed.containerName() + ": "
            + Details.brief(removed.detail()) + "]";
    if (gone) {
      settle(doomed.rowId(), ObservedState.GONE, null);
    } else {
      settle(doomed.rowId(), null, detail);
      LOG.warnf(
          "%s is still on the host after a delete; the next boot sweep replays it",
          doomed.containerName());
    }
    return new Deleted(doomed.rowId(), doomed.containerName(), true, logs, detail);
  }

  /**
   * Remove every one of an owner's workloads of this kind that was created before an instant.
   *
   * <p><b>It iterates the owner's ROWS and never a label listing</b>, which is the whole difference
   * between this and the host-wide reap it replaces. qits-ci's boot sweep removes every container
   * carrying its label on the daemon it talks to, so two instances sharing one docker daemon reap
   * each other's running steps; scoped to rows, an instance can only reach containers its own
   * registry named.
   *
   * <p>{@code createdBefore} is what makes it a boot reap rather than a purge: an owner restarting
   * passes the instant it came up, so workloads it started <em>after</em> that — including ones
   * started while the sweep runs — are not in the set.
   */
  public List<Destroyed> destroyAll(String owner, String workload, Instant createdBefore) {
    List<String> refs =
        read(
            "The destroy-all listing of " + owner + "/" + workload,
            () ->
                containers.listLive(owner, workload).stream()
                    .filter(row -> row.createdAt.isBefore(createdBefore))
                    .map(row -> row.ownerRef)
                    .toList());
    List<Destroyed> outcomes = new ArrayList<>(refs.size());
    for (String ref : refs) {
      // No logs: a destroy-all is a sweep, and nobody is holding a connection waiting to read them.
      // No volumes either — taking a volume is an explicit ask about one workload.
      Deleted deleted = delete(owner, workload, ref, false, false);
      outcomes.add(
          new Destroyed(ref, deleted.containerName(), deleted.detail() == null, deleted.detail()));
    }
    if (!outcomes.isEmpty()) {
      LOG.infof(
          "Destroyed %d %s/%s workload(s) created before %s", outcomes.size(), owner, workload,
          createdBefore);
    }
    return List.copyOf(outcomes);
  }

  // ---------------------------------------------------------------------------------------------
  // reads
  // ---------------------------------------------------------------------------------------------

  /**
   * What is at this place, or empty when no live row names it.
   *
   * <p><b>Empty means the row is cleanly absent and nothing else.</b> A database that could not be
   * reached throws out of here — {@link #read} is a retried bracket and it gives up loudly — so the
   * API above can answer 404 for "there is nothing here" without ever answering it for "this
   * service could not find out". That is the githost doctrine, and it matters more here than in a
   * catalogue: a caller that reads 404 concludes its workload was never started.
   */
  public Optional<Place> status(String owner, String workload, String ownerRef) {
    ContainersIdentifiers.requireOwner(owner);
    ContainersIdentifiers.requireWorkload(workload);
    ContainersIdentifiers.requireRef(ownerRef);
    return Optional.ofNullable(
        read(
            "The status read of " + place(owner, workload, ownerRef),
            () -> {
              CtContainer row = containers.findLive(owner, workload, ownerRef);
              return row == null ? null : placeOf(row);
            }));
  }

  /**
   * The live place a row id names, or empty when no live row carries it.
   *
   * <p>Addressed by row id rather than by owner/workload/ref because the caller is the data plane: a
   * container dialling home knows the id this service minted for it and nothing else, and the whole
   * point of the id is that it names one place without the caller having to restate the three
   * segments it was created under.
   *
   * <p><b>Empty is "no live row", never "could not ask"</b> — the same rule {@link #status} states,
   * and it matters more here: the answer admits or refuses a control connection, so an empty answer
   * on an unreachable database would disconnect every daemon of a healthy platform. {@link #read} is
   * the retried bracket that keeps the two apart by giving up loudly.
   */
  public Optional<Place> place(UUID rowId) {
    if (rowId == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(
        read(
            "The row read of " + rowId,
            () -> {
              CtContainer row = containers.findById(rowId);
              return row == null || row.desiredState == DesiredState.ABSENT ? null : placeOf(row);
            }));
  }

  /**
   * An owner's live places, oldest first — every workload when {@code workload} is null.
   *
   * <p><b>From the rows.</b> There is no listing by label anywhere in this service, and this is the
   * method a caller would otherwise be tempted to write one for.
   */
  public List<Place> list(String owner, String workload) {
    ContainersIdentifiers.requireOwner(owner);
    if (workload != null) {
      ContainersIdentifiers.requireWorkload(workload);
    }
    return read(
        "The listing of " + owner + "/" + (workload == null ? "*" : workload),
        () ->
            (workload == null ? containers.listLive(owner) : containers.listLive(owner, workload))
                .stream()
                    .map(ContainerRegistry::placeOf)
                    .toList());
  }

  /** One row, with the two addresses read off its stored spec. */
  private static Place placeOf(CtContainer row) {
    String network = "";
    String alias = row.containerName;
    try {
      ContainerSpec spec = SpecFingerprint.fromPersistedJson(row.specJson);
      network = spec.network();
      alias = spec.aliases().isEmpty() ? row.containerName : spec.aliases().getFirst();
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not read the stored spec of %s, so its listing carries no addresses: %s",
          row.containerName, e.getMessage());
    }
    return new Place(
        row.id,
        row.owner,
        row.workload,
        row.ownerRef,
        row.containerName,
        row.image,
        row.policy,
        row.desiredState,
        row.observedState,
        row.specHash,
        network,
        alias,
        row.detail,
        row.createdAt,
        row.updatedAt,
        row.lastObservedAt,
        row.lastTouchedAt);
  }

  // ---------------------------------------------------------------------------------------------
  // volumes an owner asks for by name
  // ---------------------------------------------------------------------------------------------

  /**
   * Make sure this owner has this volume, and record the row that claims it.
   *
   * <p>Same ordering as {@link #ensure}: <b>the row is committed before docker is asked</b>, so a
   * crash between the two leaves a volume the registry already names. It has to be that way round
   * for volumes as well as for containers — {@code VolumeReconcile} may remove only what a row
   * names, so a volume made before its row would be one nothing could ever take back.
   */
  public VolumeOutcome ensureVolume(String owner, String name) {
    ContainersIdentifiers.requireOwner(owner);
    ContainersIdentifiers.requireVolumeName(name);
    Claimed claimed =
        DbRetry.inNewTx(
            "The volume claim of " + owner + "/" + name,
            () -> {
              CtVolume row = volumes.findByOwnerAndName(owner, name);
              boolean fresh = row == null;
              if (fresh) {
                row = new CtVolume();
                row.id = UUID.randomUUID();
                row.owner = owner;
                row.name = name;
                row.createdAt = clock.instant();
              }
              row.labelsJson = SpecFingerprint.write(ContainerLabels.forOwnerVolume(owner));
              row.desiredState = VolumeState.PRESENT;
              if (fresh) {
                volumes.persist(row);
              }
              volumes.flush();
              return new Claimed(row.id, fresh);
            },
            CUTOVER_BUDGET);

    ContainersDriver.OpResult made =
        driver.ensureVolume(
            new VolumeSpec(name),
            ContainerLabels.forOwnerVolume(owner),
            ContainersTimeouts.VOLUME);
    if (!made.ok()) {
      LOG.warnf(
          "Could not create the volume %s for %s: %s", name, owner, Details.brief(made.detail()));
      return new VolumeOutcome(
          claimed.rowId(),
          name,
          !claimed.fresh(),
          false,
          "[docker could not create " + name + ": " + Details.brief(made.detail()) + "]");
    }
    return new VolumeOutcome(claimed.rowId(), name, !claimed.fresh(), true, null);
  }

  /** The row claiming this volume, or empty when this owner claims none by that name. */
  public Optional<Volume> volume(String owner, String name) {
    ContainersIdentifiers.requireOwner(owner);
    ContainersIdentifiers.requireVolumeName(name);
    return Optional.ofNullable(
        read(
            "The volume read of " + owner + "/" + name,
            () -> {
              CtVolume row = volumes.findByOwnerAndName(owner, name);
              return row == null
                  ? null
                  : new Volume(row.id, row.owner, row.name, row.desiredState, row.createdAt);
            }));
  }

  /**
   * Take this owner's volume away.
   *
   * <p>Idempotent, like {@link #delete}: a volume no row of this owner names is a success with
   * {@code existed=false}. The row is marked {@code ABSENT} first and is dropped only once docker
   * confirms the removal — a remove docker refused leaves an {@code ABSENT} row, which is exactly
   * what {@code VolumeReconcile} replays on the next pass.
   */
  public VolumeOutcome deleteVolume(String owner, String name) {
    ContainersIdentifiers.requireOwner(owner);
    ContainersIdentifiers.requireVolumeName(name);
    UUID rowId =
        DbRetry.inNewTx(
            "The volume delete of " + owner + "/" + name,
            () -> {
              CtVolume row = volumes.findByOwnerAndName(owner, name);
              if (row == null) {
                return null;
              }
              row.desiredState = VolumeState.ABSENT;
              volumes.flush();
              return row.id;
            },
            CUTOVER_BUDGET);
    if (rowId == null) {
      return new VolumeOutcome(null, name, false, true, "[already absent]");
    }

    ContainersDriver.OpResult dropped = driver.removeVolume(name, ContainersTimeouts.VOLUME);
    if (!dropped.ok()) {
      LOG.warnf(
          "Could not remove the volume %s of %s, so its row stays absent for the reconcile: %s",
          name, owner, Details.brief(dropped.detail()));
      return new VolumeOutcome(
          rowId,
          name,
          true,
          false,
          "[docker could not remove " + name + ": " + Details.brief(dropped.detail()) + "]");
    }
    DbRetry.runInNewTx(
        "The volume row drop of " + owner + "/" + name,
        () -> {
          CtVolume row = volumes.findByOwnerAndName(owner, name);
          if (row != null) {
            volumes.delete(row);
          }
          volumes.flush();
        },
        CUTOVER_BUDGET);
    return new VolumeOutcome(rowId, name, true, true, null);
  }

  /** The volume row a claim wrote, and whether it was written now. */
  private record Claimed(UUID rowId, boolean fresh) {}

  // ---------------------------------------------------------------------------------------------
  // The seams the sweeps share
  // ---------------------------------------------------------------------------------------------

  /**
   * Write a row's observed state and append to its detail, in its own retried transaction. Either
   * argument may be null: a state with no detail is an ordinary transition, and a detail with no
   * state is a note about an attempt that changed nothing.
   */
  void settle(UUID rowId, ObservedState observed, String detail) {
    DbRetry.runInNewTx(
        "The settle of container row " + rowId,
        () -> {
          CtContainer row = containers.findById(rowId);
          if (row == null) {
            return; // deleted under us; there is nothing left to say about it
          }
          if (observed != null) {
            row.observedState = observed;
          }
          row.detail = Details.append(row.detail, detail);
          row.updatedAt = clock.instant();
          containers.flush(); // statement phase, so a lost connection is retriable — see the class doc
        },
        CUTOVER_BUDGET);
  }

  /**
   * {@link #settle} addressed by container name, for the paths that only carry the name.
   *
   * <p><b>The live row of that name</b>, never merely a row carrying it: since V3 a settled place
   * keeps its recorded name until the prune, so a plain lookup could settle an observation onto a
   * row that was deleted a week ago while the container it describes belongs to the one running now.
   */
  private UUID settleByName(String containerName, ObservedState observed, String detail) {
    return DbRetry.inNewTx(
        "The settle of " + containerName,
        () -> {
          CtContainer row = containers.findLiveByContainerName(containerName);
          if (row == null) {
            return null;
          }
          if (observed != null) {
            row.observedState = observed;
          }
          row.detail = Details.append(row.detail, detail);
          row.updatedAt = clock.instant();
          containers.flush();
          return row.id;
        },
        CUTOVER_BUDGET);
  }

  /** A read, bracketed by itself and retried. See the class javadoc on the two spellings. */
  <T> T read(String what, Supplier<T> body) {
    return DbRetry.call(
        what, () -> QuarkusTransaction.requiringNew().call(body::get), CUTOVER_BUDGET);
  }

  private String readContainerName(String owner, String workload, String ownerRef) {
    return read(
        "The container-name read of " + place(owner, workload, ownerRef),
        () -> {
          CtContainer row = containers.findLive(owner, workload, ownerRef);
          return row == null ? null : row.containerName;
        });
  }

  private void markVolumeAbsent(String owner, String name) {
    DbRetry.runInNewTx(
        "The volume settle of " + owner + "/" + name,
        () -> {
          CtVolume row = volumes.findByOwnerAndName(owner, name);
          if (row != null) {
            row.desiredState = VolumeState.ABSENT;
          }
          volumes.flush();
        },
        CUTOVER_BUDGET);
  }

  /** The volume names a row's own workload owns. Shared mounts are excluded and always will be. */
  static List<String> ownedVolumesOf(CtContainer row) {
    try {
      return SpecFingerprint.fromPersistedJson(row.specJson).volumeMounts().stream()
          .map(ContainerSpec.VolumeMount::volumeName)
          .distinct()
          .toList();
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not read the stored spec of %s, so no volume of it is claimed: %s",
          row.containerName, e.getMessage());
      return List.of();
    }
  }

  /**
   * Whether a delete of this policy's workload may take the workload's volumes with it.
   *
   * <p>{@code IDLE_STOP} is the one that may not. Its container is stopped and never removed by
   * design, so its volume is exactly the state it comes back to; taking it would make an idle stop
   * indistinguishable from a delete. The decision lives here rather than on {@link LifecyclePolicy}
   * because it is a statement about what a <em>delete</em> does, not about what a policy renders.
   */
  static boolean ownsVolumes(LifecyclePolicy.Type policy) {
    return policy != LifecyclePolicy.Type.IDLE_STOP;
  }

  /** Whether an observation says the container is up. */
  static boolean running(ContainersDriver.Observed observed) {
    return status(observed).equals("running");
  }

  /** Whether an observation says it is not coming back on its own. */
  static boolean terminal(ContainersDriver.Observed observed) {
    return TERMINAL_STATUSES.contains(status(observed));
  }

  private static String status(ContainersDriver.Observed observed) {
    return observed == null || observed.status() == null
        ? ""
        : observed.status().strip().toLowerCase(Locale.ROOT);
  }

  /**
   * What one inspect means for a row. An absent answer is {@code MISSING} — docker having no such
   * container is a different statement from an unhealthy one, and the two must never be merged.
   * Anything docker still has a state for and that is not terminal is {@code STARTING}: created,
   * restarting and paused are all "not settled yet", and a restart loop coming back from a slow
   * first boot must not be recorded as an exit on the way.
   */
  static ObservedState observedOf(Optional<ContainersDriver.Observed> observed) {
    if (observed.isEmpty()) {
      return ObservedState.MISSING;
    }
    if (running(observed.get())) {
      return ObservedState.RUNNING;
    }
    return terminal(observed.get()) ? ObservedState.EXITED : ObservedState.STARTING;
  }

  static String place(String owner, String workload, String ownerRef) {
    return owner + "/" + workload + "/" + ownerRef;
  }

  /** What a delete carries across its docker phase. Plain values, never an entity. */
  private record Doomed(
      UUID rowId, String containerName, LifecyclePolicy.Type policy, List<String> ownVolumes) {}
}
