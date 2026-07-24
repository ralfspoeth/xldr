package io.github.ralfspoeth.xldr.spec.io;

import io.github.ralfspoeth.xldr.spec.MappingSpec;

import java.io.IOException;
import java.io.Reader;

public interface MappingSpecReader {
    MappingSpec readFrom(Reader source) throws IOException;
}
