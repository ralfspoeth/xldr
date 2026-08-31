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
     * <p>
     * What an implementation owes its caller, in full, is in the
     * {@linkplain io.github.ralfspoeth.xldr.ia package documentation}. The part
     * of it that lands here: a {@code recordSelector} the spec does not declare
     * is refused, and so is a {@code fieldSelectors} entry the record selector
     * has not got - unless the format names its own fields, as a headed CSV
     * does. {@link Result#fields()} holds one {@link Field} per requested name
     * and no others, each carrying the type the spec declared. A value the
     * record has not got is {@code null} rather than an omission or an
     * exception, and a failure part way through names the record it happened at.
     *
     * @param source         the input, read once; the caller opens a fresh stream per call
     * @param recordSelector the name of the record selector to read
     * @param fieldSelectors the names of the fields to resolve
     * @return the fields and a lazy stream of the selected records; the caller closes the stream
     * @throws IOException              if the source cannot be read
     * @throws IllegalArgumentException if the record selector or a field selector is not declared
     */
    Result parse(InputStream source, String recordSelector, Set<String> fieldSelectors) throws IOException;

}
