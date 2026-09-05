# qits-containers

The platform's container orchestrator: one service that starts, stops and remembers every container
the platform runs for itself.

Five modules shell out to the docker CLI today — qits-ci's pipeline steps, qits-workspaces'
workspaces, qits-projects' refinement agents, and two more — each with its own registry, its own
labels, and its own answer to what happens to a running container when the module restarts. This
service is the one place that answers: **a durable row is written before the container is started, a
restart adopts what is still running, and no code path removes a container that no row names.**

    ./mvnw verify                  # a clone alone, green — no monorepo, no docker, no credentials
    ./mvnw verify -DskipITs=false  # adds the packaged surface, and the real-docker adoption proof

## Layout

| Module | What |
|---|---|
| `core/` | The domain: the registry rows, the workload spec and its lifecycle policies, the docker seam. A library jar. It owns the datasource, the persistence unit and the Flyway lineage. |
| `client/` | What a consumer depends on to **call** this service: the wire records, the four-outcome answer and the HttpClient behind them. It depends on `core` **not at all**. |
| `service/` | The deployable: the REST surface under `/containers/api`, the real docker driver (`dockerhost/`), the machine guard, the boot steps. |

The directories are short and the artifactIds are namespaced (`qits-containers-*`): generic
coordinates like `eu.wohlben:core` would collide in the shared `~/.m2` that every workspace
container mounts.

`client/` not depending on `core` is the boundary worth defending. A caller wants a few records and
an HTTP client; `core` carries entities, a Flyway lineage and datasource defaults at ordinal 100,
and every one of those would arrive in qits-ci, qits-workspaces and qits-projects the day they
depend on it.

## The boundary

**qits-containers runs containers on behalf of an owner. It decides nothing about what should
run.** A caller says which workload belongs at which place, and under which lifecycle policy; the
image, the command and the schedule are the caller's knowledge and stay there.

Two things are deliberately outside it:

- **Deployed applications.** qits-platform-deployments owns those, and it must keep owning them: the
  deployer has to survive the platform being down, including this service.
- **Anything a container talks to.** Containers dial out to stable DNS aliases and re-dial on their
  own, which is what makes a restart of this service invisible to traffic that is already flowing.

