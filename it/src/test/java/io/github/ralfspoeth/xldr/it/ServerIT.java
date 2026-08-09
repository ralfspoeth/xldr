package io.github.ralfspoeth.xldr.it;

import io.github.ralfspoeth.xldr.server.Config;
import io.github.ralfspoeth.xldr.server.ConnectionSource;
import io.github.ralfspoeth.xldr.server.ServerMXBean;
import io.github.ralfspoeth.xldr.server.Watcher;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.BooleanSupplier;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End to end through the server: a feed directory appears, a spec activates it,
 * a file is moved into {@code in/}, and the rows turn up in H2 with the input
 * filed away under {@code archive/}.
 * <p>
 * Everything is driven through the file system only - no direct calls into the
 * registry or the processor - so this covers the reconcile, register, claim,
 * load and archive path as a whole, including the timing between them.
 */
public class ServerIT {

    private static final String JDBC_URL = "jdbc:h2:mem:appit;DB_CLOSE_DELAY=-1";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private Path root;
    private Path staging;
    private Watcher watcher;

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

        var config = Config.of(props);
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
        // the spec maps onto a table that does not exist
        Files.writeString(feed.resolve("spec.json"), SPEC.replace("\"person\"", "\"no_such_table\""));
        await("in/ to be created", () -> Files.isDirectory(feed.resolve("in")));

        deliver(feed, "bad.csv", """
                id,name
                1,Alice
                """);

        await("the input to be hospitalised", () -> {
            try (var files = Files.list(feed.resolve("hospital"))) {
                return files.anyMatch(p -> p.getFileName().toString().startsWith("bad.csv"));
            } catch (IOException e) {
                return false;
            }
        });
        try (var files = Files.list(feed.resolve("hospital"))) {
            var names = files.map(p -> p.getFileName().toString()).toList();
            assertTrue(names.contains("bad.csv"), "the input itself: " + names);
            assertTrue(names.stream().anyMatch(n -> n.endsWith(".log")), "an error log: " + names);
        }
        assertEquals(List.of(), selectPersons(), "nothing may have been inserted");
    }

    /**
     * Removing the spec switches a feed off again.
     */
    @Test
    @Timeout(60)
    void deactivatesAfeedWhenTheSpecIsRemoved() throws Exception {
        var feed = Files.createDirectory(root.resolve("transient"));
        Files.writeString(feed.resolve("spec.json"), SPEC);
        await("in/ to be created", () -> Files.isDirectory(feed.resolve("in")));

        Files.delete(feed.resolve("spec.json"));
        deliver(feed, "ignored.csv", """
                id,name
                9,Nobody
                """);

        // give the watcher and a scan interval the chance to react
        Thread.sleep(Duration.ofSeconds(3));
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
        Files.writeString(feed.resolve("spec.json"), SPEC.replace(
                "\"accepts\": \"glob:*.csv\"",
                "\"sentinel\": \"glob:*.done\""));
        await("in/ to be created", () -> Files.isDirectory(feed.resolve("in")));

        // the data file alone must not be loaded, even non-atomically
        var in = feed.resolve("in");
        Files.writeString(in.resolve("people-1.csv"), """
                id,name
                1,Alice
                2,Bob
                """);
        Thread.sleep(Duration.ofSeconds(3));
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
        Files.writeString(feed.resolve("spec.json"), SPEC.replace(
                "\"accepts\": \"glob:*.csv\"",
                "\"sentinel\": \"glob:*.done\""));
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
        Files.writeString(good.resolve("spec.json"), SPEC);
        var bad = Files.createDirectory(root.resolve("busy-broken"));
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
     * A feed must declare exactly one of {@code accepts} or {@code sentinel};
     * one that declares neither, or both, is not activated at all - so its
     * working directories are never even created.
     */
    @Test
    @Timeout(60)
    void refusesAfeedThatIsNotExactlyOneDeliveryRule() throws Exception {
        var neither = Files.createDirectory(root.resolve("neither"));
        Files.writeString(neither.resolve("spec.json"), SPEC.replace("\"accepts\": \"glob:*.csv\",", ""));

        var both = Files.createDirectory(root.resolve("both"));
        Files.writeString(both.resolve("spec.json"), SPEC.replace(
                "\"accepts\": \"glob:*.csv\",",
                "\"accepts\": \"glob:*.csv\", \"sentinel\": \"glob:*.done\","));

        // let the watcher and a scan interval try (and refuse) both
        Thread.sleep(Duration.ofSeconds(3));
        assertTrue(Files.notExists(neither.resolve("in")), "a feed with no delivery rule must not activate");
        assertTrue(Files.notExists(both.resolve("in")), "a feed with both delivery rules must not activate");
    }

    /**
     * Producers hand a file over by an atomic move, never by writing into in/.
     */
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
     * A spec naming an {@code env.} value that no {@code env.properties}
     * supplies must fail the load rather than insert a null: the file is missing,
     * not the value optional.
     */
    @Test
    @Timeout(60)
    void hospitalisesAloadWhoseDeploymentValueIsMissing() throws Exception {
        var feed = Files.createDirectory(root.resolve("unconfigured"));
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
        // no spec yet: an unconfigured feed is exactly the case where the watch
        // has to be in place before there is anything to activate
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
            // nothing to activate yet, so no in/ - and the directory is now watched
            assertTrue(Files.notExists(feed.resolve("in")), "the feed must still be inactive");

            Files.writeString(feed.resolve("spec.json"), SPEC);

            await("the spec to be noticed without a scan",
                    () -> Files.isDirectory(feed.resolve("in")));
        }
    }

    private static ServerMXBean serverStatus() throws Exception {
        return JMX.newMXBeanProxy(
                ManagementFactory.getPlatformMBeanServer(),
                new ObjectName("io.github.ralfspoeth.xldr:type=Server"),
                ServerMXBean.class);
    }

    private static List<Path> archived(Path feed) {
        try (var files = Files.walk(feed.resolve("archive"))) {
            return files.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            return List.of();
        }
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
                "accepts": "glob:*.xlsx",
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
                "accepts": "glob:*.json",
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
     */
    private static final String FIXED_LENGTH_SPEC = """
            {
              "input": {
                "mimeType": "text/plain",
                "accepts": "glob:*.txt",
                "properties": { "charset": "UTF-8" },
                "recordSelectors": [
                  {
                    "name": "people",
                    "selector": "people",
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
                "accepts": "glob:*.csv",
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
