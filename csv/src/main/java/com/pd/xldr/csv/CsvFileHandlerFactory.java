package com.pd.xldr.csv;

import com.pd.xldr.ia.InputAdapter;
import com.pd.xldr.ia.InputAdapterFactory;
import com.pd.xldr.spec.InputSpec;

import java.util.List;

public class CsvFileHandlerFactory implements InputAdapterFactory {

    private static final List<String> ACCEPT = List.of("text/csv");

    @Override
    public boolean accepts(String mimeType) {
        return ACCEPT.contains(mimeType);
    }

    @Override
    public InputAdapter createInputAdapter(InputSpec spec) {
        return null;
    }
}
