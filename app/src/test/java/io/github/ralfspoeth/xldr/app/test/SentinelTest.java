package io.github.ralfspoeth.xldr.app.test;

import io.github.ralfspoeth.xldr.app.Sentinel;
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
     * A regex marker with a capturing group: the data file is group 1, so the
     * marker suffix need not simply be an added extension.
     */
    @Test
    public void regexTakesCapturingGroupOne() {
        var sentinel = Sentinel.parse("regex:(x.*\\.xml)\\.done");

        assertTrue(sentinel.isMarker(DIR.resolve("x123.xml.done")));
        assertFalse(sentinel.isMarker(DIR.resolve("y123.xml.done")), "must start with x");

        assertEquals(Optional.of(DIR.resolve("x123.xml")),
                sentinel.dataFileOf(DIR.resolve("x123.xml.done")));
    }

    /**
     * A regex with no capturing group falls back to the suffix rule.
     */
    @Test
    public void regexWithoutGroupStripsTheSuffix() {
        var sentinel = Sentinel.parse("regex:.*\\.done");

        assertTrue(sentinel.isMarker(DIR.resolve("a.b.done")));
        assertEquals(Optional.of(DIR.resolve("a.b")),
                sentinel.dataFileOf(DIR.resolve("a.b.done")));
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
