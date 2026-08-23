package io.github.ralfspoeth.xldr.server.test;

import io.github.ralfspoeth.xldr.server.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The first thing a server does with a deployment's own file, and until now
 * untested.
 * <p>
 * Everything here is parsing: no filesystem, no database, no thread. That is
 * what makes it worth covering first and what made it easy to leave - it never
 * broke visibly, because a mistake in it shows up as a server that will not
 * start, which someone then reads the message of and fixes by hand.
 */
class ConfigTest {

    /** the two settings without which there is nothing to do */
    private static Properties minimal(String roots) {
        var props = new Properties();
        props.setProperty("xldr.roots", roots);
        props.setProperty("jdbc.url", "jdbc:h2:mem:test");
        return props;
    }

    // ---- the roots -----------------------------------------------------------

    @Test
    void readsOneRoot() {
        var config = Config.of(minimal("/var/lib/xldr"));
        assertEquals(List.of(Path.of("/var/lib/xldr").toAbsolutePath().normalize()), config.roots());
    }

    /**
     * Separated by the platform's path separator, so that a configuration file
     * written on the platform it runs on needs no escaping.
     */
    @Test
    void readsSeveralRootsSeparatedByThePlatformSeparator() {
        var config = Config.of(minimal("/var/lib/xldr" + File.pathSeparator + "/mnt/feeds"));
        assertEquals(
                List.of(Path.of("/var/lib/xldr").toAbsolutePath().normalize(),
                        Path.of("/mnt/feeds").toAbsolutePath().normalize()),
                config.roots());
    }

    /**
     * Absolute and normalised, because a root is compared against the path of
     * every file the watcher sees. A relative one would resolve against the
     * working directory the server happened to start in, which is a property of
     * how it was launched rather than of how it was configured.
     */
    @Test
    void makesRootsAbsoluteAndNormalised() {
        var config = Config.of(minimal("/var/lib/xldr/../feeds/./in"));
        var root = config.roots().getFirst();
        assertAll(
                () -> assertTrue(root.isAbsolute(), root.toString()),
                () -> assertEquals(root.normalize(), root),
                () -> assertEquals(Path.of("/var/lib/feeds/in").toAbsolutePath().normalize(), root));
    }

    /**
     * A trailing separator, or a doubled one, is a typo rather than a root named
     * by the empty string.
     */
    @Test
    void ignoresEmptyEntries() {
        var sep = File.pathSeparator;
        var config = Config.of(minimal(sep + "/var/lib/xldr" + sep + sep + "/mnt/feeds" + sep));
        assertEquals(2, config.roots().size());
    }

    @Test
    void stripsSurroundingSpaceFromEachRoot() {
        var config = Config.of(minimal("  /var/lib/xldr  " + File.pathSeparator + " /mnt/feeds "));
        assertEquals(
                List.of(Path.of("/var/lib/xldr").toAbsolutePath().normalize(),
                        Path.of("/mnt/feeds").toAbsolutePath().normalize()),
                config.roots());
    }

    @Test
    void refusesRootsThatNameNoDirectory() {
        var props = minimal(File.pathSeparator + "  " + File.pathSeparator);
        var thrown = assertThrows(IllegalArgumentException.class, () -> Config.of(props));
        assertTrue(thrown.getMessage().contains("xldr.roots"), thrown.getMessage());
    }

    @Test
    void refusesAmissingRoots() {
        var props = new Properties();
        props.setProperty("jdbc.url", "jdbc:h2:mem:test");
        var thrown = assertThrows(IllegalArgumentException.class, () -> Config.of(props));
        assertTrue(thrown.getMessage().contains("xldr.roots"), thrown.getMessage());
    }

    /** blank counts as missing, a key with nothing after it saying nothing */
    @Test
    void refusesABlankRoots() {
        var thrown = assertThrows(IllegalArgumentException.class, () -> Config.of(minimal("   ")));
        assertTrue(thrown.getMessage().contains("xldr.roots"), thrown.getMessage());
    }

    // ---- the numbers ---------------------------------------------------------

    @Test
    void defaultsTheScanIntervalAndTheConcurrency() {
        var config = Config.of(minimal("/var/lib/xldr"));
        assertAll(
                () -> assertEquals(30L, config.scanIntervalSeconds()),
                () -> assertEquals(4, config.maxConcurrentLoads()));
    }

    @Test
    void readsTheScanIntervalAndTheConcurrency() {
        var props = minimal("/var/lib/xldr");
        props.setProperty("xldr.scanInterval", " 90 ");
        props.setProperty("xldr.maxConcurrentLoads", " 8 ");
        var config = Config.of(props);
        assertAll(
                () -> assertEquals(90L, config.scanIntervalSeconds()),
                () -> assertEquals(8, config.maxConcurrentLoads()));
    }

    /**
     * Refused rather than read as the default. A number that does not parse is a
     * deployment that meant something, and starting on 30 seconds because "sixty"
     * is not a long would be a server running on a setting nobody chose.
     */
    @Test
    void refusesAnUnparseableNumber() {
        var props = minimal("/var/lib/xldr");
        props.setProperty("xldr.scanInterval", "sixty");
        assertThrows(NumberFormatException.class, () -> Config.of(props));
    }

