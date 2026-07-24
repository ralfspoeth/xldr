package io.github.ralfspoeth.xldr.app;

import io.github.ralfspoeth.filews.DirectoryWatchService;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.PatternSyntaxException;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;

/**
 * Knows which feeds are active and keeps that knowledge in step with the file
 * system.
 * <p>
 * Everything goes through {@link #reconcile(Path)}, which is idempotent and is
 * called from three places: at startup for every existing directory below a
 * root, from a watch event, and from the periodic scan. Because all three run
 * the same code, a missed event or an event overflow costs latency rather than
 * a feed that never comes up.
 * <p>
 * A failure is contained in the feed that caused it: an unreadable directory or
 * an unparseable spec deactivates that one feed and is logged, it never
 * propagates to a sibling or to the watcher.
 */
public class FeedRegistry {

    private static final System.Logger LOG = System.getLogger(FeedRegistry.class.getName());

    private final DirectoryWatchService watchService;
    private final Map<Path, Feed> feeds = new ConcurrentHashMap<>();
    /** in/ directory to feed, so an event in in/ can be attributed cheaply */
    private final Map<Path, Feed> byInbox = new ConcurrentHashMap<>();

    public FeedRegistry(DirectoryWatchService watchService) {
        this.watchService = watchService;
    }

    /**
     * Brings the state of {@code feedDir} up to date.
     * <p>
     * Synchronized rather than finely locked: reconciling is a handful of stat
     * calls plus the occasional parse, and it must not interleave with itself
     * for the same directory.
     */
    public synchronized void reconcile(Path feedDir) {
        try {
            if (!Files.isDirectory(feedDir)) {
                deactivate(feedDir, "directory is gone");
                return;
            }
            var specFile = MappingSpecs.find(feedDir).orElse(null);
            if (specFile == null) {
                deactivate(feedDir, "no mapping spec");
                return;
            }
            var modified = Files.getLastModifiedTime(specFile);
            var current = feeds.get(feedDir);
            if (current != null
                    && current.specFile().equals(specFile)
                    && current.specModified().equals(modified)) {
                ensureDirectoriesAndWatch(current);
                return;
            }

            var mappingSpec = MappingSpecs.read(specFile);
            var sentinelSpec = mappingSpec.inputSpec().sentinel();
            var sentinel = sentinelSpec == null ? null : Sentinel.parse(sentinelSpec);
            var acceptMatcher = acceptMatcher(mappingSpec.inputSpec().accepts());
            var feed = new Feed(feedDir, specFile, modified, mappingSpec,
                    adapterProperties(feedDir), sentinel, acceptMatcher);
            ensureDirectoriesAndWatch(feed);
            feeds.put(feedDir, feed);
            byInbox.put(feed.in(), feed);
            LOG.log(INFO, () -> (current == null ? "feed activated: " : "feed reloaded: ")
                    + feed.name() + " (" + specFile.getFileName() + ")");
        } catch (IllegalStateException e) {
            deactivate(feedDir, e.getMessage());
        } catch (IOException | RuntimeException e) {
            deactivate(feedDir, "cannot read mapping spec: " + e);
        }
    }

    /**
     * Builds the accept matcher for a feed. The pattern uses the same
     * {@code glob:} / {@code regex:} prefixes as the sentinel.
     *
     * @throws IllegalArgumentException if the pattern lacks a known prefix or
     *                                  does not compile - caught by reconcile,
     *                                  which then leaves the feed inactive
     */
    private static PathMatcher acceptMatcher(String pattern) {
        if (pattern == null) {
            return null;
        }
        if (!pattern.startsWith("glob:") && !pattern.startsWith("regex:")) {
            throw new IllegalArgumentException(
                    "accepts must start with 'glob:' or 'regex:', was: " + pattern);
        }
        try {
            return FileSystems.getDefault().getPathMatcher(pattern);
        } catch (PatternSyntaxException | UnsupportedOperationException e) {
            throw new IllegalArgumentException("invalid accepts pattern: " + pattern, e);
        }
    }

    /**
     * Reconciles every directory directly below {@code root}.
     */
    public void reconcileRoot(Path root) {
        try (var children = Files.list(root)) {
            children.filter(Files::isDirectory).forEach(this::reconcile);
        } catch (IOException e) {
            LOG.log(WARNING, () -> "cannot list root " + root + ": " + e);
        }
    }

    private void ensureDirectoriesAndWatch(Feed feed) throws IOException {
        for (var name : Feed.SUBDIRECTORIES) {
            Files.createDirectories(feed.directory().resolve(name));
        }
        watchService.register(feed.in());
    }

    private void deactivate(Path feedDir, String reason) {
        var removed = feeds.remove(feedDir);
        if (removed != null) {
            byInbox.remove(removed.in());
            watchService.unregister(removed.in());
            LOG.log(INFO, () -> "feed deactivated: " + removed.name() + " - " + reason);
        } else {
            LOG.log(DEBUG, () -> "not a feed: " + feedDir + " - " + reason);
        }
    }

    private static Properties adapterProperties(Path feedDir) throws IOException {
        var props = new Properties();
        var file = feedDir.resolve(Feed.ADAPTER_PROPERTIES);
        if (Files.isRegularFile(file)) {
            try (var in = Files.newBufferedReader(file)) {
                props.load(in);
            }
        }
        return props;
    }

    /**
     * The feed whose {@code in/} directory this is, if it is active.
     */
    public Optional<Feed> feedOfInbox(Path inbox) {
        return Optional.ofNullable(byInbox.get(inbox));
    }

    public Collection<Feed> active() {
        return feeds.values();
    }
}
