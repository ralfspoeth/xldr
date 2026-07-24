package io.github.ralfspoeth.xldr.csv;

import io.github.ralfspoeth.xldr.ia.Field;
import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.Result;
import io.github.ralfspoeth.xldr.ia.Row;
import io.github.ralfspoeth.xldr.spec.FieldSelectorSpec;
import io.github.ralfspoeth.xldr.spec.InputSpec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.github.ralfspoeth.basix.fn.Predicates.in;

class CsvFileHandler implements InputAdapter {

    private final String rowSeparator;
    private final String fieldSeparator;
    private final Charset charset;
    private final boolean header;

    private final InputSpec inputSpec;

    CsvFileHandler(String rowSeparator, String fieldSeparator, Charset charset, boolean header, InputSpec spec) {
        this.rowSeparator = rowSeparator;
        this.fieldSeparator = fieldSeparator;
        this.charset = charset;
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
        var record = inputSpec.recordSelectors()
                .stream()
                .filter(rss -> rss.name().equals(recordSelector))
                .findFirst()
                .orElse(null);
        if (record == null) {
            return new Result(List.of(), Stream.empty());
        }
        var fields = fields(recordSelector, fieldSelectors);
        var content = new String(source.readAllBytes(), charset);
        var lines = content.split(rowSeparator, -1);
        if (lines.length == 0) {
            return new Result(fields, Stream.empty());
        }
        var fieldSep = Pattern.quote(fieldSeparator);
        var index = header ? indexOfHeader(lines[0]) : positionalIndex();
        // the record selector's selector, if set, is the value the first column
        // must equal for a line to belong to this record type - the discriminator
        // of an interleaved, headerless file; absent means every line matches
        var discriminator = record.selector();
        var rows = Arrays.stream(lines)
                .skip(header ? 1 : 0)
                .filter(line -> !line.isEmpty())
                .map(line -> line.split(fieldSep, -1))
                .filter(values -> matches(discriminator, values))
                .map(values -> (Row) new Line(values, index));
        return new Result(fields, rows);
    }

    private static boolean matches(String discriminator, String[] values) {
        return discriminator == null || discriminator.isBlank()
                || (values.length > 0 && discriminator.strip().equals(values[0].strip()));
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
                return Integer.parseInt(name.strip()) - 1;
            } catch (NumberFormatException e) {
                return -1;
            }
        };
    }
}
