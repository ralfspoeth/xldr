package io.github.ralfspoeth.xldr.server.test;

import io.github.ralfspoeth.filews.DirectoryWatchService;
import io.github.ralfspoeth.xldr.ldr.Statistics;
import io.github.ralfspoeth.xldr.server.FeedRegistry;
import io.github.ralfspoeth.xldr.server.FeedState;
import io.github.ralfspoeth.xldr.server.ServerStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a monitor is told, checked against what is actually on disk.
 * <p>
 * A gauge is believed for as long as nobody checks it, which makes this the
 * kind of code that can be wrong for a long time quietly. The counts here are
 * read from the directories when asked rather than tracked as files move, so
 * the only thing that can make them right is that the counting agrees with the
 * layout - and the only way to know it does is to put files there and ask.
 */
class ServerStatusTest {

    private DirectoryWatchService watchService;
    private FeedRegistry registry;
    private Statistics statistics;
    private ServerStatus status;

    @BeforeEach
    void setUp() throws IOException {
        watchService = Feeds.watchService();
        registry = new FeedRegistry(watchService);
        statistics = new Statistics();
        status = new ServerStatus(registry, statistics);
    }

    @AfterEach
    void tearDown() throws Exception {
        watchService.close();
    }

    // ---- the feed gauges -----------------------------------------------------

    /**
     * Active, not registered: this gauge answers how many feeds can load, which
     * is the number an operator compares against how many they deployed.
     */
    @Test
    void countsTheFeedsThatCanLoad(@TempDir Path root) throws IOException {
        Feeds.active(root, "orders");
        Feeds.active(root, "invoices");
        Feeds.pending(root, "half-done");
        registry.reconcileRoot(root);

        assertEquals(2, status.getActiveFeeds());
    }

    /**
     * The map is keyed by feed name and holds every registered feed, pending
     * ones included - a half-finished deployment being the thing worth finding.
     */
    @Test
    void reportsEveryRegisteredFeedWithItsState(@TempDir Path root) throws IOException {
        Feeds.active(root, "orders");
        Feeds.pending(root, "half-done");
        registry.reconcileRoot(root);

        var feeds = status.getFeeds();
        assertAll(
                () -> assertEquals(Set.of("orders", "half-done"), feeds.keySet()),
                () -> assertEquals(FeedState.ACTIVE, feeds.get("orders").state()),
                () -> assertEquals(FeedState.PENDING, feeds.get("half-done").state()),
                () -> assertEquals("orders", feeds.get("orders").name()));
    }

    // ---- the file gauges, which is where the counting can go wrong -----------

    @Test
    void countsTheFilesWaiting(@TempDir Path root) throws IOException {
        var orders = Feeds.active(root, "orders");
        var invoices = Feeds.active(root, "invoices");
        registry.reconcileRoot(root);

        Feeds.file(orders.resolve("in"), "a.csv");
        Feeds.file(orders.resolve("in"), "b.csv");
        Feeds.file(invoices.resolve("in"), "c.csv");

        assertAll(
                () -> assertEquals(3, status.getFilesWaiting(), "across every feed"),
                () -> assertEquals(2, status.getFeeds().get("orders").filesWaiting()));
    }

    /**
     * Registered, not active: a file waiting in the inbox of a feed that has no
     * spec yet is still a file waiting. Counting only active feeds would also
     * have made the total disagree with the map, which lists the pending feed.
     */
    @Test
    void countsWhatWaitsInApendingFeedToo(@TempDir Path root) throws IOException {
        var pending = Feeds.pending(root, "half-done");
        registry.reconcileRoot(root);
        Feeds.file(pending.resolve("in"), "a.csv");

        assertAll(
                () -> assertEquals(1, status.getFilesWaiting()),
                () -> assertEquals(1, status.getFeeds().get("half-done").filesWaiting()));
    }

