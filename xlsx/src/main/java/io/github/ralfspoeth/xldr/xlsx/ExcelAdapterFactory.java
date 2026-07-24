package io.github.ralfspoeth.xldr.xlsx;

import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.InputSpec;

import java.util.Set;

/**
 * Creates Excel adapters. One adapter serves both {@code .xls} and {@code .xlsx}
 * - the format is detected from the stream at parse time.
 */
public class ExcelAdapterFactory implements InputAdapterFactory {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String XLS = "application/vnd.ms-excel";
    private static final Set<String> ACCEPT = Set.of(XLSX, XLS);

    @Override
    public void setProperty(String key, String value) {
    }

    @Override
    public boolean reads(String mimeType) {
        return ACCEPT.contains(mimeType);
    }

    @Override
    public InputAdapter createInputAdapter(InputSpec spec) {
        return new ExcelAdapter(spec);
    }
}
