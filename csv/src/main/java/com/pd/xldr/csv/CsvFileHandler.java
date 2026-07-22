package com.pd.xldr.csv;


import com.pd.xldr.ia.Field;
import com.pd.xldr.ia.InputAdapter;
import com.pd.xldr.ia.Result;
import com.pd.xldr.ia.Row;
import com.pd.xldr.spec.FieldSelectorSpec;
import com.pd.xldr.spec.InputSpec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.github.ralfspoeth.basix.fn.Predicates.in;

class CsvFileHandler implements InputAdapter {

    private final String rowSeparator;
    private final String fieldSeparator;
    private final String textEnclosingQuotes;
    private final Charset charset;
    private final Locale locale;
    private final boolean header;

    private final InputSpec inputSpec;

    CsvFileHandler(String rowSeparator, String fieldSeparator, String textEnclosingQuotes, Charset charset, Locale locale, boolean header, InputSpec spec) {
        this.rowSeparator = rowSeparator;
        this.fieldSeparator = fieldSeparator;
        this.textEnclosingQuotes = textEnclosingQuotes;
        this.charset = charset;
        this.locale = locale;
        this.header = header;
        this.inputSpec = spec;
    }

    private record Line(String[] values, ToIntFunction<String> index) implements Row {
        @Override
        public String get(String name) {
            var i = index.applyAsInt(name);
            return i >= 0 && i < values.length ? values[i] : null;
        }
    }


    @Override
    public Result parse(InputStream source, String recordSelector, Set<String> fieldSelectors) throws IOException {
        var fields = fields(recordSelector, fieldSelectors);
        var selected = inputSpec.recordSelectors()
                .stream()
                .anyMatch(rss -> rss.name().equals(recordSelector));
        if (!selected) {
            return new Result(fields, Stream.empty());
        }
        var content = new String(source.readAllBytes(), charset);
        var lines = content.split(rowSeparator, -1);
        if (lines.length == 0) {
            return new Result(fields, Stream.empty());
        }
        var fieldSep = Pattern.quote(fieldSeparator);
        // with a header the first line maps names to positions; without one the
        // field name itself is the 1-based column number ("1" -> 0, "2" -> 1, ...)
        var index = header ? indexOfHeader(lines[0]) : positionalIndex();
        var rows = Arrays.stream(lines)
                .skip(header ? 1 : 0)
                .filter(line -> !line.isEmpty())
                .map(line -> line.split(fieldSep, -1))
                .map(values -> (Row) new Line(values, index));
        return new Result(fields, rows);
    }

    private List<Field> fields(String recordSelector, Set<String> fieldSelectors) {
        return inputSpec.recordSelectors()
                .stream()
                .filter(rs -> rs.name().equals(recordSelector))
                .flatMap(rs -> rs.fieldSelectors().stream())
                .filter(in(fieldSelectors, FieldSelectorSpec::name))
                .map(fs -> new Field(fs.name(), String.class))
                .toList();
    }

    private ToIntFunction<String> indexOfHeader(String headerLine) {
        Map<String, Integer> index = new HashMap<>();
        var headers = headerLine.split(Pattern.quote(fieldSeparator), -1);
        for (int i = 0; i < headers.length; i++) {
            index.putIfAbsent(headers[i], i);
        }
        return name -> index.getOrDefault(name, -1);
    }

    private static ToIntFunction<String> positionalIndex() {
        return name -> {
            try {
                return Integer.parseInt(name.trim()) - 1;
            } catch (NumberFormatException e) {
                return -1;
            }
        };
    }
}
