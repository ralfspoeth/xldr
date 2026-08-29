package io.github.ralfspoeth.xldr.xlsx;

import io.github.ralfspoeth.xldr.ia.Field;
import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.*;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.*;

class ExcelAdapterTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /**
     * {@link InputAdapterFactory#of} rather than {@link java.util.ServiceLoader}
     * directly. These tests are patched into the module they test, and a module
     * may only load a service it declares {@code uses} for - which {@code xlsx}
     * does not, being a provider. {@code of} works anyway: its lookup runs in
     * {@code ia}, whose descriptor carries the {@code uses}.
     */
    private static InputAdapter adapter(InputSpec spec) {
        return InputAdapterFactory.of(spec)
                .orElseThrow(() -> new IllegalStateException("no adapter for " + spec.mimeType()))
                .createInputAdapter(spec);
    }

    /**
     * A cell-rectangle range with typed columns, and a relative selector reaching
     * the row above the record.
     */
    @Test
    void readsAcellRectangleWithTypesAndaRelativeSelector() throws IOException {
        var xlsx = workbook(sheet -> {
            header(sheet, 0, "id", "name", "amount");
            dataRow(sheet, 1, 1d, "Alice", 1.5d);
            dataRow(sheet, 2, 2d, "Bob", 2.5d);
        });

        var spec = new InputSpec(XLSX, List.of(
                new RecordSelectorSpec("rows", Locator.at("data!A2:C3"), List.of(
                        new FieldSelectorSpec("id", "A", DataType.INTEGRAL),
                        new FieldSelectorSpec("name", "B", DataType.TEXT),
                        new FieldSelectorSpec("amount", "C", DataType.DECIMAL),
                        // one row up, one column right of the anchor (column A)
                        new FieldSelectorSpec("above", "R-1C+1", DataType.TEXT)
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
    void readsAcolumnRangeByIndexSkippingEmptyRows() throws IOException {
        var xlsx = workbook(sheet -> {
            dataRow(sheet, 0, 10d, "x");
            dataRow(sheet, 1, 20d, "y");
            // row 2 left entirely empty -> a gap
            dataRow(sheet, 3, 30d, null);
        });

        var spec = new InputSpec(XLSX, List.of(
                new RecordSelectorSpec("all", Locator.at("data!A:B"), List.of(
                        new FieldSelectorSpec("v", "1", DataType.INTEGRAL),
                        new FieldSelectorSpec("label", "2", DataType.TEXT)
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

    /**
     * A discriminator is refused by name.
     * <p>
     * It was refused before too, but as a missing selector: this adapter needs a
     * range, so a record selector carrying only a discriminator failed for want
     * of one - telling the author about the thing they left out rather than the
     * thing they wrote. {@link Locator.Where} is now a case of its own, and the
     * complaint belongs to it.
     */
    @Test
    void rejectsAdiscriminator() {
        var spec = new InputSpec(XLSX, List.of(new RecordSelectorSpec("rows",
                Locator.where(new Discriminator.Equals(Selector.nth(1), "O")),
                List.of(new FieldSelectorSpec("id", "A", DataType.TEXT)))), List.of(), Map.of());
        var thrown = assertThrows(IllegalArgumentException.class, () -> adapter(spec));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("rows"), thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("records to point at"), thrown.getMessage()));
    }

    @Test
    void rejectsAmalformedRange() {
        var spec = new InputSpec(XLSX, List.of(
                new RecordSelectorSpec("bad", Locator.at("A2:C"), List.of())
        ), List.of(), Map.of());
        assertThrows(IllegalArgumentException.class, () -> adapter(spec));
    }

    // --- fixture building -------------------------------------------------

    private interface SheetContent {
        void fill(Sheet sheet);
    }

    /**
     * A selector that names no sheet reads the first one - the only sheet most
     * feeds have. Nothing else covered this, so a rewrite of the range parser
     * could take both the fallback and the stripping of the sheet prefix away
     * unnoticed.
     */
    @Test
    void aRangeWithoutAsheetNameReadsTheFirstSheet() throws IOException {
        var xlsx = workbook(sheet -> {
            header(sheet, 0, "id", "name");
            dataRow(sheet, 1, 1d, "Alice");
        });

        var spec = new InputSpec(XLSX, List.of(
                new RecordSelectorSpec("rows", Locator.at("A2:B2"), List.of(
                        new FieldSelectorSpec("id", "A", DataType.INTEGRAL),
                        new FieldSelectorSpec("name", "B", DataType.TEXT)
                ))
        ), List.of(), Map.of());

        var rows = adapter(spec)
                .parse(new ByteArrayInputStream(xlsx), "rows", Set.of("id", "name"))
                .rows()
                .toList();

        assertEquals(1, rows.size());
        assertAll(
                () -> assertEquals(1L, rows.getFirst().get("id")),
                () -> assertEquals("Alice", rows.getFirst().get("name"))
        );
    }

    /**
     * The two notations that look alike and are not.
     * <p>
     * {@code selector="1"} is column A of the sheet, wherever the record sits.
     * {@code nth="1"} is the first column <em>of the record</em>, which is the
     * range's own first column - so for a range at {@code C2:D3} the two are
     * three columns apart. They agree only for a range starting at column A,
     * which is why this test does not use one: every other fixture here would
     * pass whichever way {@code nth} had been implemented.
     * <p>
     * Anchor-relative is the one that matches what {@code nth} means in every
     * other adapter - the n-th component of the record the record selector
     * identified, not the n-th of whatever contains it.
     */
    @Test
    void nthCountsFromTheRangeAndAdigitSelectorFromTheSheet() throws IOException {
        var xlsx = workbook(sheet -> {
            header(sheet, 0, "far", "left", "id", "name");
            dataRow(sheet, 1, "A1", "B1", "1", "Alice");
            dataRow(sheet, 2, "A2", "B2", "2", "Bob");
        });

        var spec = new InputSpec(XLSX, List.of(
                new RecordSelectorSpec("rows", Locator.at("data!C2:D3"), List.of(
                        new FieldSelectorSpec("counted", Selector.nth(1), DataType.TEXT),
                        new FieldSelectorSpec("alsoCounted", Selector.nth(2), DataType.TEXT),
                        new FieldSelectorSpec("absoluteDigit", "1", DataType.TEXT),
                        new FieldSelectorSpec("absoluteLetter", "C", DataType.TEXT)
                ))
        ), List.of(), Map.of());

        var rows = adapter(spec)
                .parse(new ByteArrayInputStream(xlsx), "rows",
                        Set.of("counted", "alsoCounted", "absoluteDigit", "absoluteLetter"))
                .rows().toList();

        assertEquals(2, rows.size());
        var first = rows.getFirst();
        assertAll(
                () -> assertEquals("1", first.get("counted"), "the range's first column is C"),
                () -> assertEquals("Alice", first.get("alsoCounted"), "and its second is D"),
                () -> assertEquals("A1", first.get("absoluteDigit"), "while a digit selector is column A"),
                () -> assertEquals("1", first.get("absoluteLetter"), "as a letter says outright"),
                () -> assertNotEquals(first.get("counted"), first.get("absoluteDigit"),
                        "the two notations differ, which is the whole of this test"));
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
