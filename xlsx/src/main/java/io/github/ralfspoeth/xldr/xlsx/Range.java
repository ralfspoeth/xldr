package io.github.ralfspoeth.xldr.xlsx;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellReference;
import org.jspecify.annotations.Nullable;

/**
 * A record selector: an Excel range, one record per row.
 * <p>
 * Grammar {@code [Sheet!]ref:ref}, both endpoints the same kind:
 * <ul>
 *   <li>{@code A:C} - columns A to C, every data row of the sheet;</li>
 *   <li>{@code Sheet1!B2:C4} - the cell rectangle rows 2-4, columns B-C, of the
 *       named sheet.</li>
 * </ul>
 * The {@code anchorColumn} - the first column of the range - is the origin for
 * relative field selectors.
 *
 * @param sheetName the sheet, or {@code null} for the first sheet
 * @param firstRow  the first record row (0-based), or {@code null} for column
 *                  ranges, which span every data row
 * @param lastRow   the last record row (0-based), or {@code null} for column ranges
 */
record Range(@Nullable String sheetName, int anchorColumn,
             @Nullable Integer firstRow, @Nullable Integer lastRow) {

    static Range parse(String selector) {
        var s = selector.strip();
        String sheetName = null;
        var bang = s.indexOf('!');
        if (bang >= 0) {
            sheetName = s.substring(0, bang);
            // the sheet is off the front now; what remains is the range itself,
            // and the endpoints are parsed from that alone
            s = s.substring(bang + 1);
        }

        var colon = s.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("range needs a ':' - " + selector);
        }
        var left = s.substring(0, colon).strip();
        var right = s.substring(colon + 1).strip();

        if (isColumn(left) && isColumn(right)) {
            var a = CellReference.convertColStringToIndex(left.toUpperCase());
            var b = CellReference.convertColStringToIndex(right.toUpperCase());
            return new Range(sheetName, Math.min(a, b), null, null);
        }
        if (isCell(left) && isCell(right)) {
            var a = new CellReference(left);
            var b = new CellReference(right);
            return new Range(
                    sheetName,
                    Math.min(a.getCol(), b.getCol()),
                    Math.min(a.getRow(), b.getRow()),
                    Math.max(a.getRow(), b.getRow()));
        }
        throw new IllegalArgumentException(
                "range endpoints must both be columns (A:C) or both cells (B2:C4): " + selector);
    }

    Sheet sheet(Workbook workbook) {
        if (sheetName == null) {
            // a selector that names no sheet reads the first one, which is the
            // only sheet most feeds have
            if (workbook.getNumberOfSheets() == 0) {
                throw new IllegalStateException("the workbook has no sheet");
            }
            return workbook.getSheetAt(0);
        }
        var sheet = workbook.getSheet(sheetName);
        if (sheet == null) {
            throw new IllegalArgumentException("no sheet named " + sheetName);
        }
        return sheet;
    }

    /**
     * The record rows of {@code sheet} - the explicit rectangle rows, or every
     * physical row for a column range. {@code first > last} means no rows.
     */
    int[] rowSpan(Sheet sheet) {
        if (firstRow != null && lastRow != null) {
            return new int[]{firstRow, lastRow};
        }
        return new int[]{sheet.getFirstRowNum(), sheet.getLastRowNum()};
    }

    private static boolean isColumn(String s) {
        return CellRef.LETTERS.matcher(s).matches();
    }

    private static boolean isCell(String s) {
        return s.matches("[A-Za-z]+\\d+");
    }
}
