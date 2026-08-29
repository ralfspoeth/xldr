package io.github.ralfspoeth.xldr.spec.io;

import io.github.ralfspoeth.xldr.spec.*;
import io.github.ralfspoeth.xmls.Xml;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Element;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

import static io.github.ralfspoeth.xmls.XmlFunctions.attributeValue;
import static io.github.ralfspoeth.xmls.XmlFunctions.elements;
import static java.util.stream.Collectors.joining;

/**
 * Reads a mapping specification from XML. Everything is carried in attributes,
 * the nesting mirrors the structure of the spec:
 *
 * <pre>
 * &lt;mappingSpec&gt;
 *     &lt;input mimeType="text/xml"&gt;
 *         &lt;recordSelector name="fund" selector="/root/fund"&gt;
 *             &lt;fieldSelector name="id" selector="@id" type="TEXT"/&gt;
 *         &lt;/recordSelector&gt;
 *     &lt;/input&gt;
 *     &lt;mapping recordSelector="fund" table="snmandat"&gt;
 *         &lt;fieldMapping fieldSelector="id" column="ident1_txt"/&gt;
 *     &lt;/mapping&gt;
 *     &lt;transform name="close_batch"&gt;
 *         &lt;arg var="batch"/&gt;
 *     &lt;/transform&gt;
 * &lt;/mappingSpec&gt;
 * </pre>
 * <p>
 * The element and attribute names are those of the JSON format, so a spec can
 * be transliterated between the two without renaming anything. {@code type} is
 * optional.
 * <p>
 * Elements and attributes the reader does not recognize are ignored at every
 * level, so an author may annotate a spec - for instance with a
 * {@code <comments>} element - without breaking it. No name is reserved.
 * {@code load} was, held since 0.2 against the return of the commit policy it
 * once carried; what a deployment needs to say about where a load goes is now
 * {@code target.properties} beside the spec, and a name kept open for something
 * that is not coming back is only a trap for whoever picks it.
 */
public class XmlMappingSpecReader implements MappingSpecReader {

    @Override
    public boolean accepts(Path path) {
        return path.getFileSystem()
                .getPathMatcher("glob:*.xml")
                .matches(path.getFileName());
    }

    @Override
    public MappingSpec read(InputStream source) {
        var root = Xml.parse(source).getDocumentElement();
        return new MappingSpec(
                elements("input")
                        .apply(root)
                        .findFirst()
                        .map(XmlMappingSpecReader::inputSpec)
                        .orElseThrow(() -> new IllegalArgumentException("no <input> element")),
                elements("mapping")
                        .apply(root)
                        .map(XmlMappingSpecReader::recordMappingSpec)
                        .toList(),
                elements("transform")
                        .apply(root)
                        .map(XmlMappingSpecReader::procedureCall)
                        .toList()
        );
    }

    /**
     * A procedure to call once the input has been loaded, one {@code <arg>} per
     * argument:
     *
     * <pre>
     * &lt;transform name="close_batch"&gt;
     *     &lt;arg var="batch"/&gt;
     *     &lt;arg expr="${xldr.rowsLoaded}"/&gt;
     * &lt;/transform&gt;
     * </pre>
     * <p>
     * The same {@code <arg>} an {@code <fn>} takes, read by the same method, so
     * an argument may be a nested lookup or call and nothing new had to be
     * invented. No {@code type} attribute, where an {@code <fn>} requires one:
     * nothing comes back from a procedure.
     */
    private static ProcedureCall procedureCall(Element transform) {
        return new ProcedureCall(
                required(transform, "name"),
                elements("arg").apply(transform)
                        .map(XmlMappingSpecReader::valueSource)
                        .toList());
    }

    private static InputSpec inputSpec(Element input) {
        return new InputSpec(
                required(input, "mimeType"),
                elements("recordSelector")
                        .apply(input)
                        .map(XmlMappingSpecReader::recordSelectorSpec)
                        .toList(),
                elements("var")
                        .apply(input)
                        .map(XmlMappingSpecReader::varSpec)
                        .toList(),
                properties(input)
        );
    }

    /**
     * The settings of the adapter the input selects, carried as the attributes of
     * a {@code <properties>} child - {@code fieldSeparator}, {@code dateFormat},
     * {@code ns.f}, whatever that adapter understands:
     *
     * <pre>
     * &lt;properties fieldSeparator="," header="false"/&gt;
     * </pre>
     */
    private static Map<String, String> properties(Element input) {
        Map<String, String> properties = new LinkedHashMap<>();
        elements("properties").apply(input).forEach(element -> {
            var attributes = element.getAttributes();
            for (int i = 0; i < attributes.getLength(); i++) {
                var attribute = attributes.item(i);
                properties.put(attribute.getNodeName(), attribute.getNodeValue());
            }
        });
        return properties;
    }

