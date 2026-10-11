package eu.wohlben.qits.containers.client;

import eu.wohlben.qits.containers.client.ContainersAnswer.Created;
import eu.wohlben.qits.containers.client.ContainersAnswer.Ready;
import eu.wohlben.qits.containers.client.ContainersAnswer.Refused;
import eu.wohlben.qits.containers.client.ContainersAnswer.Unreachable;
import eu.wohlben.qits.containers.client.ContainersWire.DeleteOutcome;
import eu.wohlben.qits.containers.client.ContainersWire.DestroyAllOutcome;
import eu.wohlben.qits.containers.client.ContainersWire.Destroyed;
import eu.wohlben.qits.containers.client.ContainersWire.EnsureRequest;
import eu.wohlben.qits.containers.client.ContainersWire.Envelope;
import eu.wohlben.qits.containers.client.ContainersWire.ErrorBody;
import eu.wohlben.qits.containers.client.ContainersWire.Listing;
import eu.wohlben.qits.containers.client.ContainersWire.LogTail;
import eu.wohlben.qits.containers.client.ContainersWire.VolumeEnvelope;
import eu.wohlben.qits.eventstream.CausationHeader;
import eu.wohlben.qits.eventstream.CausationScope;
import eu.wohlben.qits.http.QitsHttpClients;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.jboss.logging.Logger;

/**
 * How a consumer calls qits-containers: one class, one {@code HttpClient}, and four answers per
 * call.
 *
 * <p><b>A plain class, and framework-free on purpose.</b> Nothing here is annotated, nothing is
 * injected, and this jar depends on neither quarkus-arc nor any OIDC extension. A consumer makes it
 * a bean with a producer of its own — three lines, and the README has them — which is what lets a
 * daemon that runs no container construct one with {@code new} and lets a Quarkus service configure
 * it exactly as it configures everything else. The defaults it needs ship in this jar's
 * {@code META-INF/microprofile-config.properties} at ordinal 100, so that producer names keys
 * rather than values.
 *
 * <p><b>The base URL is scheme + host + port with NO path</b>, the platform shape that
 * {@code qits.events.url} and {@code qits.ci.git-host-url} follow: the address is a deployment fact
 * and the path is the client's knowledge. This class appends {@link #CONTAINERS_PATH} and
 * {@link #VOLUMES_PATH} itself, so a base with a path in it yields a doubled one and a 404 nothing
 * retries out of.
 *
 * <p><b>Every call is synchronous and bounded.</b> Synchronous because a caller has to know which
 * of the four happened before it decides whether to record anything; bounded because these calls sit
 * on the caller's own thread — for qits-ci, the single-threaded run worker between one pipeline
 * step and the next — and an unbounded wait there parks every pipeline on the instance behind one
 * unreachable service. The deadline is a method parameter with a config-backed default, and
 * {@code ensure} has a much longer one than everything else because it may be waiting on an image
 * pull.
 *
 * <p><b>No method throws.</b> Every failure this client can meet is one of the four answers, which
 * is the property that makes the {@code switch} at the call site total. An {@code InterruptedException}
 * is {@link Unreachable} with the flag restored: the caller is a worker being asked to stop, so
 * nothing was learned about the workload.
 */
public final class ContainersClient {

  private static final Logger LOG = Logger.getLogger(ContainersClient.class);

  /** The orchestration surface's own path under the base URL, which carries none. */
  public static final String CONTAINERS_PATH = "/containers/api/containers/";

  /** The volume surface's, likewise. */
  public static final String VOLUMES_PATH = "/containers/api/volumes/";

  /**
   * How long a connection may take to establish, and it is <b>in code rather than in config</b> for
   * the reason {@code EventsPublisher} states: it belongs to the {@code HttpClient} instance, which
   * is built once, and two seconds to reach a service on the same docker network is generous
   * whatever the per-call deadline is. A host that has not answered a SYN in two seconds is not
   * going to answer this request.
   */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /** How much of a refusal body is lifted into a {@link Refused}'s message. */
  private static final int MESSAGE_MAX = 300;

