package io.github.ralfspoeth.xldr.spec;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

/**
 * Selects the records of one kind from an input and lists the fields to read
 * from each.
 *
 * @param name           the record selector name, referenced by a record mapping
 * @param selector       how the adapter locates the records (an XPath, a first-column
 *                       discriminator, an Excel range, ...), or {@code null} where
 *                       the whole file holds one kind of record and there is
 *                       nothing to locate
 * @param fieldSelectors the fields to read from each record
 */
public record RecordSelectorSpec(String name, String selector,
                                 Collection<FieldSelectorSpec> fieldSelectors) implements Serializable
{
    public RecordSelectorSpec {
        fieldSelectors = List.copyOf(fieldSelectors);
    }

    /**
     * The selector of an adapter that cannot do without one - an XPath, a JSON
     * pointer, a spreadsheet range all have to point somewhere. Adapters that
     * can read a whole file as one kind of record, such as those for CSV and
     * fixed-length files, read {@link #selector()} directly and treat an absent
     * one as "every record".
     *
     * @return the selector, never blank
     * @throws IllegalArgumentException if the spec left it out
     */
    public String requireSelector() {
        if (selector == null || selector.isBlank()) {
            throw new IllegalArgumentException(
                    "record selector '" + name + "' needs a selector for this input");
        }
        return selector;
    }
}
