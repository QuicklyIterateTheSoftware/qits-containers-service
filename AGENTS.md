# qits-containers — working notes

Read `README.md` first: it defines the boundary and the module split. This file is the conventions
on top of it.

It is short because the repository is. Grow it with the code — a design document written before the
code is a document the code will contradict.

## The rule that shapes everything

**A clone of this repo alone builds and tests green** — no monorepo, no docker, no prior
`mvn install` elsewhere, no credentials. `./mvnw verify` is the gate.

That rule bites hardest here, because docker is this repository's subject. A suite that needed a
daemon to say what the orchestrator does could never say it without one, so the docker seam is faked
in tests and the real driver is proved by an integration test that opts in — `ContainersRestartAdoptionIT`,
tagged `extended`, run by `./mvnw verify -DskipITs=false` and excluded from the `-Dnative` gate
through `qits.it.excluded-groups`. It is the one place the headline claim is really made: a
container's docker `Id` and `StartedAt` are unchanged after a restart adopts it, and those two
numbers only exist on a host.

The store being postgres costs no docker either: `testdb/EmbeddedPg` spawns zonky's real binaries as
a child process — a maven dependency, not a container. **Never Testcontainers, never a Quarkus dev
service.**

**One address is the whole exception.** `qits-db-core`, `qits-arch-rules`, `qits-eventstream` and
`qits-auth-core` come from the platform Maven repository (`<repositories>` in the root pom), so a
clone builds green with that repository reachable and offline once the jars are in `~/.m2`. Nothing
else may follow them in.

`qits-auth-core` is the fourth and it arrived in WP4, which is what widening that list costs: every
route here is addressed to an owner, the owner **is** the caller, and the platform has exactly one
answer to "who is calling". A second answer invented in this repository would be the interim static
token the platform has a standing decision against. It brings no server and no transport —
quarkus-oidc validates the bearer, and the jar reads the identity that validation leaves behind.

**The suite mints its own tokens, and they carry `groups`.** `api/MachineTokens` signs RS256 with
the key pair in `service/src/test/resources/machine-token-*.pem`, and `MachineGuardProfile` hands
quarkus-oidc the public half, so the enforced path runs end to end with no qits-idp to reach. Those
PEMs are test fixtures, not credentials. A token minted with only `iss`/`sub`/`aud` authenticates
perfectly and is then refused 403 on every route — the failure that kept this repository red through
its first two CI runs — so `token(...)` mints the two system roles and `rolelessToken(...)` mints the
empty set on purpose, to assert that refusal rather than meet it by accident.

## The two invariants this repo exists for

**1. Adopt on boot, never reap.** No code path may remove a container that no registry row names.
A restart of this service finds its containers still running and adopts them; a container with no
row is somebody else's — a compose original, a bootstrap seed, a container from before this service
existed — and unclaimed means left alone. The row is written **before** `docker run`, so a crash can
never leave a container the registry has no name for. Anything that would sweep the host by label
rather than by row is the regression this repo was built to remove.

**1a. An image is not a row, and the keep rules are what stands in for one.** The garbage
collection (`control/ImageGc`, `control/VolumeGc`, `control/BuildCacheGc`) is the one part of this
service that removes things no registry row ever named — nothing else could, because the platform's
builds put the images there and any module's containers run from them. So "unclaimed means somebody
else's" has nothing to read, and what replaces it is a list of reasons to **keep**, checked in
order, with removal as the fall-through: in use by a container, named by a live row, pinned by the
caller, younger than the caller's grace. Two consequences are not negotiable. **A listing that
protects throws rather than degrading** — `listImageReferencesInUse` and `listContainersUsingVolume`
answer empty only when docker really said so, because an empty answer from either is what makes
something removable; every other listing may degrade to empty, which costs a run that removes
nothing. And **the candidate set is always the daemon's own listing**: never `image prune -a`, never
`rm -f`, never a pattern or a label sweep, because a prune is docker deciding and the whole point is
that this service decides, one thing at a time, with the rows and the pins in front of it. **A
tagged image is removed by untagging every reference it carries, and only a dangling one by its
id** — docker refuses an id that more than one reference names, so an id-only removal was 20 of 32
candidates failed on the first real run, and reaching for `-f` there would be the one flag that
takes an image a container is holding. A
dangling volume is under the same rule with three named classes; everything else dangling is kept
`unmanaged`.

