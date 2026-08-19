package io.github.ralfspoeth.xldr.spec;

import org.jspecify.annotations.Nullable;

import java.io.Serializable;

/**
 * Selects one field of a record and names the type its value is delivered as.
 *
 * @param name     the field name, referenced by a mapping's {@code fieldSelector}
 * @param selector where the value sits: the adapter's own syntax, or a column
 *                 counted from one. Which of the two an adapter takes is its own
 *                 business - an XPath is not a column number, and a fixed-length
 *                 record has offsets rather than columns
 * @param dataType the type to deliver the value as; {@code null} leaves it to the adapter
 */
public record FieldSelectorSpec(
        String name,
        Selector selector,
        @Nullable DataType dataType
) implements Serializable {

    /**
     * The common case, spelled without ceremony: a field selected by the
     * adapter's own syntax.
     */
    public FieldSelectorSpec(String name, String selector, @Nullable DataType dataType) {
        this(name, new Selector.Text(selector), dataType);
    }

    /**
     * The selector as the adapter's own syntax, for an adapter that has no notion
     * of a column.
     *
     * @throws IllegalArgumentException naming the field and what it was given
     *                                  instead, since a spec counting components
     *                                  of a record that has none has confused two
     *                                  formats
     */
    public String requireText(String what) {
        return switch (selector) {
            case Selector.Text(var value) -> value;
            case Selector.Nth nth -> throw new IllegalArgumentException(
                    "field selector '" + name + "' asks for " + nth + ", but " + what
                            + " - use 'selector' rather than 'nth'");
        };
    }
}
