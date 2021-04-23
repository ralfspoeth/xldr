package com.pd.xldr.spec.io;

import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import com.pd.xldr.spec.*;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

public class JsonMappingSpecReader implements MappingSpecReader {

    @Override
    public MappingSpec readFrom(Reader src) throws IOException {
        // JSON parser GSON
        var gson = new GsonBuilder().setLenient().create();
        var jsr = gson.newJsonReader(src);

        // to be read from the input stream
        InputSpec is = null;
        List<RecordMappingSpec> ms = null;
        OutputSpec os = null;

        // parser
        jsr.beginObject();
        while (jsr.hasNext()) {
            var elementName = jsr.nextName();
            switch (elementName) {
                case "input" -> is = readInputSpec(jsr);
                case "output" -> os = readOutputSpec(jsr);
                case "recordMapping" -> ms = readRecordMappingSpec(jsr);
                default -> {
                    logUnknown(elementName, "mapping");
                    jsr.skipValue();
                }
            }
        }
        jsr.endObject();

        return new MappingSpec(is, ms, os);
    }

    private List<RecordMappingSpec> readRecordMappingSpec(JsonReader jsr) throws IOException {
        List<RecordMappingSpec> rMaps = new ArrayList<>();
        jsr.beginArray();
        while (jsr.hasNext()) {
            rMaps.add(readRecordMapping(jsr));
        }
        jsr.endArray();
        return rMaps;
    }

    private RecordMappingSpec readRecordMapping(JsonReader jsr) throws IOException {
        String recSel = null, tabName = null;
        List<FieldMappingSpec> fmList = new ArrayList<>();
        jsr.beginObject();
        while (jsr.hasNext()) {
            var attr = jsr.nextName();
            switch (attr) {
                case "recordSelector" -> recSel = jsr.nextString();
                case "databaseTable" -> tabName = jsr.nextString();
                case "fieldMapping" -> {
                    jsr.beginArray();
                    while (jsr.hasNext()) {
                        jsr.beginObject();
                        fmList.add(readFieldMapping(jsr));
                        jsr.endObject();
                    }
                    jsr.endArray();
                }
                default -> {
                    logUnknown(attr, "recordMapping");
                    jsr.skipValue();
                }
            }
        }
        jsr.endObject();
        return new RecordMappingSpec(recSel, tabName, fmList);
    }

    private FieldMappingSpec readFieldMapping(JsonReader jsr) throws IOException {
        String fName = null, colName = null;
        while (jsr.hasNext()) {
            var attr = jsr.nextName();
            switch (attr) {
                case "fieldName" -> fName = jsr.nextString();
                case "databaseColumn" -> colName = jsr.nextString();
                default -> {
                    logUnknown(attr, "fieldMapping");
                    jsr.skipValue();
                }
            }
        }
        return (fName != null && colName != null) ? new FieldMappingSpec(fName, colName) : null;
    }

    private OutputSpec readOutputSpec(JsonReader jsr) throws IOException {
        jsr.beginObject();
        String url = null;
        Properties info = new Properties();
        while (jsr.hasNext()) {
            var attr = jsr.nextName();
            switch (attr) {
                case "url" -> url = jsr.nextString();
                case "info" -> {
                    jsr.beginObject();
                    while (jsr.hasNext()) {
                        info.setProperty(jsr.nextName(), jsr.nextString());
                    }
                    jsr.endObject();
                }
                default -> {
                    logUnknown(attr, "output");
                    jsr.skipValue();
                }
            }
        }
        jsr.endObject();
        return new OutputSpec(url, info);
    }

    private InputSpec readInputSpec(JsonReader jsr) throws IOException {
        String mt = null;
        var rs = new ArrayList<RecordSelectorSpec>();
        jsr.beginObject();
        while (jsr.hasNext()) {
            var isElemName = jsr.nextName();
            switch (isElemName) {
                case "mimeType" -> mt = jsr.nextString();
                case "recordSelectors" -> {
                    jsr.beginArray();
                    while (jsr.hasNext()) {
                        extractRecordSelectors(jsr, rs);
                    }
                    jsr.endArray();
                }
                default -> {
                    logUnknown(isElemName, "input");
                    jsr.skipValue();
                }
            }
        }
        jsr.endObject();
        return new InputSpec(mt, rs);
    }

    private void extractRecordSelectors(JsonReader jsr, ArrayList<RecordSelectorSpec> rs) throws IOException {
        jsr.beginObject();
        String rsName = null;
        String rsSelector = null;
        var fs = new ArrayList<FieldSelectorSpec>();
        while (jsr.hasNext()) {
            var rsElemName = jsr.nextName();
            switch (rsElemName) {
                case "name" -> rsName = jsr.nextString();
                case "selector" -> rsSelector = jsr.nextString();
                case "fieldSelectors" -> {
                    jsr.beginArray();
                    while (jsr.hasNext()) {
                        extractFieldSelectorSpec(jsr, fs);
                    }
                    jsr.endArray();
                }
                default -> {
                    logUnknown(rsElemName, "recordSelectors");
                    jsr.skipValue();
                }
            }
        }
        jsr.endObject();
        rs.add(new RecordSelectorSpec(rsName, rsSelector, fs));
    }

    private boolean extractFieldSelectorSpec(JsonReader jsr, ArrayList<FieldSelectorSpec> fs) throws IOException {
        String fsName = null, fsType = null, fsSelector = null;
        jsr.beginObject();
        while (jsr.hasNext()) {
            var fsElemName = jsr.nextName();
            switch (fsElemName) {
                case "name" -> fsName = jsr.nextString();
                case "type" -> fsType = jsr.nextString();
                case "selector" -> fsSelector = jsr.nextString();
                default -> {
                    logUnknown(fsElemName, "fieldSelector");
                    jsr.skipValue();
                }
            }
        }
        jsr.endObject();
        Type type;
        try {
            type = Type.valueOf(Optional.ofNullable(fsType).orElse("STRING").toUpperCase());
        } catch (Exception ex) {
            type = Type.STRING;
            logUnknown("type: " + fsType, "fieldSelector");
        }
        if (fsName != null) {
            return fs.add(new FieldSelectorSpec(fsName, fsSelector, type));
        } else {
            System.err.printf("invalid message selector definition with name: %s, selector: %s, and type: %s%n", fsName, fsSelector, fsType);
            return false;
        }
    }


    private static void logUnknown(String attr, String context) {
        System.err.printf("Field '%s' unknown in context '%s'%n", attr, context);
    }
}