**2. Every docker call carries a timeout and an output bound.** Both are security properties, not
tuning: a `docker logs` with no bound is a heap the caller chose the size of, and a call with no
timeout is a worker held forever by a daemon that stopped answering. There is no call shape that is
exempt, including the ones that "cannot" block.

## Conventions

- `eu.wohlben.qits.containers.*`, split across three maven modules with disjoint sub-packages, so
  there is no split package. `core` owns the root, `spec`, `docker`, `control`, `entity` and
  `persistence`; `client` owns `client`; `service` owns the adapters.
- **`control` never touches docker directly and `entity` never decides anything.** The registry, the
  boot sweep, the observer and the policy sweeps all live in `control`, they all call the seam, and
  they all write rows through the repositories in `persistence`. A query that answers "which
  containers look like mine" belongs in neither: the rows are the registry, and a listing by label
  is the reap this repo exists to remove.
- **A docker daemon that did not answer is not a docker daemon with no such container.**
  `DockerContainersDriver.inspect` **throws** when the call could not be made or timed out, and
  answers empty only for a refusal that says "no such container". Its empty answer is a positive
  statement the boot sweep settles rows on and that `delete` reads as "it is really gone" — a delete
  that took "we could not find out" for that would settle `GONE` over a container still running,
  which nothing would ever look at again. Every caller already treats the throw as "say nothing".
  The listings degrade to empty with a warning instead, because an empty listing is a statement
  about no particular container.
- **`core/docker` is argv and process, never a docker call.** `DockerArgv` is pure functions and
  `ContainerProcess` is the shell-out; the driver that puts them together is an interface here
  (`control/ContainersDriver`) and an implementation in `service/`. That is what lets the argvs — the
  sandbox itself — be asserted element for element with no daemon anywhere. `docker exec` is in that
  vocabulary for **one** command — `buildctl prune`/`du` inside a `buildx_buildkit_*` container —
  and both of its words are constants; `ContainerProcess`'s javadoc says what a second use would
  cost. The reading of what those calls print is `service/dockerhost/DockerGcReads`, pure functions
  beside the driver for the same reason the argvs sit beside it.
- **The fakes are duplicated per module, not shared.** Maven has no `testFixtures`, and a test-jar
  dependency between modules that otherwise have none is the higher price. `core`'s
  `FakeContainersDriver` is the original; a module that needs one copies it. Same stance as
  qits-workspaces' two `FakeContainerRuntime`s and qits-ci's two `FakeCiStepRunner`s. **The
  `service` copy is an `@Alternative` with no priority**, because that module ships the real driver:
  an ordinary bean would be an ambiguous resolution and a globally enabled alternative would take
  the daemon away from the one test that needs it, so each suite names the driver it means in its
  profile's `getEnabledAlternatives()`.
