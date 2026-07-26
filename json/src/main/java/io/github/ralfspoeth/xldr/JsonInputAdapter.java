package io.github.ralfspoeth.xldr;

import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.Result;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

public class JsonInputAdapter implements InputAdapter  {
    @Override
    public Result parse(InputStream source, String recordSelector, Set<String> fieldSelectors) throws IOException {
        return null;
    }
}
