package io.github.ralfspoeth.xldr.spec;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

import static java.util.Objects.requireNonNull;

/**
 * The types a field value may be delivered as, each mapped to its Java class.
 * <p>
 * The names are the spec's own vocabulary and deliberately none of Java's, so
 * that no one reads {@code FP} as {@code float} or {@code INTEGRAL} as
 * {@code int} and expects the width that goes with it. {@code FP} is binary
 * floating point, delivered as a {@code Double}, and rounds; {@code DECIMAL} is
 * exact and is what money wants.
 */
public enum DataType {

    /**
     * A date/time field; no timezone;
     * wraps a {@link LocalDateTime}.
     */
    DATE(LocalDateTime.class, 93),
    /**
     * Textual content; represented by
     * {@link String}
     */
    TEXT(String.class, 12),
    /**
     * 64-bit integral value, represented by a
     * {@link Long}.
     */
    INTEGRAL(Long.class, -5),
    /**
     * 64-bit floating point value
     * represented by a {@link Double}.
     */
    FP(Double.class, 8),
    /**
     * Decimal value, represented by
     * {@link BigDecimal}.
     */
    DECIMAL(BigDecimal.class, 3);

    private final Class<?> clazz;
    private final int sqlType;

    DataType(Class<?> clazz, int sqlType) {
        this.clazz = requireNonNull(clazz);
        this.sqlType = sqlType;
    }

    /**
     * The {@code java.sql.Types} constant a driver should be told this is, when
     * one has to be named rather than inferred - registering the OUT parameter of
     * a {@link ValueSource.FunctionCall}, or binding a null.
     * <p>
     * The conservative choice in each case, and the one that matches
     * {@link #clazz()}: {@code TIMESTAMP} rather than {@code TIMESTAMP_WITH_TIMEZONE},
     * since a {@code LocalDateTime} carries no zone; {@code BIGINT} rather than
     * {@code INTEGER}, since the value is a {@code Long}; {@code DOUBLE} rather
     * than the confusingly named {@code FLOAT}, which in JDBC is also double
     * precision; {@code VARCHAR} rather than {@code LONGVARCHAR}.
     * <p>
     * The literals are deliberate. Naming {@code java.sql.Types} here would make
     * this module - the document model, which a linter or an editor plugin reads
     * without ever opening a connection - require {@code java.sql} for five
     * integers. The values are fixed by the JDBC specification and cannot drift:
     * 93, 12, -5, 8 and 3 are {@code TIMESTAMP}, {@code VARCHAR}, {@code BIGINT},
     * {@code DOUBLE} and {@code DECIMAL}.
     *
     * @return the JDBC type code for this type
     */
    public int sqlType() {
        return sqlType;
    }

    /**
     * The class instance of which represent this data type.
     * @return the Java class values of this type are delivered as
     */
    public Class<?> clazz() {
        return clazz;
    }

    /**
     * Converts the textual form of a value to this type.
     * <p>
     * The text is stripped first, since a value read from a padded or indented
     * source carries whitespace that is formatting rather than data. A value
     * that is null or blank yields {@code null} - for every type, so that a
     * blank numeric field is an absent value rather than a parse error - which
     * the loader then binds as SQL NULL.
     * <p>
     * This is the canonical form of each type: ISO-8601 for {@code DATE} (a
     * plain date as well as a timestamp), and a plain, ungrouped literal for the
     * numeric types. Input in another notation is the business of
     * {@code Formats}, which applies the feed's configured patterns.
     *
     * @param raw the textual form, which may be null
     * @return the value as this type, or {@code null} if {@code raw} is null or blank
     * @throws RuntimeException if the text is not a valid value of this type
     */
    public @Nullable Object parse(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        var s = raw.strip();
        if (s.isEmpty()) {
            return null;
        }
        return switch (this) {
            case DATE -> dateTime(s);
            case TEXT -> s;
            case INTEGRAL -> Long.parseLong(s);
            case FP -> Double.parseDouble(s);
            case DECIMAL -> new BigDecimal(s);
        };
    }

    /**
     * An ISO timestamp, or a plain ISO date - which is midnight of that day, the
     * timestamp this type declares.
     * @param s the timestamp string
     * @return a date/time instance
     */
    private static LocalDateTime dateTime(String s) {
        try {
            return LocalDateTime.parse(s);
        } catch (DateTimeParseException e) {
            return LocalDate.parse(s).atStartOfDay();
        }
    }
}
