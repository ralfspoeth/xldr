package io.github.ralfspoeth.xldr.xlsx;

import org.apache.poi.ss.util.CellReference;
import org.jspecify.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * A field selector: which cell of a record to read, relative to the record's
 * anchor (its top-left cell - the current row at the range's first column).
 * <p>
 * Three notations, of which the first two are written as a {@code selector} and
 * the third as an {@code nth}:
 * <ul>
 *   <li>an absolute column - a letter such as {@code A} or {@code AA}, or a
 *       1-based index such as {@code 3} (= column C) - read on the record's own
 *       row, wherever in the sheet that row happens to be;</li>
 *   <li>relative R1C1 - {@code R±r C±c} with both offsets present, e.g.
 *       {@code R-1C+2}: the cell {@code r} rows and {@code c} columns from the
 *       anchor, so a record can reach a neighbouring cell;</li>
 *   <li>{@code nth} - the n-th column <em>of the record</em>, which is the
 *       anchor plus n-1 and so follows the range rather than the sheet.</li>
 * </ul>
 * The first and the third read the same for a range starting at column A and
 * differ for every other range. The digit notation is the older of the two and
 * kept for the specs that use it; {@code nth} is the one that means the same
 * thing here as it does in every other adapter.
 */
sealed interface CellRef {

    Pattern RELATIVE = Pattern.compile("[Rr]([+-]?\\d+)[Cc]([+-]?\\d+)");
    Pattern LETTERS = Pattern.compile("[A-Za-z]+");
    Pattern DIGITS = Pattern.compile("\\d+");

    /**
     * The annotation sits between the element type and the brackets: it is the
     * array that may be absent, not its elements. {@code @Nullable int[]} would
     * not even compile, {@code int} being unable to be null.
     *
     * @return the cell coordinates (0-based row, 0-based column), or {@code null}
     * if they fall off the sheet (a negative index)
     */
    int @Nullable [] resolve(int recordRow, int anchorColumn);

    static CellRef parse(String selector) {
        var s = selector.strip();
        var rel = RELATIVE.matcher(s);
        if (rel.matches()) {
            return new Relative(Integer.parseInt(rel.group(1)), Integer.parseInt(rel.group(2)));
        }
        if (LETTERS.matcher(s).matches()) {
            return new AbsoluteColumn(CellReference.convertColStringToIndex(s.toUpperCase()));
        }
        if (DIGITS.matcher(s).matches()) {
            var index = Integer.parseInt(s);
            if (index < 1) {
                throw new IllegalArgumentException("column index must be 1-based: " + selector);
            }
            return new AbsoluteColumn(index - 1);
        }
        throw new IllegalArgumentException("not a valid Excel field selector: " + selector);
    }

    /**
     * The n-th column <em>of the record</em>, counted from one.
     * <p>
     * Not the same as the digit selector above, and the difference is the point.
     * {@code selector="3"} is column C of the sheet wherever the record sits;
     * {@code nth="3"} is the third column of the range the record selector named,
     * so a range at {@code data!C2:F10} makes it column E. A count that meant
     * different columns in two ranges would not be the n-th component of a
     * record, which is what {@code nth} means everywhere else in the toolkit.
     * <p>
     * It is therefore {@link Relative} with no row offset: the anchor is where
     * counting starts.
     */
    static CellRef nth(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("a component is counted from 1, was: " + n);
        }
        return new Relative(0, n - 1);
    }

    record AbsoluteColumn(int column) implements CellRef {
        @Override
        public int[] resolve(int recordRow, int anchorColumn) {
            return new int[]{recordRow, column};
        }
    }

    record Relative(int rowOffset, int columnOffset) implements CellRef {
        @Override
        public int @Nullable [] resolve(int recordRow, int anchorColumn) {
            var r = recordRow + rowOffset;
            var c = anchorColumn + columnOffset;
            return r < 0 || c < 0 ? null : new int[]{r, c};
        }
    }
}
