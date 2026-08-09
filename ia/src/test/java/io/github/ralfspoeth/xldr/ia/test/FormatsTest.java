package io.github.ralfspoeth.xldr.ia.test;

import io.github.ralfspoeth.xldr.ia.Formats;
import io.github.ralfspoeth.xldr.spec.DataType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class FormatsTest {

    /**
     * Without any pattern every value is read in its canonical form, exactly as
     * {@link DataType#parse} would.
     */
    @Test
    public void fallsBackToTheCanonicalForm() {
        var formats = Formats.defaults();
        assertAll(
                () -> assertEquals("abc", formats.parse(DataType.TEXT, " abc ")),
                () -> assertEquals(42L, formats.parse(DataType.INTEGRAL, "42")),
                () -> assertEquals(new BigDecimal("12.50"), formats.parse(DataType.DECIMAL, "12.50")),
                () -> assertEquals(0.25d, formats.parse(DataType.FLOAT, "0.25")),
                () -> assertEquals(LocalDateTime.of(2026, 7, 26, 0, 0),
                        formats.parse(DataType.DATE, "2026-07-26T00:00"))
        );
    }

    /**
     * A date pattern without a time of day still yields a timestamp, at the
     * start of the day.
     */
    @Test
    public void appliesADatePattern() {
        var formats = Formats.of(Map.of(Formats.DATE_FORMAT, "yyyyMMdd"));
        assertEquals(LocalDateTime.of(2026, 7, 26, 0, 0), formats.parse(DataType.DATE, "20260726"));
    }

    @Test
    public void appliesADatePatternWithATimeOfDay() {
        var formats = Formats.of(Map.of(Formats.DATE_FORMAT, "dd.MM.yyyy HH:mm"));
        assertEquals(LocalDateTime.of(2026, 7, 26, 8, 30), formats.parse(DataType.DATE, "26.07.2026 08:30"));
    }

    /**
     * A number pattern reads grouped input, and the locale decides the decimal
     * and grouping separators.
     */
    @Test
    public void appliesANumberPatternInALocale() {
        var german = Formats.of(Map.of(
                Formats.NUMBER_FORMAT, "#,##0.00",
                Formats.LOCALE, "de-DE"));
        assertAll(
                () -> assertEquals(new BigDecimal("1234.56"), german.parse(DataType.DECIMAL, "1.234,56")),
                () -> assertEquals(1234L, german.parse(DataType.INTEGRAL, "1.234")),
                () -> assertEquals(1234.56d, german.parse(DataType.FLOAT, "1.234,56"))
        );
    }

    /**
     * Decimals keep their exact value - the number format parses to BigDecimal
     * rather than rounding through a double.
     */
    @Test
    public void keepsDecimalsExact() {
        var formats = Formats.of(Map.of(Formats.NUMBER_FORMAT, "#,##0.00"));
        assertEquals(new BigDecimal("100000000000000000.01"),
                formats.parse(DataType.DECIMAL, "100,000,000,000,000,000.01"));
    }

    /**
     * A pattern changes nothing about absent values: null or blank stays null,
     * for every type.
     */
    @Test
    public void treatsBlankAsAbsent() {
        var formats = Formats.of(Map.of(
                Formats.DATE_FORMAT, "yyyyMMdd",
                Formats.NUMBER_FORMAT, "#,##0.00"));
        for (var type : DataType.values()) {
            assertNull(formats.parse(type, null), type + " of null");
            assertNull(formats.parse(type, "   "), type + " of blank");
        }
    }

    /**
     * An absent type reads the value as text.
     */
    @Test
    public void defaultsAmissingTypeToString() {
        assertEquals("x", Formats.defaults().parse(null, " x "));
    }

    @Test
    public void rejectsAnInvalidPattern() {
        assertThrows(IllegalArgumentException.class,
                () -> Formats.of(Map.of(Formats.DATE_FORMAT, "yyyyQQQQQQ")));
    }

    @Test
    public void reportsUnparsableInput() {
        var formats = Formats.of(Map.of(Formats.NUMBER_FORMAT, "#,##0.00"));
        assertThrows(RuntimeException.class, () -> formats.parse(DataType.INTEGRAL, "abc"));
    }
}
