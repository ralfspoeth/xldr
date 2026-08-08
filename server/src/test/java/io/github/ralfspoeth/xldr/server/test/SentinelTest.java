package io.github.ralfspoeth.xldr.server.test;

import io.github.ralfspoeth.xldr.server.Sentinel;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SentinelTest {

    private static final Path DIR = Path.of("/feed/in");

    /**
     * A glob marker: matched by the pattern, the data file is the name minus its
     * last dotted suffix.
     */
    @Test
    public void globStripsTheLastSuffix() {
        var sentinel = Sentinel.parse("glob:*.{ok,ready,done}");

        assertTrue(sentinel.isMarker(DIR.resolve("report.csv.done")));
        assertTrue(sentinel.isMarker(DIR.resolve("report.csv.ready")));
        assertFalse(sentinel.isMarker(DIR.resolve("report.csv")), "the data file is not a marker");

        assertEquals(Optional.of(DIR.resolve("report.csv")),
                sentinel.dataFileOf(DIR.resolve("report.csv.done")));
    }

    /**
     * A regex marker selects files just as a glob does; the data file is still
     * the name minus its last dotted suffix.
     */
    @Test
    public void regexStripsTheLastSuffix() {
        var sentinel = Sentinel.parse("regex:x.*\\.xml\\.done");

        assertTrue(sentinel.isMarker(DIR.resolve("x123.xml.done")));
        assertFalse(sentinel.isMarker(DIR.resolve("y123.xml.done")), "must start with x");

        assertEquals(Optional.of(DIR.resolve("x123.xml")),
                sentinel.dataFileOf(DIR.resolve("x123.xml.done")));
    }

    @Test
    public void rejectsAnUnprefixedPattern() {
        assertThrows(IllegalArgumentException.class, () -> Sentinel.parse(".done"));
    }

    @Test
    public void rejectsAnUncompilablePattern() {
        assertThrows(IllegalArgumentException.class, () -> Sentinel.parse("regex:(unbalanced"));
    }
}
