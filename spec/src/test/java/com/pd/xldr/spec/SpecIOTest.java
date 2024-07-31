package com.pd.xldr.spec;

import com.pd.xldr.spec.io.JsonMappingSpecReader;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;


class SpecIOTest {

    @Test
    void testIO() throws IOException {
        var props = System.getProperties().entrySet().stream()
                .map(e -> Map.entry((String)e.getKey(), (String)e.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        var spec = new MappingSpec(
                new InputSpec("text/xml", List.of(new RecordSelectorSpec("root", "/", List.of(new FieldSelectorSpec("id", "@id", Type.STRING))))),
                List.of(new RecordMappingSpec("root", "sntrx", List.of(new FieldMappingSpec("id", "id_txt")))),
                new OutputSpec("jdbc:oracle:thin:@localhost:1521:xe", props)
        );
        var of = File.createTempFile("spec", ".ser");
        try (var oo = new ObjectOutputStream(new FileOutputStream(of))) {
            oo.writeObject(spec);
        }
        System.err.println(of.getAbsolutePath());
    }


    @Test
    void testSimple() throws IOException {
        var specSrc = """
                {
                    "input": {
                        "mimeType": "text/xml"
                    },
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
                new InputSpec("text/xml", List.of()),
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
    void testJsonInput() throws IOException {
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
                                "a": false,
                                "fieldSelectors": [
                                    {
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
                        "idnfo": {
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
                                    "fieldName": "id",
                                    "databaseColumn": "ident1_txt"
                                }
                            ]
                        },
                        {
                            "recordSelector": "fund",
                            "databaseTable": "snlieferung",
                            "fieldMapping": [
                                {
                                    "fieldName": "id",
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
}