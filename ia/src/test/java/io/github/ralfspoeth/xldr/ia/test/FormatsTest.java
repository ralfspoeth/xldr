package io.github.ralfspoeth.xldr.ia.test;

import io.github.ralfspoeth.xldr.ia.Formats;
import io.github.ralfspoeth.xldr.spec.DataType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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
                () -> assertEquals(0.25d, formats.parse(DataType.FP, "0.25")),
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
                () -> assertEquals(1234.56d, german.parse(DataType.FP, "1.234,56"))
        );
    }

    /**
     * The worked example on tutorial page 8, value for value.
     * <p>
     * The pieces are covered above; this pins the combination the tutorial
     * actually shows, because that is what a reader copies. A German source
     * system writes {@code 01.03.2026} and {@code 1.234,56}, and the page claims
     * they arrive as a date of the first of March and a decimal of 1234.56 - an
     * easy claim to get wrong by one setting, and one nothing checked while the
     * page said it.
     */
    @Test
    public void readsTheGermanFileOfTutorialPageEight() {
        var formats = Formats.of(Map.of(
                Formats.DATE_FORMAT, "dd.MM.yyyy",
                Formats.NUMBER_FORMAT, "#,##0.00",
                Formats.LOCALE, "de-DE"));

        assertAll(
                () -> assertEquals(1L, formats.parse(DataType.INTEGRAL, "1")),
                () -> assertEquals("Alice", formats.parse(DataType.TEXT, "Alice")),
                () -> assertEquals(LocalDateTime.of(2026, 3, 1, 0, 0),
                        formats.parse(DataType.DATE, "01.03.2026"),
                        "the first of March, not the third of January"),
                () -> assertEquals(LocalDateTime.of(2026, 3, 15, 0, 0),
                        formats.parse(DataType.DATE, "15.03.2026")),
                () -> assertEquals(0, new BigDecimal("1234.56").compareTo(
                        (BigDecimal) formats.parse(DataType.DECIMAL, "1.234,56"))),
                () -> assertEquals(0, new BigDecimal("98.00").compareTo(
                        (BigDecimal) formats.parse(DataType.DECIMAL, "98,00")))
        );
    }

    /**
     * An {@code INTEGRAL} is refused where it is not one, whether or not a number
     * format is configured.
     * <p>
     * The two paths used to disagree. Without a pattern the value goes through
     * {@code Long.parseLong}, which throws; with one it went through
     * {@code DecimalFormat} and then {@code longValue()}, which drops a fraction
     * and wraps an overflow and says nothing about either. So the same field, fed
     * the same text, was an error in one feed and a quietly wrong number in
     * another - and which of the two you got depended on a {@code numberFormat}
     * that had very likely been set for the money column beside it.
     */
    @Test
    public void anIntegralThatIsNotWholeIsRefusedEitherWay() {
        var german = Formats.of(Map.of(
                Formats.NUMBER_FORMAT, "#,##0.00",
                Formats.LOCALE, "de-DE"));
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> german.parse(DataType.INTEGRAL, "1,5"),
                        "a fraction is not a whole number, pattern or no pattern"),
                () -> assertThrows(RuntimeException.class,
                        () -> Formats.defaults().parse(DataType.INTEGRAL, "1.5"),
                        "as the canonical path has always said")
        );
    }

    /**
     * And refused where it will not fit, which is the same defect one order of
     * magnitude up: {@code longValue()} wrapped, so a twenty-five digit account
     * number loaded as whatever its low bits said.
     */
    @Test
    public void anIntegralBeyondSixtyFourBitsIsRefused() {
        var formats = Formats.of(Map.of(Formats.NUMBER_FORMAT, "#,##0.##"));
        var tooBig = "9999999999999999999999999";
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> formats.parse(DataType.INTEGRAL, tooBig));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains(tooBig), thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("DECIMAL"),
                        "and says what to use instead: " + thrown.getMessage()));
    }

    /**
     * Only a non-zero fraction is refused. One {@code numberFormat} covers a
     * whole file, so a pattern with decimal places is the ordinary case even
     * where some columns are whole - and {@code 1.00} is exactly one.
     */
    @Test
    public void awholeNumberUnderAdecimalPatternStillLoads() {
        var formats = Formats.of(Map.of(Formats.NUMBER_FORMAT, "#,##0.00"));
        assertAll(
                () -> assertEquals(1L, formats.parse(DataType.INTEGRAL, "1.00")),
                () -> assertEquals(1234L, formats.parse(DataType.INTEGRAL, "1,234.00")),
                () -> assertEquals(-7L, formats.parse(DataType.INTEGRAL, "-7.000")),
                () -> assertEquals(Long.MAX_VALUE,
                        formats.parse(DataType.INTEGRAL, String.valueOf(Long.MAX_VALUE)),
                        "the boundary itself fits"));
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
