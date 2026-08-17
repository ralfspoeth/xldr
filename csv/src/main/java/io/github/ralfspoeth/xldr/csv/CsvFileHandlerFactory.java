package io.github.ralfspoeth.xldr.csv;

import io.github.ralfspoeth.xldr.ia.Formats;
import io.github.ralfspoeth.xldr.ia.Header;
import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import org.jspecify.annotations.Nullable;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Creates CSV adapters.
 * <p>
 * The defaults are RFC 4180's, so a spec that says nothing beyond
 * {@code text/csv} reads the format the MIME type is registered for: fields
 * separated by commas, double quotes around a field that needs them and doubled
 * to escape one, and no comment character, since the RFC has no such notion and
 * a {@code #} is therefore data. A record is a line unless a quoted field holds a
 * line break, so there is no row separator to configure - the RFC says CRLF and
 * this reads any of {@code \n}, {@code \r\n} or {@code \r}, which is the "be
 * liberal in what you accept" its own interoperability note asks for.
 * <p>
 * Two settings the RFC does not decide. It registers {@code header} as a MIME
 * parameter and then explicitly leaves the default to the implementor: here it is
 * {@code present}, because a selector names a column and a file without a header
 * has no names to offer. And a blank line is, by the ABNF, a record of one empty
 * field; {@code emptyLine} defaults to {@code skip} instead, which is what every
 * implementation does and what anyone editing a file by hand expects.
 * <p>
 * Recognised properties: {@code fieldSeparator} (a comma by default),
 * {@link Header} ({@code present}/{@code true} by default, or
 * {@code absent}/{@code false}), {@code quote} (a double quote by default,
 * empty to read quotes as ordinary characters), {@code comment} (none by
 * default), {@code emptyLine} ({@code skip} by default, or {@code stop}),
 * {@code fieldsFromHeader} (off by default; with it, a field the spec does not
 * declare is the column of that name), {@code charset} (UTF-8 by default), and
 * the shared conversion settings of {@link Formats}.
 */
public class CsvFileHandlerFactory implements InputAdapterFactory {

    /** the property by which a feed says that its header names its fields */
    private static final String FIELDS_FROM_HEADER = "fieldsFromHeader";

    private static final List<String> ACCEPT = List.of("text/csv");

    @Override
    public boolean reads(String mimeType) {
        return ACCEPT.contains(mimeType);
    }

    @Override
    public InputAdapter createInputAdapter(InputSpec spec) {
        var properties = spec.properties();
        return new CsvFileHandler(
                properties.getOrDefault("fieldSeparator", ","),
                // UTF-8 rather than Charset.defaultCharset(): the same file has to
                // load the same way whatever -Dfile.encoding the container was
                // started with, and UTF-8 reads every US-ASCII file the RFC
                // contemplates. A feed on some other encoding says so.
                properties.containsKey("charset")
                        ? Charset.forName(properties.get("charset"))
                        : StandardCharsets.UTF_8,
                Header.of(properties.get(Header.SETTING)).present(),
                character("quote", properties.get("quote"), '"'),
                character("comment", properties.get("comment"), null),
                EmptyLine.of(properties.get("emptyLine")),
                fieldsFromHeader(properties),
                Formats.of(properties),
                spec
        );
    }

    /**
     * Whether the header supplies a field the spec does not declare. Off by
     * default: a mapping naming a field no record selector declares is a
     * mistake worth reporting, and only a feed that says otherwise gives that
     * up. It says nothing where there is no header to take a name from.
     */
    private static boolean fieldsFromHeader(Map<String, String> properties) {
        var setting = properties.get(FIELDS_FROM_HEADER);
        if (setting == null) {
            return false;
        }
        if (!Header.of(properties.get(Header.SETTING)).present()) {
            throw new IllegalArgumentException(
                    FIELDS_FROM_HEADER + " needs a header to take the names from");
        }
        return switch (setting.strip().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(
                    FIELDS_FROM_HEADER + " must be 'true' or 'false', was: " + setting);
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
