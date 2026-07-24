package io.github.ralfspoeth.xldr.spec;

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
 * @param accepts         which data files in {@code in/} this feed claims,
 *                        matched against the file <em>name</em> in the same
 *                        {@code glob:} / {@code regex:} form as {@code sentinel}
 *                        (for example {@code glob:abc*.xml}). When {@code null}
 *                        the feed claims every file. A file that does not match
 *                        is left in {@code in/} untouched. This gates files; the
 *                        {@code mimeType} still selects the adapter.
 * @param recordSelectors the record selectors of the input
 */
public record InputSpec(String mimeType, String sentinel, String accepts,
                        Collection<RecordSelectorSpec> recordSelectors)
        implements Serializable {

    public InputSpec {
        recordSelectors = List.copyOf(requireNonNullElse(recordSelectors, List.of()));
    }

    /**
     * Atomic-delivery input claiming every file: no sentinel, no accept pattern.
     */
    public InputSpec(String mimeType, Collection<RecordSelectorSpec> recordSelectors) {
        this(mimeType, null, null, recordSelectors);
    }
}
