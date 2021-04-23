package com.pd.xldr.spec.io;

import com.pd.xldr.spec.MappingSpec;

import java.io.IOException;
import java.io.Reader;

public interface MappingSpecReader {
    MappingSpec readFrom(Reader source) throws IOException;
}