- **Every route is guarded, reads included** — and it is guarded **twice**. The rest of the fleet
  guards its writes and leaves its reads open because a person reads through the gateway; nothing
  here is read by a person, and an inventory of running containers is as much a module's own as the
  containers are.

  **The outer half is `@RolesAllowed("qits:system")`**, on `ContainersResource`, `VolumesResource`
  and `proxy/TunnelControlSocket` — the fleet's coarse machine role, which qits-idp copies from
  `qits.idp.client.<id>.roles` into the token's `groups` claim and quarkus-oidc reads as roles with
  no configuration at all. It does **not** follow the rollout gate: it is on in every posture, and
  what keeps a credential-free `./mvnw verify` green is qits-auth-core's `%test` dev user, which
  holds every platform role. The sibling control sockets carry the same annotation, so the tunnel is
  not a departure.

  **The four `gc/` routes carry the outer half and deliberately not the inner one.** They are
  addressed to the HOST — its images, its dangling volumes, its build cache — which belong to no
  owner, so there is no path owner to compare a subject against and no honest way to invent one.
  `MachineGuardTest` pins that absence as well as the role, because a route that quietly gained an
  `OwnerGuard` would refuse qits-platform-orchestrator, whose subject is nobody's owner.

  **The inner half is `api/OwnerGuard`**: the owner in the path compared against the machine token's
  **subject, whole** — `dev-qits-ci` and `prod-qits-ci` are two owners, and that prefix is what keeps
  two environments sharing one docker daemon apart. With the rollout gate off (the shipped default)
  the path owner is trusted, exactly as every sibling behaves.

  **Three doors, and knowing which shut is how a grant is debugged.** A token minted for another
  service is refused **401** by `quarkus.oidc.token.audience` before any identity exists. A token
  addressed here whose client was granted no roles authenticates and is refused **403** by
  `@RolesAllowed`. A token holding the role but belonging to another owner is refused **403** by
  `OwnerGuard`. No credential at all is 401. `MachineGuardTest` pins all four.
- **The datasource, the persistence unit and the Flyway lineage live in `core`**, shipped as
  ordinal-100 defaults in `META-INF/microprofile-config.properties`. The app's own settings are in
  `service/src/main/resources/application.properties` at ordinal 250. Never restate one file's key
  in the other, and never re-declare an app-level setting in test resources: a suite green because
  the *test* copy is right proves nothing about what ships.