**One container is the exception, argued the way the shared volumes are: the platform builder.**
`PlatformBuildkit` ensures a buildkitd container (`qits-buildkitd`, pinned
`qits.containers.buildkit.image`, state volume `qits-buildkitd-state`) at boot — warned about,
never failed on, claimed by no row — and hands its address to every workload that declared the
docker socket as `BUILDKIT_HOST`, unless the caller sent the key itself (an empty caller value is
qits-ci's kill switch and wins). It is not a workload this service decided to run for somebody; it
is infrastructure of the build plane, exactly as the shared maven volume is infrastructure of the
builds — and it exists so that "builds an image" stops implying "holds the host's docker socket".
The wrapper's `qits-buildkit-plan.md` carries the whole migration.

## The registry, and what a restart does

`core/control` is the state machine, and it is proved end to end against a scripted driver rather
than a daemon.

- **`ContainerRegistry`** — `ensure`, `stop`, `touch`, `logs`, `delete`, `destroyAll`. Every one of
  them writes the row before it calls docker, and no transaction spans a docker call. `ensure`
  stores the spec **without its environment** and hashes a canonical form **with** it, so a rotated
  credential is a change the registry can see without ever having stored one. A run docker refuses
  is re-inspected: a container carrying the row's own name is a previous attempt that died before
  recording itself, and it is adopted rather than replaced.
- **`BootSweep`** — a restart adopts what is still running, settles what stopped according to its
  policy, and replays a delete that never finished. Docker being down at boot is a warning and not a
  failed start.
- **`ContainerObserver`** — one ticker, one `ct-worker`, rows only. A running workload is demoted
  after two consecutive passes agree its container is gone; one that comes back recovers, with the
  original failure text kept under the recovery. `IdleSweep`, `VolumeReconcile`, `MaxAgeGc` and
  `RowPrune` run on the same worker, so there is one concurrency model rather than five.

**`destroyAll` is what a consumer's boot reap becomes**: an owner's own rows, filtered by
`createdBefore`. Never a listing by label — that is the reap this repository exists to remove.

## The surface

Everything lives under `/containers/api`, and a **place** is `{owner}/{workload}/{ref}`.

    PUT    /containers/{owner}/{workload}/{ref}      {spec, policy, recreate}  -> the place
    GET    /containers/{owner}/{workload}/{ref}      the place, 404 only when no row names it
    GET    /containers/{owner}[/{workload}]          this owner's places, from the ROWS
    POST   /containers/{owner}/{workload}/{ref}/stop | /touch
    GET    /containers/{owner}/{workload}/{ref}/logs?tail=N     bounded, and works while EXITED
    DELETE /containers/{owner}/{workload}/{ref}?volumes=&logs=  idempotent; the tail comes back
    DELETE /containers/{owner}/{workload}?createdBefore=<ISO>   the boot reap; the instant is REQUIRED
    PUT|GET|DELETE /volumes/{owner}/{name}

One envelope answers about a place: `{id, containerName, state:{desired,observed}, endpoint:{…},
specHash, created}`. `endpoint.proxy` is null today and is there because the data plane arrives
behind it. Errors are typed: 409 `SPEC_CONFLICT` for a recreate a run-once policy cannot answer,
409 `NAME_TAKEN` for a container name a live workload of another place holds, 409 `IMAGE_MISSING`
for an image nothing published, 400 `INVALID` for a value that will not go into an argv. **A failed
read is a 5xx and never a 404** — a caller that read 404 would conclude its workload was never
started, and start a second one.

**A place that was deleted can be started again, immediately and under the same name.** The name is
derived from the place, and V3 made it unique among live rows only — so a settled row keeps the name
it ran under for history while the name itself is free the moment the place is. V1 held it until the
row prune (P7D), which refused every delete-then-ensure a consumer runs as an uncoded 500.

**`createdBefore` being required is the boot reap's whole shape.** An owner passes the instant it
came up, so what it started afterwards — including while the sweep runs — is not in the set.

### Who may call

Every route, reads included. The rest of the platform guards its writes and leaves its reads open
because a person reads through the gateway; nothing here is read by a person, and an inventory of
running containers is as much a module's own as the containers are.

The `{owner}` in the path must be the machine token's **subject, whole**: qits-idp mints
`dev-qits-ci` and `prod-qits-ci` as two client ids, and that environment prefix is exactly what
keeps two environments sharing one docker daemon out of each other's rows. Until the platform-wide
gate `qits.auth.machine.required` is on — it ships off, as it does everywhere — the path owner is
trusted and no bearer is needed.

## The client

`client/` is what a consumer depends on to call all of the above. One class, four answers, no
framework:

    ContainersClient client = new ContainersClient(url, requestTimeout, ensureTimeout, tokens);

    switch (client.ensure(owner, workload, ref, EnsureRequest.of(spec, policy))) {
      case Created(var place) -> …   // 201, the place is new
      case Ready(var place)   -> …   // 200, it was already there
      case Refused(int s, String code, String m) -> …   // it said no
      case Unreachable(String cause)            -> …   // nothing answered
    }

**`REFUSED` and `UNREACHABLE` are never one thing.** A refusal is evidence about the request; an
unreachable service is evidence about nothing at all. A caller that read the second as the first
would conclude its workload was never started and start a second one — which is the failure this
whole service exists to remove, arriving through its own client. There is deliberately no
`retryable()`: two of the four warrant another attempt and they warrant differently-shaped ones.

`ensure` gets its own deadline because it may be pulling an image; everything else shares one, and
every method takes a `Duration` of the caller's. The client never throws.

### Wiring it up

The defaults ship in the jar at ordinal 100 — `qits.containers.url`,
`qits.containers.client.request-timeout`, `qits.containers.client.ensure-timeout` — so a consumer's
producer names keys rather than values:

```java
@ApplicationScoped
class ContainersClientProducer {
  @ConfigProperty(name = "qits.containers.url") String url;
  @ConfigProperty(name = "qits.containers.client.request-timeout") Duration request;
  @ConfigProperty(name = "qits.containers.client.ensure-timeout") Duration ensure;
  @Inject MachineTokens tokens;   // the consumer's own; TokenSource.none() until the gate is on

  @Produces @Singleton
  ContainersClient client() {
    return new ContainersClient(url, request, ensure, () -> tokens.forAudience("qits-containers"));
  }
}
```

**The producer is the consumer's job and the jar brings no container to do it.** That is what keeps
`quarkus-arc` and every OIDC extension out of this jar, and it is what lets a daemon with no CDI
construct one with `new`. `TokenSource` is asked per request, so a consumer's own caching decides
when a token is stale; empty is the shipped posture, because the service's gate ships off.

### What a native consumer registers

`ContainersJson` builds its **own** `ObjectMapper`, for the reason `CanonicalJson` does — a
consuming application's `ObjectMapperCustomizer`s must not reach what this client puts on a wire —
and that mapper is invisible to the build step that scans for what needs reflecting on. So a
deployable that native-image-compiles owes the wire records a registration, exactly as it owes
qits-eventstream one (`EventWireReflection` is the worked example, and what it cost when it was
missing is in its javadoc: a green build, a failure on every call, one WARN).

The list is closed and every entry is nested in one class, so it is a paste rather than a
derivation:

```java
@RegisterForReflection(targets = {
    ContainersWire.EnsureRequest.class, ContainersWire.Spec.class, ContainersWire.Policy.class,
    ContainersWire.Security.class, ContainersWire.VolumeMount.class, ContainersWire.SharedMount.class,
    ContainersWire.Recreate.class, ContainersWire.PolicyType.class, ContainersWire.PullPolicy.class,
    ContainersWire.Envelope.class, ContainersWire.State.class, ContainersWire.Endpoint.class,
    ContainersWire.Listing.class, ContainersWire.LogTail.class, ContainersWire.DeleteOutcome.class,
    ContainersWire.Destroyed.class, ContainersWire.DestroyAllOutcome.class,
    ContainersWire.VolumeEnvelope.class, ContainersWire.ErrorBody.class,
    ContainersWire.Desired.class, ContainersWire.Observed.class, ContainersWire.VolumeState.class})
public final class ContainersWireReflection {}
```

Both directions are on it, and a record this consumer only *sends* is as dependent on it as one it
reads: the failure is on the writing side too, where an unregistered record has no components to
find. A JVM test cannot catch a missing entry — on a JVM these types reflect whether anyone
registered them or not — which is why the list is written down here rather than discovered.

## The data plane

**A container that dials home can be reached, and it needs no address of its own.** This is the
reverse tunnel qits-workspaces-service and qits-projects-service each carry a copy of, ported here
once (`service/…/proxy/`) and **switched off**: `qits.containers.proxy.enabled` ships `false`.

    ws  /containers/tunnel/{rowId}          the control socket, dialled by the container
    ws  /containers/tunnel/stream/{nonce}   the dial-back, one per parked connection

`ContainerTunnels` binds a **loopback** `NetServer` per registry row and hands out
`ProxyOrigin(client, port)`. A caller connects there; the connection is parked; the container is
asked over its control socket to come and collect it by nonce; it dials the stream path back and the
two are married into a byte pipe. Three properties are the whole design and none is an optimisation:

- **The origin's `HttpClient` travels with its port and must be used.** An ephemeral port is reused,
  so a pool keyed on `(host, port)` can hold a connection wired through to the *previous* tenant of
  that port — here a cross-owner read into another module's container. Each tunnel owns its client
  and is closed with it.
- **The nonce is the dial-back's whole authentication**: host-minted, single-use (an atomic map
  removal), short-lived, and bound to the row it was sent to. Unknown and already-claimed both get a
  bare 404.