  /**
   * An <b>instance</b> field, not a static one: a static {@code HttpClient} is created at image
   * build time and native-image refuses the heap it lands in. A consumer's producer keeps it one
   * client per process, which is what an {@code HttpClient} is for.
   *
   * <p><b>Pinned to HTTP/1.1, and that is not a preference.</b> The JDK client defaults to HTTP/2
   * with an {@code h2c} upgrade, and an upgrade that carries a request body delivers that body
   * <em>twice</em> — measured against qits-eventstream's test stub, where one PUT arrived through
   * the server's upgrade handler and again as an HTTP/2 data frame ninety milliseconds later.
   * There, idempotency made the duplicate harmless. Here the doubled request would be a
   * {@code PUT .../ensure}, whose second delivery races the first through a registry that is
   * writing a row and calling docker between transactions. qits-containers speaks plain HTTP/1.1 on
   * qits-net; there is nothing to upgrade to. Do not drop the {@code version(...)} line.
   *
   * <p>Package-private so {@code HttpVersionPinTest} can read the configured version back off it.
   * The pin is a property of this object and not of any exchange — a response's version is what the
   * two ends negotiated, and a server that only speaks HTTP/1.1 answers 1.1 to an unpinned client
   * too — so the honest assertion is this field's own. {@link QitsHttpClients#wrap}'s client
   * delegates {@code version()} to the raw one built here, so the pin holds wrapped or not.
   */
  final HttpClient http;

  private final String base;

  private final Duration requestTimeout;

  private final Duration ensureTimeout;

  private final TokenSource tokens;

  /**
   * @param url where qits-containers answers: scheme, host and port, <b>no path</b>. A trailing
   *     slash is tolerated rather than doubled.
   * @param requestTimeout the default deadline for every call except {@code ensure}
   * @param ensureTimeout the default deadline for {@code ensure}, which may be pulling an image
   * @param tokens where the machine token comes from, or null for none. A null, empty, blank or
   *     failing source costs the header and never the call: the service refuses the bare request
   *     with a 401, which is a {@code Refused} answer rather than an exception on a worker thread.
   */
  public ContainersClient(
      String url, Duration requestTimeout, Duration ensureTimeout, TokenSource tokens) {
    this(url, requestTimeout, ensureTimeout, tokens, null);
  }

