package io.github.ralfspoeth.xldr.csv;

import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * What an empty line means in a feed.
 * <p>
 * A comment line is not an empty line, whatever is left of it once the comment
 * is taken off: a banner in the middle of a file says nothing about where the
 * data ends.
 * <p>
 * {@link #SKIP} by default, and that is the one place these defaults leave
 * RFC 4180. By its ABNF a blank line is a record of one empty field, since a
 * non-escaped field may be empty and a record may hold just the one. No
 * implementation reads it that way, and a spec would have to declare a column
 * to receive the emptiness. Skipping is what a person editing a file by hand
 * means by a blank line, so skipping is what it means here.
 */
enum EmptyLine {

    /** nothing at all - the data continues on the next line */
    SKIP,

    /** the end of the data, whatever follows it */
    STOP;

    /**
     * @param setting the {@code emptyLine} property, or {@code null} for the default
     * @return what it names
     * @throws IllegalArgumentException if it names neither
     */
    static EmptyLine of(@Nullable String setting) {
        if (setting == null || setting.isBlank()) {
            return SKIP;
        }
        return switch (setting.strip().toLowerCase(Locale.ROOT)) {
            case "skip" -> SKIP;
            case "stop" -> STOP;
            default -> throw new IllegalArgumentException(
                    "emptyLine must be 'skip' or 'stop', was: " + setting);
        };
    }
}
