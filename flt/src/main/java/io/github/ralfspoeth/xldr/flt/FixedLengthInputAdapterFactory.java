package io.github.ralfspoeth.xldr.flt;

import io.github.ralfspoeth.xldr.flt.FixedLengthInputAdapter.Bounds;
import io.github.ralfspoeth.xldr.ia.Formats;
import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.DataType;
import io.github.ralfspoeth.xldr.spec.InputSpec;

import java.nio.charset.Charset;
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
 * {@code charset}, and the shared conversion settings of {@link Formats}.
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
                ofNullable(props.get("charset")).map(Charset::forName).orElse(Charset.defaultCharset()),
                Formats.of(props),
                spec.recordSelectors()
                        .stream()
                        .flatMap(rs -> rs.fieldSelectors().stream())
                        .map(fs -> Map.entry(fs.name(), parse(fs.selector(), fs.dataType())))
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
     * The type is optional in a spec; an absent one reads the field as text.
     */
    private static Bounds parse(String s, DataType dt) {
        var m = BOUNDS.matcher(s);
        if (m.matches()) {
            return new Bounds(m.group(1).isBlank() ? -1 : Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    dt == null ? DataType.STRING : dt);
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
