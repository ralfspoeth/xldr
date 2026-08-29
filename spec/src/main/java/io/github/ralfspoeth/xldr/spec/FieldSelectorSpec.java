package io.github.ralfspoeth.xldr.spec;

import org.jspecify.annotations.Nullable;

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
) {

    /**
     * The common case, spelled without ceremony: a field selected by the
     * adapter's own syntax.
     */
    public FieldSelectorSpec(String name, String selector, @Nullable DataType dataType) {
        this(name, Selector.text(selector), dataType);
    }

    /**
     * The other case, spelled the same way: a field selected by counting.
     * <p>
     * {@code nth} is counted from one, as {@link Selector.Nth} is - the first
     * component of the record is 1, and 0 is refused. The 0-based form the
     * adapters address with is {@link Selector.Nth#index()}, and the two are
     * deliberately different words; this parameter was called {@code index}
     * until 0.48, which said the opposite of what it meant.
     * <p>
     * It also dropped the {@code dataType} on the floor, passing {@code null} to
     * the canonical constructor whatever it was given, so a field declared
     * {@code DECIMAL} arrived as text and was bound into a numeric column as a
     * string. Nothing called it - every counted field in this repository spells
     * {@code Selector.nth(n)} - which is why nothing failed and why it sat
     * there: an overload with no caller is tested by nobody and trusted by the
     * next person to find it.
     */
    public FieldSelectorSpec(String name, int nth, @Nullable DataType dataType) {
        this(name, Selector.nth(nth), dataType);
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
