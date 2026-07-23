package com.pd.xldr.it;

import com.pd.xldr.app.AppConfig;
import com.pd.xldr.app.ConnectionPool;
import com.pd.xldr.app.Watcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.BooleanSupplier;

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
    private ConnectionPool pool;
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
        props.setProperty("xldr.maxConcurrentLoads", "2");
        props.setProperty("jdbc.url", JDBC_URL);
        props.setProperty("pool.maximumPoolSize", "3");

        var config = AppConfig.of(props);
        pool = new ConnectionPool(config);
        watcher = new Watcher(config, pool);
        watcher.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (watcher != null) {
            watcher.close();
        }
        if (pool != null) {
            pool.close();
        }
    }

    @Test
    @Timeout(60)
    void loadsAFileDroppedIntoAnewFeed() throws Exception {
        var feed = Files.createDirectory(root.resolve("people"));
        Files.writeString(feed.resolve("spec.json"), SPEC);
        Files.writeString(feed.resolve("adapter.properties"), "fieldSeparator=,\nrowSeparator=\\n\n");

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
        Files.writeString(feed.resolve("adapter.properties"), "fieldSeparator=,\nrowSeparator=\\n\n");
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
        Files.writeString(feed.resolve("adapter.properties"), "fieldSeparator=,\nrowSeparator=\\n\n");
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
                "\"mimeType\": \"text/csv\"",
                "\"mimeType\": \"text/csv\", \"sentinel\": \"glob:*.done\""));
        Files.writeString(feed.resolve("adapter.properties"), "fieldSeparator=,\nrowSeparator=\\n\n");
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
     * Producers hand a file over by an atomic move, never by writing into in/.
     */
    private void deliver(Path feed, String name, String content) throws IOException {
        var tmp = staging.resolve(name);
        Files.writeString(tmp, content);
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
     * Field selector names double as CSV header names; the field mappings then
     * refer to them by {@code fieldSelector}.
     */
    private static final String SPEC = """
            {
              "input": {
                "mimeType": "text/csv",
                "recordSelectors": [
                  {
                    "name": "people",
                    "selector": "people",
                    "fieldSelectors": [
                      {"name": "id", "selector": "id", "type": "STRING"},
                      {"name": "name", "selector": "name", "type": "STRING"}
                    ]
                  }
                ]
              },
              "mapping": [
                {
                  "recordSelector": "people",
                  "databaseTable": "person",
                  "fieldMapping": [
                    {"fieldSelector": "id", "databaseColumn": "id"},
                    {"fieldSelector": "name", "databaseColumn": "name"}
                  ]
                }
              ],
              "load": {"commitPolicy": "ON_CLOSE"}
            }
            """;
}
