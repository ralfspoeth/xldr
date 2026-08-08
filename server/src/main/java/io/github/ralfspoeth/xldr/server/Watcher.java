package io.github.ralfspoeth.xldr.server;

import io.github.ralfspoeth.filews.DirectoryWatchService;
import io.github.ralfspoeth.filews.PathEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;

/**
 * The server: watches the configured roots and loads whatever turns up.
 * <p>
 * Three levels are watched. Each configured root, so that a new feed directory
 * is noticed. Each feed directory, so that a spec appearing, changing or being
 * removed switches the feed on, reloads it or switches it off. And the
 * {@code in/} of every active feed, so that arriving files are loaded.
 * <p>
 * A feed lives exactly one level below a root, which is what makes the
 * auto-registration predicate self-limiting: the {@code work/}, {@code archive/}
 * and {@code hospital/} directories have a feed as their parent, not a root, so
 * they are never watched and the archive tree cannot accumulate watches.
 * <p>
 * Watch events only reduce latency. The guarantee is the periodic
 * reconciliation, which re-derives the whole state from the file system.
 */
public class Watcher implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(Watcher.class.getName());

    private final Set<Path> roots;
    private final DirectoryWatchService watchService;
    private final FeedRegistry registry;
    private final FileProcessor processor;
    private final ScheduledExecutorService scanner;
    private final long scanIntervalSeconds;
    private final Statistics statistics;
    private Thread watchThread;
    /**
     * unregisters the JMX bean; does nothing if it could not be registered
     */
    private AutoCloseable statusBean = () -> {
    };

    public Watcher(Config config, ConnectionSource connectionSource) throws IOException {
        this.roots = Set.copyOf(config.roots());
        this.scanIntervalSeconds = config.scanIntervalSeconds();
        validate(roots);
        this.watchService = new DirectoryWatchService(
                this::onEvent, roots, dir -> roots.contains(dir.getParent()));
        this.registry = new FeedRegistry(watchService);
        this.statistics = new Statistics();
        this.processor = new FileProcessor(connectionSource, config.maxConcurrentLoads(), statistics);
        this.scanner = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    }

    private static void validate(Set<Path> roots) {
        for (var root : roots) {
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException("configured root is not a directory: " + root);
            }
            for (var other : roots) {
                if (!root.equals(other) && root.startsWith(other)) {
                    throw new IllegalArgumentException("root " + root + " is nested inside root " + other);
                }
            }
        }
    }

    public void start() {
        statusBean = ServerStatus.register(registry, statistics);
        watchThread = Thread.ofVirtual().start(watchService);
        reconcileAll();
        registry.active().forEach(processor::recoverWork);
        scanner.scheduleWithFixedDelay(
                this::reconcileAllQuietly, scanIntervalSeconds, scanIntervalSeconds, TimeUnit.SECONDS);
        LOG.log(INFO, () -> "watching " + roots + ", reconciling every " + scanIntervalSeconds + "s");
    }

    private void reconcileAllQuietly() {
        try {
            reconcileAll();
        } catch (RuntimeException e) {
            LOG.log(WARNING, () -> "reconciliation failed: " + e);
        }
    }

    private void reconcileAll() {
        roots.forEach(registry::reconcileRoot);
        registry.active().forEach(processor::scanInbox);
    }

    /**
     * Routes an event by the level of the directory it occurred in.
     */
    private void onEvent(PathEvent event) {
        try {
            var dir = event.dir();
            if (roots.contains(dir)) {
                if (Files.isDirectory(event.path())) {
                    registry.reconcile(event.path());
                }
            } else if (roots.contains(dir.getParent())) {
                registry.reconcile(dir);
            } else {
                onInboxEvent(event);
            }
        } catch (RuntimeException e) {
            LOG.log(WARNING, () -> "error handling " + event.path() + ": " + e);
        }
    }

    private void onInboxEvent(PathEvent event) {
        if (!ENTRY_CREATE.equals(event.event().kind())) {
            return;
        }
        registry.feedOfInbox(event.dir())
                .ifPresent(feed -> processor.onArrival(feed, event.path()));
    }

    @Override
    public void close() throws IOException {
        try {
            statusBean.close();
        } catch (Exception e) {
            LOG.log(WARNING, () -> "could not unregister the JMX bean: " + e);
        }
        scanner.shutdownNow();
        watchService.close();
        if (watchThread != null) {
            watchThread.interrupt();
            try {
                watchThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
