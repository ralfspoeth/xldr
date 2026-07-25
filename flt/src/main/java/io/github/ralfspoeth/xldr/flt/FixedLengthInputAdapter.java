package io.github.ralfspoeth.xldr.flt;

import io.github.ralfspoeth.xldr.ia.Field;
import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.Result;
import io.github.ralfspoeth.xldr.ia.Row;
import io.github.ralfspoeth.xldr.spec.DataType;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.stream.Gatherer;

class FixedLengthInputAdapter implements InputAdapter {
    record Bounds(int left, int right, DataType type) {}

    private final int linesPerRecord;
    private final Charset charset;
    private final Map<String, Bounds> bounds;

    FixedLengthInputAdapter(int linesPerRecord, Charset charset, Map<String, Bounds> bounds) {
        this.linesPerRecord = linesPerRecord;
        this.charset = charset;
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
                                (l, s, ds) ->
                                        l.add(s) && ds.push(new FLRow(l.getAndReset()))
                        ))
        );
    }


    private class FLRow implements Row {

        private final String text;

        private FLRow(String text) {this.text = text;}

        @Override
        public Object get(String name) {
            var bds = bounds.get(name);
            var part = text.substring(bds.left, bds.right);
            return switch (bds.type) {
                case DATE -> LocalDate.parse(part);
                case FLOAT -> Double.parseDouble(part);
                case DECIMAL -> new BigDecimal(part);
                case INTEGER -> Long.parseLong(part);
                case STRING -> part;
            };
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
