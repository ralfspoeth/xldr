package io.github.ralfspoeth.xldr.flt;

import io.github.ralfspoeth.xldr.flt.FixedLengthInputAdapter.Bounds;
import io.github.ralfspoeth.xldr.ia.Formats;
import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.DataType;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.spec.RecordSelectorSpec;
import org.jspecify.annotations.Nullable;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Gatherer;

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toMap;

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
        var record = only(spec);
        // nothing to point at in a fixed-length file, so a selector here is a
        // spec written for a format that has records to locate
        record.refuseSelector("a fixed-length file has no place to point at");
        return new FixedLengthInputAdapter(
                Integer.parseInt(props.getOrDefault("linesPerRecord", "1")),
                // UTF-8 rather than Charset.defaultCharset(), as in the CSV
                // adapter: the same file has to load the same way whatever
                // -Dfile.encoding the JVM was started with
                ofNullable(props.get("charset")).map(Charset::forName).orElse(StandardCharsets.UTF_8),
                Formats.of(props),
                record.name(),
                record.fieldSelectors()
                        .stream()
                        .map(fs -> Map.entry(fs.name(), parse(
                                fs.requireText("a fixed-length field is a character range, left:right"),
                                fs.dataType())))
                        .gather(Gatherer.<Map.Entry<String, Bounds>, AtomicInteger, Map.Entry<String, Bounds>>ofSequential(
                                AtomicInteger::new,
                                (left, e, ds) ->
                                        ds.push(Map.entry(
                                                e.getKey(),
                                                checkLeft(left, e.getValue())
                                        ))))
                        .collect(toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    /**
     * The one record selector a fixed-length input has.
     * <p>
     * Every line of such a file has the same layout, so there is nothing for a
     * second record selector to select and no way to tell one kind of line from
     * another - that is what a {@code discriminator} would be for, and this
     * adapter has none yet. A second one used to be merged into the first: the
     * fields of both went into one map keyed by name, so two selectors sharing a
     * field name kept whichever the stream happened to yield last, and the rule
     * that an omitted left bound continues from the previous field ran <em>across
     * the two</em> in that same order. A layout written as a list of end positions
     * therefore came out anchored to a field of the other record selector. None of
     * that announced itself; the load reported rows and the columns were shifted.
     *
     * @throws IllegalArgumentException naming both, so that a spec written for
     *                                  another format is told which of the two it
     *                                  has to lose
     */
    private static RecordSelectorSpec only(InputSpec spec) {
        var selectors = spec.recordSelectors();
        if (selectors.size() == 1) {
            return selectors.iterator().next();
        }
        if (selectors.isEmpty()) {
            throw new IllegalArgumentException(
                    "a fixed-length input needs a record selector to say where its fields sit");
        }
        throw new IllegalArgumentException("a fixed-length input has one record selector and this"
                + " one declares " + selectors.size() + ": "
                + selectors.stream().map(RecordSelectorSpec::name).toList()
                + ". Every line of the file has the same layout, so there is nothing to tell them"
                + " apart by; a file that mixes record types needs an adapter that can discriminate");
    }

    /**
     * The type is optional in a spec; an absent one reads the field as text.
     */
    private static Bounds parse(String s, @Nullable DataType dt) {
        var m = BOUNDS.matcher(s);
        if (m.matches()) {
            return new Bounds(m.group(1).isBlank() ? -1 : Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    dt == null ? DataType.TEXT : dt);
        } else throw new IllegalArgumentException(s + " doesn't match the pattern " + BOUNDS.pattern());
    }

    private static Bounds checkLeft(AtomicInteger oldLeft, Bounds parsedBounds) {
        int old = oldLeft.getAndSet(parsedBounds.right());
        return parsedBounds.left() < 0 ?
                new Bounds(old, parsedBounds.right(), parsedBounds.type()) :
                parsedBounds;

    }

    private static final Pattern BOUNDS = Pattern.compile("(\\d*):(\\d+)");
}
