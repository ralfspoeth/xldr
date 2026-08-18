package io.github.ralfspoeth.xldr.spec;

import java.io.Serializable;

/**
 * Where a value sits within a record: either in the adapter's own words, or in
 * the one word every tabular format shares.
 * <p>
 * A selector used to be a {@code String} whose meaning depended on something that
 * was not in it. {@code "3"} was the column named {@code 3} where a CSV file had a
 * header and the third column where it had not, decided by a property several
 * lines away - so a file whose header really did name a column {@code 3} could not
 * be addressed at all. Splitting the two cases apart means each says one thing
 * everywhere.
 * <p>
 * The spec formats say it with two names rather than two types, and the reason is
 * XML: {@code selector} is an attribute, attributes are text, and
 * {@code selector="3"} is the only thing writable. A reader deciding by <em>looks
 * like a number</em> would have kept exactly the ambiguity this removes, and the
 * two formats would have stopped meaning the same thing. Two names cost nothing
 * and let both schemas type {@code column} as an integer, so {@code column="first"}
 * is refused before any adapter sees it.
 *
 * <pre>
 * { "name": "id", "selector": "id" }      &lt;fieldSelector name="id" selector="id"/&gt;
 * { "name": "id", "column": 1 }           &lt;fieldSelector name="id" column="1"/&gt;
 * </pre>
 *
 * Which of the two an adapter accepts is the adapter's business: an XPath is not
 * a column number, and a fixed-length record has offsets rather than columns.
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
     * A column, counted from one, for inputs whose records have columns.
     * <p>
     * One-based because a spec is written by a person reading a file, and the
     * first column of a file is the first one. The adapters subtract.
     *
     * @param index at least 1
     */
    record Column(int index) implements Selector {
        public Column {
            if (index < 1) {
                throw new IllegalArgumentException(
                        "a column is counted from 1, was: " + index);
            }
        }

        @Override
        public String toString() {
            return "column " + index;
        }
    }
}
