package io.github.ralfspoeth.xldr.flt;

import io.github.ralfspoeth.xldr.ia.*;
import io.github.ralfspoeth.xldr.spec.DataType;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

class FixedLengthInputAdapter implements InputAdapter {
    record Bounds(int left, int right, DataType type) {}
    private final int linesPerRecord;
    private final Map<String, Bounds> bounds;

    FixedLengthInputAdapter(int linesPerRecord, Map<String, Bounds> bounds) {
        this.linesPerRecord = linesPerRecord;
        this.bounds = bounds;
    }


    @Override
    public Result parse(InputStream source, String recordSelector, Set<String> fieldSelectors) throws IOException {
        return null;
    }
}