    /**
     * The one that would be wrong twice over if nobody looked. A failure leaves
     * two files in the hospital - the input and a {@code .log} beside it saying
     * what went wrong - so counting every file would report every failure as two
     * patients, and a monitor's alert threshold would be off by a factor of two.
     */
    @Test
    void doesNotCountTheExplanationAsAsecondPatient(@TempDir Path root) throws IOException {
        var orders = Feeds.active(root, "orders");
        registry.reconcileRoot(root);

        var hospital = orders.resolve("hospital");
        Feeds.file(hospital, "a.csv");
        Files.writeString(hospital.resolve("a.csv.log"), "what went wrong");
        Feeds.file(hospital, "b.csv");
        Files.writeString(hospital.resolve("b.csv.log"), "and here too");

        assertAll(
                () -> assertEquals(2, status.getFilesInHospital(), "two files, not four"),
                () -> assertEquals(2, status.getFeeds().get("orders").filesInHospital()));
    }

    /**
     * A directory is what is counted, so a subdirectory someone left in an inbox
     * is not a file waiting to be loaded.
     */
    @Test
    void countsOnlyRegularFiles(@TempDir Path root) throws IOException {
        var orders = Feeds.active(root, "orders");
        registry.reconcileRoot(root);

        Feeds.file(orders.resolve("in"), "a.csv");
        Files.createDirectory(orders.resolve("in").resolve("a-directory"));

        assertEquals(1, status.getFilesWaiting());
    }

    /** and a directory that is not there counts nothing rather than failing */
    @Test
    void amissingDirectoryCountsNothing(@TempDir Path root) throws IOException {
        var orders = Feeds.active(root, "orders");
        registry.reconcileRoot(root);
        Files.delete(orders.resolve("hospital"));

        assertEquals(0, status.getFilesInHospital());
    }

    @Test
    void countsNothingWithNoFeedsAtAll() {
        assertAll(
                () -> assertEquals(0, status.getActiveFeeds()),
                () -> assertEquals(0, status.getFilesWaiting()),
                () -> assertEquals(0, status.getFilesInHospital()),
                () -> assertTrue(status.getFeeds().isEmpty()));
    }

    // ---- the counters ---------------------------------------------------------

    /**
     * The load counters are passed straight through from {@link Statistics},
     * which has its own tests; what is worth checking here is that the totals
     * and the per-feed rows come from the same place, so a monitor comparing one
     * against the other is not comparing two accounts.
     */
    @Test
    void reportsTheCountersItIsGiven(@TempDir Path root) throws IOException {
        Feeds.active(root, "orders");
        registry.reconcileRoot(root);

        statistics.loaded("orders", 12);
        statistics.loaded("orders", 30);

        var row = status.getFeeds().get("orders");
        assertAll(
                () -> assertEquals(2, status.getLoadsSucceeded()),
                () -> assertEquals(42, status.getRecordsLoaded()),
                () -> assertEquals(2, row.loadsSucceeded()),
                () -> assertEquals(42, row.recordsLoaded()),
                () -> assertNotNull(status.getLastLoad()));
    }

    // ---- registration ---------------------------------------------------------

    /**
     * Registration hands back what unregisters it again, and does so even when
     * it failed - the server carries on loading files whatever JMX thinks, so
     * the caller is never given a null to check.
     */
    @Test
    void registrationHandsBackSomethingThatCloses() throws Exception {
        try (var registered = closeable(ServerStatus.register(registry, statistics))) {
            assertNotNull(registered);
        }
    }

    /**
     * And a second registration under the same name fails without taking the
     * server with it: an already-registered bean is a warning in the log, not an
     * exception out of startup.
     */
    @Test
    void asecondRegistrationIsSurvivable() throws Exception {
        try (var first = closeable(ServerStatus.register(registry, statistics));
             var second = closeable(ServerStatus.register(registry, statistics))) {
            assertAll(
                    () -> assertNotNull(first),
                    () -> assertNotNull(second));
        }
    }

    /** {@code AutoCloseable} is not {@code Closeable}, so try-with-resources needs a hand */
    private static AutoCloseable closeable(AutoCloseable delegate) {
        return delegate;
    }
}
