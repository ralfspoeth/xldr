package com.pd.xldr.spec.io;

import com.pd.xldr.spec.*;
import io.github.ralfspoeth.json.Element;
import io.github.ralfspoeth.json.io.JsonReader;
import io.github.ralfspoeth.json.query.Queries;

import java.io.IOException;
import java.io.Reader;
import java.util.Map;
import java.util.Objects;

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toMap;
import static io.github.ralfspoeth.json.query.Queries.*;

/**
 * Reads a JSON mapping specification; the spec by example is given below.
 *
 *
 */
public class JsonMappingSpecReader implements MappingSpecReader {

    @Override
    public MappingSpec readFrom(Reader src) throws IOException {
        try(var jsonRdr = new JsonReader(src)) {
            var elem = jsonRdr.readElement();
            return new MappingSpec(
                    inputSpec(get(elem, "input")),
                    elements(get(elem, "mapping"))
                            .stream()
                            .map(this::recordMappingSpec)
                            .toList(),
                    outputSpec(get(elem, "output"))
            );
        }
    }

    private InputSpec inputSpec(Element element) {
        return new InputSpec(stringValue(get(element, "mimeType"), null),
                elements(get(element, "recordSelectors"))
                        .stream()
                        .map(this::recordSelectorSpec)
                        .toList()
        );
    }

    private OutputSpec outputSpec(Element element) {
        return new OutputSpec(
                stringValue(members(element).get("url"), null),
                members(get(element, "info"))
                        .entrySet()
                        .stream()
                        .collect(toMap(Map.Entry::getKey, e -> stringValue(e.getValue())))
        );
    }

    private RecordMappingSpec recordMappingSpec(Element element) {
        return new RecordMappingSpec(
                stringValue(get(element, "recordSelector"), null),
                stringValue(get(element, "databaseTable"), null),
                elements(get(element, "fieldMapping"))
                        .stream()
                        .filter(e -> Objects.nonNull(get(e, "fieldSelector")))
                        .map(this::fieldMappingSpec).toList()
        );
    }

    private FieldMappingSpec fieldMappingSpec(Element element) {
        return new FieldMappingSpec(
                stringValue(get(element, "fieldSelector"), null),
                stringValue(get(element, "databaseColumnName"), null)
        );
    }

    private RecordSelectorSpec recordSelectorSpec(Element element) {
        return new RecordSelectorSpec(
                stringValue(get(element, "name"), null),
                stringValue(get(element, "selector"), null),
                elements(get(element, "fieldSelectors"))
                        .stream()
                        .map(this::fieldSelectorSpec)
                        .toList()
        );
    }

    private FieldSelectorSpec fieldSelectorSpec(Element element) {
        return new FieldSelectorSpec(
                stringValue(get(element, "name"), null),
                stringValue(get(element, "selector"), null),
                ofNullable(get(element, "type"))
                        .map(Queries::stringValue)
                        .map(String::toUpperCase)
                        .map(Type::valueOf)
                        .orElse(null)
        );
    }
}
