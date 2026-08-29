package io.github.ralfspoeth.xldr.spec;

import java.io.Serializable;

/**
 * Where a value sits within a record: either in the adapter's own words, or by
 * counting.
 * <p>
 * A selector used to be a {@code String} whose meaning depended on something that
 * was not in it. {@code "3"} was the column named {@code 3} where a CSV file had a
 * header and the third column where it had not, decided by a property several
 * lines away - so a file whose header really did name a column {@code 3} could not
 * be addressed at all. Splitting the two apart means each says one thing
 * everywhere.
 * <p>
 * The spec formats say it with two names rather than two types, and the reason is
 * XML: {@code selector} is an attribute, attributes are text, and
 * {@code selector="3"} is the only thing writable. A reader deciding by <em>looks
 * like a number</em> would have kept exactly the ambiguity this removes, and the
 * two formats would have stopped meaning the same thing. Two names cost nothing
 * and let both schemas type {@code nth} as an integer, so {@code nth="first"} is
 * refused before any adapter sees it.
 *
 * <pre>
 * { "name": "id", "selector": "id" }      &lt;fieldSelector name="id" selector="id"/&gt;
 * { "name": "id", "nth": 1 }              &lt;fieldSelector name="id" nth="1"/&gt;
 * </pre>
 */
public sealed interface Selector extends Serializable {

    /**
     * The adapter's own syntax - an XPath, a character range, a JSON pointer, a
     * cell reference, or the name of a column.
     *
     * @param value never blank; a selector that says nothing selects nothing
     */
    record Text(String value) implements Selector {
        public Text {
            if (value.isBlank()) {
                throw new IllegalArgumentException("a selector cannot be blank");
            }
        }

        @Override
        public String toString() {
            return "'" + value + "'";
        }
    }

    /**
     * The n-th component of the record, counted from one.
     * <p>
     * One rule, and each adapter only has to say what its records are made of:
     * <ul>
     *   <li>a separated line - the n-th field;</li>
     *   <li>a spreadsheet record - the n-th column of its range, counted from the
     *       range's own first column rather than from the sheet's. The record is
     *       what the selector identified, and counting from anywhere else would
     *       make the same number mean different things in two ranges;</li>
     *   <li>a JSON array - the n-th element;</li>
     *   <li>an XML element - the n-th child element.</li>
     * </ul>
     * <p>
     * Where the <em>data</em> has no such thing - a JSON record that turns out to
     * be an object, which is unordered by specification, or a line with fewer
     * fields than that - the value is {@code null}, because only the data could
     * have said so and the next record may differ. Where the <em>format</em> has
     * no such thing - a fixed-length record, which has offsets and no components
     * at all - the spec is refused when the adapter is built, because the spec
     * alone already proves it wrong.
     * <p>
     * Named {@code nth} rather than {@code column}: a field mapping already says
     * {@code column} for the database column it writes to, and the two would have
     * sat a line apart meaning opposite ends of the same value. CSS spells the
     * same idea {@code :nth-child}.
     *
     * @param n at least 1
     */
    record Nth(int n) implements Selector {
        public Nth {
            if (n < 1) {
                throw new IllegalArgumentException("a component is counted from 1, was: " + n);
            }
        }

        /** the 0-based index the adapters actually address with */
        public int index() {
            return n - 1;
        }

        @Override
        public String toString() {
            return "the " + n + switch (n % 100 >= 11 && n % 100 <= 13 ? 0 : n % 10) {
                case 1 -> "st";
                case 2 -> "nd";
                case 3 -> "rd";
                default -> "th";
            } + " component";
        }
    }

    /**
     * The n-th component of the record, counted from one.
     * <p>
     * Returns {@link Nth} rather than {@code Selector}, where {@link
     * Locator#every()} and its two siblings return the interface. The difference
     * is that these cases carry something worth asking for afterwards -
     * {@link Nth#index()} is what an adapter addresses with - so a factory that
     * erased the case would make {@code Selector.nth(1).index()} not compile for
     * no reason. A locator's cases have no such accessor, so nothing is lost
     * there by returning the interface.
     *
     * @param n at least 1
     * @return the selector that counts to it
     * @throws IllegalArgumentException if {@code n} is less than 1
     */
    static Nth nth(int n) {
        return new Nth(n);
    }

    /**
     * A selector in the adapter's own syntax - an XPath, a character range, a
     * JSON pointer, a cell reference, or the name of a column.
     *
     * @param value never blank; a selector that says nothing selects nothing
     * @return the selector that says it
     * @throws IllegalArgumentException if the value is blank
     */
    static Text text(String value) {
        return new Text(value);
    }
}
