package io.github.ralfspoeth.xldr.spec.io;

import io.github.ralfspoeth.xldr.spec.MappingSpec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Reads a {@link MappingSpec} from text in one format. Implementations are
 * chosen by the spec file's extension, one per supported format (JSON, XML).
 */
public interface MappingSpecReader {

    /**
     * Discriminator for the type of spec file
     * @param path the path to the spec file
     * @return whether this reader accepts the file type
     */
    boolean accepts(Path path);

    /**
     * Reads a mapping spec from {@code source}.
     *
     * @throws IOException if the source cannot be read
     */
    MappingSpec readFrom(InputStream source) throws IOException;
}
