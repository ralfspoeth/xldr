package io.github.ralfspoeth.xldr.server;

import io.github.ralfspoeth.filews.DirectoryWatchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Whether a feed comes up, and what it takes to make it go away again.
 * <p>
 * The largest untested class in the server until now, and the one whose
 * mistakes are quietest: a feed that fails to activate does not throw, it just
 * never loads anything, and the file its producer delivered sits in
 * {@code in/} looking as though nothing has happened yet.
 * <p>
 * Everything here goes through {@code reconcile}, which is what the running
 * server does too - from three threads, at startup, on a watch event and on the
 * periodic scan. So these tests exercise the one path rather than a test-only
 * seam, and the idempotence they check is the property those three callers rely
 * on.
 */
class FeedRegistryTest {

    private DirectoryWatchService watchService;
    private FeedRegistry registry;

    @BeforeEach
    void setUp() throws IOException {
        watchService = Feeds.watchService();
        registry = new FeedRegistry(watchService);
    }

    @AfterEach
    void tearDown() throws Exception {
        watchService.close();
    }

    // ---- what makes a directory a feed ---------------------------------------

    /**
     * A directory below a root is not a feed until it says how its files arrive.
     * The tree may hold anything else - a scratch directory, someone's notes -
     * and none of it is watched for deliveries.
     */
    @Test
    void adirectoryWithoutAdeliveryIsNotAfeed(@TempDir Path root) throws IOException {
        registry.reconcile(Feeds.bare(root, "notes"));
        assertTrue(registry.registered().isEmpty());
    }

    /**
     * A delivery without a spec registers the feed and leaves it pending: its
     * directories are made and its inbox is watched, so a producer can deliver
     * into it, and there is simply nothing to load with until a spec appears.
     */
    @Test
    void adeliveryWithoutAspecIsRegisteredButNotActive(@TempDir Path root) throws IOException {
        registry.reconcile(Feeds.pending(root, "orders"));
        assertAll(
                () -> assertEquals(1, registry.registered().size()),
                () -> assertEquals(0, registry.active().size(), "nothing to load with yet"));
    }

    @Test
    void adeliveryAndAspecMakeAnActiveFeed(@TempDir Path root) throws IOException {
        registry.reconcile(Feeds.active(root, "orders"));
        assertAll(
                () -> assertEquals(1, registry.registered().size()),
                () -> assertEquals(1, registry.active().size()));
    }

    /**
     * The four working directories are made when the feed registers, so that a
     * producer delivering into {@code in/} never has to create it and an
     * operator can see where a file will go before one arrives.
     */
    @Test
    void makesTheWorkingDirectories(@TempDir Path root) throws IOException {
        var feed = Feeds.active(root, "orders");
        registry.reconcile(feed);
        assertAll(Feeds.SUBDIRECTORIES.stream()
                .map(name -> () -> assertTrue(Files.isDirectory(feed.resolve(name)), name)));
    }

    /**
     * A spec that will not parse leaves the feed out rather than half in. The
     * failure is contained: it is logged against that feed and no sibling and no
     * watcher hears about it.
     */
    @Test
    void anUnparseableSpecLeavesTheFeedOut(@TempDir Path root) throws IOException {
        var feed = Feeds.pending(root, "orders");
        Files.writeString(feed.resolve("spec.json"), "{ this is not json");
        registry.reconcile(feed);
        assertTrue(registry.registered().isEmpty());
    }

    /**
     * Two spec files are ambiguous rather than a reason to pick one. Nothing
     * about {@code spec.json} makes it more authoritative than {@code spec.xml},
     * and a feed loading through whichever the code happened to look at first
     * would be the worst of the three outcomes.
     */
    @Test
    void twoSpecFilesLeaveTheFeedOut(@TempDir Path root) throws IOException {
        var feed = Feeds.active(root, "orders");
        Files.writeString(feed.resolve("spec.xml"), "<mappingSpec/>");
        registry.reconcile(feed);
        assertTrue(registry.registered().isEmpty());
    }

    /**
     * An unreadable delivery is the same: a feed that cannot say what it accepts
     * is not one that should claim files.
     */
    @Test
    void anUnreadableDeliveryLeavesTheFeedOut(@TempDir Path root) throws IOException {
        var feed = Feeds.bare(root, "orders");
        Files.writeString(feed.resolve(Feeds.DELIVERY), "acccepts = glob:*.csv\n");
        registry.reconcile(feed);
        assertTrue(registry.registered().isEmpty());
    }

    // ---- going away again ----------------------------------------------------

    @Test
    void adirectoryThatIsGoneIsDeregistered(@TempDir Path root) throws IOException {
        var feed = Feeds.active(root, "orders");
        registry.reconcile(feed);
        assertEquals(1, registry.registered().size());

        deleteTree(feed);
        registry.reconcile(feed);
        assertTrue(registry.registered().isEmpty());
    }

    /**
     * Removing the delivery is how a feed is retired without deleting what is in
     * it: the directory and its files stay, and nothing claims from them again.
     */
    @Test
    void removingTheDeliveryDeregisters(@TempDir Path root) throws IOException {
        var feed = Feeds.active(root, "orders");
        registry.reconcile(feed);

        Files.delete(feed.resolve(Feeds.DELIVERY));
        registry.reconcile(feed);
        assertAll(
                () -> assertTrue(registry.registered().isEmpty()),
                () -> assertTrue(Files.isDirectory(feed.resolve("in")), "the tree is left alone"));
    }

