package io.github.ralfspoeth.xldr.app;

import javax.management.JMException;
import javax.management.ObjectName;
import javax.management.StandardMBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;

/**
 * The {@link ServerMXBean}, answered from the counters and from the feed
 * directories themselves.
 * <p>
 * Registration is best effort: a JMX server that will not take the bean is a
 * reason to log and carry on, never a reason for the server not to load files.
 */
final class ServerStatus implements ServerMXBean {

    static final String OBJECT_NAME = "io.github.ralfspoeth.xldr:type=Server";

    private static final System.Logger LOG = System.getLogger(ServerStatus.class.getName());

    private final FeedRegistry registry;
    private final Statistics statistics;

    ServerStatus(FeedRegistry registry, Statistics statistics) {
        this.registry = registry;
        this.statistics = statistics;
    }

    /**
     * Registers the bean, returning what unregisters it again - or nothing at
     * all if it could not be registered.
     */
    static AutoCloseable register(FeedRegistry registry, Statistics statistics) {
        try {
            var name = new ObjectName(OBJECT_NAME);
            var server = ManagementFactory.getPlatformMBeanServer();
            server.registerMBean(
                    new StandardMXBeanWrapper(new ServerStatus(registry, statistics)), name);
            LOG.log(DEBUG, () -> "registered " + name);
            return () -> {
                try {
                    server.unregisterMBean(name);
                } catch (Exception e) {
                    LOG.log(DEBUG, () -> "could not unregister " + name + ": " + e);
                }
            };
        } catch (JMException | RuntimeException e) {
            LOG.log(WARNING, () -> "no JMX statistics: " + e);
            return () -> {
            };
        }
    }

    /**
     * Names the interface to expose, and that it is an MXBean. The convention
     * would be to call the implementation {@code ServerStatusMXBean}'s
     * implementation {@code ServerStatus}; saying it outright is clearer than
     * naming classes to suit a lookup rule.
     */
    private static final class StandardMXBeanWrapper extends StandardMBean {
        StandardMXBeanWrapper(ServerMXBean bean) {
            super(bean, ServerMXBean.class, true);
        }
    }

    @Override
    public int getActiveFeeds() {
        return registry.active().size();
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
    public int getFilesWaiting() {
        return registry.active().stream().mapToInt(feed -> count(feed.in())).sum();
    }

    @Override
    public int getFilesInHospital() {
        return registry.active().stream().mapToInt(feed -> countPatients(feed.hospital())).sum();
    }

    @Override
    public Map<String, FeedStatus> getFeeds() {
        Map<String, FeedStatus> feeds = new LinkedHashMap<>();
        for (var feed : registry.active()) {
            var name = feed.name();
            feeds.put(name, new FeedStatus(
                    name,
                    statistics.loadsSucceeded(name),
                    statistics.loadsFailed(name),
                    statistics.recordsLoaded(name),
                    statistics.lastLoad(name),
                    statistics.lastFailure(name),
                    count(feed.in()),
                    countPatients(feed.hospital())));
        }
        return feeds;
    }

    /**
     * The files in a directory, counted when asked rather than tracked: these
     * are read by a monitor now and then, not on the loading path.
     */
    private static int count(Path directory) {
        return count(directory, _ -> true);
    }

    /**
     * The sick files, which is what a monitor alerts on. A failure leaves two
     * files in the hospital - the input, and a {@code .log} beside it saying
     * what went wrong - and counting the explanation as a second patient would
     * report every failure twice.
     */
    private static int countPatients(Path hospital) {
        return count(hospital, file -> !file.getFileName().toString().endsWith(".log"));
    }

    private static int count(Path directory, Predicate<Path> wanted) {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (var files = Files.list(directory)) {
            return (int) files.filter(Files::isRegularFile).filter(wanted).count();
        } catch (IOException e) {
            LOG.log(DEBUG, () -> "cannot count " + directory + ": " + e);
            return -1;
        }
    }
}
