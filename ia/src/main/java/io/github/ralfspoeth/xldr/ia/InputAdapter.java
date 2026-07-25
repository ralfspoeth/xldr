package io.github.ralfspoeth.xldr.ia;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * Parses one input file into records for the loader. An adapter is created by an
 * {@link InputAdapterFactory} for a given input spec; one adapter serves every
 * record selector of the file, its {@link #parse} method called once per mapping
 * with a freshly opened stream.
 */
public interface InputAdapter {

    /**
     * Parses {@code source} and yields the records of one record selector.
     *
     * @param source         the input, read once; the caller opens a fresh stream per call
     * @param recordSelector the name of the record selector to read
     * @param fieldSelectors the names of the fields to resolve
     * @return the fields and a lazy stream of the selected records
     * @throws IOException if the source cannot be read
     */
    Result parse(InputStream source, String recordSelector, Set<String> fieldSelectors) throws IOException;

}
