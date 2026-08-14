package io.github.ralfspoeth.xldr.server;

import io.github.ralfspoeth.filews.DirectoryWatchService;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

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
     * here, in {@code ensureRegistered} or in {@code deregister}.
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
                deregister(feedDir, "directory is gone");
            } else {
                watch(feedDir);
                var deliveryFile = feedDir.resolve(Delivery.FILE);
                if (Files.isRegularFile(deliveryFile)) {
                    ensureRegistered(feedDir, deliveryFile);
                } else {
                    deregister(feedDir, "no " + Delivery.FILE);
                }
            }
        } catch (IllegalStateException e) {
            deregister(feedDir, e.getMessage());
        } catch (RuntimeException e) {
            deregister(feedDir, "cannot read the feed's configuration: " + e);
        } finally {
            reconciliation.unlock();
        }
    }

    /**
     * Registers the feed, or brings what is registered up to date.
     * <p>
     * Both files are stamped and both are compared, so a change to either takes
     * effect: an edited {@code accepts} is no more structural than an edited
     * selector, and waiting for a restart to honour one of them and not the
     * other would be arbitrary. Re-reading both when either moved is simpler than
     * tracking which one did, and cannot leave one half stale.
     */
    private void ensureRegistered(Path feedDir, Path deliveryFile) {
        try {
            var deliveryModified = Files.getLastModifiedTime(deliveryFile);
            var specFile = MappingSpecs.find(feedDir).orElse(null);
            var specModified = specFile == null ? null : Files.getLastModifiedTime(specFile);

            var current = feeds.get(feedDir);
            if (unchanged(current, deliveryModified, specFile, specModified)) {
                ensureDirectoriesAndWatch(current);
                return;
            }

            var delivery = Delivery.read(deliveryFile);
            Feed feed = specFile == null || specModified == null
                    ? new Feed.Pending(feedDir, delivery, deliveryModified)
                    : new Feed.Active(feedDir, delivery, deliveryModified,
                    specFile, specModified, readSpec(specFile));
            ensureDirectoriesAndWatch(feed);
            feeds.put(feedDir, feed);
            announce(current, feed);
        } catch (IOException e) {
            deregister(feedDir, "cannot read the feed's configuration: " + e.getMessage());
        }
    }

    private static boolean unchanged(@Nullable Feed current, FileTime deliveryModified,
                                     @Nullable Path specFile, @Nullable FileTime specModified) {
        if (current == null || !current.deliveryModified().equals(deliveryModified)) {
            return false;
        }
        return switch (current) {
            // a spec has appeared beside a feed that had none
            case Feed.Pending _ -> specFile == null;
            case Feed.Active active -> active.specFile().equals(specFile)
                    && active.specModified().equals(specModified);
        };
    }

    /**
     * Says what just happened, once, on the transition rather than on every scan.
     * <p>
     * A feed waiting for its spec is worth a warning - it is the most likely way
     * for a feed not to come up, and the one that used to be silent - but
     * repeating it every scan interval for as long as the feed waits would train
     * whoever reads the log to ignore it.
     */
    private static void announce(@Nullable Feed previous, Feed feed) {
        switch (feed) {
            case Feed.Pending _ -> LOG.log(WARNING,
                    () -> "feed " + feed.name() + " has a " + Delivery.FILE + " but no mapping spec "
                            + MappingSpecs.SPEC_NAMES + "; what its producer delivers will wait in "
                            + feed.in().getFileName() + " until one appears");
            case Feed.Active _ -> LOG.log(INFO,
                    () -> (previous instanceof Feed.Active ? "feed reloaded: " : "feed activated: ")
                            + feed.name() + " (" + feed.delivery() + ")");
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
     * therefore never given up while the directory exists -
     * {@link #deregister(Path, String)} releases the inbox only.
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

    private void deregister(Path feedDir, String reason) {
        var removed = feeds.remove(feedDir);
        if (removed != null) {
            watchService.unregister(removed.in());
            LOG.log(INFO, () -> "feed deactivated: " + removed.name() + " - " + reason);
        } else {
            LOG.log(DEBUG, () -> "not a feed: " + feedDir + " - " + reason);
        }
    }

    /**
     * The feeds that can load - those whose spec this build could read.
     * <p>
     * A snapshot, unlike {@link #registered()}: the filter has to walk the map
     * anyway, so there is no view to hand back, and what the caller gets is one
     * consistent set rather than a sequence that may change under an iterator.
     * A registered feed still waiting for its spec is deliberately absent, which
     * is what keeps it away from the loader.
     */
    public Collection<Feed.Active> active() {
        return feeds.values()
                .stream()
                .filter(Feed.Active.class::isInstance)
                .map(Feed.Active.class::cast)
                .toList();
    }

    /**
     * The feed whose {@code in/} directory this is, if it can load.
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
    public Optional<Feed.Active> feedOfInbox(Path inbox) {
        // a path may have no parent, and ConcurrentHashMap.get(null) throws
        var feedDir = inbox.getParent();
        if (feedDir == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(feeds.get(feedDir))
                .filter(feed -> feed.in().equals(inbox))
                // a pending feed's inbox is watched all the same, so that its
                // directories exist for a producer to deliver into; there is
                // simply nothing to do with what arrives until a spec does
                .filter(Feed.Active.class::isInstance)
                .map(Feed.Active.class::cast);
    }

    /**
     * Every feed below a root, whether or not it can load yet - a directory is
     * here from the moment it holds a readable {@value Delivery#FILE}. A
     * directory without one, or with one that would not parse, is not.
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
    public Collection<Feed> registered() {
        return Collections.unmodifiableCollection(feeds.values());
    }
}
