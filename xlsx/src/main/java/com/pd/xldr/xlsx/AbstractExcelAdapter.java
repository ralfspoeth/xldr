package com.pd.xldr.xlsx;

import com.pd.xldr.ia.InputAdapter;
import com.pd.xldr.spec.InputSpec;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.io.InputStream;

abstract class AbstractExcelAdapter implements InputAdapter {
    private final InputSpec spec;

    protected AbstractExcelAdapter(InputSpec spec) {
        this.spec = spec;
    }

    protected InputSpec spec() {
        return spec;
    }

    protected Workbook open(InputStream source) throws IOException {
        return WorkbookFactory.create(source);
    }
}
