package io.github.ralfspoeth.xldr.csv.test;

import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.Field;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.DataType;
import io.github.ralfspoeth.xldr.spec.FieldSelectorSpec;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.spec.RecordSelectorSpec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class CsvFileHandlerTest {

    // input spec mentions only id and name; the file also carries short-name/long-name.
    // no discriminator (selector null): a single-record-type file takes every line.
    private static final InputSpec SPEC = new InputSpec("text/csv", List.of(
            new RecordSelectorSpec("people", null, List.of(
                    new FieldSelectorSpec("id", "id", DataType.STRING),
                    new FieldSelectorSpec("name", "name", DataType.STRING)
            ))
    ));

    // no header: columns are addressed by 1-based position ("1" -> col 0, ...)
    private static final InputSpec POSITIONAL_SPEC = new InputSpec("text/csv", List.of(
            new RecordSelectorSpec("people", null, List.of(
                    new FieldSelectorSpec("1", "1", DataType.STRING),
                    new FieldSelectorSpec("2", "2", DataType.STRING),
                    new FieldSelectorSpec("3", "3", DataType.STRING)
            ))
    ));

    // one headerless file, two interleaved record types keyed by the first column:
    // the record selector's `selector` is the discriminator the first column must equal.
    // positions stay absolute, so "1" is the discriminator column itself.
    private static final InputSpec DISCRIMINATED_SPEC = new InputSpec("text/csv", List.of(
            new RecordSelectorSpec("orders", "O", List.of(
                    new FieldSelectorSpec("2", "2", DataType.STRING),   // order id
                    new FieldSelectorSpec("3", "3", DataType.STRING),   // date
                    new FieldSelectorSpec("4", "4", DataType.STRING)    // customer
            )),
            new RecordSelectorSpec("lines", "L", List.of(
                    new FieldSelectorSpec("2", "2", DataType.STRING),   // order id
                    new FieldSelectorSpec("3", "3", DataType.STRING),   // product
                    new FieldSelectorSpec("4", "4", DataType.STRING),   // qty
                    new FieldSelectorSpec("5", "5", DataType.STRING)    // price
            ))
    ));

    private static InputAdapterFactory factory(InputSpec spec) {
        return ServiceLoader.load(InputAdapterFactory.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(iaf -> iaf.accepts(spec))
                .findFirst().orElseThrow();
    }

    private InputAdapter adapter() {
        var factory = factory(SPEC);
        factory.setProperty("fieldSeparator", ",");
        factory.setProperty("rowSeparator", "\n");
        return factory.createInputAdapter(SPEC);
    }

    private InputAdapter positionalAdapter() {
        var factory = factory(POSITIONAL_SPEC);
        factory.setProperty("fieldSeparator", ",");
        factory.setProperty("rowSeparator", "\n");
        factory.setProperty("header", "false");
        return factory.createInputAdapter(POSITIONAL_SPEC);
    }

    private InputAdapter discriminatedAdapter() {
        var factory = factory(DISCRIMINATED_SPEC);
        factory.setProperty("fieldSeparator", ",");
        factory.setProperty("rowSeparator", "\n");
        factory.setProperty("header", "false");
        return factory.createInputAdapter(DISCRIMINATED_SPEC);
    }

    @Test
    public void parsesSelectedFields() throws IOException {
        try (var in = getClass().getResourceAsStream("simple.csv")) {
            var result = adapter().parse(in, "people", Set.of("id", "name"));

            // only id and name are exposed as fields
            assertEquals(
                    List.of("id", "name"),
                    result.fields().stream().map(Field::name).toList()
            );

            var rows = result.rows().toList();
            assertEquals(2, rows.size());
            assertAll(
                    () -> assertEquals("1", rows.get(0).get("id")),
                    () -> assertEquals("Alice", rows.get(0).get("name")),
                    () -> assertEquals("2", rows.get(1).get("id")),
                    () -> assertEquals("Bob", rows.get(1).get("name"))
            );
        }
    }

    @Test
    public void parsesHeaderlessWithRaggedLines() throws IOException {
        try (var in = getClass().getResourceAsStream("positional.csv")) {
            var result = positionalAdapter().parse(in, "people", Set.of("1", "2", "3"));

            // fields keep the spec order: positions 1, 2, 3
            assertEquals(
                    List.of("1", "2", "3"),
                    result.fields().stream().map(Field::name).toList()
            );

            var rows = result.rows().toList();
            // no header line is consumed -> all 10 lines are records
            assertEquals(10, rows.size());

            assertAll(
                    // fully populated line
                    () -> assertEquals("1", rows.get(0).get("1")),
                    () -> assertEquals("Alice", rows.get(0).get("2")),
                    () -> assertEquals("Berlin", rows.get(0).get("3")),
                    // extra column beyond the spec is simply ignored
                    () -> assertEquals("Bob", rows.get(1).get("2")),
                    () -> assertEquals("Hamburg", rows.get(1).get("3")),
                    // incomplete line: missing column 3 -> null
                    () -> assertEquals("Carol", rows.get(2).get("2")),
                    () -> assertNull(rows.get(2).get("3")),
                    // present but empty column 3 -> "" (distinct from missing/null)
                    () -> assertEquals("", rows.get(4).get("3")),
                    // another incomplete line
                    () -> assertNull(rows.get(6).get("3")),
                    // last line, two-digit position value, missing column 3
                    () -> assertEquals("10", rows.get(9).get("1")),
                    () -> assertEquals("Judy", rows.get(9).get("2")),
                    () -> assertNull(rows.get(9).get("3"))
            );
        }
    }

    @Test
    public void selectsOnlyMatchingRecordType() throws IOException {
        try (var in = getClass().getResourceAsStream("discriminated.csv")) {
            var result = discriminatedAdapter().parse(in, "orders", Set.of("2", "3", "4"));

            var rows = result.rows().toList();
            // three O-lines only; the L-lines are filtered out
            assertEquals(3, rows.size());
            assertAll(
                    () -> assertEquals("1001", rows.get(0).get("2")),
                    () -> assertEquals("2026-01-05", rows.get(0).get("3")),
                    () -> assertEquals("ACME", rows.get(0).get("4")),
                    () -> assertEquals("1002", rows.get(1).get("2")),
                    () -> assertEquals("GLOBEX", rows.get(1).get("4")),
                    () -> assertEquals("1003", rows.get(2).get("2")),
                    () -> assertEquals("INITECH", rows.get(2).get("4"))
            );
        }
    }

    @Test
    public void sameFileYieldsDifferentRecordType() throws IOException {
        try (var in = getClass().getResourceAsStream("discriminated.csv")) {
            var result = discriminatedAdapter().parse(in, "lines", Set.of("2", "3", "4", "5"));

            var rows = result.rows().toList();
            // five L-lines only, with their own column layout
            assertEquals(5, rows.size());
            assertAll(
                    () -> assertEquals("widget", rows.get(0).get("3")),
                    () -> assertEquals("5", rows.get(0).get("4")),
                    () -> assertEquals("9.99", rows.get(0).get("5")),
                    () -> assertEquals("sprocket", rows.get(2).get("3")),
                    () -> assertEquals("flange", rows.get(4).get("3")),
                    () -> assertEquals("42.00", rows.get(4).get("5"))
            );
        }
    }
}
