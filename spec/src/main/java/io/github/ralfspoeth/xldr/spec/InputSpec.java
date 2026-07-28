package io.github.ralfspoeth.xldr.spec;

import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * How some input file is turned into a stream of records and how the fields are
 * extracted from them.
 *
 * @param mimeType        selects the input adapter
 * @param sentinel        marker-file delivery. A marker pattern in {@code glob:}
 *                        or {@code regex:} form (as understood by
 *                        {@code FileSystem.getPathMatcher}): the producer writes
 *                        the data file, then a marker file matching the pattern,
 *                        and only the marker's arrival triggers the load. The
 *                        data file is the marker name minus its last dotted
 *                        suffix, so {@code glob:*.{ok,ready,done}} loads
 *                        {@code report.csv} from {@code report.csv.done}. A feed
 *                        declares exactly one of {@code sentinel} or {@code accepts}.
 * @param accepts         atomic delivery. A data file whose <em>name</em> matches
 *                        this pattern (same {@code glob:} / {@code regex:} form as
 *                        {@code sentinel}, for example {@code glob:abc*.xml})
 *                        triggers the load on its own, so it must be delivered
 *                        atomically - staged under an ignored name and renamed, or
 *                        moved in from outside {@code in/}. A file that does not
 *                        match is left in {@code in/} untouched. A feed declares
 *                        exactly one of {@code accepts} or {@code sentinel}; the
 *                        server does not activate one that declares both or neither.
 * @param recordSelectors the record selectors of the input
 * @param vars            input-level variables, each evaluated once per load and
 *                        referenced from a field mapping by a {@link
 *                        ValueSource.Var}. Evaluated in declaration order, so a
 *                        variable may reference an earlier one.
 * @param properties      the settings of the adapter this input selects - a CSV
 *                        dialect, a date pattern, an XML namespace binding. They
 *                        are whatever the chosen adapter understands, which is
 *                        why they are an open map rather than named components,
 *                        and they travel with the spec so that an input is
 *                        described by one document.
 */
public record InputSpec(
        String mimeType,
        @Nullable String sentinel,
        @Nullable String accepts,
        Collection<RecordSelectorSpec> recordSelectors,
        Collection<VarSpec> vars,
        Map<String, String> properties
) implements Serializable {

    /**
     * Canonical constructor.
     */
    public InputSpec {
        recordSelectors = List.copyOf(recordSelectors);
        vars = List.copyOf(vars);
        properties = Map.copyOf(properties);
    }
}
