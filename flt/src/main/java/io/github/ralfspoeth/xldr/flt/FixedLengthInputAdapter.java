package io.github.ralfspoeth.xldr.flt;

import io.github.ralfspoeth.xldr.ia.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

class FixedLengthInputAdapter implements InputAdapter {
    private final int linesPerRecord;

    FixedLengthInputAdapter(int linesPerRecord) {this.linesPerRecord = linesPerRecord;}


    @Override
    public Result parse(InputStream source, String recordSelector, Set<String> fieldSelectors) throws IOException {
        return null;
    }
}
