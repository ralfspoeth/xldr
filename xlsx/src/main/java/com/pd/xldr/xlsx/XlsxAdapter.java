package com.pd.xldr.xlsx;

import com.pd.xldr.ia.Result;
import com.pd.xldr.spec.InputSpec;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class XlsxAdapter extends AbstractExcelAdapter {
    public XlsxAdapter(InputSpec spec) {
        super(spec);
    }

    @Override
    public Result parse(InputStream source, String recordSelector, List<String> fieldSelectors) throws IOException {
        XSSFWorkbook wb = (XSSFWorkbook) open(source);
        return null;
    }
}