    /**
     * A var reads whatever a field mapping reads, minus a field - which
     * {@link VarSpec} refuses itself, at any depth, so there is nothing to check
     * here. It used to be checked here and only at the top level, so a field
     * inside a lookup key slipped through and failed at load instead.
     */
    private static VarSpec varSpec(Element element) {
        return new VarSpec(required(element, "name"), valueSource(element));
    }

    /**
     * A {@code selector} attribute points at records in a tree or a sheet; a
     * {@code <discriminator>} child picks lines out of a flat file; and neither
     * says that the whole input holds one kind of record, which a CSV with a
     * header or a fixed-length file usually does. The three are the cases of
     * {@link io.github.ralfspoeth.xldr.spec.Locator}, and which of them this is
     * gets decided once, in {@link SpecNode#locator}.
     *
     * <pre>
     * &lt;recordSelector name="orders"&gt;
     *     &lt;discriminator nth="1" equals="O"/&gt;
     * &lt;/recordSelector&gt;
     * </pre>
     */
    private static RecordSelectorSpec recordSelectorSpec(Element recordSelector) {
        var name = required(recordSelector, "name");
        return new RecordSelectorSpec(
                name,
                node(recordSelector).locator("record selector '" + name + "'",
                        discriminator(recordSelector)),
                elements("fieldSelector")
                        .apply(recordSelector)
                        .map(XmlMappingSpecReader::fieldSelectorSpec)
                        .toList()
        );
    }

    /** A {@code <discriminator>} child, where there is one. */
    private static @Nullable Discriminator discriminator(Element recordSelector) {
        return elements("discriminator")
                .apply(recordSelector)
                .findFirst()
                .map(d -> node(d).discriminator())
                .orElse(null);
    }

    private static FieldSelectorSpec fieldSelectorSpec(Element fieldSelector) {
        return new FieldSelectorSpec(
                required(fieldSelector, "name"),
                node(fieldSelector).selector("a field selector"),
                attributeValue("type")
                        .apply(fieldSelector)
                        .map(DataType::named)
                        .orElse(null)
        );
    }

    private static RecordMappingSpec recordMappingSpec(Element mapping) {
        return new RecordMappingSpec(
                required(mapping, "recordSelector"),
                new SqlIdentifier(required(mapping, "table")),
                elements("fieldMapping")
                        .apply(mapping)
                        .map(fm -> new FieldMappingSpec(new SqlIdentifier(required(fm, "column")), valueSource(fm)))
                        .toList(),
                node(mapping).whole("limit").orElse(null)
        );
    }

    /**
     * The child elements that hold a source rather than being one, and how each
     * is read.
     * <p>
     * Three of them now, where two could be told apart in a line apiece. Named
     * once, and the same names both checked and dispatched on, so that the list a
     * spec is measured against cannot come to differ from the list the reader can
     * actually read - the {@code <fn>} the XSD offered inside a lookup's
     * condition while the reader threw on it was exactly that divergence, and it
     * stood for three releases because the two lists were never written down
     * beside each other.
     */
    private static final SequencedMap<String, Function<Element, ValueSource>> NESTED = nested();

    private static SequencedMap<String, Function<Element, ValueSource>> nested() {
        var sources = new LinkedHashMap<String, Function<Element, ValueSource>>();
        sources.put("lookup", XmlMappingSpecReader::lookup);
        sources.put("fn", XmlMappingSpecReader::functionCall);
        sources.put("regex", XmlMappingSpecReader::regex);
        return Collections.unmodifiableSequencedMap(sources);
    }

    /**
     * The same, minus the lookup, which is what a lookup's own condition may
     * hold: a condition matching against another lookup is a join, and a join
     * belongs in a view where the database can plan it.
     * <p>
     * A {@code <regex>} here may still read one, its subject being any source at
     * all. That is not the case this excludes - it is two queries one after the
     * other, the pattern applied to what the first returned, which is no more a
     * join than a var reading a lookup is. Where the condition belongs to a
     * *column's* lookup the loader refuses it regardless, a regex there being
     * planned into the same statement and having no value in hand to match
     * against.
     */
    private static final List<String> IN_A_CONDITION = List.of("fn", "regex");

    /**
     * A field mapping carries exactly one source: a {@code fieldSelector},
     * {@code constant}, {@code var} or {@code expr} attribute, or one of the
     * {@link #NESTED} children. Which one, and the refusal when it is not one, is
     * {@link SpecNode#source()}; where the children sit is this format's business.
     */
    private static ValueSource valueSource(Element fm) {
        return source(fm, NESTED.sequencedKeySet());
    }