    /**
     * Removing the spec takes a feed back to pending rather than out - it still
     * has a delivery, so what arrives is still its own and still waits.
     */
    @Test
    void removingTheSpecLeavesThefeedPending(@TempDir Path root) throws IOException {
        var feed = Feeds.active(root, "orders");
        registry.reconcile(feed);
        assertEquals(1, registry.active().size());

        Files.delete(feed.resolve("spec.json"));
        registry.reconcile(feed);
        assertAll(
                () -> assertEquals(1, registry.registered().size()),
                () -> assertEquals(0, registry.active().size()));
    }

    /**
     * And a spec appearing beside a pending feed activates it, which is the
     * transition the whole watch exists for.
     */
    @Test
    void aspecAppearingActivatesApendingFeed(@TempDir Path root) throws IOException {
        var feed = Feeds.pending(root, "orders");
        registry.reconcile(feed);
        assertEquals(0, registry.active().size());

        Files.writeString(feed.resolve("spec.json"), Feeds.SPEC);
        registry.reconcile(feed);
        assertEquals(1, registry.active().size());
    }

    // ---- idempotence, which the three callers rely on ------------------------

    /**
     * Three kinds of thread call reconcile and they overlap freely, so calling
     * it again has to be free. A missed watch event then costs latency until the
     * next scan rather than a feed that never comes up.
     */
    @Test
    void reconcilingTwiceChangesNothing(@TempDir Path root) throws IOException {
        var feed = Feeds.active(root, "orders");
        registry.reconcile(feed);
        registry.reconcile(feed);
        registry.reconcile(feed);
        assertAll(
                () -> assertEquals(1, registry.registered().size()),
                () -> assertEquals(1, registry.active().size()));
    }

    /**
     * Reconciling a whole root brings up every feed below it and ignores the
     * rest, which is what startup does.
     */
    @Test
    void reconcilesEveryChildOfAroot(@TempDir Path root) throws IOException {
        Feeds.active(root, "orders");
        Feeds.active(root, "invoices");
        Feeds.pending(root, "half-done");
        Feeds.bare(root, "notes");
        Files.writeString(root.resolve("README.txt"), "not a feed");

        registry.reconcileRoot(root);
        assertAll(
                () -> assertEquals(3, registry.registered().size()),
                () -> assertEquals(2, registry.active().size()));
    }

    /** a root that is not there is a warning, not a failure */
    @Test
    void amissingRootIsSurvivable(@TempDir Path root) {
        registry.reconcileRoot(root.resolve("absent"));
        assertTrue(registry.registered().isEmpty());
    }

    // ---- which feed an inbox belongs to --------------------------------------

    @Test
    void findsTheFeedOfAnInbox(@TempDir Path root) throws IOException {
        var feed = Feeds.active(root, "orders");
        registry.reconcile(feed);
        assertTrue(registry.feedOfInbox(feed.resolve("in")).isPresent());
    }

    /**
     * Only {@code in/}. Without the equality check any watched directory below a
     * feed would resolve to it, and a file appearing in {@code work/} - which is
     * where the loader itself puts one - would be taken for a fresh arrival and
     * loaded twice.
     */
    @Test
    void doesNotMistakeAnotherDirectoryForTheInbox(@TempDir Path root) throws IOException {
        var feed = Feeds.active(root, "orders");
        registry.reconcile(feed);
        assertAll(
                () -> assertFalse(registry.feedOfInbox(feed.resolve("work")).isPresent()),
                () -> assertFalse(registry.feedOfInbox(feed.resolve("archive")).isPresent()),
                () -> assertFalse(registry.feedOfInbox(feed).isPresent()));
    }

    /**
     * A pending feed's inbox is watched all the same, so its directories exist
     * for a producer to deliver into - but it answers nothing here, which is
     * what keeps what arrives away from the loader.
     */
    @Test
    void apendingFeedsInboxAnswersNothing(@TempDir Path root) throws IOException {
        var feed = Feeds.pending(root, "orders");
        registry.reconcile(feed);
        assertFalse(registry.feedOfInbox(feed.resolve("in")).isPresent());
    }

    /** a path with no parent cannot be an inbox, and must not throw asking */
    @Test
    void arootPathIsNotAnInbox() {
        assertFalse(registry.feedOfInbox(Path.of("/")).isPresent());
    }

    @Test
    void anUnknownInboxAnswersNothing(@TempDir Path root) throws IOException {
        registry.reconcile(Feeds.active(root, "orders"));
        assertFalse(registry.feedOfInbox(root.resolve("elsewhere").resolve("in")).isPresent());
    }

    // ---- what the readers hand back ------------------------------------------

    /**
     * {@code registered()} is a view onto the map itself, so it is unmodifiable:
     * removing from it would deactivate a feed without unregistering its watch,
     * leaving the registry and the watch service disagreeing with nothing in the
     * caller looking wrong.
     */
    @Test
    void registeredCannotBeModified(@TempDir Path root) throws IOException {
        registry.reconcile(Feeds.active(root, "orders"));
        var registered = registry.registered();
        assertThrows(UnsupportedOperationException.class, registered::clear);
    }

    /**
     * And it is live, which is what the periodic scan and the JMX gauges want:
     * they ask again each time rather than holding a snapshot.
     */
    @Test
    void registeredIsLive(@TempDir Path root) throws IOException {
        var registered = registry.registered();
        assertTrue(registered.isEmpty());

        registry.reconcile(Feeds.active(root, "orders"));
        assertEquals(1, registered.size(), "the same collection now shows the feed");
    }

    private static void deleteTree(Path dir) throws IOException {
        try (var paths = Files.walk(dir)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
