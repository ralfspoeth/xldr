package io.github.ralfspoeth.xldr.spec;

import java.io.Serializable;

/**
 * Selects one field of a record and names the type its value is delivered as.
 *
 * @param name     the field name, referenced by a mapping's {@code fieldSelector}
 * @param selector how the adapter locates the field (an XPath, a column position, ...)
 * @param dataType the type to deliver the value as; {@code null} leaves it to the adapter
 */
public record FieldSelectorSpec(
        String name,
        String selector,
        DataType dataType
) implements Serializable {
}
