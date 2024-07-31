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
                    inputSpec(members(elem).get("input")),
                    elements(members(elem).get("mapping"))
                            .stream()
                            .map(this::recordMappingSpec)
                            .toList(),
                    outputSpec(members(elem).get("output"))
            );
        }
    }

    private InputSpec inputSpec(Element element) {
        return new InputSpec(stringValue(members(element).get("mimeType"), null),
                elements(members(element).get("recordSelectors"))
                        .stream()
                        .map(this::recordSelectorSpec)
                        .toList()
        );
    }

    private OutputSpec outputSpec(Element element) {
        return new OutputSpec(
                stringValue(members(element).get("url"), null),
                members(members(element).get("info"))
                        .entrySet()
                        .stream()
                        .collect(toMap(Map.Entry::getKey, e -> stringValue(e.getValue())))
        );
    }

    private RecordMappingSpec recordMappingSpec(Element element) {
        return new RecordMappingSpec(
                stringValue(members(element).get("recordSelector"), null),
                stringValue(members(element).get("databaseTable"), null),
                elements(members(element).get("fieldMapping"))
                        .stream()
                        .filter(e -> Objects.nonNull(members(e).get("fieldSelector")))
                        .map(this::fieldMappingSpec).toList()
        );
    }

    private FieldMappingSpec fieldMappingSpec(Element element) {
        return new FieldMappingSpec(
                stringValue(members(element).get("fieldSelector"), null),
                stringValue(members(element).get("databaseColumnName"), null)
        );
    }

    private RecordSelectorSpec recordSelectorSpec(Element element) {
        return new RecordSelectorSpec(
                stringValue(members(element).get("name"), null),
                stringValue(members(element).get("selector"), null),
                elements(members(element).get("fieldSelectors"))
                        .stream()
                        .map(this::fieldSelectorSpec)
                        .toList()
        );
    }

    private FieldSelectorSpec fieldSelectorSpec(Element element) {
        return new FieldSelectorSpec(
                stringValue(members(element).get("name"), null),
                stringValue(members(element).get("selector"), null),
                ofNullable(members(element).get("type"))
                        .map(Queries::stringValue)
                        .map(String::toUpperCase)
                        .map(Type::valueOf)
                        .orElse(null)
        );
    }
}
