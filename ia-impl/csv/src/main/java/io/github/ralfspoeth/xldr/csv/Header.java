package io.github.ralfspoeth.xldr.csv;

import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * Whether the first row of the file names the columns.
 * <p>
 * A type rather than a {@code boolean} for the reason {@link EmptyLine} is one:
 * the setting has four accepted spellings and a default, and
 * {@link Boolean#parseBoolean} would quietly make {@link #ABSENT} of
 * {@code header=yes} - a headerless read of a file that has one, its first row
 * loaded as data and every column addressed by the wrong name. Refusing what it
 * does not understand is the whole job.
 * <p>
 * It lived in {@code ia} until 0.51, exported as part of the adapter SPI,
 * because {@code xldr validate} reasoned about a header without ever building an
 * adapter: a discriminator meant one thing over a headerless file and was
 * usually a mistake over a headed one. That command is gone, and with it the
 * rule and the only caller outside this module. What remains is a CSV setting
 * that CSV parses, so it sits with the format that means it.
 */
enum Header {

    /** the first row names the columns, and a selector is one of those names */
    PRESENT,

    /** there is no such row, and a selector is a 1-based column position */
    ABSENT;

    /** the property an input spec carries this in */
    static final String SETTING = "header";

    /**
     * {@code present} and {@code absent} say it the way the header itself would
     * be spoken of; {@code true} and {@code false} keep working.
     *
     * @param setting the {@value #SETTING} property, or {@code null} where the
     *                spec does not mention it
     * @throws IllegalArgumentException if the setting is none of the four
     */
    static Header of(@Nullable String setting) {
        if (setting == null || setting.isBlank()) {
            // present by default: a selector names a column, and a file without a
            // header has no names to offer
            return PRESENT;
        }
        return switch (setting.strip().toLowerCase(Locale.ROOT)) {
            case "true", "present" -> PRESENT;
            case "false", "absent" -> ABSENT;
            default -> throw new IllegalArgumentException(
                    SETTING + " must be 'present'/'true' or 'absent'/'false', was: " + setting);
        };
    }

    boolean present() {
        return this == PRESENT;
    }
}