  /**
   * {@link #ContainersClient(String, Duration, Duration, TokenSource)}, with this client's own
   * {@code HttpClient} run through {@link QitsHttpClients#wrap} — a CLIENT span per call and
   * {@code X-Qits-Caller} on it, the same two things a REST client gets from Quarkus and this one
   * otherwise would not. A null {@code clients} is the other constructor exactly: the raw client,
   * unwrapped.
   *
   * @param clients the consumer's own {@link QitsHttpClients} bean, or null to keep the client
   *     unwrapped
   */
  public ContainersClient(
      String url,
      Duration requestTimeout,
      Duration ensureTimeout,
      TokenSource tokens,
      QitsHttpClients clients) {
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("qits.containers.url is required");
    }
    String trimmed = url.trim();
    this.base = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    this.requestTimeout = require(requestTimeout, "requestTimeout");
    this.ensureTimeout = require(ensureTimeout, "ensureTimeout");
    this.tokens = tokens == null ? TokenSource.none() : tokens;
    HttpClient raw =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
    this.http = clients == null ? raw : clients.wrap(raw);
  }

  // --- the one write that starts something ---------------------------------------------------------

  /**
   * Put a container at {@code owner/workload/ref}, or confirm the one already there.
   *
   * <p>{@link Created} the first time the place is seen, {@link Ready} afterwards. An {@code ensure}
   * whose container did not start is a {@link Ready} whose envelope says {@code MISSING} and carries
   * what docker said — a true answer, not a failed request. The two refusals worth branching on are
   * {@link ContainersAnswer#specConflict()} and {@link ContainersAnswer#imageMissing()}.
   */
  public ContainersAnswer<Envelope> ensure(
      String owner, String workload, String ref, EnsureRequest request) {
    return ensure(owner, workload, ref, request, ensureTimeout);
  }

  /** {@link #ensure(String, String, String, EnsureRequest)} against a deadline of the caller's. */
  public ContainersAnswer<Envelope> ensure(
      String owner, String workload, String ref, EnsureRequest request, Duration deadline) {
    return answer(
        exchange(
            builder(placePath(owner, workload, ref), deadline)
                .header("Content-Type", "application/json")
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        ContainersJson.write(request), StandardCharsets.UTF_8))),
        Envelope.class);
  }

  // --- reads ----------------------------------------------------------------------------------------

  /**
   * What is at this place.
   *
   * <p>A 404 arrives as a {@link Refused} carrying status 404, and it means one thing: no row names
   * this place. The service answers 5xx for a read it could not make, precisely so that a caller
   * never reads "could not find out" as "never started" and starts a second workload.
   */
  public ContainersAnswer<Envelope> status(String owner, String workload, String ref) {
    return status(owner, workload, ref, requestTimeout);
  }

  /** {@link #status(String, String, String)} against a deadline of the caller's. */
  public ContainersAnswer<Envelope> status(
      String owner, String workload, String ref, Duration deadline) {
    return answer(exchange(builder(placePath(owner, workload, ref), deadline).GET()), Envelope.class);
  }

  /** Every live place of this owner, oldest first. From the rows, never from a label listing. */
  public ContainersAnswer<List<Envelope>> list(String owner) {
    return list(owner, null, requestTimeout);
  }

  /** Every live place of one of this owner's workloads. A null workload lists the owner's own. */
  public ContainersAnswer<List<Envelope>> list(String owner, String workload) {
    return list(owner, workload, requestTimeout);
  }

  /** {@link #list(String, String)} against a deadline of the caller's. */
  public ContainersAnswer<List<Envelope>> list(String owner, String workload, Duration deadline) {
    String path =
        workload == null || workload.isBlank()
            ? CONTAINERS_PATH + segment(owner)
            : CONTAINERS_PATH + segment(owner) + "/" + segment(workload);
    return map(
        answer(exchange(builder(path, deadline).GET()), Listing.class),
        listing -> listing.containers() == null ? List.of() : listing.containers());
  }

  /**
   * A bounded tail of what this container printed. It works while the place is {@code EXITED},
   * which is the case that matters: a workload that died on its first breath has nothing else to
   * offer.
   *
   * @param tail how many lines, or 0 for the service's own bound
   */
  public ContainersAnswer<LogTail> logs(String owner, String workload, String ref, int tail) {
    return logs(owner, workload, ref, tail, requestTimeout);
  }

  /** {@link #logs(String, String, String, int)} against a deadline of the caller's. */
  public ContainersAnswer<LogTail> logs(
      String owner, String workload, String ref, int tail, Duration deadline) {
    return answer(
        exchange(builder(placePath(owner, workload, ref) + "/logs?tail=" + tail, deadline).GET()),
        LogTail.class);
  }

  // --- the writes that change what is running -------------------------------------------------------

  /** Stop what is here, leaving it restartable. 404 for a place no row names. */
  public ContainersAnswer<Envelope> stop(String owner, String workload, String ref) {
    return stop(owner, workload, ref, requestTimeout);
  }

  /** {@link #stop(String, String, String)} against a deadline of the caller's. */
  public ContainersAnswer<Envelope> stop(
      String owner, String workload, String ref, Duration deadline) {
    return answer(
        exchange(
            builder(placePath(owner, workload, ref) + "/stop", deadline)
                .POST(HttpRequest.BodyPublishers.noBody())),
        Envelope.class);
  }

  /**
   * Record that the owner still wants this workload — one column, no docker call, and the idle
   * sweep is the only reader. Answers {@link Ready} with no value (the route is a 204).
   */
  public ContainersAnswer<Void> touch(String owner, String workload, String ref) {
    return touch(owner, workload, ref, requestTimeout);
  }

  /** {@link #touch(String, String, String)} against a deadline of the caller's. */
  public ContainersAnswer<Void> touch(
      String owner, String workload, String ref, Duration deadline) {
    return answer(
        exchange(
            builder(placePath(owner, workload, ref) + "/touch", deadline)
                .POST(HttpRequest.BodyPublishers.noBody())),
        null);
  }

  /**
   * Remove what is here. <b>Idempotent</b>: a place that was already absent answers {@link Ready}
   * with {@code existed=false}, which is what lets a caller retry a delete rather than special-case
   * one.
   *
   * @param withVolumes take the workload's own volumes with it. Never a shared one.
   * @param withLogs capture a bounded tail <b>before</b> the removal and carry it back on
   *     {@link DeleteOutcome#logTail()} — after the removal there is nothing left to read. This is
   *     what a consumer's own logs-then-reap does by hand today, in one call that cannot lose the
   *     ordering.
   */
  public ContainersAnswer<DeleteOutcome> delete(
      String owner, String workload, String ref, boolean withVolumes, boolean withLogs) {
    return delete(owner, workload, ref, withVolumes, withLogs, requestTimeout);
  }

  /** {@link #delete(String, String, String, boolean, boolean)} against a caller's deadline. */
  public ContainersAnswer<DeleteOutcome> delete(
      String owner,
      String workload,
      String ref,
      boolean withVolumes,
      boolean withLogs,
      Duration deadline) {
    String path =
        placePath(owner, workload, ref) + "?volumes=" + withVolumes + "&logs=" + withLogs;
    return answer(exchange(builder(path, deadline).DELETE()), DeleteOutcome.class);
  }

  /**
   * Remove every one of this owner's workloads of this kind created before an instant — what a
   * consumer's boot reap becomes.
   *
   * <p><b>{@code createdBefore} is required and there is no overload without it.</b> It is what
   * makes this a boot reap instead of a purge: an owner passes the instant it came up, so a
   * workload it started afterwards — including one started while the sweep runs — is not in the set.
   * The service refuses a missing one with a 400 rather than defaulting to now, and this client
   * refuses a null the same way, as an {@link IllegalArgumentException}, because a caller that
   * reached this method with no instant has a bug rather than an outage.
   *
   * <p>It iterates the owner's <b>rows</b>. Two instances sharing one docker daemon cannot reach
   * each other's containers, because neither one's registry names the other's — which is the
   * difference between this and the host-wide label sweep it replaces.
   */
  public ContainersAnswer<List<Destroyed>> destroyAll(
      String owner, String workload, Instant createdBefore) {
    return destroyAll(owner, workload, createdBefore, requestTimeout);
  }

  /** {@link #destroyAll(String, String, Instant)} against a deadline of the caller's. */
  public ContainersAnswer<List<Destroyed>> destroyAll(
      String owner, String workload, Instant createdBefore, Duration deadline) {
    if (createdBefore == null) {
      throw new IllegalArgumentException(
          "createdBefore is required: a default would turn a boot reap into a purge of everything"
              + " this owner has running");
    }
    String path =
        CONTAINERS_PATH
            + segment(owner)
            + "/"
            + segment(workload)
            + "?createdBefore="
            + query(createdBefore.toString());
    return map(
        answer(exchange(builder(path, deadline).DELETE()), DestroyAllOutcome.class),
        outcome -> outcome.destroyed() == null ? List.of() : outcome.destroyed());
  }

  // --- volumes ---------------------------------------------------------------------------------------

  /** Make sure this owner has this volume. Idempotent — docker's own create is. */
  public ContainersAnswer<VolumeEnvelope> ensureVolume(String owner, String name) {
    return ensureVolume(owner, name, requestTimeout);
  }

  /** {@link #ensureVolume(String, String)} against a deadline of the caller's. */
  public ContainersAnswer<VolumeEnvelope> ensureVolume(
      String owner, String name, Duration deadline) {
    return answer(
        exchange(
            builder(volumePath(owner, name), deadline).PUT(HttpRequest.BodyPublishers.noBody())),
        VolumeEnvelope.class);
  }

  /** The row claiming this volume. 404 only when this owner claims none by that name. */
  public ContainersAnswer<VolumeEnvelope> volume(String owner, String name) {
    return volume(owner, name, requestTimeout);
  }

  /** {@link #volume(String, String)} against a deadline of the caller's. */
  public ContainersAnswer<VolumeEnvelope> volume(String owner, String name, Duration deadline) {
    return answer(exchange(builder(volumePath(owner, name), deadline).GET()), VolumeEnvelope.class);
  }

  /** Take this owner's volume away. Idempotent, exactly as a container delete is. */
  public ContainersAnswer<VolumeEnvelope> deleteVolume(String owner, String name) {
    return deleteVolume(owner, name, requestTimeout);
  }

  /** {@link #deleteVolume(String, String)} against a deadline of the caller's. */
  public ContainersAnswer<VolumeEnvelope> deleteVolume(
      String owner, String name, Duration deadline) {
    return answer(exchange(builder(volumePath(owner, name), deadline).DELETE()), VolumeEnvelope.class);
  }

  // --- the one place a request is made ----------------------------------------------------------------

  /** What one attempt produced: a response, or the reason there was none. Never both. */
  private record Exchange(int status, String body, String failure) {}

  /**
   * Build a request against this client's base, with the deadline, the bearer and the cause on it.
   *
   * <p><b>The cause is stamped here rather than by a filter</b>, because there is no filter to
   * stamp it: {@code CausationClientFilter} is a JAX-RS provider and this is a bare
   * {@code HttpClient}. The two lines are the ones {@code CausationHeader}'s javadoc gives for
   * exactly this case, and stamping is skipped rather than faked when there is no ambient cause —
   * an absent header reads as "no cause", which is what it is.
   */
  private HttpRequest.Builder builder(String path, Duration deadline) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(deadline == null ? requestTimeout : deadline)
            .header("Accept", "application/json");
    bearer().ifPresent(token -> builder.header("Authorization", "Bearer " + token));
    UUID cause = CausationScope.current();
    if (cause != null) {
      builder.header(CausationHeader.NAME, cause.toString());
    }
    return builder;
  }

  /**
   * The token, or none. A source that throws is a source with nothing to give.
   *
   * <p><b>A missing token costs the header and never the call.</b> Every route of qits-containers
   * carries {@code @RolesAllowed("qits:system")}, so a bare request comes back 401 — a
   * {@link ContainersAnswer.Refused} that names the real problem, reaches the caller as one of the
   * four answers, and is reportable. Refusing here instead would be a fifth answer, thrown on the
   * caller's own worker thread, and it would guard nothing the service does not already guard.
   */
  private Optional<String> bearer() {
    try {
      Optional<String> token = tokens.bearer();
      return token == null ? Optional.empty() : token.filter(value -> !value.isBlank());
    } catch (RuntimeException e) {
      LOG.warnf("The token source refused to answer, calling unauthenticated: %s", e.toString());
      return Optional.empty();
    }
  }

  /** Send it. Every throw out of {@code send} is "no response arrived", and nothing else is. */
  private Exchange exchange(HttpRequest.Builder builder) {
    HttpRequest request = builder.build();
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      return new Exchange(response.statusCode(), response.body(), null);
    } catch (InterruptedException e) {
      // Restore the flag and treat it as "nothing came back": the caller is a worker being asked to
      // stop, so no answer was reached and nothing was learned about the workload.
      Thread.currentThread().interrupt();
      return new Exchange(0, null, "interrupted");
    } catch (Exception e) {
      // A refused connection, an unresolvable name, a deadline that passed. A STATUS CODE IS THE
      // ONLY THING THAT MAKES AN ATTEMPT A REFUSAL, and there is none here.
      LOG.debugf("%s %s failed: %s", request.method(), request.uri(), e.toString());
      return new Exchange(0, null, e.toString());
    }
  }

  /**
   * The four answers, from one exchange.
   *
   * @param type what a successful body binds to, or null for a route that answers none
   */
  private <T> ContainersAnswer<T> answer(Exchange exchange, Class<T> type) {
    if (exchange.failure() != null) {
      return new Unreachable<>(exchange.failure());
    }
    int status = exchange.status();
    if (status < 200 || status >= 300) {
      return refusal(status, exchange.body());
    }
    if (type == null) {
      return status == 201 ? new Created<>(null) : new Ready<>(null);
    }
    T value = ContainersJson.read(exchange.body(), type);
    if (value == null) {
      // A 2xx whose body will not bind. A response ARRIVED, so this is a refusal and never an
      // unreachable service — the network is fine and retrying changes nothing about the body.
      return new Refused<>(
          status, ContainersWire.UNREADABLE, "could not read a " + type.getSimpleName());
    }
    return status == 201 ? new Created<>(value) : new Ready<>(value);
  }

  /** A response that said no, with the service's own code on it when the body carries one. */
  private static <T> ContainersAnswer<T> refusal(int status, String body) {
    ErrorBody error = ContainersJson.read(body, ErrorBody.class);
    if (error != null && error.code() != null && !error.code().isBlank()) {
      return new Refused<>(status, error.code(), bounded(error.message()));
    }
    // A refusal from something that is not this service — a proxy's 502, a 401 challenge with no
    // body. The status IS the word; there is no null code to make a caller check for one.
    return new Refused<>(status, String.valueOf(status), bounded(body));
  }

  /** Carry an answer's value through a shape change, leaving the other three exactly as they are. */
  private static <A, B> ContainersAnswer<B> map(
      ContainersAnswer<A> answer, Function<A, B> shape) {
    return switch (answer) {
      case Created<A> created -> new Created<>(shape.apply(created.value()));
      case Ready<A> ready -> new Ready<>(shape.apply(ready.value()));
      case Refused<A> refused ->
          new Refused<>(refused.status(), refused.code(), refused.message());
      case Unreachable<A> unreachable -> new Unreachable<>(unreachable.cause());
    };
  }

  // --- paths ---------------------------------------------------------------------------------------

  private static String placePath(String owner, String workload, String ref) {
    return CONTAINERS_PATH + segment(owner) + "/" + segment(workload) + "/" + segment(ref);
  }

  private static String volumePath(String owner, String name) {
    return VOLUMES_PATH + segment(owner) + "/" + segment(name);
  }

  /**
   * One path segment, percent-encoded.
   *
   * <p><b>The client encodes; the service validates.</b> Every one of these values is checked by
   * {@code ContainersIdentifiers} on the far side, where a refusal can name the field and come back
   * as a 400 — so a value this client rejected itself would be a second, drifting set of rules with
   * no way to report itself. What it must not do is let a value forge a path: a {@code /} or a
   * {@code ?} that went through unencoded would address a different route, and that is what this
   * turns into an ordinary 400 instead.
   *
   * <p>{@code URLEncoder} is not used: it encodes a space as {@code +}, which is a query-string
   * rule and is a literal plus inside a path.
   */
  private static String segment(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder out = new StringBuilder(value.length());
    for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
      int c = b & 0xFF;
      boolean unreserved =
          (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9')
              || c == '-'
              || c == '.'
              || c == '_'
              || c == '~';
      if (unreserved) {
        out.append((char) c);
      } else {
        out.append('%').append(String.format("%02X", c));
      }
    }
    return out.toString();
  }

  /** One query-string value. Here {@code URLEncoder} is right, because here a space IS a plus. */
  private static String query(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  // --- shared belts --------------------------------------------------------------------------------

  /**
   * A refusal's text, bounded and with its control characters stripped. It ends up in a caller's
   * log and often in a row, and a value that could carry a newline could forge a second log line —
   * the same reason the service strips what it echoes.
   */
  private static String bounded(String text) {
    if (text == null || text.isBlank()) {
      return "";
    }
    String line = text.strip().lines().findFirst().orElse("").replaceAll("\\p{Cntrl}", " ");
    return line.length() > MESSAGE_MAX ? line.substring(0, MESSAGE_MAX) : line;
  }

  private static Duration require(Duration value, String name) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be a positive duration");
    }
    return value;
  }
}
