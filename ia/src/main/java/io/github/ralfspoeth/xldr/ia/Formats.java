package io.github.ralfspoeth.xldr.ia;

import io.github.ralfspoeth.xldr.spec.DataType;
import org.jspecify.annotations.Nullable;

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
import java.util.Optional;

import static java.util.function.Predicate.not;

/**
 * How an adapter turns the text of a field into a typed value, where the text is
 * not in the canonical form {@link DataType#parse} expects.
 * <p>
 * Two settings, both optional and both taken from the adapter properties of a
 * feed:
 * <ul>
 *   <li>{@code dateFormat} - a {@link DateTimeFormatter} pattern for {@code TEMPORAL}
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

    private final @Nullable DateTimeFormatter date;
    private final @Nullable DecimalFormat number;

    private Formats(@Nullable DateTimeFormatter date, @Nullable DecimalFormat number) {
        this.date = date;
        this.number = number;
    }

    /**
     * @return formats that apply no pattern of their own, so that every value is
     * read in its canonical form
     */
    public static Formats defaults() {
        return DEFAULTS;
    }

    /**
     * Reads {@code dateFormat}, {@code numberFormat} and {@code locale} from the
     * adapter properties. A property that is absent leaves that part canonical.
     *
     * @param properties the adapter settings of an input
     * @return the formats those settings describe
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
     * @param type the type to convert to; {@code null} reads the value as text
     * @param raw  the textual form, which may be null
     * @return the value as that type, or {@code null} if {@code raw} is null or blank
     * @throws RuntimeException if the text is not a valid value of that type
     */
    public @Nullable Object parse(@Nullable DataType type, @Nullable String raw) {
        return Optional.ofNullable(raw)
                .map(String::strip)
                .filter(not(String::isEmpty))
                .map(s -> switch (type) {
                    case TEMPORAL -> date == null ? DataType.TEMPORAL.parse(s) : dateTime(s);
                    case INTEGRAL -> number == null
                            ? DataType.INTEGRAL.parse(s)
                            : integral(bigDecimal(s), s);
                    case FP -> number == null ? DataType.FP.parse(s) : number(s).doubleValue();
                    case DECIMAL -> number == null ? DataType.DECIMAL.parse(s) : bigDecimal(s);
                    case TEXT -> s;
                    case null -> s;
                })
                .orElse(null);
    }

    /**
     * A pattern without a time of day still yields a {@code LocalDateTime}, at
     * the start of the day - the type the spec declares for {@code TEMPORAL}.
     */
    private LocalDateTime dateTime(String s) {
        try {
            assert date != null;
            return LocalDateTime.parse(s, date);
        } catch (DateTimeParseException e) {
            return LocalDate.parse(s, date).atStartOfDay();
        }
    }

    private Number number(String s) {
        try {
            assert number != null;
            return number.parse(s);
        } catch (ParseException e) {
            throw new IllegalArgumentException("cannot read '" + s + "' with " + number.toPattern(), e);
        }
    }

    private BigDecimal bigDecimal(String s) {
        // parseBigDecimal is on, so this is exact
        var parsed = number(s);
        return parsed instanceof BigDecimal bd ? bd : new BigDecimal(parsed.toString());
    }

    /**
     * A whole number, refusing what it cannot carry rather than dropping it.
     * <p>
     * {@link DataType#INTEGRAL} is a {@code Long}, so there are two ways for a
     * number to be a number and still not be one of these: a non-zero fraction,
     * and a magnitude beyond 64 bits. Both used to end in
     * {@code Number.longValue()}, which drops the first and wraps the second -
     * so {@code 1,5} loaded as {@code 1} and a twenty-five digit account number
     * loaded as whatever its low bits said, in both cases with the load
     * reporting success.
     * <p>
     * The canonical path never did this, {@code Long.parseLong} refusing both.
     * What made the disagreement hard to see is that the path is chosen by a
     * property rather than by the field: configuring {@code numberFormat} for a
     * money column changed how the id column beside it handled bad input, and
     * nothing in the spec says so.
     * <p>
     * Only a non-zero fraction is refused. One {@code numberFormat} covers a
     * whole file, so a pattern with decimal places is the ordinary case even
     * where some columns are whole, and {@code 1.00} under {@code #,##0.00} is
     * exactly one.
     * <p>
     * Public and static because the JSON adapter needs the same rule on a path
     * that never reaches an instance of this class: a JSON number is already a
     * {@link BigDecimal} when the adapter sees it, so no pattern is involved and
     * there would otherwise be a second copy of this to keep in step.
     *
     * @param value the number, already parsed
     * @param shown how the input looked, for the complaint - the text as the
     *              file wrote it, which is what an operator will be looking at
     * @throws IllegalArgumentException if it is not a whole 64-bit number
     */
    public static long integral(BigDecimal value, String shown) {
        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("'" + shown + "' is " + value.toPlainString()
                    + ", which is not a whole number a 64-bit INTEGRAL can hold. Use DECIMAL for a"
                    + " value with a fraction, and for one beyond 9223372036854775807 a text"
                    + " column - nothing arithmetic is done to an identifier that long anyway", e);
        }
    }
}
