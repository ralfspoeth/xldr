package io.github.ralfspoeth.xldr.xlsx;

import io.github.ralfspoeth.xldr.ia.Field;
import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.Result;
import io.github.ralfspoeth.xldr.ia.Row;
import io.github.ralfspoeth.xldr.spec.DataType;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;

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

    private record RecordDef(Range range, Map<String, Field> fields, Map<String, CellRef> refs) {}

    private final Map<String, RecordDef> records = new LinkedHashMap<>();

    ExcelAdapter(InputSpec spec) {
        for (var rss : spec.recordSelectors()) {
            // a sheet range has to point somewhere, so this input cannot omit it
            var range = Range.parse(rss.requireSelector());
            var fields = new LinkedHashMap<String, Field>();
            var refs = new LinkedHashMap<String, CellRef>();
            for (var fss : rss.fieldSelectors()) {
                var type = fss.dataType() == null ? DataType.STRING : fss.dataType();
                fields.put(fss.name(), new Field(fss.name(), type.clazz()));
                refs.put(fss.name(), CellRef.parse(fss.selector()));
            }
            if (records.putIfAbsent(rss.name(), new RecordDef(range, fields, refs)) != null) {
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
        var unknown = fieldSelectors.stream().filter(n -> !record.refs().containsKey(n)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("record selector " + recordSelector
                    + " declares no field selector(s) " + unknown);
        }
        var selected = record.refs().keySet().stream().filter(fieldSelectors::contains).toList();

        try (var workbook = WorkbookFactory.create(source)) {
            var sheet = record.range().sheet(workbook);
            var span = record.range().rowSpan(sheet);
            var anchorColumn = record.range().anchorColumn();
            var formatter = new DataFormatter();
            var evaluator = workbook.getCreationHelper().createFormulaEvaluator();

            var rows = new ArrayList<Row>();
            for (int r = span[0]; r <= span[1]; r++) {
                var values = new LinkedHashMap<String, Object>();
                var allNull = true;
                for (var name : selected) {
                    var value = valueOf(sheet, r, anchorColumn, record.refs().get(name),
                            record.fields().get(name).type(), formatter, evaluator);
                    values.put(name, value);
                    allNull &= isEmpty(value);
                }
                if (!allNull) {
                    rows.add(values::get);
                }
            }
            var fields = selected.stream().map(record.fields()::get).toList();
            return new Result(fields, rows.stream());
        }
    }

    private static boolean isEmpty(Object value) {
        return value == null || "".equals(value);
    }

    private static Object valueOf(Sheet sheet, int recordRow, int anchorColumn, CellRef ref,
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