- **Bytes, not framed requests**, which is what lets a WebSocket upgrade traverse a tunnel unchanged.
  The stream route is raw Vert.x for exactly one reason: websockets-next' connection has
  `sendBinary` and no `writeQueueFull`/`drainHandler`, and a byte tunnel with no backpressure signal
  is an unbounded heap buffer.

**The control socket authenticates, and that is where this departs from what it was ported from.**
Both sources name their caller with a path parameter and check nothing, so anything on the platform
network can claim to be any project's or any workspace's daemon — a weakness both repos record and
carry because containers are already running against those contracts. This one is fresh, so the row
id in the path is the *claim* and `X-Qits-Tunnel-Secret` is the evidence.

**`TunnelProtocol` is append-only.** Its paths and frame names are baked into a container's
environment at creation and only a recreate re-injects them, so a value that changed meaning would
break every container already running.

### What a restart needs from it: nothing

All of this is in memory and rebuilt lazily. The durable fact this service keeps is **which
containers exist** — that is what the rows are — and a socket is not that kind of fact: it dies with
the process at both ends. So a restart re-adopts the containers, the daemons re-dial, and the first
request binds a listener again. Nothing here is reconciled with anything and nothing may be
persisted "for" it.

### What round 2 owes

- **A consumer.** qits-workspaces and qits-projects own their proxy routes for now, and neither was
  ported: what a route needs is the origin, and the origin is what this ships. `endpoint.proxy` is
  still null on the envelope, and it is what a migrating consumer will read.
