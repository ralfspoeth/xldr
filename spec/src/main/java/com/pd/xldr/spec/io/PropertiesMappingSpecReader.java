package com.pd.xldr.spec.io;

import com.pd.xldr.spec.*;

import java.io.IOException;
import java.io.Reader;
import java.util.*;

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toMap;

public class PropertiesMappingSpecReader implements MappingSpecReader {

    private sealed interface Node permits Leaf, NonLeaf {
        Node parent();

        Map<String, Node> children();

        Optional<Node> get(String name);

        default Optional<Leaf> leaf(String name) {
            return get(name).filter(Leaf.class::isInstance).map(Leaf.class::cast);
        }

        default String leafValue(String name) {
            return leaf(name).map(Leaf::value).orElse(null);
        }

        default Map<String, Node> children(String name) {
            return get(name).map(Node::children).orElse(Map.of());
        }

        default Collection<Node> values(String name) {
            return get(name).map(Node::children).orElse(Map.of()).values();
        }
    }

    record Leaf(Node parent, String value) implements Node {
        @Override
        public Map<String, Node> children() {
            return Map.of();
        }

        public Optional<Node> get(String name) {
            return Optional.empty();
        }
    }

    static abstract sealed class NonLeaf implements Node permits Root, SubTree {

        private final Map<String, Node> children = new HashMap<>();

        @Override
        public Map<String, Node> children() {
            return children;
        }

        public Optional<Node> get(String name) {
            return ofNullable(children.get(name));
        }

        void add(String name, String value) {
            int index = name.indexOf('.');
            if (index != -1) {
                var group = name.substring(0, index);
                var n = children.computeIfAbsent(group, (k) -> new SubTree(this));
                if (n instanceof SubTree st) {
                    st.add(name.substring(index + 1), value);
                } else {
                    throw new IllegalStateException(n + " should have been a SubTree");
                }
            } else {
                children.put(name, new Leaf(this, value));
            }
        }
    }

    static final class SubTree extends NonLeaf {
        private final Node parent;

        SubTree(Node parent) {
            this.parent = parent;
        }

        @Override
        public Node parent() {
            return parent;
        }

    }

    static final class Root extends NonLeaf {
        @Override
        public Node parent() {
            return null;
        }
    }

    private static Root parse(Properties props) {
        var root = new Root();
        props.stringPropertyNames().forEach(n -> root.add(n, props.getProperty(n)));
        return root;
    }


    @Override
    public MappingSpec readFrom(Reader source) throws IOException {
        var props = new Properties();
        props.load(source);
        var root = parse(props);

        return new MappingSpec(new InputSpec(
                root.get("input").map(i -> i.leafValue("mimeType")).orElse(null),
                root.get("input").map(i -> i.values("recordSelectors"))
                        .orElse(List.of())
                        .stream()
                        .map(r -> new RecordSelectorSpec(
                                r.leafValue("name"),
                                r.leafValue("selector"),
                                r.values("fieldSelectors").stream().map(f -> new FieldSelectorSpec(
                                        f.leafValue("name"),
                                        f.leafValue("selector"),
                                        Type.valueOf(f.leaf("type").map(Leaf::value).orElse(Type.STRING.name()))
                                )).toList()
                        )).toList()
        ), root.values("recordMappings").stream().map(
                n -> new RecordMappingSpec(
                        n.leafValue("recordSelector"),
                        n.leafValue("databaseTable"),
                        n.values("fieldMappings").stream().map(f -> new FieldMappingSpec(
                                f.leafValue("fieldName"),
                                f.leafValue("databaseColumn")
                        )).toList()
                )).toList()
        , new OutputSpec(
                root.get("output").map(o -> o.leafValue("url")).orElse(null),
                root.get("output").map(o -> o.children("info")).orElse(Map.of())
                        .entrySet()
                        .stream()
                        .collect(toMap(Map.Entry::getKey, e -> ofNullable(e.getValue())
                                .filter(Leaf.class::isInstance)
                                .map(Leaf.class::cast)
                                .map(Leaf::value)
                                .orElseThrow()))
        ));
    }
}
