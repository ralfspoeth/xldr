package io.github.ralfspoeth.xldr.csv;

import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.InputSpec;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Properties;

public class CsvFileHandlerFactory implements InputAdapterFactory {

    private static final List<String> ACCEPT = List.of("text/csv");
    private final Properties csvFileHandlerProperties = new Properties();

    @Override
    public void setProperty(String property, String value) {
        csvFileHandlerProperties.setProperty(property, value);
    }

    @Override
    public boolean reads(String mimeType) {
        return ACCEPT.contains(mimeType);
    }

    @Override
    public InputAdapter createInputAdapter(InputSpec spec) {
        return new CsvFileHandler(
                csvFileHandlerProperties.getProperty("rowSeparator", System.lineSeparator()),
                csvFileHandlerProperties.getProperty("fieldSeparator", "\t"),
                csvFileHandlerProperties.containsKey("encoding")?
                        Charset.forName(csvFileHandlerProperties.getProperty("encoding")):Charset.defaultCharset(),
                Boolean.parseBoolean(csvFileHandlerProperties.getProperty("header", "true")),
                spec
        );
    }
}
