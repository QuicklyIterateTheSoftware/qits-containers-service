# qits-containers as a GraalVM-native container IMAGE — the same shape as qits-githost's,
# qits-platform-dns' and qits-gateway's docker/Dockerfile: a Mandrel builder stage compiles the
# binary, a minimal runtime stage carries only it.
#
# This file is not how the binary is built; it is how the binary is SHIPPED. `.sdkmanrc` names a
# GraalVM (25.0.2-graalce), so `sdk env && ./mvnw verify -Dnative` produces
# `service/target/qits-containers` locally with no container involved. What this adds is the image
# around it — and a way for a machine with no GraalVM (a CI runner, a laptop on a plain OpenJDK) to
# get a binary at all, since the builder stage brings its own native-image.
#
# Build from the repo root, on the network where the platform's Maven repository answers:
#
#     docker build --network host \
#       --build-arg QITS_MAVEN_REPOSITORY_URL="http://$QITS_REGISTRY/artifacts/maven/maven" \
#       -t qits/containers:latest -f docker/Dockerfile .
#
# BOTH FLAGS ARE REQUIRED and neither has a workable default. This reactor depends on
# `qits-db-core`, `qits-arch-rules`, `qits-eventstream` and `qits-auth-core`, jars published to the
# platform's own Maven repository and available from nowhere else — not Maven Central, and not the
# builder container's empty ~/.m2. A RUN step reaches the public internet and nothing else, so
# without `--network` there is no address that answers at all. See .config/qits/ci-post-receive.yml,
# which passes exactly this pair.
#
# There is no client to build first and no npm anywhere: this service is machine-facing and serves
# no page. That is the one way this file is shorter than qits-githost's.
#
# --- this image does NOT run with an empty environment, and that is not a packaging bug ------------
# `docker run --rm qits/containers:latest` dies before the HTTP listener, at Flyway, because this
# service ships BOTH its datasources as the platform's generic resource contract and nothing
# resolves either:
#
#   quarkus.datasource.containers.jdbc.url=${QITS_RESOURCE_DB_URL}            (the registry rows)
#   quarkus.datasource.eventstream.jdbc.url=${QITS_RESOURCE_EVENTSTREAM_URL}  (the outbox)
#
# THAT IS THE DESIGN AND A FALLBACK IS NOT A FIX, and here the stake is the invariant the whole
# repository exists for. The rows are the only record of which containers may exist. An orchestrator
# that came up healthy on a store it invented would see every running container as named by no row —
# which is precisely the state the adoption rule exists to keep it from acting on.
#
# ON THE PLATFORM NOBODY SETS THESE BY HAND. This repository's .config/qits/deployments.yml carries
# `resources: postgresql:db, postgresql:eventstream:qits_containers_eventstream`; qits-deployments
# creates a role and a database for each on the tier's postgres before the successor container
# starts and injects all six variables.
#
# --- READ THIS BEFORE DEPLOYING: the socket is not in this image ----------------------------------
# This service does its work by shelling out to the docker CLI, so the runtime stage below is
# deliberately NOT the usual "the binary and nothing else". ONE external program is part of this
# service's *runtime* and no config value substitutes for it — the CLI is installed below. But the
# CLI is only half of what a deployment supplies, and the other half is a decision rather than a
# default:
#
#   THE DOCKER SOCKET IS NOT IN THIS IMAGE. Run with
#   `-v /var/run/docker.sock:/var/run/docker.sock --group-add <the socket's gid>`, or this service
#   can start no container.
#
# That mount hands this container control of the host's docker daemon, which is root-equivalent, so
# it is left as an explicit deployment act rather than baked in. `--group-add` is the other half of
# the same act: this image runs as UID 1001 in group 0, and the socket is `root:docker` 0660, so
# without the daemon's group the CLI is present and every call is a permission denial.
#
# Without the socket the service still starts and still serves — a boot that refused would be one
# that could not be deployed to fix docker (README.md, "What a boot does, in order"). SharedResources
# logs a warning, the boot sweep logs a warning, and each request fails with a docker connection
# error instead of a place.
#
# --- stage `build`: native-compile ----------------------------------------------------------------
# The builder JDK MUST be 25: native-image cannot read bytecode newer than its own JDK, and this
# project targets release 25.
#
# `mirror.dev.localhost:8080/quay/…` is qits-platform-mirror's pull-through mirror of quay.io: the
# first build fetches the image from upstream and caches it, every later build is served from local
# disk. The tag is upstream's, unchanged — the mirror revalidates it, so `jdk-25` still moves when
# quay moves it.
FROM mirror.dev.localhost:8080/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
USER root
WORKDIR /src

