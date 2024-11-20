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
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class PropertiesMappingSpecReader implements MappingSpecReader {

    private static void insert(String name, String value, Directory<String, String> into) {
        var index = name.indexOf('.');
        if (index != -1) {
            var group = name.substring(0, index - 1);
            var remainder = name.substring(index + 1);
            var intoSub = into.subDirectory(group);
            insert(remainder, value, intoSub == null ? into.addSubDirectory(group) : intoSub);
        } else {
            into.addResource(name, value);
        }
    }


    private static Directory<String, String> parse(Properties props) {
        var propsHierarchy = Directory.<String, String>newRootDirectory();
        props.stringPropertyNames().forEach(n -> insert(n, props.getProperty(n), propsHierarchy));
        return propsHierarchy;
    }


    record Path(Path parent, String name){
        Path(String name) {
            this(null, name);
        }
        static Path of(String propertyName) {
            var parts = propertyName.split("\\.");
            var root = new Path(parts[0]);
            var p = root;
            for(int i=1; i<parts.length; i++) {
                p = new Path(p, parts[i]);
            }
            return p;
        }
        String propertyName() {
            return parent==null?name:parent().propertyName() + "." + name;
        }
    }

    sealed interface Node permits Parent, Leaf {}

    record Leaf(String propertyName, String propertyValue) implements Node {}

    final static class Parent implements Node {
        final Map<String, Node> children = new HashMap<>();
        void insert(Leaf l) {
            String[] parts = l.propertyName.split("\\.");
            Parent root = this;
            for(int i=0; i< parts.length-1; i++) {
                root = (Parent) root.children.computeIfAbsent(parts[i], _->new Parent());
            }
            root.children.put(parts[parts.length-1], l);
        }
    }

    static Parent propertyTree(Properties props) {
        var root = new Parent();
        props.stringPropertyNames()
                .stream()
                .map(n -> new Leaf(n, props.getProperty(n)))
                .forEach(root::insert);
        return root;
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
                                                        Type.valueOf(f.resource("type")
                                                                .map(Resource::value)
                                                                .orElse(Type.STRING.name())))
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
                                .collect(toMap(Map.Entry::getKey, e -> e.getValue().value(e.getKey())))
                )
        );
    }
}
