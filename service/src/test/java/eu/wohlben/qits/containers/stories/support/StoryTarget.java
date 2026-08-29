package eu.wohlben.qits.containers.stories.support;

import eu.wohlben.qits.userflows.Labels;

/**
 * The one launched process, addressed the way every one of its surfaces is addressed — and named the
 * way a diagram names it.
 *
 * <p>Everything qits-containers serves is a machine's. {@code quarkus.rest.path=/containers/api} is
 * the JSON API and {@code quarkus.http.non-application-root-path=/containers/q} is what Quarkus
 * itself serves, so the framework's shipped RestAssured tap — which skips any path carrying a {@code
 * /q/} <i>segment</i> rather than a leading one — is exactly right here and no story class overrides
 * the predicate. That is worth re-reading when this class is copied: a service whose probe root is
 * {@code /q} rather than {@code /<segment>/q} would need a different check.
 *
 * <p>The <b>port is random</b> — failsafe launches the artifact with {@code
 * quarkus.http.test-port=0} — so nothing here is a constant except the paths, and RestAssured is
 * handed the port by the Quarkus integration-test extension.
 *
 * <p><b>Every owner, workload and ref a story uses is a stable literal</b>, never a run stamp. Each
 * is a whole path segment and {@link Labels} rewrites only segments it can tell were generated (a
 * uuid, a long hex run, a bare number); {@code story-alpha} is none of those and would survive into
 * a label exactly as written, so a stamped one would move every {@code networkHash} on every run.
 * The suite can afford literals because each story class owns its own names and both the embedded
 * postgres and the docker stand-in's state are new per run.
 *
 * <p><b>A query string never reaches a label from the shipped tap.</b> {@code
 * NetworkTaps.restAssured} labels {@code METHOD <scrubbed PATH> -> <status>} and drops the query
 * entirely, which is worth knowing before writing a {@code labelNormalizer} for one: the boot reap's
 * {@code ?createdBefore=<instant>} is the one genuinely run-local value a story of this catalogue
 * sends, and it is invisible to the diagram for that reason rather than because anything here
 * templated it. The corollary is the trap: two routes differing only in their query are ONE edge,
 * so a story that wanted them apart has to address different paths.
 */
public final class StoryTarget {

  /** How every diagram in this catalogue names the service under test, on both sides of an edge. */
  public static final String SERVICE = "qits-containers";

  /** {@code /containers/api} — {@code quarkus.rest.path}. A resource's {@code @Path} is relative. */
  public static final String API_PATH = "/containers/api";

  /** The orchestration surface. A place under it is {@code {owner}/{workload}/{ref}}. */
  public static final String CONTAINERS_PATH = API_PATH + "/containers";

  /** Volumes an owner asks for by name — the store that outlives every container mounting it. */
  public static final String VOLUMES_PATH = API_PATH + "/volumes";

  /** The host's own stores. The one family here that is addressed to no owner. */
  public static final String GC_PATH = API_PATH + "/gc";

  /** The network every workload in this catalogue is addressed on — the shipped default. */
  public static final String NETWORK = "qits-net";

  private StoryTarget() {}

  /** One place: {@code {owner}/{workload}/{ref}}. */
  public static String placePath(String owner, String workload, String ref) {
    return CONTAINERS_PATH + "/" + owner + "/" + workload + "/" + ref;
  }

  /** A place's log tail. */
  public static String logsPath(String owner, String workload, String ref) {
    return placePath(owner, workload, ref) + "/logs";
  }

  /** Every live place of one owner. */
  public static String ownerPath(String owner) {
    return CONTAINERS_PATH + "/" + owner;
  }

  /** Every live place of one of an owner's workloads — and the collection a boot reap addresses. */
  public static String workloadPath(String owner, String workload) {
    return CONTAINERS_PATH + "/" + owner + "/" + workload;
  }

  /** One volume an owner claims by name. */
  public static String volumePath(String owner, String name) {
    return VOLUMES_PATH + "/" + owner + "/" + name;
  }
}
