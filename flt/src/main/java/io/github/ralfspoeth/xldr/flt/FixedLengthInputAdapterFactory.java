package io.github.ralfspoeth.xldr.flt;

import io.github.ralfspoeth.xldr.flt.FixedLengthInputAdapter.Bounds;
import io.github.ralfspoeth.xldr.flt.FixedLengthInputAdapter.Layout;
import io.github.ralfspoeth.xldr.ia.Formats;
import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.DataType;
import io.github.ralfspoeth.xldr.spec.Discriminator;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.spec.RecordSelectorSpec;
import io.github.ralfspoeth.xldr.spec.Selector;
import org.jspecify.annotations.Nullable;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static java.util.Optional.ofNullable;

/**
 * Creates fixed-length adapters.
 * <p>
 * Recognised properties: {@code linesPerRecord} (one by default; the lines of a
 * record are joined and the field bounds address the joined text),
 * {@code charset} (UTF-8 by default), and the shared conversion settings of
 * {@link Formats}.
 * <p>
 * A word about the charset, because fixed-length is where it bites hardest: the
 * bounds are counted in characters, so decoding with the wrong charset does not
 * merely garble a value, it moves every field after the first non-ASCII byte.
 * <p>
 * A field selector is a half-open character range {@code left:right} counted
 * from zero. The left bound may be omitted, in which case the field starts where
 * the previous one ended, so a layout can be written as a list of end positions.
 */
public class FixedLengthInputAdapterFactory implements InputAdapterFactory {

    @Override
    public boolean reads(String mimeType) {
        return "text/plain".equals(mimeType);
    }

    @Override
    public InputAdapter createInputAdapter(InputSpec spec) {
        var props = spec.properties();
        return new FixedLengthInputAdapter(
                Integer.parseInt(props.getOrDefault("linesPerRecord", "1")),
                // UTF-8 rather than Charset.defaultCharset(), as in the CSV
                // adapter: the same file has to load the same way whatever
                // -Dfile.encoding the JVM was started with
                ofNullable(props.get("charset")).map(Charset::forName).orElse(StandardCharsets.UTF_8),
                Formats.of(props),
                layouts(spec)
        );
    }

    /**
     * One layout per record selector, each with its own bounds.
     * <p>
     * Own bounds, and not one map shared: a field may omit its left bound and
     * continue where the previous one ended, so a layout is a running total.
     * Sharing one used to mean the second record selector's first field
     * continuing from the first selector's last, which shifted every column after
     * it - silently, with the load reporting rows the whole time.
     *
     * @throws IllegalArgumentException where the spec declares nothing to read,
     *                                  or names a record selector twice
     */
    private static Map<String, Layout> layouts(InputSpec spec) {
        if (spec.recordSelectors().isEmpty()) {
            throw new IllegalArgumentException(
                    "a fixed-length input needs a record selector to say where its fields sit");
        }
        Map<String, Layout> layouts = new LinkedHashMap<>();
        for (var rs : spec.recordSelectors()) {
            // nothing to point at in a fixed-length file, so a selector here is a
            // spec written for a format that has records to locate
            rs.refuseSelector("a fixed-length file has no place to point at");
            if (layouts.put(rs.name(), layout(rs)) != null) {
                throw new IllegalArgumentException("two record selectors are named '" + rs.name()
                        + "'; a mapping names one of them and could not say which");
            }
        }
        return layouts;
    }

    private static Layout layout(RecordSelectorSpec rs) {
        Map<String, Bounds> bounds = new LinkedHashMap<>();
        // the end of the field before, which is where one that omits its left
        // bound begins. Per record selector, so it cannot run across two
        int previousRight = 0;
        for (var fs : rs.fieldSelectors()) {
            var parsed = parse(fs.requireText("a fixed-length field is a character range, left:right"),
                    fs.dataType());
            var field = parsed.left() < 0 ? new Bounds(previousRight, parsed.right(), parsed.type()) : parsed;
            // no duplicate to worry about: RecordSelectorSpec refuses two field
            // selectors of one name, which matters more here than anywhere else -
            // a repeat would not merely shadow the earlier one, it would move
            // every field after it, the layout being a running total
            bounds.put(fs.name(), field);
            previousRight = field.right();
        }
        var discriminator = rs.discriminator();
        return new Layout(bounds, discriminator,
                discriminator == null ? null : at(discriminator, rs.name()));
    }

    /**
     * Where a discriminator looks, as a character range.
     * <p>
     * A {@link Selector.Nth} is refused for the same reason a field selector's is:
     * a fixed-length record is a stretch of characters with declared bounds rather
     * than components to count, so there is no n-th anything to test. And the left
     * bound may not be omitted here - a discriminator has no previous field to
     * continue from, so {@code ":2"} would be asking to start where nothing ended.
     */
    private static Bounds at(Discriminator discriminator, String recordSelector) {
        return switch (discriminator.at()) {
            case Selector.Text(var range) -> {
                var bounds = parse(range, DataType.TEXT);
                if (bounds.left() < 0) {
                    throw new IllegalArgumentException("record selector '" + recordSelector
                            + "' discriminates on '" + range + "', but a discriminator has no previous"
                            + " field to continue from. Say both bounds: \"0:2\"");
                }
                yield bounds;
            }
            case Selector.Nth nth -> throw new IllegalArgumentException("record selector '"
                    + recordSelector + "' discriminates on " + nth + ", but a fixed-length record has"
                    + " offsets and no components to count. Say the character range the type code sits"
                    + " in instead: { \"selector\": \"0:2\", \"equals\": ... }");
        };
    }

    /**
     * The type is optional in a spec; an absent one reads the field as text. A
     * left bound of {@code -1} means it was omitted, which the caller resolves
     * against whatever came before.
     */
    private static Bounds parse(String s, @Nullable DataType dt) {
        var m = BOUNDS.matcher(s);
        if (m.matches()) {
            return new Bounds(m.group(1).isBlank() ? -1 : Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    dt == null ? DataType.TEXT : dt);
        } else throw new IllegalArgumentException(s + " doesn't match the pattern " + BOUNDS.pattern());
    }

    private static final Pattern BOUNDS = Pattern.compile("(\\d*):(\\d+)");
}