    /**
     * One source out of the attributes and whichever children are allowed here.
     *
     * @param element the element that carries the source
     * @param allowed the child elements that may carry a source in this position,
     *                in the order a complaint should list them
     */
    private static ValueSource source(Element element, SequencedCollection<String> allowed) {
        var written = new LinkedHashMap<String, Element>();
        allowed.forEach(name -> elements(name).apply(element)
                .findFirst()
                .ifPresent(held -> written.put(name, held)));
        if (written.isEmpty()) {
            return node(element).source();
        }
        if (written.size() > 1) {
            throw new IllegalArgumentException("<" + element.getNodeName() + "> has "
                    + written.keySet().stream().map(name -> "<" + name + ">").collect(joining(" and "))
                    + ", and one source is wanted");
        }
        var only = written.firstEntry();
        if (node(element).hasSource()) {
            throw new IllegalArgumentException("<" + element.getNodeName() + "> has a <" + only.getKey()
                    + "> and a source attribute, and one source is wanted");
        }
        return NESTED.get(only.getKey()).apply(only.getValue());
    }

    private static ValueSource.Lookup lookup(Element lookup) {
        return new ValueSource.Lookup(
                new SqlIdentifier(required(lookup, "table")),
                new SqlIdentifier(required(lookup, "column")),
                conditions(lookup));
    }

    /**
     * A pattern applied to another source: the {@code pattern} it matches with,
     * the capturing {@code group} to take, and the source it reads, written as
     * that source would be written anywhere else.
     *
     * <pre>
     * &lt;fieldMapping column="currency"&gt;
     *     &lt;regex pattern=".*_([A-Z]{3})_.*" group="1" expr="${xldr.filename}"/&gt;
     * &lt;/fieldMapping&gt;
     * </pre>
     * <p>
     * The subject sits on the element beside the pattern, or as a child of it
     * where the subject is itself an {@code <fn>} or a {@code <lookup>}, which is
     * why this can hand the whole element back to {@link #valueSource}:
     * everything a {@code <fieldMapping>} may say about where its value comes
     * from, a {@code <regex>} may say about where the text it matches comes from.
     * {@code pattern} and {@code group} are not sources and are ignored there.
     * <p>
     * {@code group} defaults to 0, the whole match, so the common case of a
     * pattern written to match exactly what is wanted says nothing. The pattern
     * is compiled here, by {@link ValueSource.Regex#matching}, so that a spec
     * that will not compile is refused when it is read rather than when the first
     * record reaches it - a feed is activated only if its patterns compile.
     * <p>
     * The attribute is {@code pattern} where a {@code <discriminator>} says
     * {@code matches}. A discriminator carries its pattern beside the test it is
     * an alternative to, so the attribute has to say what it does; here the
     * element already says it.
     */
    private static ValueSource.Regex regex(Element regex) {
        var pattern = attributeValue("pattern")
                .apply(regex)
                .orElseThrow(() -> new IllegalArgumentException(
                        "<regex> says the pattern it matches with: " + node(regex).shown()));
        var group = node(regex).whole("group").orElse(0);
        return ValueSource.Regex.matching(valueSource(regex), pattern, group);
    }

    /**
     * What a lookup matches on: either a {@code keyColumn} attribute beside its
     * source attribute, which is one condition and how nearly every lookup is
     * written, or a {@code <conditions>} child for anything else.
     *
     * <pre>
     * &lt;lookup table="rate" column="factor"&gt;
     *     &lt;conditions&gt;
     *         &lt;condition column="ccy" fieldSelector="currency"/&gt;
     *         &lt;condition column="asof" var="valueDate"/&gt;
     *     &lt;/conditions&gt;
     * &lt;/lookup&gt;
     * </pre>
     * <p>
     * Both spellings, and never both at once: a spec that writes each has said
     * the same thing twice and is refused rather than picked between.
     * <p>
     * The wrapper earns its nesting by making {@code <conditions/>} sayable,
     * which is a lookup that matches on nothing - a single-row view, or
     * {@code dual}. Repeated {@code <condition>} children directly under the
     * lookup could not express that: an empty list and a forgotten key would
     * have been the same document, and the second is a mistake worth keeping an
     * error. The JSON format says it as {@code "conditions": []}, so the two
     * still transliterate.
     */
    private static SequencedMap<SqlIdentifier, ValueSource> conditions(Element lookup) {
        var listed = elements("conditions").apply(lookup).findFirst().orElse(null);
        var keyColumn = attributeValue("keyColumn").apply(lookup).orElse(null);
        if (listed != null && keyColumn != null) {
            throw new IllegalArgumentException("<lookup> has a keyColumn attribute and a <conditions>"
                    + " child, and one of the two is wanted");
        }
        var conditions = new LinkedHashMap<SqlIdentifier, ValueSource>();
        if (listed == null) {
            if (keyColumn == null) {
                throw new IllegalArgumentException("<lookup> matches on something: give it a keyColumn,"
                        + " or a <conditions> child - empty, if it is to match on nothing");
            }
            conditions.put(new SqlIdentifier(keyColumn), conditionValue(lookup));
            return conditions;
        }
        for (var condition : elements("condition").apply(listed).toList()) {
            var column = new SqlIdentifier(required(condition, "column"));
            if (conditions.put(column, conditionValue(condition)) != null) {
                // the map keeps the first quietly, and a SqlIdentifier collides
                // with a differently-spelled one for the same column - so this
                // catches ccy beside CCY as well as ccy beside ccy
                throw new IllegalArgumentException("<lookup> matches '" + column + "' twice");
            }
        }
        return conditions;
    }

