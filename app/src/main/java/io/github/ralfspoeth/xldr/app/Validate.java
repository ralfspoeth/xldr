package io.github.ralfspoeth.xldr.app;

import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static io.github.ralfspoeth.xldr.spec.io.MappingSpecReader.readSpec;

/**
 * Checks mapping specs without a database and without a server, so that the
 * author of a spec finds out what is wrong while writing it rather than from a
 * line in the server's log after a feed silently fails to activate.
 * <p>
 * What is checked here is what can be checked from the spec alone: that it
 * parses, that its delivery rule is exactly one and its patterns compile, that
 * an adapter for the MIME type exists and accepts every selector, and that the
 * names the mappings use are declared by the input. Whether the target tables
 * and columns exist is a question for the database and is left to the load.
 */
@Command(
        name = "validate",
        mixinStandardHelpOptions = true,
        description = "Checks mapping specs and reports what would keep them from loading."
)
class Validate implements Callable<Integer> {

    @Parameters(
            arity = "1..*",
            paramLabel = "SPEC",
            description = "the mapping specs to check, spec.json or spec.xml"
    )
    private List<Path> specs;

    @Override
    public Integer call() {
        int broken = 0;
        for (var spec : specs) {
            var problems = check(spec);
            if (problems.isEmpty()) {
                System.out.println(spec + ": ok");
            } else {
                broken++;
                System.out.println(spec + ": " + problems.size() + " problem(s)");
                problems.forEach(p -> System.out.println("    " + p));
            }
        }
        return broken == 0 ? 0 : 1;
    }

    /**
     * Everything wrong with one spec, rather than only the first thing - an
     * author would otherwise fix and re-run once per mistake.
     */
    private static List<String> check(Path file) {
        var problems = new ArrayList<String>();
        if (!Files.isRegularFile(file)) {
            return List.of("no such file");
        }
        MappingSpec spec;
        try {
            spec = readSpec(file);
        } catch (Exception e) {
            // a spec that does not parse cannot be checked any further
            return List.of("cannot be read: " + message(e));
        }
        var input = spec.inputSpec();
        checkDelivery(input, problems);
        checkAdapter(input, problems);
        checkCsvDiscriminator(input, problems);
        checkReferences(spec, problems);
        return problems;
    }

    /**
     * Whether the input lets a mapping name a field its record selector does not
     * declare, the CSV adapter taking such a name for the column of that name.
     * Nothing here can check those: which columns a file has is a property of
     * the file, not of the spec.
     */
    private static boolean fieldsFromHeader(InputSpec input) {
        return "text/csv".equals(input.mimeType())
                && Boolean.parseBoolean(input.properties().getOrDefault("fieldsFromHeader", "false"));
    }

    /**
     * A CSV record selector's {@code selector} is a first-column discriminator,
     * which belongs to a headerless file interleaving several record types. Given
     * one alongside a header it is almost always a misreading of what a selector
     * is here, and the file loads without a single row: no line's first column
     * equals it, nothing matches, and the load reports success over zero records.
     * That is the quietest way a spec can be wrong, so it is worth a word even
     * though the combination is not strictly illegal - a headered file may carry
     * a type column, in which case the fix is to say {@code header=false} or to
     * drop the selector.
     */
    private static void checkCsvDiscriminator(InputSpec input, List<String> problems) {
        if ("text/csv".equals(input.mimeType())
                && !Boolean.parseBoolean(input.properties().getOrDefault("header", "true"))) {
            input.recordSelectors().stream()
                    .filter(rs -> rs.selector() != null && !rs.selector().isBlank())
                    .forEach(rs -> problems.add("record selector '" + rs.name() + "': a CSV selector is a"
                            + " first-column discriminator, and with a header no line's first column will equal '"
                            + rs.selector() + "', so nothing would load. Drop the selector, or set the"
                            + " 'header' property to false if the file really does name its record type in"
                            + " the first column."));
        }
    }

    /**
     * A feed declares exactly one of the two, and the pattern has to be one
     * {@code FileSystem.getPathMatcher} understands.
     */
    private static void checkDelivery(InputSpec input, List<String> problems) {
        if ((input.sentinel() == null) == (input.accepts() == null)) {
            problems.add("input must declare exactly one of 'accepts' or 'sentinel', found "
                    + (input.sentinel() == null ? "neither" : "both"));
        }
        checkPattern("accepts", input.accepts(), problems);
        checkPattern("sentinel", input.sentinel(), problems);
    }

