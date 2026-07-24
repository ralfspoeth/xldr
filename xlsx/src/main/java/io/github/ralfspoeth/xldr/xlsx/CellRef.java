package io.github.ralfspoeth.xldr.xlsx;

import org.apache.poi.ss.util.CellReference;

import java.util.regex.Pattern;

/**
 * A field selector: which cell of a record to read, relative to the record's
 * anchor (its top-left cell - the current row at the range's first column).
 * <p>
 * Two notations:
 * <ul>
 *   <li>an absolute column - a letter such as {@code A} or {@code AA}, or a
 *       1-based index such as {@code 3} (= column C) - read on the record's own
 *       row;</li>
 *   <li>relative R1C1 - {@code R±r C±c} with both offsets present, e.g.
 *       {@code R-1C+2}: the cell {@code r} rows and {@code c} columns from the
 *       anchor, so a record can reach a neighbouring cell.</li>
 * </ul>
 */
sealed interface CellRef {

    Pattern RELATIVE = Pattern.compile("[Rr]([+-]?\\d+)[Cc]([+-]?\\d+)");
    Pattern LETTERS = Pattern.compile("[A-Za-z]+");
    Pattern DIGITS = Pattern.compile("\\d+");

    /**
     * @return the cell coordinates (0-based row, 0-based column), or {@code null}
     * if they fall off the sheet (a negative index)
     */
    int[] resolve(int recordRow, int anchorColumn);

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

    record AbsoluteColumn(int column) implements CellRef {
        @Override
        public int[] resolve(int recordRow, int anchorColumn) {
            return new int[]{recordRow, column};
        }
    }

    record Relative(int rowOffset, int columnOffset) implements CellRef {
        @Override
        public int[] resolve(int recordRow, int anchorColumn) {
            var r = recordRow + rowOffset;
            var c = anchorColumn + columnOffset;
            return r < 0 || c < 0 ? null : new int[]{r, c};
        }
    }
}
