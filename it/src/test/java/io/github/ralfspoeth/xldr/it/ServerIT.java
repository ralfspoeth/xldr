package io.github.ralfspoeth.xldr.it;

import io.github.ralfspoeth.xldr.server.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.management.JMX;
import javax.management.ObjectName;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.BooleanSupplier;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End to end through the server: a feed directory appears, a spec activates it,
 * a file is moved into {@code in/}, and the rows turn up in H2 with the input
 * filed away under {@code archive/}.
 * <p>
 * Everything is driven through the file system only - no direct calls into the
 * registry or the processor - so this covers the reconcile, register, claim,
 * load and archive path as a whole, including the timing between them.
 */
class ServerIT {

    private static final String JDBC_URL = "jdbc:h2:mem:appit;DB_CLOSE_DELAY=-1";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private Path root;
    private Path staging;
    /**
     * The file that makes a directory a feed, written out rather than borrowed
     * from {@code server}: the name is part of what a deployment is promised,
     * and this test is the one that reads the server from the file system the
     * way an operator does.
     */
    private static final String DELIVERY = "delivery.properties";

    private Watcher watcher;
    /**
     * kept, because {@code recoverWork} runs once when a watcher starts: a test
     * of it has to lay the feed down first and start a watcher of its own
     */
    private Config config;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("xldr-root");
        // same file system as the feed, so ATOMIC_MOVE works
        staging = Files.createTempDirectory("xldr-staging");
        try (var conn = DriverManager.getConnection(JDBC_URL);
             var stmt = conn.createStatement()) {
            stmt.execute("drop table if exists person");
            stmt.execute("create table person(id varchar(10), name varchar(50))");
        }
        var props = new Properties();
        props.setProperty("xldr.roots", root.toString());
        // short, so a missed event is recovered quickly enough for a test
        props.setProperty("xldr.scanInterval", "1");
        // the pool is sized from this, so nothing else has to say how many
        // connections the server may hold
        props.setProperty("xldr.maxConcurrentLoads", "2");
        props.setProperty("jdbc.url", JDBC_URL);