# Fetch the Maven distribution first, keyed only on the wrapper files, so a source-only change does
# not re-download it.
COPY mvnw .
COPY .mvn/ .mvn/
RUN ./mvnw --version

# The whole reactor, not a module list. `-am` builds `service`'s dependencies from source —
# `qits-containers-core` and `qits-containers-client` are 1.0.0-SNAPSHOTs this repo publishes
# nowhere at build time, so a builder container with an empty ~/.m2 has nowhere else to get them.
# Copying module by module would mean editing this file every time one is added, and forgetting to
# would fail the build with an unresolved dependency rather than anything that names the real cause.
# `.dockerignore` is what keeps this cheap, and it also stops a stale target/ shadowing what this
# stage compiles.
COPY . .

# -Dnative flips quarkus.native.enabled (the `native` profile lives in service/pom.xml — only that
# module is an application). Cap native-image RAM so the build is deterministic on small machines.
#
# `-pl service -am` builds the app and its dependency closure rather than the whole reactor. It is
# not a module list: this repository publishes `core` and `client` as library jars for consumers, and
# a whole-reactor build would let a compile error in something no image contains block deploying this
# service. The image build should depend on exactly what the image contains.
#
# Tests are skipped because `./mvnw verify` on a clone is their gate (AGENTS.md) and re-running them
# here proves the same thing more slowly — and they spawn two postgres child processes, which this
# stage is not the right place to arrange. Note the native profile also flips skipITs to false, so
# stopping at `package` rather than `verify` is deliberate: it keeps ContainersPackagedSurfaceIT out
# of the image build, and the docker-backed adoption proof out of a stage that has no daemon.
#
# `-s .qits-maven-settings.xml` is what lets the `qits-maven` repository be an http:// URL at all:
# Maven blocks external http repositories by default, and an exact repository-id mirror is the one
# thing that outranks the blocker. The settings file permits that one id and nothing else.
ARG QITS_MAVEN_REPOSITORY_URL=http://registry.dev.localhost:8080/artifacts/maven/maven
ENV QITS_MAVEN_REPOSITORY_URL=$QITS_MAVEN_REPOSITORY_URL
#
# --- and where Maven Central is mirrored, when it is ----------------------------------------------
# The pipeline passes the platform's pull-through (qits-platform-mirror's central proxy, the same
# authority the FROM lines above pull through); EMPTY — the default — means direct Maven Central.
# `.qits-maven-settings.xml` activates its central-proxy profile only on a non-empty value, so a
# bootstrap or hand build that passes nothing resolves exactly as it always did.
ARG QITS_MAVEN_CENTRAL_URL=
ENV QITS_MAVEN_CENTRAL_URL=$QITS_MAVEN_CENTRAL_URL
#
# The same settings file also carries the CREDENTIAL, and the two secret mounts below are where it
# comes from: they put the CI run's commissioned client into THIS `RUN`'s environment and nowhere
# else — not a layer, not the image history, which is what a `--build-arg` could never promise on a
# pullable image. Absent secrets leave both variables unset and the four jars resolve anonymously —
# today's state, and a developer host's permanently, because Maven sends credentials only when a
# repository answers 401 and the registry does not yet.
RUN --mount=type=secret,id=qits-client-id,env=QITS_MAVEN_AUTH_USR \
    --mount=type=secret,id=qits-client-secret,env=QITS_MAVEN_AUTH_PSW \
    ./mvnw -B -ntp -s .qits-maven-settings.xml -pl service -am package -Dnative -DskipTests \
      -Dqits.maven.repository.url="$QITS_MAVEN_REPOSITORY_URL" \
      -Dquarkus.native.native-image-xmx=4g \
      org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom \
      -DoutputFormat=json -DoutputName=sbom -DschemaVersion=1.6 \
      -Dcyclonedx.skipNotDeployed=false
