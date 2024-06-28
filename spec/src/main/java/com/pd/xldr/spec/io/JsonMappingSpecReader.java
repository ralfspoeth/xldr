package com.pd.xldr.spec.io;

import com.pd.xldr.spec.*;
import io.github.ralfspoeth.json.JsonObject;
import io.github.ralfspoeth.json.conv.StandardConversions;
import io.github.ralfspoeth.json.io.JsonReader;

import java.io.IOException;
import java.io.Reader;

public class JsonMappingSpecReader implements MappingSpecReader {

    @Override
    public MappingSpec readFrom(Reader src) throws IOException {
        try(var jsonRdr = new JsonReader(src)) {
            return switch(jsonRdr.readElement()) {
                case JsonObject jo -> StandardConversions.as(MappingSpec.class, jo);
                case null, default -> throw new IllegalArgumentException("source not a JSON object");
            };
        }
    }
}
