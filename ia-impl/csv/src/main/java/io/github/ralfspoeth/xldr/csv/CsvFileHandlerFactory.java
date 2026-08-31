package io.github.ralfspoeth.xldr.csv;

import io.github.ralfspoeth.xldr.ia.Formats;
import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import org.jspecify.annotations.Nullable;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Creates adapters for the two registered separated-value media types,
 * {@code text/csv} and {@code text/tab-separated-values}.
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
 * A field says where it sits with a {@code selector}, naming a column, or with an
 * {@code nth}, counting the fields of the line from one. A name wants a header to
 * name something in; a count works either way, and is the only way to address a
 * headerless file.
 * Which records are of a kind is a {@code discriminator} on the record selector -
 * a field and a value or a pattern - and a record selector with none takes every
 * line, which is what a file holding one kind of record looks like.
 * <p>
 * Recognised properties: {@code fieldSeparator} (a comma by default),
 * {@link Header} ({@code present}/{@code true} by default, or
 * {@code absent}/{@code false}), {@code quote} (a double quote by default,
 * empty to read quotes as ordinary characters), {@code comment} (none by
 * default), {@code emptyLine} ({@code skip} by default, or {@code stop}),
 * {@code fieldsFromHeader} (off by default; with it, a field the spec does not
 * declare is the column of that name), {@code charset} (UTF-8 by default), and
 * the shared conversion settings of {@link Formats}.
 * <p>
 * <strong>{@code text/tab-separated-values} settles three of those by itself.</strong>
 * Its registration is shorter than RFC 4180 and stricter: fields are separated by
 * a tab, a field <em>cannot contain</em> a tab and therefore needs no quoting
 * mechanism at all, and the first line is the field names rather than optionally
 * so. A spec naming that type need say none of the three, and a spec contradicting
 * one of them is refused rather than quietly obeyed - the type is a claim about
 * what the file is, and a spec that disagrees with it has one of the two wrong.
 * A file that is tab-separated without being TSV - quoted fields, or no header -
 * is {@code text/csv} with {@code "fieldSeparator": "\t"}, which is what that type
 * is for. Everything the registration does not mention is still open: a comment
 * character, {@code emptyLine}, {@code charset} and the {@link Formats} settings.
 */
public class CsvFileHandlerFactory implements InputAdapterFactory {

    /** the property by which a feed says that its header names its fields */
    private static final String FIELDS_FROM_HEADER = "fieldsFromHeader";

    private static final String FIELD_SEPARATOR = "fieldSeparator";
    private static final String QUOTE = "quote";

    static final String CSV = "text/csv";
    static final String TSV = "text/tab-separated-values";

    private static final List<String> ACCEPT = List.of(CSV, TSV);

    private static final String TAB = "\t";

    @Override
    public boolean reads(String mimeType) {
        return ACCEPT.contains(mimeType);
    }

    @Override
    public InputAdapter createInputAdapter(InputSpec spec) {
        var properties = spec.properties();
        boolean tsv = TSV.equals(spec.mimeType());
        if (tsv) {
            refuseWhatTheTypeHasSettled(properties);
        }
        return new CsvFileHandler(
                properties.getOrDefault(FIELD_SEPARATOR, tsv ? TAB : ","),
                // UTF-8 rather than Charset.defaultCharset(): the same file has to
                // load the same way whatever -Dfile.encoding the container was
                // started with, and UTF-8 reads every US-ASCII file the RFC
                // contemplates. A feed on some other encoding says so.
                properties.containsKey("charset")
                        ? Charset.forName(properties.get("charset"))
                        : StandardCharsets.UTF_8,
                Header.of(properties.get(Header.SETTING)).present(),
                // no quoting for TSV, a field there being unable to hold a tab
                character(QUOTE, properties.get(QUOTE), tsv ? null : '"'),
                character("comment", properties.get("comment"), null),
                EmptyLine.of(properties.get("emptyLine")),
                fieldsFromHeader(properties),
                Formats.of(properties),
                spec
        );
    }

    /**
     * The three things {@value #TSV} decides for itself. A spec may repeat any of
     * them - saying a tab separator for a TSV file is redundant, not wrong - but
     * may not contradict one.
     * <p>
     * Refusing rather than obeying, because the media type is a claim about what
     * the file is: a spec that names TSV and then asks for semicolons describes
     * two different files, and whichever of the two the adapter picked, it would
     * be guessing. Refusing at adapter creation puts that in front of the author
     * rather than in a table of nulls.
     */
    private static void refuseWhatTheTypeHasSettled(Map<String, String> properties) {
        settled(FIELD_SEPARATOR, properties.get(FIELD_SEPARATOR), TAB::equals,
                "a tab separates fields");
        settled(QUOTE, properties.get(QUOTE), String::isEmpty,
                "there is no quoting, a field being unable to contain a tab");
        settled(Header.SETTING, properties.get(Header.SETTING), s -> Header.of(s).present(),
                "the first line is the field names");
    }

    private static void settled(String name, @Nullable String setting,
                                Predicate<String> agrees, String what) {
        if (setting != null && !agrees.test(setting)) {
            throw new IllegalArgumentException(TSV + " settles " + name + ": " + what
                    + ", but the spec says '" + setting + "'. A file that is tab-separated"
                    + " without being TSV - quoted fields, or no header - is " + CSV
                    + " with a fieldSeparator of \\t.");
        }
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
