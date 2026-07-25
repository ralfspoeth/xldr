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
 *                       discriminator, an Excel range, ...)
 * @param fieldSelectors the fields to read from each record
 */
public record RecordSelectorSpec(String name, String selector,
                                 Collection<FieldSelectorSpec> fieldSelectors) implements Serializable
{
    public RecordSelectorSpec {
        fieldSelectors = List.copyOf(fieldSelectors);
    }
}
