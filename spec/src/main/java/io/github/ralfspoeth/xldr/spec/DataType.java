package io.github.ralfspoeth.xldr.spec;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * The types a field value may be delivered as, each mapped to its Java class.
 */
public enum DataType {

    DATE(LocalDateTime.class),
    STRING(String.class),
    INTEGER(Long.class),
    FLOAT(Double.class),
    DECIMAL(BigDecimal.class);

    private final Class<?> clazz;

    DataType(Class<?> clazz) {
        this.clazz = clazz;
    }

    /**
     * The Java class values of this type are delivered as.
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
     * @throws RuntimeException if the text is not a valid value of this type
     */
    public Object parse(String raw) {
        if (raw == null) {
            return null;
        }
        var s = raw.strip();
        if (s.isEmpty()) {
            return null;
        }
        return switch (this) {
            case DATE -> dateTime(s);
            case STRING -> s;
            case INTEGER -> Long.parseLong(s);
            case FLOAT -> Double.parseDouble(s);
            case DECIMAL -> new BigDecimal(s);
        };
    }

    /**
     * An ISO timestamp, or a plain ISO date - which is midnight of that day, the
     * timestamp this type declares.
     */
    private static LocalDateTime dateTime(String s) {
        try {
            return LocalDateTime.parse(s);
        } catch (DateTimeParseException e) {
            return LocalDate.parse(s).atStartOfDay();
        }
    }
}