- **The secret on the ensure envelope.** `ContainerTunnels.issueSecret` mints one; nothing calls it
  yet, because handing it to a container is a change to the wire both the client and the service
  restate, and that lands with the first consumer.
- **A durable-secret decision, whose default is already chosen.** A column on the row would survive a
  restart of *this* service — at the cost of a migration and of storing a live credential in the one
  table whose design decision is that it stores none. **Re-issue on adopt** is the restart-safe
  answer and needs no schema: the boot sweep already adopts every row whose container is still
  running, which is exactly the moment a fresh secret can be handed over. What it needs is one frame
  in the protocol for telling a running container its new secret.

## Garbage collection

Four routes clean up what the platform's builds and containers leave on the host. They are the
platform's rather than an owner's — an image is named by no owner — so they carry the machine role
and **no owner guard**, and every policy value arrives in the body from
qits-platform-orchestrator, which reads the pins once per run and hands them to every deleter.

    GET  /containers/api/gc/usage           docker system df, as four stores of four numbers
    POST /containers/api/gc/images          {dryRun, minAge, keep[], keepPrefixes[]}
    POST /containers/api/gc/volumes         {dryRun, minAge}
    POST /containers/api/gc/build-cache     {dryRun, keepStorageBytes}

The image listing is `docker image ls --all`, which is not a detail: on the containerd image store
a plain listing prints **no** `<none>` rows at all, so 55 dangling images survived two collection
runs before anyone noticed.

**Images are kept by four rules, checked in order, and removed only if none of them speaks:**
`in-use` (a container was created from it, running or not), `live-row` (a live registry row names
it), `pinned` (a `keep` entry or a `keepPrefixes` match), `too-young` (built inside `minAge` — which
is what protects an image a CI step has built and not yet pushed). Everything else goes, dangling
and tagged alike.

Removal is never forced and never a `prune`, and it takes two shapes because docker has two
answers: a **dangling** image goes by `docker image rm <id>`, a **tagged** one by
`docker image rm <every tag it carries>` — an id that more than one reference names is refused with
`must be forced`, and two tags of one repository are two references. Untagging them all in one call
removes the image on the last.

