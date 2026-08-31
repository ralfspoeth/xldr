package io.github.ralfspoeth.xldr.it;

import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.DataType;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.spec.Locator;
import io.github.ralfspoeth.xldr.tck.InputAdapterContract;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jspecify.annotations.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import static io.github.ralfspoeth.xldr.it.Conformance.discovered;
import static io.github.ralfspoeth.xldr.it.Conformance.field;
import static io.github.ralfspoeth.xldr.it.Conformance.records;
import static io.github.ralfspoeth.xldr.it.Conformance.twice;

class XlsxConformanceIT extends InputAdapterContract {

    @Override
    protected @NonNull InputAdapterFactory factory() {
        return discovered(spec());
    }

    @Override
    protected @NonNull String mimeType() {
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }

    @Override
    protected @NonNull InputSpec spec() {
        return Conformance.spec(mimeType(), Map.of(), Locator.at("data!A2:C3"),
                field("id", "A", DataType.INTEGRAL),
                field("name", "B", DataType.TEXT),
                field("amount", "C", DataType.DECIMAL));
    }

    /** two data rows under a header, which is the layout the range describes */
    @Override
    protected byte @NonNull [] sample() {
        return workbook(sheet -> {
            row(sheet.createRow(1), 1d, "Alice", 12.5d);
            row(sheet.createRow(2), 2d, "Bob", 98d);
        });
    }

    /**
     * A note or a total left in a numeric column is the commonest thing wrong
     * with a spreadsheet, and the row as the author sees it in Excel - counted
     * from one, not from POI's zero - is what sends them to it.
     */
    @Override
    protected @NonNull List<Breakage> breakages() {
        return List.of(new Breakage("text in the column the spec declared a decimal",
                workbook(sheet -> {
                    row(sheet.createRow(1), 1d, "Alice", 12.5d);
                    var second = sheet.createRow(2);
                    second.createCell(0).setCellValue(2d);
                    second.createCell(1).setCellValue("Bob");
                    second.createCell(2).setCellValue("see note");
                }),
                "row 3 of sheet 'data'"));
    }

    /** a workbook with the header row this spec's range sits under, and whatever else is asked for */
    private static byte[] workbook(java.util.function.Consumer<org.apache.poi.ss.usermodel.Sheet> body) {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("data");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("id");
            header.createCell(1).setCellValue("name");
            header.createCell(2).setCellValue("amount");
            body.accept(sheet);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A range is the one selector here with a grammar of its own, and three ways
     * to get it wrong that are all worth catching before a workbook is opened -
     * opening one being the expensive part of this format.
     */
    @Override
    protected @NonNull List<Refusal> refusals() {
        return List.of(
                new Refusal("no locator, in a format where a record is a range of cells",
                        Conformance.spec(mimeType(), Map.of(), Locator.every(),
                                field("id", "A", DataType.INTEGRAL))),
                new Refusal("a range with no ':' in it, so with one endpoint",
                        Conformance.spec(mimeType(), Map.of(), Locator.at("data!A2"),
                                field("id", "A", DataType.INTEGRAL))),
                new Refusal("a range of one column and one cell, which describes no rectangle",
                        Conformance.spec(mimeType(), Map.of(), Locator.at("data!A:C4"),
                                field("id", "A", DataType.INTEGRAL))),
                new Refusal("a field selector that is no cell reference",
                        Conformance.spec(mimeType(), Map.of(), Locator.at("data!A2:C3"),
                                field("id", "??", DataType.TEXT))),
                new Refusal("two record selectors of one name",
                        twice(mimeType(), records(Locator.at("data!A2:C3"),
                                field("id", "A", DataType.INTEGRAL)))));
    }

    private static void row(org.apache.poi.ss.usermodel.Row row, double id, String name, double amount) {
        row.createCell(0).setCellValue(id);
        row.createCell(1).setCellValue(name);
        row.createCell(2).setCellValue(amount);
    }
}
