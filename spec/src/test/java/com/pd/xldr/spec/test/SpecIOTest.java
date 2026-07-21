package com.pd.xldr.spec.test;

import com.pd.xldr.spec.*;
import com.pd.xldr.spec.io.JsonMappingSpecReader;
import com.pd.xldr.spec.io.PropertiesMappingSpecReader;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;


public class SpecIOTest {

    @Test
    public void testIO() throws IOException {
        var props = System.getProperties().entrySet().stream()
                .map(e -> Map.entry((String) e.getKey(), (String) e.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        var spec = new MappingSpec(
                new InputSpec("text/xml", List.of(new RecordSelectorSpec("root", "/", List.of(new FieldSelectorSpec("id", "@id", DataType.STRING))))),
                List.of(new RecordMappingSpec("root", "sntrx", List.of(new FieldMappingSpec("id", "id_txt")))),
                new OutputSpec("jdbc:oracle:thin:@localhost:1521:xe", props)
        );
        var of = File.createTempFile("spec", ".ser");
        try (var oo = new ObjectOutputStream(new FileOutputStream(of))) {
            oo.writeObject(spec);
        }
    }


    @Test
    public void testSimple() throws IOException {
        var specSrc = """
                {
                    "input": {
                        "mimeType": "text/xml",
                        "recordSelectors":[{
                            "name": "*all*",
                            "selector": "//",
                            "fieldSelectors":[{"name": "id", "selector": "@id"}]
                        }]
                    },
                    "mapping":[],
                    "output": {
                        "url": "jdbc:oracle:thin://localhost:1521/xe",
                        "info": {
                            "user": "heinz",
                            "password": "geheim"
                        }
                    }
                }""";

        var mappingSpecReader = new JsonMappingSpecReader();
        MappingSpec result = mappingSpecReader.readFrom(new StringReader(specSrc));
        MappingSpec expected = new MappingSpec(
                new InputSpec("text/xml", List.of(
                        new RecordSelectorSpec("*all*", "//", List.of(
                                new FieldSelectorSpec("id", "@id", null)
                        )))
                ),
                List.of(),
                new OutputSpec("jdbc:oracle:thin://localhost:1521/xe",
                        Map.of("user", "heinz", "password", "geheim")
                )
        );
        assertAll(
                () -> assertEquals(expected, result)
        );
    }


    @Test
    public void testJsonInput() throws IOException {
        var reader = new StringReader("""
                {
                    "input": {
                        "unk": false,
                        "mimeType": "text/xml",
                        "recordSelectors": [
                            {
                                "name": "fund",
                                "selector": "//fund",
                                "fieldSelectors": [
                                    {
                                        "name": "id",
                                        "type": "String",
                                        "tüp": false,
                                        "selector": "@fund"
                                    }
                                ]
                            },
                            {
                                "name": "position",
                                "selector": "//pos",
                                "a": false,
                                "fieldSelectors": [
                                    {
                                        "name": "aaaa",
                                        "selector": "huj",
                                        "f": true,
                                        "g": "oh, doppelt..."
                                    }
                                ]
                            }
                        ]
                    },
                    "liliput": true,
                    "output": {
                        "url": "jdbc:mock:dbx",
                        "looser": true,
                        "info": {
                            "user": "usr",
                            "pwd": "geheim"
                        }
                    },
                    "mapping": [
                        {
                            "recordSelector": "fund",
                            "databaseTable": "snmandat",
                            "fieldMapping": [
                                {
                                    "fieldSelector": "id",
                                    "databaseColumn": "ident1_txt"
                                }
                            ]
                        },
                        {
                            "recordSelector": "fund",
                            "databaseTable": "snlieferung",
                            "fieldMapping": [
                                {
                                    "fieldSelector": "id",
                                    "databaseColumn": "lieferung_nr",
                                    "fieldName": "PD",
                                    "databaseColumn": "schnittstelle_cd"
                                }
                            ]
                        }
                    ]
                }
                """);
        var xmlSource = """
                <?xml version='1.0'?>
                <root>
                    <fund id='1234'>
                
                    </fund>
                </root>""";
        var ms = new JsonMappingSpecReader().readFrom(reader);
        System.out.println(ms);
    }

    @Test
    public void testPropertiesReader() throws IOException {
        var propSource = """
                input.mimeType=text/csv
                input.recordSelectors.1.name=t1
                input.recordSelectors.1.selector=tsel1
                input.recordSelectors.1.fieldSelectors.1.name=c1
                input.recordSelectors.1.fieldSelectors.1.selector=1
                input.recordSelectors.2.name=t2
                input.recordSelectors.2.selector=tsel2
                input.recordSelectors.2.fieldSelectors.1.name=c1
                input.recordSelectors.2.fieldSelectors.1.selector=csel1
                input.recordSelectors.2.fieldSelectors.2.name=c2
                input.recordSelectors.2.fieldSelectors.2.selector=csel2
                output.url=jdbc:oracle:thin://localhost/xe
                output.info.user=heinz
                output.info.password=geheim
                recordMappings.1.recordSelector=t1
                recordMappings.1.databaseTable=sntrx
                recordMappings.1.fieldMappings.1.fieldName=c1
                recordMappings.1.fieldMappings.1.databaseColumn=lieferung_nr
                recordMappings.2.recordSelector=t1
                recordMappings.2.databaseTable=sntrx
                recordMappings.2.fieldMappings.1.fieldName=c1
                recordMappings.2.fieldMappings.1.databaseColumn=lieferung_nr
                recordMappings.2.fieldMappings.2.fieldName=c2
                recordMappings.2.fieldMappings.2.databaseColumn=sort_no
                """;

        var ms = new PropertiesMappingSpecReader().readFrom(new StringReader(propSource));
        System.out.println(ms);
    }
}