    private static void checkPattern(String name, String pattern, List<String> problems) {
        if (pattern == null) {
            return;
        }
        if (!pattern.startsWith("glob:") && !pattern.startsWith("regex:")) {
            problems.add(name + " must start with 'glob:' or 'regex:', was: " + pattern);
            return;
        }
        try {
            FileSystems.getDefault().getPathMatcher(pattern);
        } catch (RuntimeException e) {
            problems.add("invalid " + name + " pattern " + pattern + ": " + message(e));
        }
    }

    /**
     * An adapter has to exist for the MIME type, and has to accept the
     * selectors: building one compiles every XPath, character range, pointer or
     * cell reference the spec contains.
     */
    private static void checkAdapter(InputSpec input, List<String> problems) {
        var factory = ServiceLoader.load(InputAdapterFactory.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(f -> f.reads(input))
                .findFirst()
                .orElse(null);
        if (factory == null) {
            problems.add("no input adapter for mime type " + input.mimeType()
                    + "; is its module on the module path?");
            return;
        }
        try {
            factory.createInputAdapter(input);
        } catch (Exception e) {
            problems.add("the " + input.mimeType() + " adapter rejects the input: " + message(e));
        }
    }

    /**
     * Every name a mapping uses has to be declared by the input: the record
     * selector, the field selectors of that record, and the variables. These are
     * the mistakes that would otherwise surface as an empty load or an error
     * half way through one.
     */
    private static void checkReferences(MappingSpec spec, List<String> problems) {
        var input = spec.inputSpec();
        var recordSelectors = input.recordSelectors()
                .stream()
                .collect(Collectors.toMap(RecordSelectorSpec::name, rs -> rs, (a, b) -> a));

        var declaredVars = new LinkedHashSet<String>();
        for (var v : input.vars()) {
            // a var may only use one declared before it, since they are evaluated in order
            checkSources(v.source(), null, false, declaredVars, "var '" + v.name() + "'", problems);
            declaredVars.add(v.name());
        }

        for (var mapping : spec.recordMappingSpecs()) {
            var where = "mapping of '" + mapping.recordSelector() + "' onto " + mapping.table();
            var record = recordSelectors.get(mapping.recordSelector());
            if (record == null) {
                problems.add(where + ": the input declares no record selector '"
                        + mapping.recordSelector() + "', but " + recordSelectors.keySet());
                continue;
            }
            var fields = record.fieldSelectors().stream()
                    .map(FieldSelectorSpec::name)
                    .collect(Collectors.toSet());
            for (var fm : mapping.fieldMappings()) {
                checkSources(fm.source(), fields, fieldsFromHeader(input), declaredVars,
                        where + ", column " + fm.column(), problems);
            }
        }
    }

    /**
     * Walks a value source, a lookup key being one in turn.
     *
     * @param fields    the fields of the record in scope, or {@code null} where
     *                  there is no record at all - a var. The two differ: a
     *                  record selector that declares no fields is a spec with
     *                  something missing, and saying "no record is in scope"
     *                  about it would point away from the mistake.
     * @param anyColumn whether the input takes an undeclared name for a column
     *                  of that name, in which case there is nothing here to
     *                  check: what the file's header holds is not known until a
     *                  file arrives
     */
    private static void checkSources(ValueSource source, Set<String> fields, boolean anyColumn,
                                     Set<String> vars, String where, List<String> problems) {
        switch (source) {
            case ValueSource.Field f -> {
                if (anyColumn) {
                    // the header answers for it, and only the load can ask
                } else if (fields == null) {
                    problems.add(where + ": reads the field '" + f.fieldName()
                            + "', but no record is in scope here");
                } else if (fields.isEmpty()) {
                    problems.add(where + ": reads the field '" + f.fieldName()
                            + "', but its record selector declares no field selectors at all");
                } else if (!fields.contains(f.fieldName())) {
                    problems.add(where + ": the record selector declares no field '"
                            + f.fieldName() + "', but " + fields);
                }
            }
            case ValueSource.Var v -> {
                if (!vars.contains(v.name())) {
                    problems.add(where + ": no var '" + v.name() + "' is declared before it"
                            + (vars.isEmpty() ? "" : "; declared are " + vars));
                }
            }
            case ValueSource.Lookup lk ->
                    checkSources(lk.key(), fields, anyColumn, vars, where + " (lookup key)", problems);
            case ValueSource.Constant _, ValueSource.Expr _ -> {
                // a constant is always valid; an expression is only resolvable
                // against a record and a connection, so it is left to the load
            }
        }
    }

    private static String message(Throwable e) {
        var message = e.getMessage();
        return message == null ? e.toString() : message;
    }
}
