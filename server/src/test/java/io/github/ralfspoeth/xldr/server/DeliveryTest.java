package io.github.ralfspoeth.xldr.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryTest {

    private static Delivery of(String... pairs) {
        var props = new Properties();
        for (int i = 0; i < pairs.length; i += 2) {
            props.setProperty(pairs[i], pairs[i + 1]);
        }
        return Delivery.of(props);
    }

    private static IllegalArgumentException refused(String... pairs) {
        return assertThrows(IllegalArgumentException.class, () -> of(pairs));
    }

    @Test
    void readsAtomicDelivery() {
        var delivery = assertInstanceOf(Delivery.Atomic.class, of("accepts", "glob:*.csv"));
        assertAll(
                () -> assertTrue(delivery.claims(Path.of("/feeds/orders/in/orders.csv"))),
                () -> assertFalse(delivery.claims(Path.of("/feeds/orders/in/orders.txt"))),
                // matched against the name, never the path - a directory called
                // something.csv above the file must not make it match
                () -> assertFalse(delivery.claims(Path.of("/feeds/x.csv/in/orders.txt")))
        );
    }

    @Test
    void readsSignalledDelivery() {
        var delivery = assertInstanceOf(Delivery.Signalled.class, of("sentinel", "glob:*.done"));
        assertAll(
                // the marker is what is claimed, not the data file it vouches for
                () -> assertTrue(delivery.claims(Path.of("in/report.csv.done"))),
                () -> assertFalse(delivery.claims(Path.of("in/report.csv"))),
                () -> assertEquals(Path.of("in/report.csv"),
                        delivery.sentinel().dataFileOf(Path.of("in/report.csv.done")).orElseThrow())
        );
    }

    @Test
    void acceptsRegexAsWellAsGlob() {
        var delivery = of("accepts", "regex:ORD_\\d{8}\\.csv");
        assertAll(
                () -> assertTrue(delivery.claims(Path.of("ORD_20260814.csv"))),
                () -> assertFalse(delivery.claims(Path.of("ORD_2026.csv")))
        );
    }

    /**
     * The invariant is the shape of the type, so these two are the only ways to
     * fail it, and both have to say which one happened - "neither" and "both"
     * call for opposite edits.
     */
    @Test
    void insistsOnExactlyOneWayOfDelivering() {
        assertAll(
                () -> assertTrue(refused().getMessage().contains("neither")),
                () -> assertTrue(refused("accepts", "glob:*.csv", "sentinel", "glob:*.done")
                        .getMessage().contains("both")),
                // present but blank is the missing choice, not a pattern that
                // matches nothing
                () -> assertTrue(refused("accepts", "   ").getMessage().contains("neither"))
        );
    }

    /**
     * A properties file has no schema to catch a typo, so the reader is the only
     * thing standing between a misspelled key and a feed that silently claims
     * nothing.
     */
    @Test
    void refusesASettingItDoesNotKnow() {
        var e = refused("accepts", "glob:*.csv", "acccepts", "glob:*.txt");
        assertAll(
                () -> assertTrue(e.getMessage().contains("acccepts"), e.getMessage()),
                // and says what it does read, so the fix does not need the source
                () -> assertTrue(e.getMessage().contains("accepts"), e.getMessage()),
                () -> assertTrue(e.getMessage().contains("sentinel"), e.getMessage())
        );
    }

    @Test
    void refusesAPatternItCannotUse() {
        assertAll(
                // no prefix: getPathMatcher would reject it anyway, but late and
                // less clearly
                () -> assertTrue(refused("accepts", "*.csv").getMessage().contains("glob:")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> of("accepts", "regex:[unclosed")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> of("sentinel", "*.done"))
        );
    }

    @Test
    void readsAfileFromDisk(@TempDir Path feed) throws IOException {
        var file = feed.resolve(Delivery.FILE);
        // a plain string, not a text block: the trailing blanks are the point,
        // and a text block would strip them before the test ever ran.
        // Properties.load keeps them, and unstripped they land inside the
        // pattern, which then matches nothing
        Files.writeString(file, "# how the nightly job delivers\naccepts = glob:ORD_*.csv   \n");
        var delivery = Delivery.read(file);
        assertAll(
                () -> assertTrue(delivery.claims(Path.of("ORD_1.csv"))),
                // trailing whitespace in a properties value is kept by
                // Properties.load, and would otherwise end up inside the pattern
                () -> assertEquals("accepts=glob:ORD_*.csv", delivery.toString())
        );
    }
}
