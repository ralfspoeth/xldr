package com.pd.xldr.xlsx;

import com.pd.xldr.ia.Result;
import com.pd.xldr.spec.InputSpec;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class XlsAdapter extends AbstractExcelAdapter {
    public XlsAdapter(InputSpec spec) {
        super(spec);
    }

    @Override
    public Result parse(InputStream source, String recordSelector, List<String> fieldSelectors) throws IOException {
        HSSFWorkbook wb = (HSSFWorkbook) open(source);
        return null;
    }
}
