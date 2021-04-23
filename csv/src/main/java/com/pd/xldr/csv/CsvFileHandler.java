package com.pd.xldr.csv;


import com.pd.xldr.ia.InputAdapter;
import com.pd.xldr.ia.Result;

import java.io.InputStream;
import java.util.List;

public class CsvFileHandler implements InputAdapter {

    @Override
    public Result parse(InputStream source, String recordSelector, List<String> fieldSelectors) {
        return null;
    }
}
