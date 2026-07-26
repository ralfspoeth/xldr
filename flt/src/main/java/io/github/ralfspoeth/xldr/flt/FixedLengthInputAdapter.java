package io.github.ralfspoeth.xldr.flt;

import io.github.ralfspoeth.xldr.ia.Field;
import io.github.ralfspoeth.xldr.ia.Formats;
import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.Result;
import io.github.ralfspoeth.xldr.ia.Row;
import io.github.ralfspoeth.xldr.spec.DataType;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Set;
import java.util.stream.Gatherer;

import static java.lang.Math.min;

class FixedLengthInputAdapter implements InputAdapter {
    record Bounds(int left, int right, DataType type) {}

    private final int linesPerRecord;
    private final Charset charset;
    private final Formats formats;
    private final Map<String, Bounds> bounds;

    FixedLengthInputAdapter(int linesPerRecord, Charset charset, Formats formats, Map<String, Bounds> bounds) {
        this.linesPerRecord = linesPerRecord;
        this.charset = charset;
        this.formats = formats;
        this.bounds = bounds;
    }


    @Override
    public Result parse(InputStream source, String recordSelector, Set<String> fieldSelectors) {
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
        public Object get(String name) {
            var bds = bounds.get(name);
            if (bds.left >= text.length()) return null;
            int right = min(bds.right, text.length());
            return formats.parse(bds.type, text.substring(bds.left, right));
        }
    }

    private class LineAccu {
        StringBuilder bldr = new StringBuilder();
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
