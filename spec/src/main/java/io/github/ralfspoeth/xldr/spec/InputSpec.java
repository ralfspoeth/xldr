package io.github.ralfspoeth.xldr.spec;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * How some input file is turned into a stream of records and how the fields are
 * extracted from them.
 * <p>
 * Not how those files arrive: which names a feed claims, and whether a marker
 * announces them, is a property of the deployment rather than of the mapping,
 * and it differs between test and production while this document does not. It
 * lives in the feed's {@code delivery.properties}, which the server owns - see
 * {@code io.github.ralfspoeth.xldr.server.Delivery}. Nothing here would have
 * known what to do with it.
 *
 * @param mimeType        selects the input adapter
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
