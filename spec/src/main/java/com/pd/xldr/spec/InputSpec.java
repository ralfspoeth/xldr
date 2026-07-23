package com.pd.xldr.spec;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

import static java.util.Objects.requireNonNullElse;


/**
 * How some input file is turned into a stream of records and how the fields are
 * extracted from them.
 *
 * @param mimeType        selects the input adapter
 * @param sentinel        how the server knows a file has arrived complete. When
 *                        {@code null} the producer delivers atomically - stage
 *                        under an ignored name and rename, or move in from
 *                        outside {@code in/}. When set, it is a marker pattern in
 *                        {@code glob:} or {@code regex:} form (as understood by
 *                        {@code FileSystem.getPathMatcher}): the producer writes
 *                        the data file, then a marker file matching the pattern;
 *                        only the marker's arrival triggers the load. Examples:
 *                        {@code glob:*.{ok,ready,done}} loads the marker name
 *                        minus its last suffix; {@code regex:(x.*\.xml)\.done}
 *                        loads capturing group 1.
 * @param recordSelectors the record selectors of the input
 */
public record InputSpec(String mimeType, String sentinel, Collection<RecordSelectorSpec> recordSelectors)
        implements Serializable {

    public InputSpec {
        recordSelectors = List.copyOf(requireNonNullElse(recordSelectors, List.of()));
    }

    /**
     * Atomic-delivery input: no sentinel.
     */
    public InputSpec(String mimeType, Collection<RecordSelectorSpec> recordSelectors) {
        this(mimeType, null, recordSelectors);
    }
}
