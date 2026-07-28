package io.github.ralfspoeth.xldr.csv;

import io.github.ralfspoeth.xldr.ia.Formats;
import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import org.jspecify.annotations.Nullable;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Locale;

/**
 * Creates CSV adapters.
 * <p>
 * Recognised properties: {@code fieldSeparator} (a tab by default),
 * {@code header} ({@code present}/{@code true} by default, or
 * {@code absent}/{@code false}), {@code quote} (a double quote by default,
 * empty to read quotes as ordinary characters), {@code comment} (none by
 * default), {@code emptyLine} ({@code skip} by default, or {@code stop}),
 * {@code charset}, and the shared conversion settings of {@link Formats}. A
 * record is a line unless a quoted field holds a line break, so there is no row
 * separator to configure.
 */
public class CsvFileHandlerFactory implements InputAdapterFactory {

    private static final List<String> ACCEPT = List.of("text/csv");

    @Override
    public boolean reads(String mimeType) {
        return ACCEPT.contains(mimeType);
    }

    @Override
    public InputAdapter createInputAdapter(InputSpec spec) {
        var properties = spec.properties();
        return new CsvFileHandler(
                properties.getOrDefault("fieldSeparator", "\t"),
                properties.containsKey("charset")
                        ? Charset.forName(properties.get("charset"))
                        : Charset.defaultCharset(),
                header(properties.get("header")),
                character("quote", properties.get("quote"), '"'),
                character("comment", properties.get("comment"), null),
                EmptyLine.of(properties.get("emptyLine")),
                Formats.of(properties),
                spec
        );
    }

    /**
     * Whether the first row names the columns. {@code present} and {@code absent}
     * say it the way the header itself would be spoken of; {@code true} and
     * {@code false} keep working.
     * <p>
     * A setting that is none of the four is refused rather than read as
     * {@code false}, which is what {@code Boolean.parseBoolean} would quietly
     * have made of {@code header = yes} - a headerless read of a file that has
     * one, and a column of nulls to show for it.
     */
    private static boolean header(@Nullable String setting) {
        if (setting == null || setting.isBlank()) {
            return true;
        }
        return switch (setting.strip().toLowerCase(Locale.ROOT)) {
            case "true", "present" -> true;
            case "false", "absent" -> false;
            default -> throw new IllegalArgumentException(
                    "header must be 'present'/'true' or 'absent'/'false', was: " + setting);
        };
    }

    /**
     * A single-character setting: the quote, or what begins a comment. An empty
     * setting switches the character off - no quoting at all, or no comments -
     * which is how a feed says that the character is data wherever it appears.
     *
     * @param fallback what the character is when the feed does not mention it
     */
    private static @Nullable Character character(String name, @Nullable String setting, @Nullable Character fallback) {
        if (setting == null) {
            return fallback;
        }
        if (setting.isEmpty()) {
            return null;
        }
        if (setting.length() != 1) {
            throw new IllegalArgumentException(name + " must be a single character, was: " + setting);
        }
        return setting.charAt(0);
    }
}
