package io.github.ralfspoeth.xldr.ia;

import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * Whether the first row of a tabular input names the columns.
 * <p>
 * Here rather than in the adapter that reads it, because the adapter is not the
 * only one who has to know. {@code validate} reasons about a spec without ever
 * creating an adapter - a record selector's discriminator means one thing over a
 * headerless file and is almost certainly a mistake over a headed one - and it
 * cannot depend on an adapter module to ask, since adapters arrive by
 * {@link java.util.ServiceLoader} and any of them may be absent. Two readings of
 * one setting is one too many: they drifted, and the spelling the documentation
 * recommends was the one the validator did not understand.
 *
 * @see Formats for the other settings shared this way
 */
public enum Header {

    /** the first row names the columns, and a selector is one of those names */
    PRESENT,

    /** there is no such row, and a selector is a 1-based column position */
    ABSENT;

    /** the property an input spec carries this in */
    public static final String SETTING = "header";

    /**
     * {@code present} and {@code absent} say it the way the header itself would
     * be spoken of; {@code true} and {@code false} keep working.
     * <p>
     * Anything else is refused rather than read as {@link #ABSENT}, which is what
     * {@link Boolean#parseBoolean} would quietly have made of {@code header=yes}
     * - a headerless read of a file that has one, its first row loaded as data
     * and every column addressed by the wrong name.
     *
     * @param setting the {@value #SETTING} property, or {@code null} where the
     *                spec does not mention it
     * @throws IllegalArgumentException if the setting is none of the four
     */
    public static Header of(@Nullable String setting) {
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

    public boolean present() {
        return this == PRESENT;
    }
}
