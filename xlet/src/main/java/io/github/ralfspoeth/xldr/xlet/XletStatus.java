package io.github.ralfspoeth.xldr.xlet;

import io.github.ralfspoeth.xldr.ldr.Statistics;

import javax.management.JMException;
import javax.management.ObjectName;
import javax.management.StandardMBean;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;

/**
 * The {@link XletMXBean}, answered from the shared load counters and from the two
 * this front end keeps for itself.
 * <p>
 * It exists whether or not JMX would take it. The servlet counts through this
 * object on every request, so a management server that refuses the registration
 * must cost the deployment its statistics and nothing else - never a load, and
 * never a null check on the loading path.
 */
final class XletStatus implements XletMXBean {

    private static final System.Logger LOG = System.getLogger(XletStatus.class.getName());

    static final String DOMAIN = "io.github.ralfspoeth.xldr";

    private final SpecRegistry specs;
    private final Statistics statistics;
    private final int maxConcurrentLoads;
    private final long acquireTimeoutMillis;
    private final long maxBytes;

    /**
     * The two counters HTTP adds. They are here rather than in {@code Statistics}
     * because the file server has neither: it refuses nothing, a file in
     * {@code in/} having already been accepted by matching the delivery rule, and
     * it rejects nothing, blocking instead until a permit frees - a file does not
     * mind waiting and a caller does.
     */
    private final AtomicLong requestsRefused = new AtomicLong();
    private final AtomicLong loadsRejected = new AtomicLong();

    XletStatus(SpecRegistry specs, Statistics statistics,
               int maxConcurrentLoads, long acquireTimeoutMillis, long maxBytes) {
        this.specs = specs;
        this.statistics = statistics;
        this.maxConcurrentLoads = maxConcurrentLoads;
        this.acquireTimeoutMillis = acquireTimeoutMillis;
        this.maxBytes = maxBytes;
    }

    void requestRefused() {
        requestsRefused.incrementAndGet();
    }

    void loadRejected() {
        loadsRejected.incrementAndGet();
    }

    /**
     * The name this bean registers under, which has to carry the deployment.
     * <p>
     * The file server registers a fixed name, which is right for one process
     * running one server and wrong here: two deployments of the same WAR, or one
     * beside the standalone server, and the second registration would throw
     * {@code InstanceAlreadyExistsException} - so the first deployment to come up
     * would be the only one anybody could see. The context path and the servlet
     * name are what tell them apart, and both are quoted rather than trusted: a
     * context path contains a {@code /} and may contain worse, and
     * {@link ObjectName#quote} is the only thing that knows the whole list.
     */
    static ObjectName nameFor(String contextPath, String servletName) throws JMException {
        return new ObjectName(DOMAIN + ":type=Loader"
                + ",context=" + ObjectName.quote(contextPath)
                + ",name=" + ObjectName.quote(servletName));
    }

    /**
     * Registers this bean, returning what unregisters it again - or something
     * that does nothing, if it could not be registered.
     * <p>
     * <strong>The caller must close it in {@code destroy()}.</strong> The platform
     * {@code MBeanServer} outlives the web application, so a bean left registered
     * holds a strong reference to an object of a class loaded by the application's
     * class loader, and every redeploy then leaks a class loader and everything it
     * loaded. That is the classic slow death of a container that is redeployed all
     * day, and it is why this returns something to close rather than registering
     * and forgetting.
     */
    AutoCloseable register(String contextPath, String servletName) {
        try {
            var name = nameFor(contextPath, servletName);
            var server = ManagementFactory.getPlatformMBeanServer();
            server.registerMBean(new StandardMXBeanWrapper(this), name);
            LOG.log(DEBUG, () -> "registered " + name);
            return () -> {
                try {
                    server.unregisterMBean(name);
                    LOG.log(DEBUG, () -> "unregistered " + name);
                } catch (Exception e) {
                    LOG.log(DEBUG, () -> "could not unregister " + name + ": " + e);
                }
            };
        } catch (JMException | RuntimeException e) {
            LOG.log(WARNING, () -> "no JMX statistics for this deployment: " + e);
            return () -> {
            };
        }
    }

    /**
     * Names the interface to expose, and that it is an MXBean, rather than naming
     * this class to suit the lookup rule that would otherwise decide.
     */
    private static final class StandardMXBeanWrapper extends StandardMBean {
        StandardMXBeanWrapper(XletMXBean bean) {
            super(bean, XletMXBean.class, true);
        }
    }

    @Override
    public int getMaxConcurrentLoads() {
        return maxConcurrentLoads;
    }

    @Override
    public long getAcquireTimeoutMillis() {
        return acquireTimeoutMillis;
    }

    @Override
    public long getMaxBytes() {
        return maxBytes;
    }

    @Override
    public int getLoadsInProgress() {
        return statistics.loadsInProgress();
    }

    @Override
    public long getLoadsSucceeded() {
        return statistics.loadsSucceeded();
    }

    @Override
    public long getLoadsFailed() {
        return statistics.loadsFailed();
    }

    @Override
    public long getRecordsLoaded() {
        return statistics.recordsLoaded();
    }

    @Override
    public String getLastLoad() {
        return statistics.lastLoad();
    }

    @Override
    public String getLastFailure() {
        return statistics.lastFailure();
    }

    @Override
    public long getRequestsRefused() {
        return requestsRefused.get();
    }

    @Override
    public long getLoadsRejected() {
        return loadsRejected.get();
    }

    /**
     * Every spec the deployment carries, in the registry's order, so that a spec
     * which has never loaded appears as a row of zeroes rather than not at all.
     * Iterating the counters instead would show only what has been used, and
     * "this spec has loaded nothing" is exactly the row worth seeing.
     */
    @Override
    public Map<String, SpecStatus> getSpecs() {
        Map<String, SpecStatus> rows = new LinkedHashMap<>();
        for (var name : specs.names()) {
            rows.put(name, new SpecStatus(
                    name,
                    statistics.loadsSucceeded(name),
                    statistics.loadsFailed(name),
                    statistics.recordsLoaded(name),
                    statistics.lastLoad(name),
                    statistics.lastFailure(name)));
        }
        return rows;
    }
}
