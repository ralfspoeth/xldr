package com.pd.xldr.csv;

import com.pd.xldr.ia.InputAdapter;
import com.pd.xldr.ia.InputAdapterFactory;
import com.pd.xldr.spec.InputSpec;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

public class CsvFileHandlerFactory implements InputAdapterFactory {

    private static final List<String> ACCEPT = List.of("text/csv");
    private final Properties csvFileHandlerProperties = new Properties();

    @Override
    public void setProperty(String property, String value) {
        csvFileHandlerProperties.setProperty(property, value);
    }

    @Override
    public boolean accepts(String mimeType) {
        return ACCEPT.contains(mimeType);
    }

    @Override
    public InputAdapter createInputAdapter(InputSpec spec) {
        return new CsvFileHandler(
                csvFileHandlerProperties.getProperty("rowSeparator", System.lineSeparator()),
                csvFileHandlerProperties.getProperty("fieldSeparator", "\t"),
                csvFileHandlerProperties.getProperty("textEnclosingQuotes", "\""),
                csvFileHandlerProperties.containsKey("encoding")?
                        Charset.forName(csvFileHandlerProperties.getProperty("encoding")):Charset.defaultCharset(),
                csvFileHandlerProperties.containsKey("locale")?
                        Locale.of(csvFileHandlerProperties.getProperty("locale")):Locale.getDefault(),
                Boolean.parseBoolean(csvFileHandlerProperties.getProperty("header", "true")),
                spec
        );
    }
}