- **A type Jackson touches outside a REST signature is named in a reflection holder.** Quarkus
  auto-registers what it finds on a resource method; nothing else. `SpecFingerprint` builds its own
  `ObjectMapper` and serializes `ContainerSpec` from inside the registry, so the spec records are
  invisible to that scan — and a missing registration is green on the JVM, green in this suite, and
  a 500 on every `ensure` the native binary answers. Measured 2026-08-11, on the first real CI step
  this service was asked to start. Two holders carry the lists, each beside what it covers:
  `spec/SpecReflection` for the spec records and `api/ContainersWireReflection` for the whole wire
  family (`ErrorBody` is the one that is really invisible — it reaches a caller only as a `Response`
  entity — and registering the family rather than the doubted entry is what stops the next record
  inheriting somebody's reading of the build step). Adding a record component adds an entry;
  `SpecReflectionCoverageTest` and `ContainersWireReflectionTest` fail when the two drift apart.
  Neither test can prove the registration *works* — only a native binary can.
- **Schema changes append to `core/src/main/resources/db/containers/migration/`.** Never edit an
  applied migration. V1's header records the two decisions it makes — no check constraints on enum
  columns, and the spec's environment is never persisted because it carries secrets. V2 is that rule
  being followed: one nullable `max_age_s`, no backfill, because a policy value the sweeps read has
  to live on the row or a restart forgets it. V3 is the correction V1 needed: `container_name` was
  unique table-wide while the place index beside it was partial, so a soft-deleted row held its own
  place's derived name until the prune horizon and every delete-then-ensure came back as a raw 23505
  — a 500 with no code. The name is unique among live rows now, and the collision that is real (a
  live row of ANOTHER place) is refused before the insert, as `NAME_TAKEN`.
- **A lookup by container name is a lookup among LIVE rows.** Since V3 a settled row keeps its name,
  so `findLiveByContainerName` is the only shape that question has: a plain one could settle an
  observation onto a row deleted a week ago while the container it describes belongs to the row
  running now.

## The worker, and the brackets

**One worker.** `ContainerObserver` owns a bare daemon ticker (`ct-observation-ticker`) and a
single-threaded `ct-worker`, and every background write of this service runs there in queue order —
the observation pass, the idle sweep, the volume reconcile, the max-age collection and the row
prune. A tick arriving while a pass is queued collapses into it. Do not give a sweep a thread or a
scheduler of its own: a second concurrency model is how a sweep comes to stop a container an
`ensure` is halfway through starting.

**No transaction spans a docker call**, anywhere. Read the candidates in one bracket, copy them out
as plain values, ask docker between brackets, write each outcome in its own. A record crossing that
boundary is never an entity.

**Which `DbRetry` spelling to use is decided by who owns the transaction**, not by taste. A read is
`DbRetry.call` around a bracket the read opens itself; a state transition **is** a
`DbRetry.inNewTx`/`runInNewTx`, and every one of those bodies ends in a `flush()` — an ORM flushes
at commit by default, which would put the write on the far side of the one round trip nothing can
place. Without the flush the wrap reports rather than helps. The budget is
`ContainerRegistry.CUTOVER_BUDGET` (30s), package-private so one number is not spelled twice.

**Nothing in `control` sets a causation id.** The table's one insert is `ensure`, on the caller's own
thread, where `@PrePersist` still sees the ambient scope — measured, and `CtCausationStampTest` is
the measurement. A writer that ever **inserts** a row from a background thread must set the cause as
data (`CausedRow.causationId(UUID)`), the way qits-ci's `CiRun` does across its queue hop, and never
ship a stamp that writes nothing.

**The Clock is injected and this module produces none.** The qits-eventstream jar ships a
`@DefaultBean` `java.time.Clock` for the whole platform, and a second default producer of the same
type fails the build with an ambiguous resolution — measured 2026-08-11. `java.time.Clock` is a JDK
type, so nothing in `core` imports an eventstream class for it.
- **`client` must not gain a dependency on `core`.** See README's Layout, and the client section
  below.
- `DatasourceBaselineTest` and `ArchRulesTest` are the platform's shared rules, test scope from
  `qits-arch-rules`. They fail this build for a datasource missing a line of the three-line
  resilience block, and for an entity that neither implements `CausedRow` nor declares `@Uncaused`.

## The data plane, and the two rules it is under

`service/…/proxy/` is the reverse tunnel, ported from qits-projects' `AgentTunnels` and
qits-workspaces' `WorkspaceTunnels` — near-identical twins whose javadocs carry the measurements and
are reproduced rather than summarized. README's "The data plane" says what it is and what round 2
owes; these are the two rules you can break without the build noticing.

**1. `TunnelProtocol` is APPEND-ONLY.** Every constant in it — both paths, both frame names, the
field names, the handshake header — is baked into a container's environment at creation, and only a
*recreate* re-injects it. So a container started this morning is still dialling the string that file
held this morning. Add a constant, never repurpose one; a behaviour change bumps
`CAPABILITY_VERSION` and the host branches on what a daemon announces. The one derivation allowed is
the stream prefix being built on the control prefix, so the two cannot drift.

**2. The per-tunnel secret is deliberate, and it is where this port departs from its sources.** Both
control sockets it was ported from are token-free and take their caller's identity from a **path
parameter**, so anything on the platform's network can claim to be any project's or any workspace's
daemon. Both repositories record that and carry it, correctly: containers are already running
against those contracts and an interim token is what the platform has a standing decision against.
Neither reason applies here — nothing runs against this contract yet — so the row id in the path is
the *claim* and `X-Qits-Tunnel-Secret` is the evidence, checked constant-time, before the row is even
read. **Do not "simplify" it back to the sources' shape**, and do not add a second credential beside
it: the dial-back stays nonce-only, because a dial-back repeating the secret would be a second place
for it to leak.

The durable-secret question is *deferred, not open*: re-issue on adopt is the answer, a column on
the row is the one to argue against. README says why.

Three smaller things, each a trap:

- **The gate refuses at open; it cannot unregister.** A websockets-next endpoint is registered at
  augmentation, so with `qits.containers.proxy.enabled` off the socket exists and closes every dial
  with `TunnelProtocol.Close.DISABLED`. `TunnelGateTest` asserts the refusal, the route's 404 **and**
  that no loopback listener is bound — that third one is what keeps the gate from becoming a gate in
  name only. It runs on `FakeDriverProfile`, the profile with no config overrides, because the claim
  is about the value the jar ships.
- **The stream route is raw Vert.x and must stay raw**, even though the extension is now in this
  module. `io.quarkus.websockets.next.Connection` has `sendBinary` and no
  `writeQueueFull`/`drainHandler`, and a byte tunnel with no backpressure signal is an unbounded heap
  buffer. Same reason both sources give.
- **The socket reads the row through `ContainerRegistry.place`, never through the repository.** An
  empty answer there disconnects a daemon, so it must mean "no live row" and never "could not ask" —
  the retried bracket is what keeps those apart, and a direct `findById` would refuse every healthy
  container the moment postgres blinked.

## The client module, and its three rules

**1. No `core`, no `service`, and no framework.** The first two are the module split; the third is
what makes the first two survivable. `ContainersClient` is a plain class with a constructor —
nothing annotated, nothing injected — so the jar's whole dependency list is `jackson-databind` and
`qits-eventstream`. A `quarkus-arc` here would put a CDI container into a consumer that deliberately
runs none, and an OIDC extension would put a second answer to "who is calling" beside qits-idp's.
The consumer writes a three-line producer; the README has it.

The one dependency that is not obviously free is **qits-eventstream, and it is taken on purpose**.
The client stamps `X-Qits-Causation-Id` by hand, which is what `CausationHeader`'s own javadoc
prescribes for a caller speaking `java.net.http.HttpClient` — `CausationClientFilter` is a JAX-RS
provider and there is no JAX-RS here to discover it. Copying the header name into this jar would be
a second answer to a settled question that stops matching the day the name moves; declaring the jar
`provided` would make it a line every consumer must restate and a `NoClassDefFoundError` for the one
that forgets. Compile scope, the same reasoning qits-eventstream's own pom gives for taking
qits-db-core at runtime scope rather than optional.

**2. The wire is the contract, and both sides restate it.** `client/ContainersWire` mirrors
`service/api/ContainersWire`; neither jar sees the other and neither may be made to. That is what
lets a consumer be built and released without this repository, and it is why the client's records
**validate nothing** — the belts are `ContainersIdentifiers`', on the far side, where a refusal can
name the field and come back as a 400. Two sets of rules would drift the first time the service's
widened.

The client is **forward compatible in the direction the platform deploys in**: unknown JSON fields
are ignored and an unknown enum constant reads as null (`ContainersJson`, which says what that
costs). The service ships first; a client that refused a body it did not fully recognise would
break every consumer on the day the service was deployed.

**A missing machine token costs the HEADER and never the call**, and that rule belongs to rule 3
below rather than to the guard. `TokenSource` answering empty, null, blank or by throwing sends the
request bare; every route answers 401, which is a `Refused` naming the real problem, reportable, and
one of the four answers. Refusing inside the client would be a fifth answer thrown on a consumer's
own worker thread, and it would guard nothing the service does not already guard — a consumer whose
own oidc client is switched off would stop being able to start a container instead of being told
why. Measured: it was written that way for one commit and took 18 of this repo's own tests down
with it.

**3. Four answers, and the last two never merge.** `ContainersAnswer` is a sealed interface —
`Created`, `Ready`, `Refused`, `Unreachable` — and it carries **no `retryable()`**, for the reason
`EventsPublisher.Delivery` carries none. A 2xx whose body will not bind is a `Refused` with
`UNREADABLE` on it and never an `Unreachable`: something answered, so the evidence is about the
response. Nothing throws; a throw would be a fifth answer with no place in the four, arriving on the
caller's own worker thread.

**Where the client's tests live is decided by what they can see.** The client module's suite is
pure unit tests against a JDK `HttpServer` stub — outcome mapping, URL building, header stamping,
the HTTP/1.1 pin — with no application, no database and no docker. The pairing with the real routes
is `service`'s `ContainersClientWireTest`, because test scope on the deployable is the only
classpath both jars may share. **The HTTP/1.1 pin is asserted on the client's configuration, not on
a response**: `HttpResponse.version()` reports what the two ends negotiated, and every server this
client meets speaks HTTP/1.1, so that assertion would pass with the `version(...)` line deleted.

## The pipelines, and the one thing that is new about them

`README.md`'s "Deploying it" has the shape. Two things about `.config/qits/` are this file's.

**The two that produce an image are two steps, and no build image would let them be one.** (The
third, `ci-event-userflows.yml`, produces documentation and is one step on `maven-base` — see the
section below.) A `./mvnw` needs a JDK
and `ci-base` is `docker:cli` plus bash/curl/git/jq; `maven-base` carries no docker CLI. So the
suite runs on one image and the image build on the other. Each step is its own container with its
own clone, which is why the release pipeline's two steps each read the version and check out the tag
rather than one doing it for both.

**`.config/qits/ci-event-release.yml` is the platform's FIRST dual maven+docker release**, and the
probe-skip semantics are the part to get right. `artifacts:` declares three things — the two library
jars and the image — because a declaration is a claim about what **this script pushes**, and qits-ci
announces one `SoftwareRelease` per entry without being able to see what a step really did.
`qits-containers-service` is not declared, because nothing uploads it; the deployable ships as the
image. qits-githost's file is the same rule pointing the other way and is worth reading beside this
one: it publishes library jars, does not push them from its release pipeline, and therefore does not
declare them.

The jar step is skip-if-published, and **the two probes are AND-chained on purpose**. `deploy` runs
across one reactor and ships both modules together, so a version with one module missing has to go
again whole; an `||` there would skip on a half-published version and leave it half-published
forever. The probes read the module poms and not the root: the root can only be absent if the run
never reached either module, which the two probes already say.

**`-pl .,core,client` — the root rides along, and `-N` is the wrong tool.** Both module poms declare
this repository's root as their `<parent>`, so a consumer resolving either jar resolves that parent
pom first; deploying the modules alone publishes jars whose parent exists in no registry, and the
failure lands on the consumer's build. `-N` means "do not recurse into `<modules>`", so it would
produce the mirror-image failure — the parent pom with neither jar. `-pl` is already the selection
that is wanted.

## The userflow catalogue

Thirteen `@UserStory` methods across six `@QuarkusIntegrationTest` classes, all on **one**
`@TestProfile` and therefore **one** launched fast-jar. The run writes `service/target/userstories/`
— the proof as documentation, a network diagram beside the steps — and
`.config/qits/ci-event-userflows.yml` publishes it per commit as the docs bundle
`@userflows/qits-containers`.

    api/TokenValidationBootstrapIT      authentication   the packaged boot with the OIDC tenant ON
    stories/boot/HostBootstrapIT        startup          what the boot does to the host
    stories/lifecycle/WorkloadLifecycleIT   workloads    ensure, confirm, and the unpublished image
    stories/ownership/OwnershipBoundaryIT   ownership    two machine actors, and the host's own stores
    stories/reap/WorkloadReapIT         reaping          the addressed removal, and the boot reap
    stories/refusals/AccessRefusalIT    refusals         four doors, and what none of them reached

**`api/TokenValidationBootstrapIT` is still the one that boots the tenant.** It runs the **packaged**
fast-jar with the **machine-auth gate on** — `qits.auth.machine.required=true`, which is what
`quarkus.oidc.tenant-enabled` is spelled in terms of — against
`eu.wohlben.qits.servicemock.idp.MockIdp`, a recording stand-in for qits-platform-idp that serves a
real JWKS for a generated keypair and mints RS256 bearers signed by it. That combination is the gap
`MachineGuardTest` leaves: that test flips the same gate but inlines `quarkus.oidc.public-key` and
clears `auth-server-url`, so the shipped `auth-server-url` + `discovery-enabled=false` +
`jwks-path=jwks` trio — a real fetch over a real listener, at startup, before any caller arrives —
is exercised nowhere else. Its denied story carries the claim no sibling repo's copy can: after the
401s for an unknown key and a foreign audience, an **impeccable token that is another module's** is
refused 403 on this owner's rows and served 200 on its own.

### The docker stand-in, which is what makes a catalogue possible here

`stories/support/StoryDocker` writes an **executable** and the profile points
`qits.containers.container-runtime` at it. That is the honest shape of this seam and not a
convenience: `core/docker/ContainerProcess` **spawns the docker CLI** and reads its pipes, so a
stubbed HTTP endpoint would stand in for nothing. The script records every argv with the exit code
it answered, and keeps just enough state under `target/story-docker/state/` that the registry's own
state machine — the row before the run, the inspect that settles it, the idempotent delete — runs
for real against it.

So **the docker hop is drawn as evidence, not declared**. That is the difference this repository
could most easily have got wrong: a `Network.declare("socket", …)` would have documented the one
dependency this service exists for as a claim. The only declared edge in the whole catalogue is the
registry's postgres, which no tap on this side can see.

Four things about it are load-bearing:

- **The answers are docker's own words where the wording is read.** `No such object` is what
  `DockerContainersDriver.ABSENT_MARKERS` matches to tell "docker has no such container" from
  "docker did not answer"; `manifest unknown` is what `ContainersResource.IMAGE_MISSING_MARKERS`
  matches to turn a refused run into a 409. Getting either string wrong would make the stories pass
  against a daemon that behaves differently from every real one.
- **The recording has NO floor**, unlike every other file-backed tap in the fleet. The calls the
  boot makes — three shared volumes, one network inspect — are the whole subject of
  `HostBootstrapIT`, exactly as the startup JWKS fetch is the subject of the authentication story.
  So the source is registered at zero and the cursor attributes those lines to whichever story
  drains first, which the class order makes the story about them.
- **The class order is FQCN-alphabetical inside the profile group**, broken by
  `UserflowClassOrderer` (registered as junit's *secondary* orderer — see below). The story packages
  are named `boot`, `lifecycle`, `ownership`, `reap`, `refusals` so that alphabetical is *intended*;
  every story method also declares `@UserflowRunsAfter` naming the classes it assumes ran. Run one
  of the later classes on its own and its first story inherits the boot calls and fails its edge
  count — loudly, which is the right way for that assumption to break.
- **A label is a summary, never the argv.** `StoryDocker.summarize` reduces a call to `run
  qits-ct-qits-ci-step-story-alpha` and appends `-> 0`. Keeping the whole command line would put
  `--label qits.containers.row=<uuid>` in a hashed label (a `networkHash` that never settles) and a
  Go `--format` template's braces in a mermaid diagram.

### The observation ticker is off, and that is the catalogue's one real gap

The profile sets `qits.containers.observe-interval-seconds=0`. Zero is a **shipped** configuration
with its own documented behaviour, not a test-only switch — but it is set here because the
observation pass is a **timer**: a `docker inspect` it made mid-story would land in whichever story
drained next, and nothing in the recorded argv distinguishes it from the inspect an `ensure` caused,
so it could not even be excluded by content. What that costs is stated plainly: **`ContainerObserver`'s
own transitions, `IdleSweep`, `MaxAgeGc`, `VolumeReconcile` and `RowPrune` are the part of this
service no story reaches.** They have no HTTP surface, so a packaged IT cannot drive them; their
proofs are the `@QuarkusTest`s in `core` that call `observeOnce()` and the sweeps' own entry points
directly. Giving them a story would need a route that triggers a pass, and inventing one for a test
is worse than the gap.

### The rest of the mechanics

- **`Interactions` records notes only.** Service-to-service traffic is never narrated: the framework
  ships the inbound tap (`NetworkTaps.restAssured`) and the local `StoryNetworkFilter` every service
  used to hand-copy is **deleted**. `MockIdp`'s recording and `StoryDocker`'s are cumulative
  `NetworkCapture.source`s. Every story is **browserless** (an `Interactions` parameter, sometimes a
  `Network` one, and no `Flow`), so the framework's transitive Playwright never launches anything —
  the only shape a repository holding the clone-alone rule could take it in.
- **The shipped tap labels the PATH and drops the query.** So the boot reap's
  `?createdBefore=<instant>` — the one genuinely run-local value a story sends — never reaches a
  label, and no `labelNormalizer` is needed for it. The corollary is the trap: two routes differing
  only in their query are **one** edge.
- **The actor names an edge's initiator**, set before each call, which is how the ownership pair
  draws as two arrows from one named module.
- **The class orderer is installed the one way Quarkus permits** — the
  `junit.quarkus.orderer.secondary-orderer` line in `service`'s test properties. A local
  `junit-platform.properties` hard-fails surefire.
- **The story profile owns two databases of its own** (`containers_userflow_it` and its eventstream
  sibling), asked for through `PackagedUnderTarget.databaseUrl`, which is package-private for
  exactly that. The stories write `qits-ci` rows and `ContainersPackagedSurfaceIT` asserts that
  owner's listing is empty; sharing one store would make that IT pass or fail on which profile group
  ran first, which is not a fact about the packaged artifact.
- **Every override the profile sets is a RUNTIME key**, and the darkness is not inherited: a
  launched artifact runs under neither `%dev` nor `%test`, so `quarkus.otel.sdk.disabled` and
  `qits.eventstream.enabled=false` are set again there. Dark is still not absent — the outbox
  datasource opens and migrates, which is why the second `QITS_RESOURCE_*` triple is not optional.
- **The ITs are opted in by NAME, not by `skipITs`.** The root pom keeps `skipITs=true`, because
  failsafe has one run per module and flipping it would turn `ContainersRestartAdoptionIT` back on
  with it. Run them — and `.config/qits/ci-event-userflows.yml` runs them — as

      ./mvnw verify -DskipITs=false \
        -Dit.test=TokenValidationBootstrapIT,HostBootstrapIT,WorkloadLifecycleIT,OwnershipBoundaryIT,WorkloadReapIT,AccessRefusalIT

  That pipeline is **non-gating by design**: it is a separate file from `ci-post-receive.yml` so a
  red story does not cost the branch its image. It is the only one of the three pipelines with no
  image step and no docker at all, and the only one that needs no `-Dquarkus.quinoa=false` — this
  service is machine-facing and carries no client.

## `ContainersPackagedSurfaceIT` is RED, and it was red before the catalogue

Measured 2026-08-29 on the released main (`bb06fed`), with the working tree stashed, so it is this
repository's own state and not a story's doing:

    ContainersPackagedSurfaceIT.theOrchestrationRoutesAnswerUnderTheGatewaySegmentAndNowhereElse
    ContainersPackagedSurfaceIT.aRowRoundTripsAgainstTheShippedSchemaWithNoDockerOnTheHost
    Expected status code <200> but was <401>

It is not a flake and it is not fixable by a retry. That IT drives the routes **unauthenticated**,
and every route of this service carries `@RolesAllowed("qits:system")`. What makes an anonymous
request work in a `@QuarkusTest` is qits-auth-core's `%test` dev user, which is `LaunchMode`-guarded
— and a **launched artifact runs in NORMAL mode**, where it does not exist. So with the machine gate
off there is no identity at all and `@RolesAllowed` answers 401 before any resource method runs. The
IT has been unrunnable since WP4 put the guard on every route, and nothing noticed because
`skipITs=true` and neither pipeline names this class.

**It is deliberately left alone here.** Making it green means deciding what it should claim, and both
answers are somebody's design decision rather than a userflows change: either its expectations become
401/404 — which still separates "the route is there and guarded" from "there is no such route", but
gives up the row round-trip that is the reason the class exists — or its profile turns the machine
gate on and presents `MockIdp` bearers, which is what `TokenValidationBootstrapIT.PackagedWithMockIdp`
already does and would make the two profiles nearly the same thing. Pick one on purpose.
