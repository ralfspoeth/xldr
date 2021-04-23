package com.pd.xldr.xlsx;

import com.pd.xldr.ia.InputAdapter;
import com.pd.xldr.ia.InputAdapterFactory;
import com.pd.xldr.spec.InputSpec;

import java.util.List;

public class ExcelAdapterFactory implements InputAdapterFactory {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String XLS = "application/vnd.ms-excel";
    private static final List<String> ACCEPT = List.of(XLSX, XLS);

    @Override
    public boolean accepts(String mimeType) {
        return ACCEPT.contains(mimeType);
    }

    @Override
    public InputAdapter createInputAdapter(InputSpec spec) {
        return switch (spec.mimeType()) {
            case XLS -> new XlsAdapter(spec);
            case XLSX -> new XlsxAdapter(spec);
            default -> throw new IllegalArgumentException("Unsupported type: " + spec.mimeType());
        };
    }
}
