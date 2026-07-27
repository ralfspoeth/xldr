package io.github.ralfspoeth.xldr.csv;

import io.github.ralfspoeth.xldr.ia.Formats;
import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.InputSpec;

import java.nio.charset.Charset;
import java.util.List;

/**
 * Creates CSV adapters.
 * <p>
 * Recognised properties: {@code fieldSeparator} (a tab by default),
 * {@code header} (whether the first row names the columns, true by default),
 * {@code charset}, and the shared conversion settings of {@link Formats}. A
 * record is a line, so there is no row separator to configure.
 */
public class CsvFileHandlerFactory implements InputAdapterFactory {

    private static final List<String> ACCEPT = List.of("text/csv");

    @Override
    public boolean reads(String mimeType) {
        return ACCEPT.contains(mimeType);
    }

    @Override
    public InputAdapter createInputAdapter(InputSpec spec) {
        var properties = spec.properties();
        return new CsvFileHandler(
                properties.getOrDefault("fieldSeparator", "\t"),
                properties.containsKey("charset")
                        ? Charset.forName(properties.get("charset"))
                        : Charset.defaultCharset(),
                Boolean.parseBoolean(properties.getOrDefault("header", "true")),
                Formats.of(properties),
                spec
        );
    }
}
