package com.pd.xldr.csv.test;

import com.pd.xldr.ia.InputAdapter;
import com.pd.xldr.ia.Field;
import com.pd.xldr.ia.InputAdapterFactory;
import com.pd.xldr.spec.DataType;
import com.pd.xldr.spec.FieldSelectorSpec;
import com.pd.xldr.spec.InputSpec;
import com.pd.xldr.spec.RecordSelectorSpec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class CsvFileHandlerTest {

    // input spec mentions only id and name; the file also carries short-name/long-name
    private static final InputSpec SPEC = new InputSpec("text/csv", List.of(
            new RecordSelectorSpec("people", "people", List.of(
                    new FieldSelectorSpec("id", "id", DataType.STRING),
                    new FieldSelectorSpec("name", "name", DataType.STRING)
            ))
    ));

    // no header: columns are addressed by 1-based position ("1" -> col 0, ...)
    private static final InputSpec POSITIONAL_SPEC = new InputSpec("text/csv", List.of(
            new RecordSelectorSpec("people", "people", List.of(
                    new FieldSelectorSpec("1", "1", DataType.STRING),
                    new FieldSelectorSpec("2", "2", DataType.STRING),
                    new FieldSelectorSpec("3", "3", DataType.STRING)
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
}