# cyclonedx.skipNotDeployed=false is LOAD-BEARING, measured on qits-artifacts' first SBOM release
# (2026.902.45658): the service module skips maven-deploy — it ships as this image, not as a jar —
# and the plugin SKIPS deploy-skipping modules by default, with a green build and one INFO line. The
# sbom stage's COPY then fails on a file nothing wrote. The property spelling matters too: the bare
# -DskipNotDeployed=false is silently ignored; only the cyclonedx.-prefixed one reaches the plugin.

# --- sbom -----------------------------------------------------------------------------------------
# Not part of the runtime image: a `FROM scratch` holding only the service module's CycloneDX
# document, so the release step can lift it out with `--target sbom --output type=local` using the
# SAME args and secrets as the image build — every layer above is then a cache hit and the export
# costs seconds. `makeBom` rides on the mvnw invocation above rather than a second one, for the same
# cache reason — one RUN, one layer, one truth.
FROM scratch AS sbom
COPY --from=build /src/service/target/sbom.json /sbom.json

# --- runtime --------------------------------------------------------------------------------------
# `mirror.dev.localhost:8080/redhat/…` is the same mirror, pointed at registry.access.redhat.com.
FROM mirror.dev.localhost:8080/redhat/ubi9/ubi-minimal:9.6
WORKDIR /work

# The docker CLI comes from Docker's own repository because no UBI repository carries a
# docker-compatible client at all; the repo definition is removed again afterwards so the shipped
# image does not carry a third-party package source it has no reason to consult. The identical block
# is in qits-ci's and qits-deployments' Dockerfiles — the two services that shell docker today — and
# the three should keep matching.
#
# `docker-ce-cli` plus `docker-buildx-plugin`, and nothing else: no engine, no compose. This service
# runs containers through a socket a deployment mounts; it builds no image and needs no daemon of its
# own. The buildx plugin is here for one reason: on a 29-series CLI `docker builder prune` and
# `docker buildx du` are the plugin, and the build-cache collection (`/gc/build-cache`) shells both.
# Without it the host prune answers "unknown command: docker buildx" (measured live 2026-08-21).
#
# No git and no ENV HOME. Nothing here clones and nothing reads user.home — checked rather than
# assumed, and a HOME would read like the user.home workaround qits-ci's header refuses.
RUN printf '[docker-ce-stable]\nname=Docker CE Stable\nbaseurl=https://download.docker.com/linux/centos/9/$basearch/stable\nenabled=1\ngpgcheck=1\ngpgkey=https://download.docker.com/linux/centos/gpg\n' \
      > /etc/yum.repos.d/docker-ce.repo \
 && microdnf -y --setopt=install_weak_deps=0 --nodocs install docker-ce-cli docker-buildx-plugin \
 && microdnf -y clean all \
 && rm -f /etc/yum.repos.d/docker-ce.repo

# curl, so the container answers a docker healthcheck — the deployer injects one at `docker run` and
# polls it through `docker inspect`, and the probe runs INSIDE the container.
# UBI minimal already supplies curl-minimal; assert it is present without installing the conflicting
# full curl package. (qits-platform-idp's trick; installing `curl` here fails on the conflict.)
RUN curl --version >/dev/null

# `quarkus.package.output-name` names the binary `qits-containers` (no -runner suffix), so the
# process is identifiable in `ps`/`docker top` — which matters more here than elsewhere, because this
# service's own work is spawning `docker` children and its process tree gets read while debugging a
# place. That name is spelled a third time in service/pom.xml as failsafe's `native.image.path`; all
# three move together.
COPY --from=build --chown=1001:root /src/service/target/qits-containers /work/qits-containers
RUN chmod +x /work/qits-containers

# NO /data AND NOTHING TO OWN. Every durable fact this service keeps is a row in the registry
# database, and the data plane's sockets die with the process at both ends by design (README.md,
# "What a restart needs from it: nothing"). This image writes nothing outside its own process.

EXPOSE 8080
USER 1001

# Quarkus binds 127.0.0.1 outside dev mode, which inside a container means nothing can reach it —
# not the gateway, not a peer on qits-net.
ENTRYPOINT ["/work/qits-containers", "-Dquarkus.http.host=0.0.0.0"]
