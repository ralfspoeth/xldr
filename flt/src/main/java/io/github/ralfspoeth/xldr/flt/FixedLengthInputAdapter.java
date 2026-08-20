package io.github.ralfspoeth.xldr.flt;

import io.github.ralfspoeth.xldr.ia.*;
import io.github.ralfspoeth.xldr.spec.DataType;
import io.github.ralfspoeth.xldr.spec.Discriminator;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Gatherer;
import java.util.stream.Stream;

import static java.lang.Math.min;

class FixedLengthInputAdapter implements InputAdapter {

    /**
     * A half-open character range over the record, and what the text in it means.
     */
    record Bounds(int left, int right, DataType type) {

        /**
         * The characters this range covers, or {@code null} where the record stops
         * short of it. A short line is a fact about that line rather than about
         * the layout, so it reads as an absent value.
         */
        @Nullable
        String of(String record) {
            if (left >= record.length()) {
                return null;
            }
            return record.substring(left, min(right, record.length()));
        }
    }

    /**
     * One record selector's reading of the file: where its fields sit, and which
     * records are its own.
     * <p>
     * Every record selector has its own bounds rather than sharing one map. That
     * is not tidiness: a field may omit its left bound and continue where the
     * previous one ended, so a layout is a running total, and two layouts sharing
     * one would leave the second anchored to a field of the first.
     *
     * @param discriminator which records belong here, or {@code null} where every
     *                      record does
     * @param at            where the discriminator looks; non-null exactly when
     *                      the discriminator is
     */
    record Layout(Map<String, Bounds> bounds, @Nullable Discriminator discriminator, @Nullable Bounds at) {

        Layout {
            if ((discriminator == null) != (at == null)) {
                throw new IllegalArgumentException("a discriminator and the range it looks at go together");
            }
            // not Map.copyOf: the fields are read in the order the spec declares
            // them, and that map has no order
            bounds = Collections.unmodifiableMap(new LinkedHashMap<>(bounds));
        }

        /**
         * Whether this record is one of this record selector's.
         * <p>
         * A record too short to hold the discriminating range answers null, which
         * matches nothing - a record that could not be asked is not one that
         * answered.
         */
        boolean keeps(String record) {
            return discriminator == null || discriminator.accepts(at.of(record));
        }
    }

    private final int linesPerRecord;
    private final Charset charset;
    private final Formats formats;
    /** the record selectors the spec declares, in the order it declares them */
    private final Map<String, Layout> layouts;

    FixedLengthInputAdapter(int linesPerRecord, Charset charset, Formats formats, Map<String, Layout> layouts) {
        this.linesPerRecord = linesPerRecord;
        this.charset = charset;
        this.formats = formats;
        this.layouts = Collections.unmodifiableMap(new LinkedHashMap<>(layouts));
    }

    /**
     * The records of one kind, read through that kind's own layout.
     * <p>
     * A name the spec does not declare is refused, as it is by every other
     * adapter. This one used to ignore the argument altogether and read the same
     * fields whatever it was handed, so a mapping naming a record selector that
     * did not exist loaded the whole file as though it did. Nothing cross-checks
     * a mapping against the record selectors the input declares, which leaves the
     * adapter as the only place a typo can surface - and a fixed-length load that
     * silently answers to the wrong name is one that will be reconciled against
     * the wrong table.
     */
    @Override
    public Result parse(InputStream source, String recordSelector, Set<String> fieldSelectors) {
        var layout = layouts.get(recordSelector);
        if (layout == null) {
            throw new IllegalArgumentException("no record selector named " + recordSelector
                    + "; the input spec declares " + layouts.keySet());
        }
        // and a field the record selector does not declare, which used to reach
        // FLRow.get and come back as a NullPointerException out of a map lookup
        var unknown = fieldSelectors.stream().filter(n -> !layout.bounds().containsKey(n)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("record selector " + recordSelector
                    + " declares no field selector(s) " + unknown);
        }
        return new Result(
                layout.bounds().entrySet().stream()
                        .filter(e -> fieldSelectors.contains(e.getKey()))
                        .map(e -> new Field(e.getKey(), e.getValue().type().clazz()))
                        .toList(),
                records(source)
                        .filter(layout::keeps)
                        .map(text -> (Row) new FLRow(layout, text))
        );
    }

    /**
     * The records of the file, each the text of however many lines one takes.
     * <p>
     * Every record selector reads the same records and keeps its own; the file is
     * read once per record mapping, by the loader, rather than once per kind.
     */
    private Stream<String> records(InputStream source) {
        return new BufferedReader(new InputStreamReader(source, charset))
                .lines()
                .gather(Gatherer.ofSequential(
                        LineAccu::new,
                        (l, s, ds) -> {
                            if (l.add(s)) {
                                return ds.push(l.getAndReset());
                            } else {
                                return true;
                            }
                        },
                        (l, ds) -> {
                            if (l.count > 0 && !ds.isRejecting()) {
                                throw new IllegalArgumentException("incomplete final record");
                            }
                        }
                ));
    }

    private class FLRow implements Row {

        private final Layout layout;
        private final String text;

        private FLRow(Layout layout, String text) {
            this.layout = layout;
            this.text = text;
        }

        @Override
        public @Nullable Object get(String name) {
            var bds = layout.bounds().get(name);
            if (bds == null) {
                return null;
            }
            return formats.parse(bds.type(), bds.of(text));
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
