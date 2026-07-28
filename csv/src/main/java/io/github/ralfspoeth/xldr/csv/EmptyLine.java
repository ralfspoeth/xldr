package io.github.ralfspoeth.xldr.csv;

import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * What an empty line means in a feed.
 * <p>
 * A comment line is not an empty line, whatever is left of it once the comment
 * is taken off: a banner in the middle of a file says nothing about where the
 * data ends.
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
