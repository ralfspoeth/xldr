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
import java.util.Map;

import static io.github.ralfspoeth.xldr.it.Conformance.discovered;
import static io.github.ralfspoeth.xldr.it.Conformance.field;

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
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("data");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("id");
            header.createCell(1).setCellValue("name");
            header.createCell(2).setCellValue("amount");
            row(sheet.createRow(1), 1d, "Alice", 12.5d);
            row(sheet.createRow(2), 2d, "Bob", 98d);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void row(org.apache.poi.ss.usermodel.Row row, double id, String name, double amount) {
        row.createCell(0).setCellValue(id);
        row.createCell(1).setCellValue(name);
        row.createCell(2).setCellValue(amount);
    }
}
