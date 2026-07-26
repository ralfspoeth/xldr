package io.github.ralfspoeth.xldr.ia;

import io.github.ralfspoeth.xldr.spec.DataType;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParseException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;

/**
 * How an adapter turns the text of a field into a typed value, where the text is
 * not in the canonical form {@link DataType#parse} expects.
 * <p>
 * Two settings, both optional and both taken from the adapter properties of a
 * feed:
 * <ul>
 *   <li>{@code dateFormat} - a {@link DateTimeFormatter} pattern for {@code DATE}
 *       fields, e.g. {@code yyyyMMdd} or {@code dd.MM.yyyy}. A pattern that
 *       carries no time of day yields midnight;</li>
 *   <li>{@code numberFormat} - a {@link DecimalFormat} pattern for the numeric
 *       types, e.g. {@code #,##0.00} for grouped input. {@code locale} (a
 *       language tag such as {@code de-DE}) selects the decimal and grouping
 *       separators; it defaults to {@link Locale#ROOT}, which is
 *       {@code 1234.56}.</li>
 * </ul>
 * Whatever is not configured falls through to {@link DataType#parse}, so an
 * adapter can always hand its text to {@link #parse} and let the configuration
 * decide.
 * <p>
 * <strong>Not thread safe</strong>: {@code DecimalFormat} is stateful, so one
 * instance belongs to one adapter, which is used by one load at a time.
 */
public final class Formats {

    public static final String DATE_FORMAT = "dateFormat";
    public static final String NUMBER_FORMAT = "numberFormat";
    public static final String LOCALE = "locale";

    private static final Formats DEFAULTS = new Formats(null, null);

    private final DateTimeFormatter date;
    private final DecimalFormat number;

    private Formats(DateTimeFormatter date, DecimalFormat number) {
        this.date = date;
        this.number = number;
    }

    /**
     * Formats that apply no pattern of their own; every value is read in its
     * canonical form.
     */
    public static Formats defaults() {
        return DEFAULTS;
    }

    /**
     * Reads {@code dateFormat}, {@code numberFormat} and {@code locale} from the
     * adapter properties. A property that is absent leaves that part canonical.
     *
     * @throws IllegalArgumentException if a pattern or language tag is invalid
     */
    public static Formats of(Map<String, String> properties) {
        var datePattern = properties.get(DATE_FORMAT);
        var numberPattern = properties.get(NUMBER_FORMAT);
        if (datePattern == null && numberPattern == null) {
            return DEFAULTS;
        }
        var locale = properties.containsKey(LOCALE)
                ? Locale.forLanguageTag(properties.get(LOCALE))
                : Locale.ROOT;
        try {
            return new Formats(
                    datePattern == null ? null : DateTimeFormatter.ofPattern(datePattern, locale),
                    numberPattern == null ? null : decimalFormat(numberPattern, locale));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid " + DATE_FORMAT + "/" + NUMBER_FORMAT + ": " + e.getMessage(), e);
        }
    }

    private static DecimalFormat decimalFormat(String pattern, Locale locale) {
        var format = new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(locale));
        // otherwise a decimal would be rounded through double on the way in
        format.setParseBigDecimal(true);
        return format;
    }

    /**
     * Converts {@code raw} to {@code type}, applying the configured patterns and
     * falling back to {@link DataType#parse} where none applies. A null or blank
     * value is absent, exactly as there.
     *
     * @throws RuntimeException if the text is not a valid value of that type
     */
    public Object parse(DataType type, String raw) {
        if (type == null) {
            type = DataType.STRING;
        }
        if (raw == null) {
            return null;
        }
        var s = raw.strip();
        if (s.isEmpty()) {
            return null;
        }
        return switch (type) {
            case DATE -> date == null ? DataType.DATE.parse(s) : dateTime(s);
            case INTEGER -> number == null ? DataType.INTEGER.parse(s) : number(s).longValue();
            case FLOAT -> number == null ? DataType.FLOAT.parse(s) : number(s).doubleValue();
            case DECIMAL -> number == null ? DataType.DECIMAL.parse(s) : bigDecimal(s);
            case STRING -> s;
        };
    }

    /**
     * A pattern without a time of day still yields a {@code LocalDateTime}, at
     * the start of the day - the type the spec declares for {@code DATE}.
     */
    private LocalDateTime dateTime(String s) {
        try {
            return LocalDateTime.parse(s, date);
        } catch (DateTimeParseException e) {
            return LocalDate.parse(s, date).atStartOfDay();
        }
    }

    private Number number(String s) {
        try {
            return number.parse(s);
        } catch (ParseException e) {
            throw new IllegalArgumentException("cannot read '" + s + "' with " + number.toPattern(), e);
        }
    }

    private BigDecimal bigDecimal(String s) {
        // parseBigDecimal is on, so this is exact
        return number(s) instanceof BigDecimal bd ? bd : new BigDecimal(number(s).toString());
    }
}
