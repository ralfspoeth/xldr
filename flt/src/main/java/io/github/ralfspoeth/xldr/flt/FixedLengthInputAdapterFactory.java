package io.github.ralfspoeth.xldr.flt;

import io.github.ralfspoeth.xldr.flt.FixedLengthInputAdapter.Bounds;
import io.github.ralfspoeth.xldr.ia.Formats;
import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.DataType;
import io.github.ralfspoeth.xldr.spec.InputSpec;

import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Gatherer;

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toMap;

public class FixedLengthInputAdapterFactory implements InputAdapterFactory {

    private final Map<String, String> props = new HashMap<>();

    @Override
    public boolean reads(String mimeType) {
        return "text/plain".equals(mimeType);
    }

    @Override
    public void setProperty(String property, String value) {
        props.put(property, value);
    }

    @Override
    public InputAdapter createInputAdapter(InputSpec spec) {
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
