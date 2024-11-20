import com.pd.xldr.ia.*;
import com.pd.xldr.spec.FieldSelectorSpec;
import com.pd.xldr.spec.InputSpec;
import com.pd.xldr.spec.RecordSelectorSpec;
import com.pd.xldr.spec.Type;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

class InputAdapterFactoryTest {

    @Test
    void testFactory1() {
        var factory = new InputAdapterFactory() {

            @Override
            public boolean accepts(String mimeType) {
                return true;
            }

            @Override
            public InputAdapter createInputAdapter(InputSpec spec) {
                return new InputAdapter() {
                    @Override
                    public Result parse(InputStream source, String recordSelector, Set<String> fieldSelectors) throws IOException {
                        var rs = spec.recordSelectors()
                                .stream()
                                .filter(r -> recordSelector.equals(r.name()))
                                .findFirst()
                                .orElseThrow();
                        var fs = rs.fieldSelectors().stream()
                                .filter(x -> fieldSelectors.contains(x.name()))
                                .map(x -> new Field(x.name(), x.type().clazz()))
                                .toList();
                        return new Result(fs, Stream.of(
                                Map.of("c1", 1)::get
                        ));
                    }
                };
            }
        };

        var ia = factory.createInputAdapter(new InputSpec("in-mem"), List.of(new RecordSelectorSpec(
                "t1", "*", List.of(
                        new FieldSelectorSpec("c1", "*", Type.INTEGER)
        ))));
        var src= """
                """;
    }

}
