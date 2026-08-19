package io.github.ralfspoeth.xldr.flt;

import io.github.ralfspoeth.xldr.ia.*;
import io.github.ralfspoeth.xldr.spec.DataType;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Gatherer;

import static java.lang.Math.min;

class FixedLengthInputAdapter implements InputAdapter {
    record Bounds(int left, int right, DataType type) {}

    private final int linesPerRecord;
    private final Charset charset;
    private final Formats formats;
    /** the one record selector the spec declares, which every line belongs to */
    private final String declared;
    private final Map<String, Bounds> bounds;

    FixedLengthInputAdapter(int linesPerRecord, Charset charset, Formats formats,
                            String declared, Map<String, Bounds> bounds) {
        this.linesPerRecord = linesPerRecord;
        this.charset = charset;
        this.formats = formats;
        this.declared = declared;
        this.bounds = bounds;
    }

    /**
     * A name other than the declared one is refused, as it is by every other
     * adapter.
     * <p>
     * This one used to ignore the argument altogether and read the same fields
     * whatever it was handed, so a mapping naming a record selector that did not
     * exist loaded the whole file as though it did. Nothing cross-checks a
     * mapping against the record selectors the input declares, which leaves the
     * adapter as the only place a typo can surface - and a fixed-length load that
     * silently answers to the wrong name is one that will be reconciled against
     * the wrong table.
     */
    @Override
    public Result parse(InputStream source, String recordSelector, Set<String> fieldSelectors) {
        if (!declared.equals(recordSelector)) {
            throw new IllegalArgumentException("no record selector named " + recordSelector
                    + "; the input spec declares " + List.of(declared));
        }
        // and a field the record selector does not declare, which used to reach
        // FLRow.get and come back as a NullPointerException out of a map lookup
        var unknown = fieldSelectors.stream().filter(n -> !bounds.containsKey(n)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("record selector " + recordSelector
                    + " declares no field selector(s) " + unknown);
        }
        return new Result(
                bounds.entrySet().stream()
                        .filter(e -> fieldSelectors.contains(e.getKey()))
                        .map(e -> new Field(e.getKey(), e.getValue().type().clazz()))
                        .toList(),
                new BufferedReader(new InputStreamReader(source, charset))
                        .lines()
                        .gather(Gatherer.ofSequential(
                                LineAccu::new,
                                (l, s, ds) -> {
                                    if (l.add(s)) {
                                        return ds.push(new FLRow(l.getAndReset()));
                                    } else {
                                        return true;
                                    }
                                },
                                (l, ds) -> {
                                    if (l.count > 0 && !ds.isRejecting()) {
                                        throw new IllegalArgumentException("incomplete final record");
                                    }
                                }
                        ))
        );
    }


    private class FLRow implements Row {

        private final String text;

        private FLRow(String text) {this.text = text;}

        @Override
        public @Nullable Object get(String name) {
            var bds = bounds.get(name);
            if (bds.left >= text.length()) return null;
            int right = min(bds.right, text.length());
            return formats.parse(bds.type, text.substring(bds.left, right));
        }
    }

    private class LineAccu {
        final StringBuilder bldr = new StringBuilder();
        int count = 0;

        boolean add(String s) {
            bldr.append(s);
            return ++count == linesPerRecord;
        }

        String getAndReset() {
            var ret = bldr.toString();
            bldr.setLength(0);
            count = 0;
            return ret;
        }
    }
}