        config = Config.of(props);
        watcher = Watcher.watch(config, () -> DriverManager.getConnection(JDBC_URL));
    }

    @AfterEach
    void tearDown() throws IOException {
        if (watcher != null) {
            watcher.close();
        }
    }

    @Test
    @Timeout(60)
    void loadsAFileDroppedIntoAnewFeed() throws Exception {
        var feed = Files.createDirectory(root.resolve("people"));
        Files.writeString(feed.resolve(DELIVERY), "accepts = glob:*.csv\n");
        Files.writeString(feed.resolve("spec.json"), SPEC);

        // in/ is created by the server once the spec is seen
        await("in/ to be created", () -> Files.isDirectory(feed.resolve("in")));

        deliver(feed, "people-1.csv", """
                id,name
                1,Alice
                2,Bob
                """);

        await("rows to arrive", () -> selectPersons().size() == 2);
        assertEquals(List.of("1:Alice", "2:Bob"), selectPersons());

        await("the input to be archived", () -> !archived(feed).isEmpty());
        assertTrue(archived(feed).getFirst().getFileName().toString().startsWith("people-1"));
        assertTrue(Files.list(feed.resolve("in")).findAny().isEmpty(), "in/ should be empty again");
        assertTrue(Files.list(feed.resolve("work")).findAny().isEmpty(), "work/ should be empty again");
    }

    /**
     * A load that cannot succeed must leave the input in the hospital together
     * with an error log, and must not insert anything.
     */
    @Test
    @Timeout(60)
    void sendsAfailingLoadToTheHospital() throws Exception {
        var feed = Files.createDirectory(root.resolve("broken"));
        Files.writeString(feed.resolve(DELIVERY), "accepts = glob:*.csv\n");
        // the spec maps onto a table that does not exist
        Files.writeString(feed.resolve("spec.json"), SPEC.replace("\"person\"", "\"no_such_table\""));
        await("in/ to be created", () -> Files.isDirectory(feed.resolve("in")));

        deliver(feed, "bad.csv", """
                id,name
                1,Alice
                """);

        // the input itself, not merely something named after it: the log is
        // written first and is named after the input, so `startsWith` would be
        // satisfied by the explanation before the file it explains has arrived
        await("the input to be hospitalised", () -> hospital(feed).contains("bad.csv"));

        var names = hospital(feed);
        assertTrue(names.contains("bad.csv"), "the input itself: " + names);
        assertTrue(names.stream().anyMatch(n -> n.endsWith(".log")), "an error log: " + names);
        assertEquals(List.of(), selectPersons(), "nothing may have been inserted");
    }

    /**
     * Removing the spec switches a feed off again.
     */
    @Test
    @Timeout(60)
    void deactivatesAfeedWhenTheSpecIsRemoved() throws Exception {
        var feed = Files.createDirectory(root.resolve("transient"));
        Files.writeString(feed.resolve(DELIVERY), "accepts = glob:*.csv\n");
        Files.writeString(feed.resolve("spec.json"), SPEC);
        await("in/ to be created", () -> Files.isDirectory(feed.resolve("in")));

        Files.delete(feed.resolve("spec.json"));
        deliver(feed, "ignored.csv", """
                id,name
                9,Nobody
                """);

        // two full sweeps: the server has looked twice and done nothing, which is
        // the assertion. Waiting on the count rather than on the clock
        awaitSweeps(2);
        assertEquals(List.of(), selectPersons(), "a deactivated feed must not load");
        assertTrue(Files.exists(feed.resolve("in").resolve("ignored.csv")),
                "the file should still be sitting in in/");
    }

    /**
     * A feed configured with a sentinel does nothing until the marker arrives,
     * then loads the data file the marker names and consumes the marker.
     */
    @Test
    @Timeout(60)
    void waitsForTheSentinelBeforeLoading() throws Exception {
        var feed = Files.createDirectory(root.resolve("signalled"));
        Files.writeString(feed.resolve(DELIVERY), "sentinel = glob:*.done\n");
        Files.writeString(feed.resolve("spec.json"), SPEC);
        await("in/ to be created", () -> Files.isDirectory(feed.resolve("in")));

        // the data file alone must not be loaded, even non-atomically
        var in = feed.resolve("in");
        Files.writeString(in.resolve("people-1.csv"), """
                id,name
                1,Alice
                2,Bob
                """);
        awaitSweeps(2);
        assertEquals(List.of(), selectPersons(), "no load before the sentinel");
        assertTrue(Files.exists(in.resolve("people-1.csv")), "data file still waiting");

        // the marker releases it
        Files.writeString(in.resolve("people-1.csv.done"), "");
        await("rows to arrive", () -> selectPersons().size() == 2);
        assertEquals(List.of("1:Alice", "2:Bob"), selectPersons());
        await("the marker to be consumed", () -> !Files.exists(in.resolve("people-1.csv.done")));
        assertTrue(archived(feed).stream()
                .anyMatch(p -> p.getFileName().toString().startsWith("people-1.csv")));
    }

    /**
     * A second feed, in a different format, alongside the CSV ones: the server
     * picks the fixed-length adapter by MIME type through {@code ServiceLoader},
     * and the character ranges of the spec become typed columns.
     */
    @Test
    @Timeout(60)
    void loadsAfixedLengthFeed() throws Exception {
        var feed = Files.createDirectory(root.resolve("fixed"));
        Files.writeString(feed.resolve(DELIVERY), "accepts = glob:*.txt\n");
        Files.writeString(feed.resolve("spec.json"), FIXED_LENGTH_SPEC);
        await("in/ to be created", () -> Files.isDirectory(feed.resolve("in")));

        // columns 0:3 and 3:13; the second line stops early, which is not an error
        deliver(feed, "people-1.txt", """
                001Alice
                002Bob
                """);

        await("rows to arrive", () -> selectPersons().size() == 2);
        // the id is declared INTEGRAL, so "001" arrives as the number 1
        assertEquals(List.of("1:Alice", "2:Bob"), selectPersons());
        await("the input to be archived", () -> !archived(feed).isEmpty());
    }

    /**
     * A JSON feed: the record selector points into the document, the field
     * selectors read members of each element, and a JSON number reaches the
     * database as a number rather than as text.
     */
    @Test
    @Timeout(60)
    void loadsAjsonFeed() throws Exception {
        var feed = Files.createDirectory(root.resolve("documents"));
        Files.writeString(feed.resolve(DELIVERY), "accepts = glob:*.json\n");
        Files.writeString(feed.resolve("spec.json"), JSON_SPEC);
        await("in/ to be created", () -> Files.isDirectory(feed.resolve("in")));

        deliver(feed, "people-1.json", """
                { "data": { "people": [
                    { "id": 1, "person": { "name": "Alice" } },
                    { "id": 2, "person": { "name": "Bob" } }
                ] } }
                """);

        await("rows to arrive", () -> selectPersons().size() == 2);
        assertEquals(List.of("1:Alice", "2:Bob"), selectPersons());
        await("the input to be archived", () -> !archived(feed).isEmpty());
    }

    /**
     * A spreadsheet feed, delivered as the binary a producer would actually
     * write: the record selector is a column range, the field selectors are
     * columns of it, and the numeric cells arrive as numbers.
     */
    @Test
    @Timeout(60)
    void loadsAspreadsheetFeed() throws Exception {
        var feed = Files.createDirectory(root.resolve("sheets"));
        Files.writeString(feed.resolve(DELIVERY), "accepts = glob:*.xlsx\n");
        Files.writeString(feed.resolve("spec.json"), XLSX_SPEC);
        await("in/ to be created", () -> Files.isDirectory(feed.resolve("in")));

        byte[] workbook;
        try (var wb = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("people");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("id");
            header.createCell(1).setCellValue("name");
            var first = sheet.createRow(1);
            first.createCell(0).setCellValue(1d);
            first.createCell(1).setCellValue("Alice");
            var second = sheet.createRow(2);
            second.createCell(0).setCellValue(2d);
            second.createCell(1).setCellValue("Bob");
            wb.write(out);
            workbook = out.toByteArray();
        }
        deliver(feed, "people-1.xlsx", workbook);

        await("rows to arrive", () -> selectPersons().size() == 2);
        // the id cells are numeric, and the spec declares them INTEGRAL
        assertEquals(List.of("1:Alice", "2:Bob"), selectPersons());
        await("the input to be archived", () -> !archived(feed).isEmpty());
    }

    /**
     * The server counts what it does and offers it over JMX, so that a load can
     * be watched without reading the log - and, for the numbers that matter, so
     * that a monitor can alert on them.
     */
    @Test
    @Timeout(60)
    void reportsWhatItHasDoneOverJmx() throws Exception {
        var mbeans = ManagementFactory.getPlatformMBeanServer();
        var name = new ObjectName("io.github.ralfspoeth.xldr:type=Server");
        var status = JMX.newMXBeanProxy(mbeans, name, ServerMXBean.class);

        var feed = Files.createDirectory(root.resolve("counted"));
        Files.writeString(feed.resolve(DELIVERY), "accepts = glob:*.csv\n");
        Files.writeString(feed.resolve("spec.json"), SPEC);
        await("the feed to become active", () -> status.getActiveFeeds() == 1);

        deliver(feed, "people-1.csv", """
                id,name
                1,Alice
                2,Bob
                """);
        await("the load to be counted", () -> status.getLoadsSucceeded() == 1);

        assertEquals(2, status.getRecordsLoaded());
        assertEquals(0, status.getLoadsFailed());
        assertEquals(0, status.getFilesInHospital());
        assertTrue(status.getLastLoad().startsWith("20"), "an instant: " + status.getLastLoad());

        var counted = status.getFeeds().get("counted");
        assertEquals(1, counted.loadsSucceeded());
        assertEquals(2, counted.recordsLoaded());

        // A failing load moves the numbers the other way and leaves the file
        // where a monitor would see it. A second feed rather than a rewritten
        // spec: rewriting one would race the registry's reload against the
        // delivery, and the load would fail or succeed by timing.
        var broken = Files.createDirectory(root.resolve("broken"));
        Files.writeString(broken.resolve(DELIVERY), "accepts = glob:*.csv\n");
        Files.writeString(broken.resolve("spec.json"), SPEC.replace("\"person\"", "\"no_such_table\""));
        await("the second feed to become active", () -> status.getActiveFeeds() == 2);

        deliver(broken, "bad.csv", """
                id,name
                9,Nobody
                """);
        await("the failure to be counted", () -> status.getLoadsFailed() == 1);
        // the hospital holds the input and a log explaining it; one file is sick
        await("the file to reach the hospital", () -> status.getFilesInHospital() == 1);

        var feeds = status.getFeeds();
        assertEquals(1, feeds.get("broken").filesInHospital());
        assertEquals(1, feeds.get("broken").loadsFailed());
        assertEquals(0, feeds.get("counted").filesInHospital());
        assertEquals(1, feeds.get("counted").loadsSucceeded(), "the healthy feed is untouched by it");
    }

    /**
     * A file sitting in an {@code in/} is the number an operator watches: it
     * means the server has been handed work it has not done. A sentinel feed
     * makes it observable without a race - the data file waits, by design, until
     * the marker says it is complete.
     */
    @Test
    @Timeout(60)
    void reportsFilesWaitingInAnInbox() throws Exception {
        var status = serverStatus();
        var feed = Files.createDirectory(root.resolve("waiting"));
        Files.writeString(feed.resolve(DELIVERY), "sentinel = glob:*.done\n");
        Files.writeString(feed.resolve("spec.json"), SPEC);
        await("the feed to become active", () -> Files.isDirectory(feed.resolve("in")));
        assertEquals(0, status.getFilesWaiting(), "nothing delivered yet");

        deliver(feed, "people-1.csv", """
                id,name
                1,Alice
                """);
        await("the file to be counted as waiting", () -> status.getFilesWaiting() == 1);

        // the marker releases it, and both files leave in/
        deliver(feed, "people-1.csv.done", "");
        await("the load to happen", () -> selectPersons().size() == 1);
        await("the inbox to drain", () -> status.getFilesWaiting() == 0);
    }

    /**
     * The counterpart to {@code lastLoad}: empty until something has failed, an
     * instant afterwards. A monitor differences the counters and reads this to
     * find out when.
     */
    @Test
    @Timeout(60)
    void reportsWhenAloadLastFailed() throws Exception {
        var status = serverStatus();
        assertEquals("", status.getLastFailure(), "nothing has failed yet");

        var feed = Files.createDirectory(root.resolve("failing"));
        Files.writeString(feed.resolve(DELIVERY), "accepts = glob:*.csv\n");
        Files.writeString(feed.resolve("spec.json"), SPEC.replace("\"person\"", "\"no_such_table\""));
        await("the feed to become active", () -> Files.isDirectory(feed.resolve("in")));

        deliver(feed, "bad.csv", """
                id,name
                9,Nobody
                """);
        await("the failure to be counted", () -> status.getLoadsFailed() == 1);
        assertTrue(status.getLastFailure().startsWith("20"),
                "an instant: " + status.getLastFailure());
    }

    /**
     * {@code loadsInProgress} is a gauge rather than a counter, so what is worth
     * pinning is that it comes back down. A load that increments it and then
     * fails to decrement - on the error path especially - would read as a server
     * permanently busy, and nothing else here would notice.
     */
    @Test
    @Timeout(60)
    void countsLoadsInProgressBackToZero() throws Exception {
        var status = serverStatus();
        assertEquals(0, status.getLoadsInProgress(), "idle before anything is delivered");

        var good = Files.createDirectory(root.resolve("busy"));
        Files.writeString(good.resolve(DELIVERY), "accepts = glob:*.csv\n");
        Files.writeString(good.resolve("spec.json"), SPEC);
        var bad = Files.createDirectory(root.resolve("busy-broken"));
        Files.writeString(bad.resolve(DELIVERY), "accepts = glob:*.csv\n");
        Files.writeString(bad.resolve("spec.json"), SPEC.replace("\"person\"", "\"no_such_table\""));
        await("both feeds to become active", () -> status.getActiveFeeds() == 2);

        deliver(good, "people-1.csv", """
                id,name
                1,Alice
                """);
        deliver(bad, "bad.csv", """
                id,name
                9,Nobody
                """);

        await("both loads to be accounted for",
                () -> status.getLoadsSucceeded() == 1 && status.getLoadsFailed() == 1);
        // the failing one is the point: its path through FileProcessor is the
        // one where a decrement is easiest to lose
        await("the gauge to come back to zero", () -> status.getLoadsInProgress() == 0);
    }

    /**
     * A delivery file declares exactly one of {@code accepts} or {@code sentinel},
     * and one that will not parse leaves the directory not a feed at all - so its
     * working directories are never even created, and a producer pointed at it
     * finds nowhere to deliver rather than a hole that swallows files.
     * <p>
     * The misspelled key is the case worth pinning. A properties file has no
     * schema, so nothing but the reader stands between {@code acccepts} and a
     * feed that comes up and claims nothing.
     */
    @Test
    @Timeout(60)
    void refusesAdeliveryFileItCannotRead() throws Exception {
        var neither = Files.createDirectory(root.resolve("neither"));
        Files.writeString(neither.resolve(DELIVERY), "# nothing at all\n");
        Files.writeString(neither.resolve("spec.json"), SPEC);

        var both = Files.createDirectory(root.resolve("both"));
        Files.writeString(both.resolve(DELIVERY),
                "accepts = glob:*.csv\nsentinel = glob:*.done\n");
        Files.writeString(both.resolve("spec.json"), SPEC);

        var typo = Files.createDirectory(root.resolve("typo"));
        Files.writeString(typo.resolve(DELIVERY), "acccepts = glob:*.csv\n");
        Files.writeString(typo.resolve("spec.json"), SPEC);

        // two full sweeps: the watcher has tried all three twice and refused them
        awaitSweeps(2);
        assertTrue(Files.notExists(neither.resolve("in")), "no delivery rule must not become a feed");
        assertTrue(Files.notExists(both.resolve("in")), "both delivery rules must not become a feed");
        assertTrue(Files.notExists(typo.resolve("in")), "an unknown setting must not become a feed");
    }

    // ---- what happens after the transaction commits ---------------------------

    /**
     * A load that committed and could not be archived stays in {@code work/}
     * with a marker, and is not a failure.
     * <p>
     * The case the whole split in {@code runLoad} exists for. Before it, the
     * archive throwing was caught with the load, so the file went to
     * {@code hospital/} with its rows already in the database - and an operator
     * doing what the hospital is for would load them a second time. Nothing
     * exercised it, because provoking it looked like it needed a filesystem that
     * fails on demand.
     * <p>
     * It needs one line. {@code archive()} puts a file under
     * {@code archive/yyyy/MM/dd}, so a regular file where the year directory
     * should go makes {@code createDirectories} throw. The feed's own
     * {@code archive/} is still a directory, which matters: the registry
     * recreates the four working directories on every reconcile, and breaking
     * one of those would deactivate the feed instead of the load.
     */
    @Test
    @Timeout(60)
    void strandsAloadThatCommittedButCouldNotBeArchived() throws Exception {
        var feed = Files.createDirectory(root.resolve("unarchivable"));
        Files.writeString(feed.resolve(DELIVERY), "accepts = glob:*.csv\n");
        Files.writeString(feed.resolve("spec.json"), SPEC);
        await("in/ to be created", () -> Files.isDirectory(feed.resolve("in")));

        // where archive() wants to make a directory, leave it a file
        var year = String.valueOf(LocalDate.now().getYear());
        Files.writeString(feed.resolve("archive").resolve(year), "not a directory");

        deliver(feed, "committed.csv", """
                id,name
                1,Alice
                2,Bob
                """);

        await("rows to arrive", () -> selectPersons().size() == 2);
        await("the marker to be written", () -> !markers(feed).isEmpty());

        var work = names(feed.resolve("work"));
        assertAll(
                () -> assertEquals(List.of("1:Alice", "2:Bob"), selectPersons(),
                        "the transaction committed, whatever happened afterwards"),
                () -> assertTrue(work.contains("committed.csv"),
                        "the input stays in work/, not hospital/: " + work),
                () -> assertTrue(work.contains("committed.csv.loaded"),
                        "with a marker beside it: " + work),
                () -> assertTrue(hospital(feed).isEmpty(),
                        "the hospital is where an operator looks for work to redeliver, and this "
                                + "one must not be: " + hospital(feed)),
                () -> assertTrue(
                        Files.readString(feed.resolve("work").resolve("committed.csv.loaded"))
                                .contains("COMMITTED"),
                        "and the marker says so in as many words"));
    }

    // ---- what happens to a file a dead process left behind --------------------

    /**
     * A file left in {@code work/} by a process that died is handed to an
     * operator rather than retried.
     * <p>
     * Retrying would be the tempting thing and the wrong one: whether the
     * transaction committed is unknown, so a retry either loses nothing or
     * duplicates everything, and nothing here can tell which.
     * <p>
     * This starts a watcher of its own, because {@code recoverWork} runs once at
     * startup over the feeds registered by then - it is the answer to what a
     * crash left behind, not to anything that happens while the server is up.
     */
    @Test
    @Timeout(60)
    void recoversAstaleClaimAtStartup() throws Exception {
        var feed = staleClaim("crashed", "interrupted.csv", null);

        await("the stale claim to be recovered", () -> hospital(feed).contains("interrupted.csv"));
        assertAll(
                () -> assertTrue(names(feed.resolve("work")).isEmpty(),
                        "work/ means a load is running, and none is"),
                () -> assertTrue(recoveredLog(feed).contains("unknown"),
                        "the note has to say the outcome is unknown: " + recoveredLog(feed)),
                () -> assertTrue(recoveredLog(feed).contains("check the target tables"),
                        recoveredLog(feed)));
    }

    /**
     * And one that carries a {@code .loaded} marker gets the stronger note.
     * <p>
     * Same directory, same recovery, opposite instruction. The generic note tells
     * an operator to check the target tables and decide; here there is nothing to
     * decide, because the previous run already recorded that the rows are in. A
     * file whose marker was ignored would be redelivered on the strength of a
     * note saying "unknown", which is the duplicate this marker exists to
     * prevent.
     */
    @Test
    @Timeout(60)
    void recoversAloadedButUnarchivedFileWithTheStrongerNote() throws Exception {
        var feed = staleClaim("half-filed", "committed.csv", """
                This file's load COMMITTED. Its rows are in the database.
                """);

        await("the stale claim to be recovered", () -> hospital(feed).contains("committed.csv"));
        assertAll(
                () -> assertTrue(recoveredLog(feed).contains("COMMITTED"),
                        "the note has to say the rows are in: " + recoveredLog(feed)),
                () -> assertTrue(recoveredLog(feed).contains("Do not move it back"),
                        recoveredLog(feed)),
                () -> assertTrue(names(feed.resolve("work")).isEmpty(),
                        "the marker goes with the file it described: " + names(feed.resolve("work"))));
    }

    /**
     * A feed laid out as a dead process would have left it, with a watcher
     * started afterwards so that {@code recoverWork} sees it.
     *
     * @param marker the content of the {@code .loaded} file, or null for a claim
     *               whose outcome nothing recorded
     */
    private Path staleClaim(String name, String input, String marker) throws Exception {
        watcher.close();
        var feed = Files.createDirectory(root.resolve(name));
        Files.writeString(feed.resolve(DELIVERY), "accepts = glob:*.csv\n");
        Files.writeString(feed.resolve("spec.json"), SPEC);
        Files.createDirectories(feed.resolve("work"));
        Files.writeString(feed.resolve("work").resolve(input), "id,name\n1,Alice\n");
        if (marker != null) {
            Files.writeString(feed.resolve("work").resolve(input + ".loaded"), marker);
        }
        watcher = Watcher.watch(config, () -> DriverManager.getConnection(JDBC_URL));
        return feed;
    }

    /** the {@code .recovered.log} of the one file this feed recovered */
    private static String recoveredLog(Path feed) {
        try (var files = Files.list(feed.resolve("hospital"))) {
            var log = files.filter(p -> p.getFileName().toString().endsWith(".recovered.log"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no .recovered.log in " + hospital(feed)));
            return Files.readString(log);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> markers(Path feed) {
        return names(feed.resolve("work")).stream().filter(n -> n.endsWith(".loaded")).toList();
    }

    private static List<String> names(Path dir) {
        try (var files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private void deliver(Path feed, String name, String content) throws IOException {
        deliver(feed, name, content.getBytes(UTF_8));
    }

    /**
     * The same, for a format that is not text.
     */
    private void deliver(Path feed, String name, byte[] content) throws IOException {
        var tmp = staging.resolve(name);
        Files.write(tmp, content);
        Files.move(tmp, feed.resolve("in").resolve(name), ATOMIC_MOVE);
    }

    private List<String> selectPersons() {
        var rows = new ArrayList<String>();
        try (var conn = DriverManager.getConnection(JDBC_URL);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("select id, name from person order by id")) {
            while (rs.next()) {
                rows.add(rs.getString(1) + ":" + rs.getString(2));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return rows;
    }

    /**
     * A feed may carry an {@code env.properties} beside its spec, and its keys
     * become expression names under {@code env.}. The file is read per load, so
     * an edit reaches the next file without the feed being reloaded - which is
     * the second half of what this asserts, and the reason the same feed loads
     * twice.
     */
    @Test
    @Timeout(60)
    void readsDeploymentValuesFromEnvProperties() throws Exception {
        var feed = Files.createDirectory(root.resolve("labelled"));
        Files.writeString(feed.resolve(DELIVERY), "accepts = glob:*.csv\n");
        Files.writeString(feed.resolve("env.properties"), "label = from-test\n");
        // the name column comes from the deployment, not from the file
        Files.writeString(feed.resolve("spec.json"), SPEC.replace(
                "{\"fieldSelector\": \"name\", \"column\": \"name\"}",
                "{\"expr\": \"${env.label}\", \"column\": \"name\"}"));
        await("in/ to be created", () -> Files.isDirectory(feed.resolve("in")));

        deliver(feed, "first.csv", """
                id,name
                1,ignored
                """);
        await("the first row", () -> selectPersons().size() == 1);
        assertEquals(List.of("1:from-test"), selectPersons());

        // no reload in between: only the file on disk changes
        Files.writeString(feed.resolve("env.properties"), "label = from-prod\n");
        deliver(feed, "second.csv", """
                id,name
                2,ignored
                """);
        await("the second row", () -> selectPersons().size() == 2);
        assertEquals(List.of("1:from-test", "2:from-prod"), selectPersons(),
                "env.properties is read per load, so the edit must reach the second file");
    }

    /**
     * A feed's {@code target.properties} decides which schema its rows land in.
     * <p>
     * The dual of {@code delivery.properties}: one says how the files arrive, the
     * other where their rows go, and neither is in the spec because a spec is
     * meant to travel from test to production unchanged while both of those
     * differ between the two.
     * <p>
     * Two schemas hold a table of the same name, and the assertion is not only
     * that the rows arrived but that the other schema is still empty. Without
     * that half the test would pass against an unqualified insert whose search
     * path happened to find the right table first, which is precisely the
     * accident this file exists to remove.
     */
    @Test
    @Timeout(60)
    void loadsIntoTheSchemaTargetPropertiesNames() throws Exception {
        try (var conn = DriverManager.getConnection(JDBC_URL);
             var stmt = conn.createStatement()) {
            for (var schema : List.of("staging", "elsewhere")) {
                stmt.execute("drop schema if exists " + schema + " cascade");
                stmt.execute("create schema " + schema);
                stmt.execute("create table " + schema + ".person(id varchar(10), name varchar(50))");
            }
        }
        var feed = Files.createDirectory(root.resolve("targeted"));
        Files.writeString(feed.resolve(DELIVERY), "accepts = glob:*.csv\n");
        Files.writeString(feed.resolve("target.properties"), "schema = staging\n");
        Files.writeString(feed.resolve("spec.json"), SPEC);
        await("in/ to be created", () -> Files.isDirectory(feed.resolve("in")));

        deliver(feed, "people-1.csv", """
                id,name
                1,Alice
                """);

        await("the row to arrive in staging", () -> personsIn("staging").size() == 1);
        assertAll(
                () -> assertEquals(List.of("1:Alice"), personsIn("staging")),
                () -> assertEquals(List.of(), personsIn("elsewhere"),
                        "the other schema has a person table too, and must stay empty"),
                () -> assertTrue(selectPersons().isEmpty(),
                        "and so must the unqualified one"));
    }

    private static List<String> personsIn(String schema) {
        var rows = new ArrayList<String>();
        try (var conn = DriverManager.getConnection(JDBC_URL);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("select id, name from " + schema + ".person order by id")) {
            while (rs.next()) {
                rows.add(rs.getString(1) + ":" + rs.getString(2));
            }
        } catch (SQLException e) {
            return List.of();
        }
        return rows;
    }

    /**
     * A database that will not take a catalog in an insert says so, and the file
     * is hospitalised rather than failing halfway through.
     * <p>
     * PostgreSQL is the real case - it cannot qualify across databases, so
     * {@code supportsCatalogsInDataManipulation} is false and
     * {@code catalog = warehouse} is a spec it can never load. No PostgreSQL in
     * this build, so the connection is wrapped to answer as one would. That is
     * not a fake of the database: what is under test is how we react to what a
     * driver reports, and the driver reporting it is the input.
     * <p>
     * The seam is {@code ConnectionSource}, which the server already takes from
     * its caller - a lambda in every other test here.
     */
    @Test
    @Timeout(60)
    void refusesAcatalogTheDatabaseCannotTake() throws Exception {
        watcher.close();
        var feed = Files.createDirectory(root.resolve("catalogued"));
        Files.writeString(feed.resolve(DELIVERY), "accepts = glob:*.csv\n");
        Files.writeString(feed.resolve("target.properties"), "catalog = warehouse\n");
        Files.writeString(feed.resolve("spec.json"), SPEC);
        watcher = Watcher.watch(config, () -> withoutCatalogSupport(DriverManager.getConnection(JDBC_URL)));
        await("in/ to be created", () -> Files.isDirectory(feed.resolve("in")));

        deliver(feed, "people-1.csv", """
                id,name
                1,Alice
                """);

        await("the input to be hospitalised", () -> hospital(feed).contains("people-1.csv"));
        var log = hospitalLog(feed);
        assertAll(
                () -> assertTrue(log.contains("warehouse"),
                        "the message names the catalog that cannot be used: " + log),
                () -> assertTrue(selectPersons().isEmpty(), "and nothing was loaded"));
    }

    /**
     * The same connection, reporting no catalog support. Everything else is the
     * real H2 - only the one answer differs, so the load fails where a
     * PostgreSQL deployment's would and nowhere else.
     */
    private static Connection withoutCatalogSupport(Connection real) {
        return (Connection) Proxy.newProxyInstance(
                ServerIT.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> "getMetaData".equals(method.getName())
                        ? denyingCatalogs(real.getMetaData())
                        : method.invoke(real, args));
    }

    private static DatabaseMetaData denyingCatalogs(DatabaseMetaData real) {
        return (DatabaseMetaData) Proxy.newProxyInstance(
                ServerIT.class.getClassLoader(), new Class<?>[]{DatabaseMetaData.class},
                (proxy, method, args) -> "supportsCatalogsInDataManipulation".equals(method.getName())
                        ? Boolean.FALSE
                        : method.invoke(real, args));
    }

    /** the text of the one {@code .log} this feed's hospital holds */
    private static String hospitalLog(Path feed) {
        try (var files = Files.list(feed.resolve("hospital"))) {
            var log = files.filter(p -> p.getFileName().toString().endsWith(".log"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no log in " + hospital(feed)));
            return Files.readString(log);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A misspelled setting is refused rather than ignored, which matters more
     * here than in {@code delivery.properties}: an ignored {@code schmea} leaves
     * the load unqualified, and an unqualified load against a search path that
     * finds a table of the same name succeeds - into the wrong schema, with
     * nothing said.
     */
    @Test
    @Timeout(60)
    void hospitalisesAloadWhoseTargetSettingIsMisspelled() throws Exception {
        var feed = Files.createDirectory(root.resolve("mistyped-target"));
        Files.writeString(feed.resolve(DELIVERY), "accepts = glob:*.csv\n");
        Files.writeString(feed.resolve("target.properties"), "schmea = staging\n");
        Files.writeString(feed.resolve("spec.json"), SPEC);
        await("in/ to be created", () -> Files.isDirectory(feed.resolve("in")));

        deliver(feed, "people-1.csv", """
                id,name
                1,Alice
                """);

        await("the input to be hospitalised", () -> hospital(feed).contains("people-1.csv"));
        assertTrue(selectPersons().isEmpty(), "and nothing was loaded");
    }

    /**
     * {@code env.properties} is read as UTF-8, not as the ISO-8859-1 that
     * {@code Properties.load(InputStream)} assumes.
     * <p>
     * A deliberate departure from the {@code .properties} convention, and worth
     * pinning because nothing about it is visible: these values are written by
     * hand in an editor that saves UTF-8, and they reach a database column
     * verbatim. Read the other way, {@code Grüße} arrives as {@code GrÃ¼ÃŸe} -
     * no error anywhere, just a column that is quietly wrong in every row of
     * every load until somebody looks at it.
     */
    @Test
    @Timeout(60)
    void readsEnvPropertiesAsUtfEight() throws Exception {
        var feed = Files.createDirectory(root.resolve("accented"));
        Files.writeString(feed.resolve(DELIVERY), "accepts = glob:*.csv\n");
        // Files.writeString is UTF-8, which is what an editor would have written
        Files.writeString(feed.resolve("env.properties"), "label = Grüße\n");
        Files.writeString(feed.resolve("spec.json"), SPEC.replace(
                "{\"fieldSelector\": \"name\", \"column\": \"name\"}",
                "{\"expr\": \"${env.label}\", \"column\": \"name\"}"));
        await("in/ to be created", () -> Files.isDirectory(feed.resolve("in")));

        deliver(feed, "accented.csv", """
                id,name
                1,ignored
                """);

        await("the row", () -> selectPersons().size() == 1);
        assertEquals(List.of("1:Grüße"), selectPersons(),
                "read as ISO-8859-1 this would be GrÃ¼ÃŸe, and nothing would have complained");
    }

    /**
     * A spec naming an {@code env.} value that no {@code env.properties}
     * supplies must fail the load rather than insert a null: the file is missing,
     * not the value optional.
     */
    @Test
    @Timeout(60)
    void hospitalisesAloadWhoseDeploymentValueIsMissing() throws Exception {
        var feed = Files.createDirectory(root.resolve("unconfigured"));
        Files.writeString(feed.resolve(DELIVERY), "accepts = glob:*.csv\n");
        Files.writeString(feed.resolve("spec.json"), SPEC.replace(
                "{\"fieldSelector\": \"name\", \"column\": \"name\"}",
                "{\"expr\": \"${env.label}\", \"column\": \"name\"}"));
        await("in/ to be created", () -> Files.isDirectory(feed.resolve("in")));

        deliver(feed, "nolabel.csv", """
                id,name
                1,Alice
                """);

        await("the input to be hospitalised", () -> {
            try (var files = Files.list(feed.resolve("hospital"))) {
                return files.anyMatch(p -> p.getFileName().toString().endsWith(".log"));
            } catch (IOException e) {
                return false;
            }
        });
        try (var files = Files.list(feed.resolve("hospital"))) {
            var log = files.filter(p -> p.getFileName().toString().endsWith(".log")).findFirst().orElseThrow();
            assertTrue(Files.readString(log).contains("env.label"),
                    "the log should name the value it could not resolve");
        }
        assertEquals(List.of(), selectPersons(), "nothing may have been inserted");
    }

    /**
     * A feed directory that already existed when the server started must be
     * watched, so that a spec written into it later activates the feed at once.
     * <p>
     * Runs its own server on its own root with the scan effectively switched
     * off, because the shared one scans every second and would hide the very
     * thing under test: with no scan to fall back on, only a watch on the feed
     * directory can carry the news.
     */
    @Test
    @Timeout(60)
    void watchesAfeedDirectoryThatExistedBeforeTheServerStarted() throws Exception {
        var otherRoot = Files.createTempDirectory("xldr-preexisting");
        // an empty directory: not a feed yet, which is exactly the case where the
        // watch has to be in place before there is anything to register
        var feed = Files.createDirectory(otherRoot.resolve("later"));

        var props = new Properties();
        props.setProperty("xldr.roots", otherRoot.toString());
        // an hour: long enough that a scan cannot rescue the assertion
        props.setProperty("xldr.scanInterval", "3600");
        props.setProperty("xldr.maxConcurrentLoads", "1");
        props.setProperty("jdbc.url", JDBC_URL);
        var config = Config.of(props);
        ConnectionSource otherPool = () -> DriverManager.getConnection(JDBC_URL);
        try (var _ = Watcher.watch(config, otherPool)) {
            // nothing registered yet, so no in/ - and the directory is now watched
            assertTrue(Files.notExists(feed.resolve("in")), "the directory must not be a feed yet");

            Files.writeString(feed.resolve(DELIVERY), "accepts = glob:*.csv\n");

            await("the delivery file to be noticed without a scan",
                    () -> Files.isDirectory(feed.resolve("in")));
        }
    }

    /**
     * The two files a feed is made of arrive from different hands and need not
     * arrive together. With the delivery file alone the feed is real - its
     * directories exist and its producer may deliver - but nothing is loaded;
     * what arrives waits in {@code in/} until a spec turns up, and is then loaded
     * without being delivered again.
     * <p>
     * The file is deliberately delivered before the spec exists. That it is still
     * there afterwards is the point: an unconfigured feed must not consume,
     * quarantine or discard what it cannot yet load.
     */
    @Test
    @Timeout(60)
    void holdsWhatArrivesUntilAspecAppears() throws Exception {
        var feed = Files.createDirectory(root.resolve("awaited"));
        Files.writeString(feed.resolve(DELIVERY), "accepts = glob:*.csv\n");

        await("in/ to be created without a spec", () -> Files.isDirectory(feed.resolve("in")));

        deliver(feed, "early.csv", """
                id,name
                1,Alice
                """);

        // two full sweeps: a feed that was going to mishandle the file has had
        // every chance to, and the count says so rather than a stopwatch
        awaitSweeps(2);
        assertEquals(List.of(), selectPersons(), "nothing loaded without a spec");
        assertTrue(Files.exists(feed.resolve("in").resolve("early.csv")),
                "the file waits in in/, neither claimed nor hospitalised");
        assertTrue(archived(feed).isEmpty(), "and is certainly not archived");

        // and it is findable while it waits. The log says this once, when the
        // feed goes pending; the bean is what still knows tomorrow.
        var status = serverStatus();
        var pending = status.getFeeds().get("awaited");
        assertEquals(FeedState.PENDING, pending.state(), "a feed with no spec is pending, not absent");
        assertEquals(1, pending.filesWaiting(), "and its backlog is counted");
        assertTrue(status.getFilesWaiting() >= 1, "the total counts it too, so the rows add up");

        Files.writeString(feed.resolve("spec.json"), SPEC);

        await("the backlog to be loaded once the spec is there",
                () -> selectPersons().size() == 1);
        assertEquals(List.of("1:Alice"), selectPersons());
        await("the input to be archived", () -> !archived(feed).isEmpty());
        assertEquals(FeedState.ACTIVE, serverStatus().getFeeds().get("awaited").state());
    }

    private static ServerMXBean serverStatus() throws Exception {
        return JMX.newMXBeanProxy(
                ManagementFactory.getPlatformMBeanServer(),
                new ObjectName("io.github.ralfspoeth.xldr:type=Server"),
                ServerMXBean.class);
    }

    /**
     * The file names in a feed's {@code hospital/}, or none where the server has
     * not created it yet - so that a poll can ask before there is anything there.
     */
    private static List<String> hospital(Path feed) {
        try (var files = Files.list(feed.resolve("hospital"))) {
            return files.map(p -> p.getFileName().toString()).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static List<Path> archived(Path feed) {
        try (var files = Files.walk(feed.resolve("archive"))) {
            return files.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Waits until the watcher has completed {@code sweeps} more reconciliations
     * than it had when this was called.
     * <p>
     * The four places that use this assert that <em>nothing</em> happened, which
     * cannot be awaited directly - there is no condition to poll for an absence.
     * They used to sleep three seconds each, twelve seconds of the suite spent
     * proving a negative by the clock, and the number was a guess that would have
     * had to grow if the scan interval ever did. The count is the observable the
     * assertion actually needs: the server has looked, twice, and did not act.
     */
    private static void awaitSweeps(int sweeps) throws Exception {
        var status = serverStatus();
        var target = status.getReconciliations() + sweeps;
        await(sweeps + " reconciliation(s)", () -> status.getReconciliations() >= target);
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        var deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(Duration.ofMillis(50));
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    /**
     * The same two columns as {@link #SPEC}, read from a spreadsheet: the record
     * selector is a cell rectangle on the named sheet - rows 2 to 3, so the
     * header row is not a record - and each field selector is one of its columns.
     */
    private static final String XLSX_SPEC = """
            {
              "input": {
                "mimeType": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "recordSelectors": [
                  {
                    "name": "people",
                    "selector": "people!A2:B3",
                    "fieldSelectors": [
                      {"name": "id", "selector": "A", "type": "INTEGRAL"},
                      {"name": "name", "selector": "B", "type": "TEXT"}
                    ]
                  }
                ]
              },
              "mapping": [
                {
                  "recordSelector": "people",
                  "table": "person",
                  "fieldMapping": [
                    {"fieldSelector": "id", "column": "id"},
                    {"fieldSelector": "name", "column": "name"}
                  ]
                }
              ]
            }
            """;

    /**
     * The same two columns as {@link #SPEC}, read from a JSON document: the
     * record selector walks into {@code data/people}, and the name is fetched
     * from a nested object. No adapter settings at all - JSON carries its own
     * types, and UTF-8 is the default.
     */
    private static final String JSON_SPEC = """
            {
              "input": {
                "mimeType": "application/json",
                "recordSelectors": [
                  {
                    "name": "people",
                    "selector": "data/people",
                    "fieldSelectors": [
                      {"name": "id", "selector": "id", "type": "INTEGRAL"},
                      {"name": "name", "selector": "person/name", "type": "TEXT"}
                    ]
                  }
                ]
              },
              "mapping": [
                {
                  "recordSelector": "people",
                  "table": "person",
                  "fieldMapping": [
                    {"fieldSelector": "id", "column": "id"},
                    {"fieldSelector": "name", "column": "name"}
                  ]
                }
              ]
            }
            """;

    /**
     * The same two columns as {@link #SPEC}, read from a fixed-length file: a
     * field selector is a character range, and the second one omits its left
     * bound to continue where the first ended. The id is typed, so the padding
     * zeroes do not reach the database.
     * <p>
     * The record selector carries no {@code selector}: a fixed-length file has
     * nowhere to point at, and one written here is refused when the adapter is
     * built. This spec used to carry {@code "selector": "people"}, which the
     * adapter read and discarded.
     */
    private static final String FIXED_LENGTH_SPEC = """
            {
              "input": {
                "mimeType": "text/plain",
                "properties": { "charset": "UTF-8" },
                "recordSelectors": [
                  {
                    "name": "people",
                    "fieldSelectors": [
                      {"name": "id", "selector": "0:3", "type": "INTEGRAL"},
                      {"name": "name", "selector": ":13", "type": "TEXT"}
                    ]
                  }
                ]
              },
              "mapping": [
                {
                  "recordSelector": "people",
                  "table": "person",
                  "fieldMapping": [
                    {"fieldSelector": "id", "column": "id"},
                    {"fieldSelector": "name", "column": "name"}
                  ]
                }
              ]
            }
            """;

    /**
     * Field selector names double as CSV header names; the field mappings then
     * refer to them by {@code fieldSelector}.
     */
    private static final String SPEC = """
            {
              "input": {
                "mimeType": "text/csv",
                "properties": { "fieldSeparator": "," },
                "recordSelectors": [
                  {
                    "name": "people",
                    "fieldSelectors": [
                      {"name": "id", "selector": "id", "type": "TEXT"},
                      {"name": "name", "selector": "name", "type": "TEXT"}
                    ]
                  }
                ]
              },
              "mapping": [
                {
                  "recordSelector": "people",
                  "table": "person",
                  "fieldMapping": [
                    {"fieldSelector": "id", "column": "id"},
                    {"fieldSelector": "name", "column": "name"}
                  ]
                }
              ]
            }
            """;
}
