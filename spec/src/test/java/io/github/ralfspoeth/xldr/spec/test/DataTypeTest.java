package io.github.ralfspoeth.xldr.spec.test;

import io.github.ralfspoeth.xldr.spec.DataType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class DataTypeTest {

    /**
     * Each type converts to the Java class it declares.
     */
    @Test
    public void parsesEachType() {
        assertAll(
                () -> assertEquals("abc", DataType.TEXT.parse("abc")),
                () -> assertEquals(42L, DataType.INTEGRAL.parse("42")),
                () -> assertEquals(new BigDecimal("12.50"), DataType.DECIMAL.parse("12.50")),
                () -> assertEquals(0.25d, DataType.FP.parse("0.25")),
                () -> assertEquals(LocalDateTime.of(2026, 7, 26, 8, 30),
                        DataType.DATE.parse("2026-07-26T08:30"))
        );
    }

    /**
     * A plain ISO date is a timestamp at the start of that day, so a source that
     * carries dates without a time of day needs no pattern of its own.
     */
    @Test
    public void acceptsAplainIsoDate() {
        assertAll(
                () -> assertEquals(LocalDateTime.of(2026, 7, 22, 0, 0),
                        DataType.DATE.parse("2026-07-22")),
                () -> assertEquals(LocalDateTime.of(2026, 7, 21, 14, 30),
                        DataType.DATE.parse("2026-07-21T14:30"))
        );
    }

    /**
     * Surrounding whitespace is formatting, not data: a padded value parses like
     * an unpadded one, for every type.
     */
    @Test
    public void stripsBeforeConverting() {
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
    public void treatsNullAndBlankAsAbsent() {
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
    public void rejectsMalformedValues() {
        assertAll(
                () -> assertThrows(RuntimeException.class, () -> DataType.INTEGRAL.parse("x")),
                () -> assertThrows(RuntimeException.class, () -> DataType.DECIMAL.parse("1,5")),
                () -> assertThrows(RuntimeException.class, () -> DataType.DATE.parse("26.07.2026"))
        );
    }

    /**
     * The declared Java class matches what {@code parse} produces.
     */
    @Test
    public void clazzMatchesTheParsedValue() {
        for (var type : DataType.values()) {
            var sample = switch (type) {
                case TEXT -> "x";
                case INTEGRAL -> "1";
                case FP -> "1.5";
                case DECIMAL -> "1.75";
                case DATE -> "2026-07-26T00:00";
            };
            assertEquals(type.clazz(), requireNonNull(type.parse(sample)).getClass(), type.toString());
        }
    }
}