**Volumes: only dangling ones are candidates**, and only three classes of them are removed —
`managed-no-row` (ours, and no row claims it), `buildx-state` (a dead builder's cache store) and
`anonymous` (docker's own 64-hex name). Everything else dangling is kept as `unmanaged`, which is
this repository's first invariant read for volumes: what this service cannot account for is
somebody else's.

**Build cache** is pruned down to `keepStorageBytes` on the host builder and to
`builderKeepStorageBytes` inside every `buildx_buildkit_*` container — the second is optional and
falls back to the first, and it is there because a bootstrap builder is only useful while a
bootstrap runs, so the platform keeps it far smaller than its own cache. The host prune is
`--all`: **keep-storage is the policy, `--all` is the candidate set** — without it a prune only
considers dangling cache records, which freed 4 GB of a 40 GB cache on the run that found this.
`--all` offers every record no build is using; how much survives is still the keep-storage, LRU,
and a record an in-flight build holds is untouchable either way. A builder that fails costs itself
a row of the answer and never the call.

**No row is written, updated or deleted by any of it.** Two of the four read rows, and only to
protect something: the image sweep keeps what a live row names, and the volume sweep keeps what a
row claims. A collection that could write a row would be able to erase the record of what it
removed.

Everything is reversible before it happens: `dryRun` reports exactly what a real run would do, and
a body that forgot the field **is** a dry run.

## What is deliberately *not* here yet

- **The consumers.** qits-ci, qits-workspaces and qits-projects still run their own containers; the
  point of this service is that they stop, one at a time.

## Deploying it

Nothing builds on a push any more. **Releasing is opening a release request** — `POST
/projects/api/repositories/<repoId>/release-requests` — which folds the named branches onto
`release/<id>`; `.config/qits/ci-event-release-request.yml` builds that fold and its green gating
verdict is what lets qits-projects stamp the CalVer, tag, and publish `SCMRelease`. Only then does
`.config/qits/ci-event-release.yml` build `docker/Dockerfile` — a Mandrel builder stage that
native-compiles `service`, a `ubi-minimal` runtime stage that carries the binary **and the docker
CLI** — and push it as `qits/qits-containers:<version>`. Both files' builds run `--network host`
with `--build-arg QITS_MAVEN_REPOSITORY_URL=…`, because the four platform jars this repo takes exist
only in the platform's own Maven repository and a docker build reaches no other address for them.

**Neither pipeline can be one step**, because no build image carries both a JDK and the docker CLI:
the suite runs on `maven-base`, the image build on `ci-base`. The QA pipeline adds a third,
non-gating step for the userflow bundle. The release pipeline publishes **three** things —
`qits-containers-core`, `qits-containers-client` and the image — which makes it the platform's first
dual maven+docker release; `AGENTS.md` says what that costs.

`.config/qits/deployments.yml` is the deploy answer: **an environment service**, with
`resources: postgresql:db, postgresql:eventstream:qits_containers_eventstream` and the health gate at
`/containers/q/health/ready`.

**One port, 8080**, for all of it — `/containers/api/**` (JAX-RS), `/containers/tunnel/**` (the data
plane's sockets) and `/containers/q/**` (health, OpenAPI) are three route stacks in one process, not
three listeners. The tunnels this service binds for itself are **loopback** and are not this port.

### What a deployment must set

| Env | Why it is not defaulted |
|---|---|
| `QITS_RESOURCE_DB_URL` / `_USERNAME` / `_PASSWORD` | The registry. Nothing is defaulted, so an unset triple is a boot failure at Flyway rather than an orchestrator answering from a database nobody meant. On the platform these are not set by hand: `.config/qits/deployments.yml` declares `resources: postgresql:db` and qits-deployments injects all three before the container starts. |
| `QITS_RESOURCE_EVENTSTREAM_URL` / `_USERNAME` / `_PASSWORD` | The event bus client's own store, on the same contract — `resources: postgresql:eventstream:qits_containers_eventstream`. The resource must be named `eventstream`, because the variable names follow the resource name, and it must name its database, because the derived default is the one `db` already took. |

**Refusing to boot without a database is deliberate.** The rows are the only record of which
containers may exist. An orchestrator that came up on an empty store it invented would see every
running container as named by no row — which is precisely the state the adoption rule exists to
prevent it from acting on.

### The docker socket, which is a decision rather than a default

**The socket is not in the image, and it is the deployment that grants it:**

    -v /var/run/docker.sock:/var/run/docker.sock --group-add <the socket's gid>

The mount hands this container control of the host's daemon, which is root-equivalent, so it is an
explicit act rather than something baked in. `--group-add` is the other half of the same act: the
image runs as UID 1001 in group 0 and the socket is `root:docker` 0660, so without the daemon's group
the CLI is present and every call is a permission denial — which reads like a broken image and is
not. Neither is a key in the deployment grammar; both are run-args.

Without the socket the service still starts and still serves. That is the boot stance below, not an
oversight: a host that has just rebooted has this service up before its docker.

**A workload that declares the bind gets the same second half, and it does not ask for it.** A spec
with `hostDockerSocket` renders `-v /var/run/docker.sock:…` *and* `--group-add <the socket's gid>`,
where the gid is read off the socket by this process (`dockerhost/DockerSocketGroup`, overridable
with `QITS_CONTAINERS_DOCKER_SOCKET_GROUP`) — the same `unix:gid` the platform bootstrap reads when
it decides which group to start this service in. The reason is the paragraph above, one hop out: a
container holding the bind and running as anybody but root is refused on `connect`, so the mount
alone is a puzzle rather than a privilege. qits-ci never met it because its opted-in steps run as
the image's own root; qits-workspaces' admin workspaces do, because a workspace container runs as
the host uid.

It is **not** a spec field, deliberately: the group is a fact about this host rather than a request,
so no caller can name one, and it is rendered inside the socket's own arm — a workload that did not
declare the bind joins no group whatever the host's is. A host with no socket at that path answers
blank and renders no flag at all, which is the argv this service shipped before the group existed.

### What else a deployment may set

Everything else has a shipped default and a deployment overrides what it means to:
`QITS_AUTH_MACHINE_REQUIRED=true` with `QITS_AUTH_MACHINE_AUDIENCE=<env>-qits-containers` turns the
gate on — one platform-wide gate, shipped off, and turning it on with no audience set fails at
startup rather than accepting tokens addressed elsewhere. `QITS_CONTAINERS_INSTANCE` distinguishes
two instances in `docker ps`; `QITS_CONTAINERS_NETWORK` and `QITS_CONTAINERS_SHARED_VOLUMES` name
what the boot step makes sure of; `QITS_CONTAINERS_PROXY_ENABLED=true` switches the data plane on,
and no deployment sets it yet.

### What a boot does, in order

1. **`SharedResources`** makes the three shared volumes (`qits_shared_*`) and asks whether
   `qits-net` is there. **It never creates a network**: one invented here would be a network no
   other module's containers are on, and a bridge cannot be created on a swarm host at all.
2. **`BootSweep`** adopts what is still running, settles what stopped per policy, and replays a
   delete that never finished.
3. **`ContainerObserver`** starts its ticker — last, by CDI priority, so no observation pass meets
   an in-flight row before the sweep has decided about it.

None of the three fails a boot. A host that has just rebooted has this service up before its docker,
and an orchestrator that refused to start because it could not reach docker would be one that could
not be deployed to fix docker.

### The health probe

    GET /containers/q/health/ready     the gate; UP only when both stores are reachable
    GET /containers/q/health/live      the process is running

Readiness, not liveness: quarkus-agroal contributes a check per datasource, so a container that
booted and cannot reach its registry is red.
