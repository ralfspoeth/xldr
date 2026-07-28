package io.github.ralfspoeth.xldr.xlsx.test;

import io.github.ralfspoeth.xldr.ia.Field;
import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.DataType;
import io.github.ralfspoeth.xldr.spec.FieldSelectorSpec;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.spec.RecordSelectorSpec;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.*;

public class ExcelAdapterTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static InputAdapter adapter(InputSpec spec) {
        var factory = ServiceLoader.load(InputAdapterFactory.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(f -> f.reads(spec))
                .findFirst()
                .orElseThrow();
        return factory.createInputAdapter(spec);
    }

    /**
     * A cell-rectangle range with typed columns, and a relative selector reaching
     * the row above the record.
     */
    @Test
    public void readsAcellRectangleWithTypesAndaRelativeSelector() throws IOException {
        var xlsx = workbook(sheet -> {
            header(sheet, 0, "id", "name", "amount");
            dataRow(sheet, 1, 1d, "Alice", 1.5d);
            dataRow(sheet, 2, 2d, "Bob", 2.5d);
        });

        var spec = new InputSpec(XLSX, null, null, List.of(
                new RecordSelectorSpec("rows", "data!A2:C3", List.of(
                        new FieldSelectorSpec("id", "A", DataType.INTEGER),
                        new FieldSelectorSpec("name", "B", DataType.STRING),
                        new FieldSelectorSpec("amount", "C", DataType.DECIMAL),
                        // one row up, one column right of the anchor (column A)
                        new FieldSelectorSpec("above", "R-1C+1", DataType.STRING)
                ))
        ), List.of(), Map.of());
        var wanted = Set.of("id", "name", "amount", "above");

        var result = adapter(spec).parse(new ByteArrayInputStream(xlsx), "rows", wanted);
        assertEquals(List.of("id", "name", "amount", "above"),
                result.fields().stream().map(Field::name).toList());

        var rows = result.rows().toList();
        assertEquals(2, rows.size());
        assertAll(
                () -> assertEquals(1L, rows.get(0).get("id")),
                () -> assertEquals("Alice", rows.get(0).get("name")),
                () -> assertEquals(new BigDecimal("1.5"), rows.get(0).get("amount")),
                // the record's row is 2; one up is the header row
                () -> assertEquals("name", rows.get(0).get("above")),
                () -> assertEquals(2L, rows.get(1).get("id")),
                () -> assertEquals("Bob", rows.get(1).get("name")),
                () -> assertEquals(new BigDecimal("2.5"), rows.get(1).get("amount")),
                // one up from row 3 is row 2, column B
                () -> assertEquals("Alice", rows.get(1).get("above"))
        );
    }

    /**
     * A whole-column range spans every data row; 1-based numeric indices address
     * columns, and a wholly empty row is skipped.
     */
    @Test
    public void readsAcolumnRangeByIndexSkippingEmptyRows() throws IOException {
        var xlsx = workbook(sheet -> {
            dataRow(sheet, 0, 10d, "x");
            dataRow(sheet, 1, 20d, "y");
            // row 2 left entirely empty -> a gap
            dataRow(sheet, 3, 30d, null);
        });

        var spec = new InputSpec(XLSX, null, null, List.of(
                new RecordSelectorSpec("all", "data!A:B", List.of(
                        new FieldSelectorSpec("v", "1", DataType.INTEGER),
                        new FieldSelectorSpec("label", "2", DataType.STRING)
                ))
        ), List.of(), Map.of());

        var rows = adapter(spec).parse(new ByteArrayInputStream(xlsx), "all", Set.of("v", "label"))
                .rows().toList();

        assertEquals(3, rows.size(), "the empty middle row is skipped");
        assertAll(
                () -> assertEquals(10L, rows.get(0).get("v")),
                () -> assertEquals("x", rows.get(0).get("label")),
                () -> assertEquals(20L, rows.get(1).get("v")),
                () -> assertEquals(30L, rows.get(2).get("v")),
                // an absent cell in a string column is the empty string
                () -> assertEquals("", rows.get(2).get("label"))
        );
    }

    @Test
    public void rejectsAmalformedRange() {
        var spec = new InputSpec(XLSX, null, null, List.of(
                new RecordSelectorSpec("bad", "A2:C", List.of())
        ), List.of(), Map.of());
        assertThrows(IllegalArgumentException.class, () -> adapter(spec));
    }

    // --- fixture building -------------------------------------------------

    private interface SheetContent {
        void fill(Sheet sheet);
    }

    private static byte[] workbook(SheetContent content) throws IOException {
        try (var wb = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            content.fill(wb.createSheet("data"));
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static void header(Sheet sheet, int rowIndex, String... names) {
        var row = sheet.createRow(rowIndex);
        for (int c = 0; c < names.length; c++) {
            row.createCell(c).setCellValue(names[c]);
        }
    }

    private static void dataRow(Sheet sheet, int rowIndex, Object... values) {
        var row = sheet.createRow(rowIndex);
        for (int c = 0; c < values.length; c++) {
            switch (values[c]) {
                case null -> { /* leave the cell absent */ }
                case Double d -> row.createCell(c).setCellValue(d);
                case String s -> row.createCell(c).setCellValue(s);
                default -> row.createCell(c).setCellValue(requireNonNull(values[c]).toString());
            }
        }
    }
}
