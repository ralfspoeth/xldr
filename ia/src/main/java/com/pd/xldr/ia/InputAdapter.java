package com.pd.xldr.ia;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

public interface InputAdapter {

    Result parse(InputStream source, String recordSelector, Set<String> fieldSelectors) throws IOException;

}
