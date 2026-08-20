package io.github.ralfspoeth.xldr.xlsx;

import io.github.ralfspoeth.xldr.ia.Field;
import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.Result;
import io.github.ralfspoeth.xldr.ia.Row;
import io.github.ralfspoeth.xldr.spec.DataType;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.spec.Selector;
import org.apache.poi.ss.usermodel.*;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Reads records out of an Excel workbook, {@code .xls} or {@code .xlsx} alike -
 * {@link WorkbookFactory} detects the format from the stream.
 * <p>
 * A record selector is a {@link Range} and a field selector a {@link CellRef};
 * both are parsed once, in the constructor, so a malformed selector is reported
 * when the adapter is created rather than half way through a load. The whole
 * selection is read eagerly into memory - a workbook cannot be streamed cell by
 * cell without keeping it open past the parse - which suits loading a file into
 * a database.
 */
class ExcelAdapter implements InputAdapter {

    /**
     * One column of a record: what it is called and what type it is delivered
     * as, together with where to read it. The two travel as one because they are
     * always wanted together - keeping them in two maps under the same key only
     * created lookups that can, as far as any reader or checker can tell, come
     * back with nothing.
     * <p>
     * Called {@code Mapped} rather than {@code Column} because a column is what a
     * field mapping writes <em>to</em>, and this is where one is read from.
     */
    private record Mapped(Field field, CellRef ref) {}

    private record RecordDef(Range range, Map<String, Mapped> columns) {}

    private final Map<String, RecordDef> records = new LinkedHashMap<>();

    ExcelAdapter(InputSpec spec) {
        for (var rss : spec.recordSelectors()) {
            // before requiring the range, so that a spec carrying a discriminator
            // is told about the thing it wrote rather than about the thing it
            // left out
            rss.refuseDiscriminator("a sheet has records to point at");
            // a sheet range has to point somewhere, so this input cannot omit it
            var range = Range.parse(rss.requireSelector());
            var columns = new LinkedHashMap<String, Mapped>();
            for (var fss : rss.fieldSelectors()) {
                var type = fss.dataType() == null ? DataType.TEXT : fss.dataType();
                // an absolute cell reference, or a count from the record's own
                // first column - the two differ wherever a range does not start
                // at column A, which is why both exist
                var ref = switch (fss.selector()) {
                    case Selector.Text(var s) -> CellRef.parse(s);
                    case Selector.Nth nth -> CellRef.nth(nth.n());
                };
                columns.put(fss.name(), new Mapped(new Field(fss.name(), type.clazz()), ref));
            }
            if (records.putIfAbsent(rss.name(), new RecordDef(range, columns)) != null) {
                throw new IllegalArgumentException("duplicate record selector " + rss.name());
            }
        }
    }

    @Override
    public Result parse(InputStream source, String recordSelector, Set<String> fieldSelectors) throws IOException {
        var record = records.get(recordSelector);
        if (record == null) {
            throw new IllegalArgumentException("no record selector named " + recordSelector
                    + "; the input spec declares " + records.keySet());
        }
        var unknown = fieldSelectors.stream().filter(n -> !record.columns().containsKey(n)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("record selector " + recordSelector
                    + " declares no field selector(s) " + unknown);
        }
        // in the order the spec declares them, which is the order of the map
        var selected = record.columns().entrySet().stream()
                .filter(e -> fieldSelectors.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .toList();

        try (var workbook = WorkbookFactory.create(source)) {
            var sheet = record.range().sheet(workbook);
            var span = record.range().rowSpan(sheet);
            var anchorColumn = record.range().anchorColumn();
            var formatter = new DataFormatter();
            var evaluator = workbook.getCreationHelper().createFormulaEvaluator();

            var rows = new ArrayList<Row>();
            for (int r = span[0]; r <= span[1]; r++) {
                var values = new LinkedHashMap<String, @Nullable Object>();
                var allNull = true;
                for (var column : selected) {
                    var value = valueOf(sheet, r, anchorColumn, column.ref(),
                            column.field().type(), formatter, evaluator);
                    values.put(column.field().name(), value);
                    allNull &= isEmpty(value);
                }
                if (!allNull) {
                    rows.add(values::get);
                }
            }
            var fields = selected.stream().map(Mapped::field).toList();
            return new Result(fields, rows.stream());
        }
    }

    private static boolean isEmpty(@Nullable Object value) {
        return value == null || "".equals(value);
    }

    private static @Nullable Object valueOf(Sheet sheet, int recordRow, int anchorColumn, CellRef ref,
                                            Class<?> type, DataFormatter formatter, FormulaEvaluator evaluator) {
        var at = ref.resolve(recordRow, anchorColumn);
        if (at == null) {
            return null;
        }
        var poiRow = sheet.getRow(at[0]);
        var cell = poiRow == null ? null : poiRow.getCell(at[1]);
        if (cell == null) {
            return type == String.class ? "" : null;
        }
        var effective = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType()
                : cell.getCellType();

        if (type == String.class) {
            return formatter.formatCellValue(cell, evaluator);
        } else if (type == Long.class) {
            return effective == CellType.NUMERIC ? (long) cell.getNumericCellValue()
                    : effective == CellType.STRING ? Long.valueOf(cell.getStringCellValue().strip())
                    : null;
        } else if (type == Double.class) {
            return effective == CellType.NUMERIC ? cell.getNumericCellValue()
                    : effective == CellType.STRING ? Double.valueOf(cell.getStringCellValue().strip())
                    : null;
        } else if (type == BigDecimal.class) {
            return effective == CellType.NUMERIC ? BigDecimal.valueOf(cell.getNumericCellValue())
                    : effective == CellType.STRING ? new BigDecimal(cell.getStringCellValue().strip())
                    : null;
        } else {
            return effective == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue()
                    : null;
        }
    }
}
