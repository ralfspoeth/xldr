package com.pd.xldr.spec.io;

import com.pd.xldr.spec.*;
import io.github.ralfspoeth.dirs.Directory;
import io.github.ralfspoeth.dirs.Resource;

import java.io.IOException;
import java.io.Reader;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static java.util.stream.Collectors.toMap;

public class PropertiesMappingSpecReader implements MappingSpecReader {

    private static void insert(String name, String value, Directory<String, String> into) {
        var index = name.indexOf('.');
        if (index > -1) {
            var group = name.substring(0, index);
            var remainder = name.substring(index + 1);
            if(into.subDirectory(group)==null) {
                var intoSub = into.addSubDirectory(group).subDirectory(group);
                insert(remainder, value, intoSub);
            } else {
                insert(remainder, value, into.subDirectory(group));
            }
        } else {
            into.addResource(name, value);
        }
    }

    private static Directory<String, String> parse(Properties props) {
        var propsHierarchy = Directory.<String, String>newRootDirectory();
        props.stringPropertyNames().forEach(n -> insert(n, props.getProperty(n), propsHierarchy));
        return propsHierarchy;
    }

    @Override
    public MappingSpec readFrom(Reader source) throws IOException {
        var props = new Properties();
        props.load(source);
        var root = parse(props);

        return new MappingSpec(
                new InputSpec(root.get("input").map(i -> i.value("mimeType")).orElse(null),
                        root.get("input").filter(Directory.class::isInstance)
                                .map(n -> (Directory<String, String>) n)
                                .map(i -> i.values("recordSelectors"))
                                .orElse(List.of())
                                .stream()
                                .map(r -> new RecordSelectorSpec(
                                        r.value("name"),
                                        r.value("selector"),
                                        r.values("fieldSelectors")
                                                .stream()
                                                .map(f -> new FieldSelectorSpec(
                                                        f.value("name"),
                                                        f.value("selector"),
                                                        DataType.valueOf(f.resource("type")
                                                                .map(Resource::value)
                                                                .orElse(DataType.STRING.name())))
                                                )
                                                .toList())
                                )
                                .toList()),
                root.values("recordMappings")
                        .stream()
                        .map(n -> new RecordMappingSpec(
                                n.value("recordSelector"),
                                n.value("databaseTable"),
                                n.values("fieldMappings").stream()
                                        .map(f -> new FieldMappingSpec(
                                                f.value("fieldName"),
                                                f.value("databaseColumn"))
                                        ).toList()))
                        .toList(),
                new OutputSpec(
                        root.get("output")
                                .map(o -> o.value("url"))
                                .orElse(null),
                        root.get("output")
                                .map(o -> o.children("info"))
                                .orElse(Map.of())
                                .entrySet()
                                .stream()
                                .filter(e -> e.getValue() instanceof Resource)
                                .map(e -> Map.entry(e.getKey(), (Resource<String, String>) e.getValue()))
                                .collect(toMap(Map.Entry::getKey, e -> e.getValue().value()))
                )
        );
    }
}
