package com.pd.xldr.it;

import com.pd.xldr.ia.InputAdapter;
import com.pd.xldr.ia.InputAdapterFactory;
import com.pd.xldr.ldr.Loader;
import com.pd.xldr.spec.FieldMappingSpec;
import com.pd.xldr.spec.MappingSpec;
import com.pd.xldr.spec.io.JsonMappingSpecReader;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.SQLException;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

public class XmlTest1 {

    @Disabled
    @Test
    public void test1() throws IOException, SQLException {

        var spec = new JsonMappingSpecReader().readFrom(new InputStreamReader(getClass().getResourceAsStream("/test1.json")));

        final Loader ldr = new Loader(spec);

        final InputAdapter source = createInputAdapter(spec);

        {
            {
                spec.recordMappingSpecs().forEach(rm -> {
                    var rs = rm.recordSelector();
                    var fieldNames = rm.fieldMappings().stream()
                            .map(FieldMappingSpec::fieldName)
                            .distinct()
                            .collect(Collectors.toList());

                    try {
                        var result = source.parse(getClass().getResourceAsStream("/test1.xml"), rs, fieldNames);
                        result.rows().forEach(r -> {
                            var fields = result.fields().stream()
                                    .map(r::get)
                                    .collect(Collectors.toList());
                            System.out.println(fields);
                        });
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
    }


    private static InputAdapter createInputAdapter(MappingSpec spec) {
        InputAdapter tmp = null;
        for (var iaf : ServiceLoader.load(InputAdapterFactory.class)) {
            if (iaf.accepts(spec.inputSpec().mimeType())) {
                tmp = iaf.createInputAdapter(spec.inputSpec());
                break;
            }
        }
        if (tmp == null) {
            throw new IllegalStateException("couldn't create an input adapter");
        }
        return tmp;
    }

}
