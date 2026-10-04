package eu.wohlben.qits.containers.control;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.ContainersTimeouts;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * What the host's four stores hold, read once — the before and the after of a collection run.
 *
 * <p><b>It is a read and it decides nothing.</b> The orchestrator asks for it either side of a gc
 * run so a person can see what the run was worth; nothing in this service acts on the numbers, and
 * nothing may start to. A collection that removed things because a store looked full would be a
 * sweep with a threshold instead of a rule, which is the shape this repository exists to remove.
 *
 * <p><b>A usage that could not be read is a failure and never a zero.</b> {@code diskUsage} throws
 * when the daemon did not answer, and that throw is left alone here: an invented {@code 0 bytes}
 * would be read as an empty host by whatever draws it, and an empty host is a statement no failed
 * call is entitled to make.
 */
@ApplicationScoped
public class GcUsage {

  @Inject ContainersDriver driver;

  /** The four stores, as docker reports them now. */
  public ContainersDriver.DiskUsage read() {
    return driver.diskUsage(ContainersTimeouts.DISK_USAGE);
  }
}
