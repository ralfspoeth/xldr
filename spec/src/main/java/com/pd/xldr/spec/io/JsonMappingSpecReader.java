package com.pd.xldr.spec.io;

import com.pd.xldr.spec.*;
import io.github.ralfspoeth.json.Greyson;
import io.github.ralfspoeth.json.data.JsonValue;
import io.github.ralfspoeth.json.query.Pointer;
import io.github.ralfspoeth.json.query.Selector;

import java.io.IOException;
import java.io.Reader;

/**
 * Reads a JSON mapping specification; the spec by example is given below.
 *
 *
 */
public class JsonMappingSpecReader implements MappingSpecReader {

    @Override
    public MappingSpec readFrom(Reader src) throws IOException {
        return Greyson.readValue(src)
                .map(v -> new MappingSpec(
                        PTR.member("input").apply(v).map(JsonMappingSpecReader::inputSpec).orElseThrow(),
                        PTR.member("mapping").select(Selector.all()).apply(v).map(JsonMappingSpecReader::recordMappingSpec).toList(),
                        PTR.member("load").apply(v).map(JsonMappingSpecReader::loadSpec).orElseGet(LoadSpec::new)
                ))
                .orElseThrow();
    }

    private static InputSpec inputSpec(JsonValue is) {
        return new InputSpec(
                PTR.member("mimeType").stringOrThrow(is),
                PTR.member("sentinel").stringValue(is).orElse(null),
                PTR.member("recordSelectors")
                        .select(Selector.all())
                        .apply(is)
                        .map(JsonMappingSpecReader::recordSelectorSpec)
                        .toList()
        );
    }

    private static LoadSpec loadSpec(JsonValue ls) {
        return new LoadSpec(
                PTR.member("commitPolicy")
                        .stringValue(ls)
                        .map(String::toUpperCase)
                        .map(CommitPolicy::valueOf)
                        .orElse(null)
        );
    }

    private static RecordMappingSpec recordMappingSpec(JsonValue element) {
        return new RecordMappingSpec(
                PTR.member("recordSelector").stringOrThrow(element),
                PTR.member("databaseTable").stringOrThrow(element),
                PTR.member("fieldMapping")
                        .select(Selector.all())
                        .apply(element)
                        .map(JsonMappingSpecReader::fieldMappingSpec)
                        .toList()
        );
    }

    private static FieldMappingSpec fieldMappingSpec(JsonValue fm) {
        return new FieldMappingSpec(
                PTR.member("fieldSelector").stringOrThrow(fm),
                PTR.member("databaseColumn").stringOrThrow(fm)
        );
    }

    private static RecordSelectorSpec recordSelectorSpec(JsonValue rs) {
        return new RecordSelectorSpec(
                PTR.member("name").stringOrThrow(rs),
                PTR.member("selector").stringOrThrow(rs),
                PTR.member("fieldSelectors")
                        .select(Selector.all())
                        .apply(rs)
                        .map(JsonMappingSpecReader::fieldSelectorSpec)
                        .toList()
        );
    }

    private static FieldSelectorSpec fieldSelectorSpec(JsonValue fs) {
        return new FieldSelectorSpec(
                PTR.member("name").stringOrThrow(fs),
                PTR.member("selector").stringOrThrow(fs),
                PTR.member("type")
                        .stringValue(fs)
                        .map(String::toUpperCase)
                        .map(DataType::valueOf)
                        .orElse(null)
        );
    }

    private static final Pointer PTR = Pointer.self();
}
