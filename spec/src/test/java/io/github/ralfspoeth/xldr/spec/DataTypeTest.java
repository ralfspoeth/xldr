package io.github.ralfspoeth.xldr.spec;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.*;

class DataTypeTest {

    /**
     * Each type converts to the Java class it declares.
     */
    @Test
    void parsesEachType() {
        assertAll(
                () -> assertEquals("abc", DataType.TEXT.parse("abc")),
                () -> assertEquals(42L, DataType.INTEGRAL.parse("42")),
                () -> assertEquals(new BigDecimal("12.50"), DataType.DECIMAL.parse("12.50")),
                () -> assertEquals(0.25d, DataType.FP.parse("0.25")),
                () -> assertEquals(LocalDateTime.of(2026, 7, 26, 8, 30),
                        DataType.TEMPORAL.parse("2026-07-26T08:30"))
        );
    }

    /**
     * A plain ISO date is a timestamp at the start of that day, so a source that
     * carries dates without a time of day needs no pattern of its own.
     */
    @Test
    void acceptsAplainIsoDate() {
        assertAll(
                () -> assertEquals(LocalDateTime.of(2026, 7, 22, 0, 0),
                        DataType.TEMPORAL.parse("2026-07-22")),
                () -> assertEquals(LocalDateTime.of(2026, 7, 21, 14, 30),
                        DataType.TEMPORAL.parse("2026-07-21T14:30"))
        );
    }

    /**
     * Surrounding whitespace is formatting, not data: a padded value parses like
     * an unpadded one, for every type.
     */
    @Test
    void stripsBeforeConverting() {
        assertAll(
                () -> assertEquals("abc", DataType.TEXT.parse("  abc  ")),
                () -> assertEquals(42L, DataType.INTEGRAL.parse("   42")),
                () -> assertEquals(new BigDecimal("12.50"), DataType.DECIMAL.parse(" 12.50 ")),
                () -> assertEquals(0.25d, DataType.FP.parse("0.25   "))
        );
    }

    /**
     * A null or blank value is absent rather than an error - including for the
     * numeric types, where a blank column is a missing value, not a zero.
     */
    @Test
    void treatsNullAndBlankAsAbsent() {
        for (var type : DataType.values()) {
            assertNull(type.parse(null), type + " of null");
            assertNull(type.parse(""), type + " of empty");
            assertNull(type.parse("   "), type + " of blank");
        }
    }

    /**
     * Text that is not a value of the type is an error, not a silent null.
     */
    @Test
    void rejectsMalformedValues() {
        assertAll(
                () -> assertThrows(RuntimeException.class, () -> DataType.INTEGRAL.parse("x")),
                () -> assertThrows(RuntimeException.class, () -> DataType.DECIMAL.parse("1,5")),
                () -> assertThrows(RuntimeException.class, () -> DataType.TEMPORAL.parse("26.07.2026"))
        );
    }

    /**
     * The declared Java class matches what {@code parse} produces.
     */
    @Test
    void clazzMatchesTheParsedValue() {
        for (var type : DataType.values()) {
            var sample = switch (type) {
                case TEXT -> "x";
                case INTEGRAL -> "1";
                case FP -> "1.5";
                case DECIMAL -> "1.75";
                case TEMPORAL -> "2026-07-26T00:00";
            };
            assertEquals(type.clazz(), requireNonNull(type.parse(sample)).getClass(), type.toString());
        }
    }

    // ---- reading the name a spec wrote ---------------------------------------

    /** Every type is named without regard to case, which is what a spec relies on. */
    @Test
    void namesEveryTypeWhateverTheCase() {
        for (var type : DataType.values()) {
            assertAll(
                    () -> assertEquals(type, DataType.named(type.name())),
                    () -> assertEquals(type, DataType.named(type.name().toLowerCase(Locale.ROOT))),
                    () -> assertEquals(type, DataType.named("  " + type.name() + " ")));
        }
    }

    /**
     * And in any default locale, which is the bug this method was extracted to
     * fix.
     * <p>
     * The JSON reader upper-cased a type name with no locale, so under a Turkish
     * default {@code "integral"} became {@code "İNTEGRAL"} - the dotted capital I
     * - and matched no constant. Two of the five names carry an {@code i}. The
     * XML reader had always folded with {@link Locale#ROOT}; the two now share
     * one method, so there is one place left to get this wrong.
     */
    @Test
    void namesATypeInAturkishDefaultLocale() {
        var was = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            assertAll(
                    () -> assertEquals(DataType.INTEGRAL, DataType.named("integral")),
                    () -> assertEquals(DataType.DECIMAL, DataType.named("decimal")),
                    () -> assertEquals(DataType.TEMPORAL, DataType.named("temporal")));
        } finally {
            Locale.setDefault(was);
        }
    }

    /**
     * A spec still saying {@code DATE} is told what to write, rather than being
     * handed {@code valueOf}'s "No enum constant io.github...DataType.DATE".
     * <p>
     * The name was right until 0.47 and is in every spec written before it, so
     * this is the one error message here that has a specific reader in mind.
     */
    @Test
    void tellsAspecStillSayingDateWhatToWrite() {
        var thrown = assertThrows(IllegalArgumentException.class, () -> DataType.named("DATE"));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("TEMPORAL"), thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("0.47"),
                        "and when it changed: " + thrown.getMessage()));
        // the lower-case spelling reaches the same advice, since a spec may write either
        assertTrue(assertThrows(IllegalArgumentException.class, () -> DataType.named("date"))
                .getMessage().contains("TEMPORAL"));
    }

    /** anything else is refused with the list of what there is */
    @Test
    void refusesANameThatIsNotAtype() {
        var thrown = assertThrows(IllegalArgumentException.class, () -> DataType.named("TIMESTAMP"));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("TIMESTAMP"), thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("TEMPORAL"),
                        "and lists the types, so the answer is in the complaint: " + thrown.getMessage()));
    }
}
