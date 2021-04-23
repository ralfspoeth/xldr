package com.pd.xldr.ia;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public interface InputAdapter {

    Result parse(InputStream source, String recordSelector, List<String> fieldSelectors) throws IOException;

}
