package io.github.ralfspoeth.xldr.server;

import io.github.ralfspoeth.filews.DirectoryWatchService;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.PatternSyntaxException;

import static io.github.ralfspoeth.xldr.spec.io.MappingSpecReader.readSpec;
import static java.lang.System.Logger.Level.*;

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
class FeedRegistry {

    private static final System.Logger LOG = System.getLogger(FeedRegistry.class.getName());

    private final DirectoryWatchService watchService;
    private final Map<Path, Feed> feeds = new ConcurrentHashMap<>();

    /**
     * Serialises reconciliation. A lock of its own rather than
     * {@code synchronized}: the monitor of a public object is part of its public
     * surface, and a method declared {@code synchronized} publishes it - a
     * caller may hold it, wait on it, or deadlock against it, and nothing in the
     * signature says whether that is expected. Nothing outside this class can
     * reach this one, so the question does not arise.
     */
    private final Lock reconciliation = new ReentrantLock();

    public FeedRegistry(DirectoryWatchService watchService) {
        this.watchService = watchService;
    }

    /**
     * Brings the state of {@code feedDir} up to date.
     * <p>
     * Serialised, and deliberately across the whole registry rather than per
     * directory:
     * reconciling is a handful of stat calls plus the occasional parse, so
     * serialising every feed against every other costs nothing worth a finer
     * lock. It is needed. Three kinds of thread call this - the one that starts
     * the server, the scanner, and a virtual thread per watch event, of which
     * there may be many at once - and every mutation of {@link #feeds} happens
     * here, in {@code ensureActive} or in {@code deactivate}.
     * <p>
     * The readers - {@link #feedOfInbox} and {@link #active()} - deliberately do
     * not take the lock. They read a {@link ConcurrentHashMap}, so they see a
     * consistent entry or none, and a watch event is never held up behind a
     * spec being parsed.
     */
    public void reconcile(Path feedDir) {
        reconciliation.lock();
        try {
            if (!Files.isDirectory(feedDir)) {
                deactivate(feedDir, "directory is gone");
            } else {
                watch(feedDir);
                MappingSpecs.find(feedDir).ifPresentOrElse(
                        specFile -> ensureActive(feedDir, specFile),
                        () -> deactivate(feedDir, "no mapping spec")
                );
            }
        } catch (IllegalStateException e) {
            deactivate(feedDir, e.getMessage());
        } catch (RuntimeException e) {
            deactivate(feedDir, "cannot read mapping spec: " + e);
        } finally {
            reconciliation.unlock();
        }
    }

    private void ensureActive(Path feedDir, Path specFile) {
        try {
            var modified = Files.getLastModifiedTime(specFile);
            var current = feeds.get(feedDir);
            if (current != null
                    && current.specFile().equals(specFile)
                    && current.specModified().equals(modified)) {
                ensureDirectoriesAndWatch(current);
            } else {
                var mappingSpec = readSpec(specFile);
                var sentinelSpec = mappingSpec.inputSpec().sentinel();
                var acceptsSpec = mappingSpec.inputSpec().accepts();
                if ((sentinelSpec == null) == (acceptsSpec == null)) {
                    throw new IllegalStateException("input must declare exactly one of 'sentinel' or 'accepts', found "
                            + (sentinelSpec == null ? "neither" : "both"));
                }
                var sentinel = sentinelSpec == null ? null : Sentinel.parse(sentinelSpec);
                var acceptMatcher = acceptMatcher(acceptsSpec);
                var feed = new Feed(feedDir, specFile, modified, mappingSpec, sentinel, acceptMatcher);
                ensureDirectoriesAndWatch(feed);
                feeds.put(feedDir, feed);
                LOG.log(INFO,
                        () -> (current == null ? "feed activated: " : "feed reloaded: ")
                                + feed.name() + " (" + specFile.getFileName() + ")");
            }
        } catch (IOException e) {
            deactivate(feedDir, "cannot read mapping spec: " + e.getMessage());
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

    /**
     * Watches the feed directory itself, so that a spec appearing, changing or
     * being removed is noticed at once rather than at the next scan.
     * <p>
     * Called for every directory below a root, whether it holds a spec or not: a
     * directory without one is a feed waiting to be configured, and that is
     * precisely the moment the watch has to already be in place. The watch is
     * therefore never given up while the directory exists - {@link #deactivate}
     * releases the inbox only.
     * <p>
     * A {@link java.nio.file.WatchService} reports the entries of a watched
     * directory, so this covers {@code spec.json} and {@code spec.xml} without
     * naming them. It cannot cover them any other way: a file cannot be watched,
     * only the directory holding it.
     * <p>
     * Failure is not fatal. The periodic reconciliation remains the guarantee,
     * and a directory that could not be registered costs latency, not
     * correctness.
     */
    private void watch(Path feedDir) {
        try {
            // idempotent: the platform returns the existing key for a directory
            // already watched, so this may run on every scan
            watchService.register(feedDir);
        } catch (IOException e) {
            LOG.log(WARNING, () -> "cannot watch feed directory " + feedDir
                    + "; changes there will only be seen by the periodic scan: " + e);
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
            watchService.unregister(removed.in());
            LOG.log(INFO, () -> "feed deactivated: " + removed.name() + " - " + reason);
        } else {
            LOG.log(DEBUG, () -> "not a feed: " + feedDir + " - " + reason);
        }
    }

    /**
     * The feed whose {@code in/} directory this is, if it is active.
     * <p>
     * Derived rather than looked up in a second map. An inbox is
     * {@code <feed>/in} and {@link #feeds} is keyed by {@code <feed>}, so the
     * parent is the key - and one map cannot fall out of step with another that
     * is not there. That mattered: the two maps were filled one after the other,
     * so a watch thread asking between the two statements found the feed active
     * and its inbox unknown, and dropped an arriving file until the next scan.
     * <p>
     * The equality check is what keeps this exact. Without it any watched
     * directory below a feed would resolve to that feed, and a file appearing in
     * {@code work/} would be taken for an arrival. Only {@code in/} is watched
     * today, so it could not happen - but that is a property of what
     * {@link #ensureDirectoriesAndWatch} registers, not of this method, and
     * relying on it from here would be the sort of implicit invariant that comes
     * apart when the other end changes.
     */
    public Optional<Feed> feedOfInbox(Path inbox) {
        // a path may have no parent, and ConcurrentHashMap.get(null) throws
        var feedDir = inbox.getParent();
        if (feedDir == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(feeds.get(feedDir))
                .filter(feed -> feed.in().equals(inbox));
    }

    /**
     * The feeds that are active - those below a root with a spec this build
     * could read. A directory without one, or with one that would not parse, is
     * not here.
     * <p>
     * A live, unmodifiable view rather than a copy. Live, because the callers
     * are the periodic scan and the JMX attributes, which want the feeds as they
     * are each time they ask, and because iterating a {@link ConcurrentHashMap}
     * is weakly consistent - it never throws while reconciliation is mutating,
     * it just may or may not show a feed activated part-way through. Unmodifiable,
     * because {@code feeds.values()} is a view onto the map itself: removing
     * from it would deactivate a feed without unregistering its watch, leaving
     * the registry and the watch service disagreeing, and nothing in the caller
     * would look wrong.
     * <p>
     * The consequence of liveness is that two questions asked in a row may get
     * answers about different sets of feeds. For a scan and for a monitor's
     * gauges that is what is wanted; a caller needing one consistent set should
     * copy what it gets.
     */
    public Collection<Feed> active() {
        return Collections.unmodifiableCollection(feeds.values());
    }
}
