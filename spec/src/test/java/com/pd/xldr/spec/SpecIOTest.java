package com.pd.xldr.spec;

import com.pd.xldr.spec.io.JsonMappingSpecReader;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.List;


public class SpecIOTest {

    @Test
    public void testIO() throws IOException {
        var spec = new MappingSpec(
                new InputSpec("text/xml", List.of(new RecordSelectorSpec("root", "/", List.of(new FieldSelectorSpec("id", "@id", Type.STRING))))),
                List.of(new RecordMappingSpec("root", "sntrx", List.of(new FieldMappingSpec("id", "id_txt")))),
                new OutputSpec("jdbc:oracle:thin:@localhost:1521:xe", System.getProperties())
        );
        var of = File.createTempFile("spec", ".ser");
        try (var oo = new ObjectOutputStream(new FileOutputStream(of))) {
            oo.writeObject(spec);
        }
        System.err.println(of.getAbsolutePath());
    }

    @Test
    public void testGson() throws IOException {
        var reader = new StringReader("""
                {
                    input: {
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
                                a: false,
                                "fieldSelectors": [
                                    {
                                        f: true,
                                        "f": "oh, doppelt..."
                                    }
                                ]
                            }
                        ]
                    },
                    liliput: true;
                    "output": {
                        "url": "jdbc:mock:dbx",
                        "looser": true,
                        "info": {
                            "user": "usr",
                            "pwd": "geheim"
                        }
                    },
                    "recordMapping": [
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
                </root>
                    """;
        var ms = new JsonMappingSpecReader().readFrom(reader);
        System.out.println(ms);
    }
}