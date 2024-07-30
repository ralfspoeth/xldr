package com.pd.xldr.spec.io;

import com.pd.xldr.spec.*;
import io.github.ralfspoeth.json.JsonObject;
import io.github.ralfspoeth.json.io.JsonReader;

import java.io.IOException;
import java.io.Reader;

import static io.github.ralfspoeth.json.query.Queries.members;
import static io.github.ralfspoeth.json.query.Queries.stringValue;

public class JsonMappingSpecReader implements MappingSpecReader {

    @Override
    public MappingSpec readFrom(Reader src) throws IOException {
        try(var jsonRdr = new JsonReader(src)) {
            return switch(jsonRdr.readElement()) {
                case JsonObject jo -> new MappingSpec(
                        new InputSpec(stringValue(members(jo).get("input")))
                );
                case null, default -> throw new IllegalArgumentException("source not a JSON object");
            };
        }
    }
}