    @Test
    void refusesAconcurrencyBelowOne() {
        var props = minimal("/var/lib/xldr");
        props.setProperty("xldr.maxConcurrentLoads", "0");
        var thrown = assertThrows(IllegalArgumentException.class, () -> Config.of(props));
        assertTrue(thrown.getMessage().contains("at least 1"), thrown.getMessage());
    }

    // ---- the pool ------------------------------------------------------------

    @Test
    void buildsThePoolFromTheJdbcSettings() {
        var props = minimal("/var/lib/xldr");
        props.setProperty("jdbc.user", "dbuser");
        props.setProperty("jdbc.password", "secret");
        var pool = Config.of(props).poolProperties();
        assertAll(
                () -> assertEquals("jdbc:h2:mem:test", pool.getProperty("jdbcUrl")),
                () -> assertEquals("dbuser", pool.getProperty("username")),
                () -> assertEquals("secret", pool.getProperty("password")));
    }

    /**
     * A URL is the one thing a pool cannot be built without; a user and a
     * password are not, an embedded database or an OS-authenticated connection
     * needing neither.
     */
    @Test
    void refusesAmissingUrl() {
        var props = new Properties();
        props.setProperty("xldr.roots", "/var/lib/xldr");
        var thrown = assertThrows(IllegalArgumentException.class, () -> Config.of(props));
        assertTrue(thrown.getMessage().contains("jdbc.url"), thrown.getMessage());
    }

    @Test
    void leavesTheUserAndPasswordOutWhereTheyAreNotGiven() {
        var pool = Config.of(minimal("/var/lib/xldr")).poolProperties();
        assertAll(
                () -> assertNull(pool.getProperty("username")),
                () -> assertNull(pool.getProperty("password")));
    }

    /**
     * Every {@code pool.*} key reaches HikariConfig under its own name, so the
     * whole of its configuration is reachable without this class mirroring it.
     */
    @Test
    void passesPoolKeysThroughUnderTheirOwnNames() {
        var props = minimal("/var/lib/xldr");
        props.setProperty("pool.connectionTimeout", "5000");
        props.setProperty("pool.poolName", "xldr-loads");
        var pool = Config.of(props).poolProperties();
        assertAll(
                () -> assertEquals("5000", pool.getProperty("connectionTimeout")),
                () -> assertEquals("xldr-loads", pool.getProperty("poolName")),
                () -> assertNull(pool.getProperty("pool.connectionTimeout"),
                        "the prefix is stripped, not kept alongside"));
    }

    /**
     * The pool is sized to the concurrency, so that how many loads run at once
     * is said once. Hikari's own default of ten would otherwise be a second
     * limit that no setting names.
     */
    @Test
    void sizesThePoolToTheConcurrency() {
        var props = minimal("/var/lib/xldr");
        props.setProperty("xldr.maxConcurrentLoads", "8");
        assertEquals("8", Config.of(props).poolProperties().getProperty("maximumPoolSize"));
    }

    /** and an explicit setting still wins, for a database with few sessions */
    @Test
    void anExplicitPoolSizeWins() {
        var props = minimal("/var/lib/xldr");
        props.setProperty("xldr.maxConcurrentLoads", "8");
        props.setProperty("pool.maximumPoolSize", "2");
        var config = Config.of(props);
        assertAll(
                () -> assertEquals("2", config.poolProperties().getProperty("maximumPoolSize")),
                () -> assertEquals(8, config.maxConcurrentLoads(),
                        "the loads are still what was configured; the pool is what queues them"));
    }

    // ---- from a file ---------------------------------------------------------

    @Test
    void readsAfile(@TempDir Path dir) throws IOException {
        var file = dir.resolve("xldr.properties");
        Files.writeString(file, """
                xldr.roots = /var/lib/xldr
                xldr.scanInterval = 60
                xldr.maxConcurrentLoads = 2
                jdbc.url = jdbc:h2:mem:test
                jdbc.user = dbuser
                pool.connectionTimeout = 5000
                """);
        var config = Config.load(file);
        assertAll(
                () -> assertEquals(60L, config.scanIntervalSeconds()),
                () -> assertEquals(2, config.maxConcurrentLoads()),
                () -> assertEquals("dbuser", config.poolProperties().getProperty("username")),
                () -> assertEquals("5000", config.poolProperties().getProperty("connectionTimeout")),
                () -> assertEquals("2", config.poolProperties().getProperty("maximumPoolSize")));
    }

    @Test
    void saysWhichFileItCouldNotRead(@TempDir Path dir) {
        var missing = dir.resolve("absent.properties");
        var thrown = assertThrows(IOException.class, () -> Config.load(missing));
        assertTrue(thrown.getMessage().contains("absent.properties"), thrown.getMessage());
    }

    // ---- the record itself ---------------------------------------------------

    /**
     * The roots are copied on the way in, so that a caller holding the list it
     * passed cannot change where the server watches after the fact.
     */
    @Test
    void copiesTheRoots() {
        var roots = new ArrayList<>(List.of(Path.of("/var/lib/xldr")));
        var config = new Config(roots, 30L, 4, new Properties());
        roots.add(Path.of("/somewhere/else"));
        assertEquals(1, config.roots().size());
    }
}
