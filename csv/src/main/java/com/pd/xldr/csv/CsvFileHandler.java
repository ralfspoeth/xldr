package com.pd.xldr.csv;


import com.pd.xldr.ia.Field;
import com.pd.xldr.ia.InputAdapter;
import com.pd.xldr.ia.Result;
import com.pd.xldr.ia.Row;
import com.pd.xldr.spec.FieldSelectorSpec;
import com.pd.xldr.spec.InputSpec;
import io.github.ralfspoeth.basix.fn.Indexed;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;

import static io.github.ralfspoeth.basix.fn.Functions.indexed;
import static io.github.ralfspoeth.basix.fn.Predicates.in;
import static java.util.stream.Collectors.toMap;

class CsvFileHandler implements InputAdapter {

    private final String rowSeparator;
    private final String fieldSeparator;
    private final String textEnclosingQuotes;
    private final Charset charset;
    private final Locale locale;

    private final InputSpec inputSpec;

    CsvFileHandler(String rowSeparator, String fieldSeparator, String textEnclosingQuotes, Charset charset, Locale locale, InputSpec spec) {
        this.rowSeparator = rowSeparator;
        this.fieldSeparator = fieldSeparator;
        this.textEnclosingQuotes = textEnclosingQuotes;
        this.charset = charset;
        this.locale = locale;
        this.inputSpec = spec;
    }

    private record Line(String[] values, ToIntFunction<String> index) implements Row {
        @Override
        public String get(String name) {
            return values[index.applyAsInt(name)];
        }
    }


    @Override
    public Result parse(InputStream source, String recordSelector, Set<String> fieldSelectors) throws IOException {
        return inputSpec.recordSelectors()
                .stream()
                .filter(rss -> rss.name().equals(recordSelector))
                .findAny()
                .map(s -> {
                    try (var rdr = new InputStreamReader(source, charset)) {
                        var all = rdr.readAllAsString();
                        var lines = all.split(rowSeparator);
                        return new Result(
                                null, Arrays.stream(lines).skip(1)
                                .map(fieldSeparator::split)
                                .map(values -> new Line(values, indexOfHeader(lines)))
                        );
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }).orElseGet(() -> new Result(fields(recordSelector, fieldSelectors), Stream.empty()));
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

    private ToIntFunction<String> indexOfHeader(String[] lines) {
        if (lines.length > 0) {
            return Arrays
                    // stream header names
                    .stream(lines[0].split(fieldSeparator))
                    // add index starting with 0
                    .map(indexed(0))
                    // into a map from header names to index
                    .collect(
                            toMap(Indexed::value, Indexed::index)
                    )::get; // turn into function
        } else {
            return name -> -1;
        }
    }
}
