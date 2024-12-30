package com.pd.xldr.csv;


import com.pd.xldr.ia.InputAdapter;
import com.pd.xldr.ia.Result;
import com.pd.xldr.spec.InputSpec;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Locale;

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

    @Override
    public Result parse(InputStream source, String recordSelector, List<String> fieldSelectors) {
        return null;
    }
}
