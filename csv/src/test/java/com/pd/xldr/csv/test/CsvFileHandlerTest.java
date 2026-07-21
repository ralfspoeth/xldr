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

public class CsvFileHandlerTest {

    // input spec mentions only id and name; the file also carries short-name/long-name
    private static final InputSpec SPEC = new InputSpec("text/csv", List.of(
            new RecordSelectorSpec("people", "people", List.of(
                    new FieldSelectorSpec("id", "id", DataType.STRING),
                    new FieldSelectorSpec("name", "name", DataType.STRING)
            ))
    ));

    private InputAdapter adapter() {
        var factory = ServiceLoader.load(InputAdapterFactory.class).findFirst().orElseThrow();
        factory.setProperty("fieldSeparator", ",");
        factory.setProperty("rowSeparator", "\n");
        return factory.createInputAdapter(SPEC);
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
}
