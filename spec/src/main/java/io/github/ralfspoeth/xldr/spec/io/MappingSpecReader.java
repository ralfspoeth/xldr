package io.github.ralfspoeth.xldr.spec.io;

import io.github.ralfspoeth.xldr.spec.MappingSpec;

import java.io.IOException;
import java.io.Reader;

/**
 * Reads a {@link MappingSpec} from text in one format. Implementations are
 * chosen by the spec file's extension, one per supported format (JSON, XML).
 */
public interface MappingSpecReader {

    /**
     * Reads a mapping spec from {@code source}.
     *
     * @throws IOException if the source cannot be read
     */
    MappingSpec readFrom(Reader source) throws IOException;
}