    /**
     * What one condition matches against: {@link #IN_A_CONDITION}, which is
     * everything but a nested {@code <lookup>}.
     * <p>
     * The {@code <fn>} half is what the XSD has claimed since 0.40 and the
     * reader did not do: a var's lookup keyed by a call validated in an editor
     * and then threw when the spec was read. A call in a *column* lookup's
     * condition is still refused, by {@link
     * io.github.ralfspoeth.xldr.spec.FieldMappingSpec}, one call per row being
     * what that rule exists to prevent, and it walks through a regex as well.
     */
    private static ValueSource conditionValue(Element condition) {
        return source(condition, IN_A_CONDITION);
    }

    /**
     * A call, as an element with a child per argument:
     *
     * <pre>
     * &lt;fn name="next_id" type="INTEGRAL"&gt;
     *     &lt;arg constant="7"/&gt;
     *     &lt;arg var="feed"/&gt;
     * &lt;/fn&gt;
     * </pre>
     *
     * The first repeated child this format carries inside a value source. A
     * {@code <lookup>} could hold its one key as attributes on itself; a call has
     * as many arguments as it has, so each needs an element - and an {@code <arg>}
     * carries exactly what a {@code <fieldMapping>} carries, which is what lets
     * the same method read both and lets an argument be a nested {@code <lookup>}
     * or {@code <fn>}.
     * <p>
     * {@code type} is required, where a field selector's may be left out and
     * defaults to {@code TEXT}: the loader registers an OUT parameter before the
     * call and has nothing to infer it from.
     */
    private static ValueSource.FunctionCall functionCall(Element fn) {
        return new ValueSource.FunctionCall(
                required(fn, "name"),
                DataType.named(required(fn, "type")),
                elements("arg").apply(fn)
                        .map(XmlMappingSpecReader::valueSource)
                        .toList());
    }

    /**
     * This format's answers to the five questions {@link SpecNode} asks, which is
     * all it takes to inherit the rules about what a spec may say.
     * <p>
     * An attribute is text and carries no type of its own, so {@code string} and
     * {@code scalar} are the same question here and a constant is always a string.
     * The distinction JSON draws between {@code "1"} and {@code 1} is drawn in this
     * format by which attribute was written, which is the reason the format has
     * both {@code selector} and {@code nth} rather than one attribute read two
     * ways.
     */
    private record Node(Element element) implements SpecNode {

        @Override
        public Optional<String> string(String name) {
            return attributeValue(name).apply(element);
        }

        @Override
        public Optional<String> scalar(String name) {
            return string(name);
        }

        @Override
        public Optional<Integer> whole(String name) {
            return string(name).map(value -> wholeNumber(name, value));
        }

        @Override
        public Optional<ValueSource.Constant> constant() {
            return string("constant").map(ValueSource.Constant::new);
        }

        /**
         * The element as written, attributes and all. A complaint about
         * {@code <fieldSelector name="id"/>} is worth little without the name, and
         * a spec has enough of them that the tag on its own does not locate one.
         */
        @Override
        public String shown() {
            var text = new StringBuilder("<").append(element.getNodeName());
            var attributes = element.getAttributes();
            for (int i = 0; i < attributes.getLength(); i++) {
                var attribute = attributes.item(i);
                text.append(' ').append(attribute.getNodeName())
                        .append("=\"").append(attribute.getNodeValue()).append('"');
            }
            return text.append("/>").toString();
        }
    }

    private static SpecNode node(Element element) {
        return new Node(element);
    }

    private static int wholeNumber(String name, String value) {
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    name + " is a whole number, was '" + value + "'", e);
        }
    }

    /**
     * {@code attributeValue} yields an empty Optional for an absent attribute,
     * where {@code getAttribute} cannot tell that from an empty one - which is
     * the difference between a broken spec and a deliberately empty value.
     */
    private static String required(Element element, String attribute) {
        return attributeValue(attribute)
                .apply(element)
                .orElseThrow(() -> new IllegalArgumentException(
                        "<" + element.getNodeName() + "> has no " + attribute + " attribute"));
    }
}
