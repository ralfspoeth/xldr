package io.github.ralfspoeth.xldr.app;

import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.ia.Row;
import io.github.ralfspoeth.xldr.spec.MappingSpec;
import io.github.ralfspoeth.xldr.spec.RecordMappingSpec;
import io.github.ralfspoeth.xldr.spec.RecordSelectorSpec;
import io.github.ralfspoeth.xldr.spec.ValueSource;
import io.github.ralfspoeth.xldr.spec.VarSpec;
import io.github.ralfspoeth.xldr.spec.io.MappingSpecReader;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Checks a draft spec against a sample file and the target database, before any
 * feed exists.
 *
 * <h2>Why this is not the {@code validate} that was removed</h2>
 * That one duplicated checks belonging to the adapters, and removing it was
 * right: an adapter refusing a selector knows more and says so earlier. What is
 * missing is different in kind. Three artifacts have to agree - the spec, the
 * file, and the table - and no component of a running server ever holds all
 * three before a load. The adapter has the spec and the file and knows nothing
 * of the table; the loader has the spec and the table, but only once a file is
 * already being loaded and a transaction is open. So the failures below are
 * real, they are the ones a draft spec actually has, and every one of them
 * currently surfaces after a producer has delivered something.
 * <p>
 * That is tolerable for a spec someone wrote by hand over a week. It is the
 * whole difficulty for a spec drafted in a minute, by a person new to the
 * format or by a language model working from the schema - which validates the
 * document and cannot see any of this.
 *
 * <h2>What it will not do</h2>
 * Insert anything. It opens a connection to read {@code DatabaseMetaData} and
 * parses the sample in memory; nothing is written, so it is safe to point at
 * production if that is the only place the table exists.
 * <p>
 * It also cannot tell you that a value is <em>wrong</em>, only that it parsed.
 * That is what {@code --rows} is for: no static check can know whether
 * {@code 03.04.2026} is the third of April or the fourth of March, but a human
 * reading {@code 2026-04-03} beside their own file knows immediately.
 */
@Command(
        name = "check",
        mixinStandardHelpOptions = true,
        description = "Checks a mapping spec against a sample file and the target database. "
                + "Reads only - nothing is inserted."
)
public class Check implements Callable<Integer> {

    @Spec
    @Nullable
    private CommandSpec commandSpec;

    @Parameters(index = "0", paramLabel = "SPEC", description = "the spec.json or spec.xml to check")
    @Nullable
    private Path specFile;

    @Option(names = {"-s", "--sample"}, paramLabel = "FILE",
            description = "a sample input file; without one only the spec and the database are compared")
    @Nullable
    private Path sample;

    @Option(names = {"-u", "--url"}, paramLabel = "JDBC_URL",
            description = "the target database; without one only the spec and the sample are compared")
    @Nullable
    private String url;

    @Option(names = "--user", paramLabel = "NAME", description = "database user")
    @Nullable
    private String user;

    @Option(names = "--password", paramLabel = "PASSWORD", interactive = true, arity = "0..1",
            description = "database password; prompted for if the option is given without one")
    @Nullable
    private String password;

    @Option(names = {"-n", "--rows"}, paramLabel = "N", defaultValue = "3",
            description = "how many parsed records to show per record selector; 0 for none")
    private int rows;

    @Option(names = "--schema", paramLabel = "NAME",
            description = "the schema the tables are in, as the feed's target.properties would say; "
                    + "without one the database is asked about any table of that name it can see")
    @Nullable
    private String schema;

    @Option(names = "--catalog", paramLabel = "NAME", description = "the catalog, likewise")
    @Nullable
    private String catalog;

    @Option(names = "--same-as", paramLabel = "SPEC",
            description = "another spec, in either format, that this one should be equivalent to; "
                    + "for checking a transliteration between spec.json and spec.xml")
    @Nullable
    private Path sameAs;

    /**
     * Everything wrong with the spec, in one run.
     * <p>
     * Collected rather than thrown at the first, because a draft usually has
     * several and fixing them one build at a time is the slow way. The exit code
     * is the count, capped, so a script can branch on it.
     */
    private final List<String> findings = new ArrayList<>();

    @Override
    public Integer call() throws Exception {
        assert specFile != null && commandSpec != null;
        var out = commandSpec.commandLine().getOut();
        var err = commandSpec.commandLine().getErr();

        if (!Files.isRegularFile(specFile)) {
            err.println("no such spec: " + specFile);
            return 2;
        }
        MappingSpec spec;
        try {
            spec = MappingSpecReader.readSpec(specFile);
        } catch (IOException | RuntimeException e) {
            // the spec did not even parse, so there is nothing to cross-check
            // against and the reader has already said what is wrong with it
            err.println("cannot read " + specFile + ": " + e.getMessage());
            return 2;
        }
        out.println("checking " + specFile);
        out.printf("  input          %s, %d record selector(s)%n",
                spec.inputSpec().mimeType(), spec.inputSpec().recordSelectors().size());

        checkRecordSelectorsExist(spec, out);
        if (url != null) {
            checkColumnsExist(spec, out, err);
        } else {
            out.println("  columns        not checked, no --url given");
        }
        if (sameAs != null) {
            compareWith(spec, out, err);
        }
        if (sample != null) {
            checkAgainstTheSample(spec, out, err);
        } else {
            out.println("  sample         not checked, no --sample given");
        }

        printPlan(spec, out);

        out.println();
        if (findings.isEmpty()) {
            out.println("no findings.");
            return 0;
        }
        out.println(findings.size() + " finding(s):");
        findings.forEach(f -> out.println("  - " + f));
        return Math.min(findings.size(), 100);
    }

    // ---- the spec against itself ---------------------------------------------

    /**
     * A mapping names a record selector the input has to declare.
     * <p>
     * Nothing cross-checks this today. The name goes straight to
     * {@code adapter.parse}, so a typo is refused - by the adapter, on the first
     * file, with the feed already deployed and a producer already waiting.
     */
    private void checkRecordSelectorsExist(MappingSpec spec, PrintWriter out) {
        var declared = spec.inputSpec().recordSelectors().stream()
                .map(RecordSelectorSpec::name)
                .collect(Collectors.toCollection(TreeSet::new));
        for (var mapping : spec.recordMappingSpecs()) {
            if (!declared.contains(mapping.recordSelector())) {
                findings.add("mapping into '" + mapping.table() + "' names record selector '"
                        + mapping.recordSelector() + "', which the input does not declare; it declares "
                        + declared);
            }
        }
        var mapped = spec.recordMappingSpecs().stream()
                .map(RecordMappingSpec::recordSelector)
                .collect(Collectors.toSet());
        for (var name : declared) {
            if (!mapped.contains(name)) {
                findings.add("record selector '" + name
                        + "' is declared but no mapping reads it, so nothing it matches is loaded");
            }
        }
        out.printf("  mappings       %d, over %d declared record selector(s)%n",
                spec.recordMappingSpecs().size(), declared.size());
    }

    // ---- the mapping side, which nothing else here says anything about ---------

    /**
     * Where each target column's value comes from, one line each.
     * <p>
     * Nothing is evaluated. A constant, a {@code var}, an {@code expr} or what a
     * lookup resolves to is the load rather than a reading of the spec, and
     * working them out here would mean a second implementation of the loader's
     * expression engine - one that could disagree with it, which is worse than
     * not having one. So this shows the wiring and not the values.
     * <p>
     * That is still the only view of the mapping half there is. A spec spreads
     * forty columns over a hundred lines of JSON, each with its source nested
     * inside it, and the question a reader actually has - <em>where does
     * {@code home_city} come from?</em> - is answered nowhere in one place.
     * Wiring a column to the wrong source produces a spec that validates, loads
     * and is wrong in every row, and this is what makes it visible.
     */
    private static void printPlan(MappingSpec spec, PrintWriter out) {
        for (var mapping : spec.recordMappingSpecs()) {
            out.println();
            out.printf("  %s <- '%s'%s%n", mapping.table(), mapping.recordSelector(),
                    mapping.limit() == null ? "" : "  (limit " + mapping.limit() + ")");
            var width = mapping.fieldMappings().stream()
                    .mapToInt(fm -> fm.column().length())
                    .max().orElse(0);
            for (var fm : mapping.fieldMappings()) {
                out.printf("      %-" + Math.max(width, 8) + "s  %s%n", fm.column(), describe(fm.source()));
            }
        }
        if (!spec.inputSpec().vars().isEmpty()) {
            out.println();
            out.println("  vars, evaluated once per load:");
            var width = spec.inputSpec().vars().stream()
                    .mapToInt(v -> v.name().length())
                    .max().orElse(0);
            for (var var : spec.inputSpec().vars()) {
                out.printf("      %-" + Math.max(width, 8) + "s  %s%n", var.name(), describe(var.source()));
            }
        }
    }

    /** one value source, as a phrase rather than as a record's toString */
    private static String describe(ValueSource source) {
        return switch (source) {
            case ValueSource.Field(var name) -> "field     " + name;
            case ValueSource.Constant(var value) -> "constant  "
                    + (value == null ? "null (a SQL NULL)" : "'" + value + "'");
            case ValueSource.Var(var name) -> "var       " + name;
            case ValueSource.Expr(var template) -> "expr      " + template;
            case ValueSource.Lookup(var table, var column, var keyColumn, var key) ->
                    "lookup    " + table + "." + column + " where " + keyColumn
                            + " = " + describe(key).replaceAll("\\s{2,}", " ").strip();
        };
    }

    // ---- one spec against another ----------------------------------------------

    /**
     * Whether two specs say the same thing, which is what
     * {@code spec.json} and {@code spec.xml} of the same feed are supposed to.
     * <p>
     * The formats are transliterations of each other, and the tutorial has a page
     * on converting between them - so "did I convert it faithfully?" is a
     * question people have and nothing could answer. Both are read into a
     * {@link MappingSpec}, which is records all the way down, so the comparison
     * is equality; what takes the work is saying <em>where</em> two specs part
     * company, since "they differ" is no help against a hundred lines.
     */
    private void compareWith(MappingSpec spec, PrintWriter out, PrintWriter err) {
        assert sameAs != null;
        MappingSpec other;
        try {
            other = MappingSpecReader.readSpec(sameAs);
        } catch (IOException | RuntimeException e) {
            // a finding, not a note on stderr. Printing and returning left the
            // exit code at zero, so --same-as pointed at a spec that does not
            // parse reported success - the one answer it must never give, since
            // the whole question is whether the two agree and one of them could
            // not be read at all
            err.println("  same-as        cannot read " + sameAs.getFileName() + ": " + e.getMessage());
            findings.add("cannot read " + sameAs.getFileName() + ", so nothing was compared against it: "
                    + e.getMessage());
            return;
        }
        var differences = differences(spec, other);
        if (differences.isEmpty()) {
            out.printf("  same-as        matches %s%n", sameAs.getFileName());
        } else {
            out.printf("  same-as        differs from %s%n", sameAs.getFileName());
            differences.forEach(d -> findings.add(sameAs.getFileName() + ": " + d));
        }
    }

    /**
     * Where two specs part company, piece by piece.
     * <p>
     * Compared by name rather than in order: a record selector or a mapping
     * written in a different order is the same spec, and reporting that as a
     * difference would bury the one that matters.
     */
    private static List<String> differences(MappingSpec a, MappingSpec b) {
        var out = new ArrayList<String>();
        if (!a.inputSpec().mimeType().equals(b.inputSpec().mimeType())) {
            out.add("mimeType " + a.inputSpec().mimeType() + " vs " + b.inputSpec().mimeType());
        }
        if (!a.inputSpec().properties().equals(b.inputSpec().properties())) {
            out.add("properties " + new TreeMap<>(a.inputSpec().properties())
                    + " vs " + new TreeMap<>(b.inputSpec().properties()));
        }
        compare(out, "var", byName(a.inputSpec().vars(), VarSpec::name),
                byName(b.inputSpec().vars(), VarSpec::name));
        compare(out, "record selector",
                byName(a.inputSpec().recordSelectors(), RecordSelectorSpec::name),
                byName(b.inputSpec().recordSelectors(), RecordSelectorSpec::name));
        compare(out, "mapping",
                byName(a.recordMappingSpecs(), RecordMappingSpec::recordSelector),
                byName(b.recordMappingSpecs(), RecordMappingSpec::recordSelector));
        return out;
    }

    private static <T> Map<String, T> byName(Collection<T> items, Function<T, String> name) {
        var map = new LinkedHashMap<String, T>();
        items.forEach(item -> map.put(name.apply(item), item));
        return map;
    }

    private static <T> void compare(List<String> out, String what,
                                    Map<String, T> a, Map<String, T> b) {
        for (var name : a.keySet()) {
            if (!b.containsKey(name)) {
                out.add(what + " '" + name + "' is only in the first");
            } else if (!a.get(name).equals(b.get(name))) {
                out.add("%s '%s' differs:%n      %s%n      %s"
                        .formatted(what, name, a.get(name), b.get(name)));
            }
        }
        b.keySet().stream()
                .filter(name -> !a.containsKey(name))
                .forEach(name -> out.add(what + " '" + name + "' is only in the second"));
    }

    // ---- the spec against the database ----------------------------------------

    /**
     * Every table a spec names exists, and every column it names is one of that
     * table's own.
     * <p>
     * Read through {@link java.sql.DatabaseMetaData} rather than by parsing DDL:
     * the database is the authority on what its tables hold, and a DDL file is a
     * statement about what they held when it was written.
     * <p>
     * Both kinds of table, which was not so at first. A spec names its targets in
     * a {@code mapping}, and it names <em>reference</em> tables inside every
     * {@link ValueSource.Lookup} - each with a column to return and a column to
     * match on. Those are as easy to misspell and worse to get wrong: a lookup
     * against a table that is not there fails on the first record of the first
     * file, by which time the load has begun. Sweeping the tutorial found this
     * gap rather than a mistake, its lookup pages having passed without their
     * reference tables being examined at all.
     */
    private void checkColumnsExist(MappingSpec spec, PrintWriter out, PrintWriter err) {
        try (var conn = connect()) {
            var lower = conn.getMetaData().storesLowerCaseIdentifiers();
            for (var mapping : spec.recordMappingSpecs()) {
                var actual = columnsOf(conn, normalize(mapping.table(), lower));
                if (actual.isEmpty()) {
                    findings.add("no table '" + mapping.table() + "' in the target database");
                } else {
                    for (var fm : mapping.fieldMappings()) {
                        requireColumn(actual, mapping.table(), fm.column(), lower);
                    }
                }
                for (var fm : mapping.fieldMappings()) {
                    checkLookups(conn, fm.source(), lower);
                }
            }
            for (var var : spec.inputSpec().vars()) {
                checkLookups(conn, var.source(), lower);
            }
            out.printf("  columns        checked against %s%n", conn.getMetaData().getURL());
        } catch (SQLException e) {
            err.println("  columns        not checked: " + e.getMessage());
        }
    }

    /**
     * A lookup's own table and its two columns.
     * <p>
     * Recursive, because a lookup's key is itself a value source and may be
     * another lookup. Vars are walked as well as field mappings: a var may hold a
     * lookup, and it is evaluated once per load rather than per record, so a
     * broken one fails the load before a single row is read.
     */
    private void checkLookups(Connection conn, ValueSource source, boolean lower) throws SQLException {
        if (source instanceof ValueSource.Lookup(String table, String column, String keyColumn, ValueSource key)) {
            var actual = columnsOf(conn, normalize(table, lower));
            if (actual.isEmpty()) {
                findings.add("a lookup reads table '" + table
                        + "', which is not in the target database");
            } else {
                requireColumn(actual, table, column, lower);
                requireColumn(actual, table, keyColumn, lower);
            }
            checkLookups(conn, key, lower);
        }
    }

    private void requireColumn(Set<String> actual, String table, String column, boolean lower) {
        if (!actual.contains(normalize(column, lower))) {
            findings.add("table '" + table + "' has no column '" + column
                    + "'; it has " + new TreeSet<>(actual));
        }
    }

    private Connection connect() throws SQLException {
        assert url != null;
        return user == null
                ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, user, password);
    }

    /**
     * The columns of one table, asked of the database rather than of a DDL file.
     * <p>
     * Narrowed by {@code --catalog} and {@code --schema} where they are given,
     * and by neither where they are not - a null there means "any", so an
     * unqualified check finds a table of that name wherever the connection can
     * see one. That is the right default and the wrong answer for a deployment
     * whose {@code target.properties} names a schema: the server would qualify
     * the insert and land somewhere this check never looked, so the two have to
     * be told the same thing.
     */
    private Set<String> columnsOf(Connection conn, String table) throws SQLException {
        var columns = new LinkedHashSet<String>();
        var lower = conn.getMetaData().storesLowerCaseIdentifiers();
        try (var rs = conn.getMetaData().getColumns(
                catalog == null ? null : normalize(catalog, lower),
                schema == null ? null : normalize(schema, lower),
                table, null)) {
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME"));
            }
        }
        return columns;
    }

    /**
     * An unquoted SQL identifier is case-insensitive, and a driver folds it one
     * way or the other; a quoted one is exact. The same rule the loader applies
     * when it builds the insert, so that what is checked here is what will be
     * executed there.
     */
    private static String normalize(String name, boolean lowerCase) {
        if (QUOTED.matcher(name).matches()) {
            return name.substring(1, name.length() - 1);
        }
        return lowerCase ? name.toLowerCase(Locale.ROOT) : name.toUpperCase(Locale.ROOT);
    }

    private static final Pattern QUOTED = Pattern.compile("\".*\"");

    // ---- the spec against the sample -------------------------------------------

    /**
     * Builds the adapter the spec asks for and reads the sample through it.
     * <p>
     * Building it is itself a check - every selector compiles here, exactly as it
     * would when a feed activates - and it is the only part of this command that
     * a running server would also have caught. What follows is not: whether each
     * record selector matches anything at all in a file the author says is
     * representative.
     */
    private void checkAgainstTheSample(MappingSpec spec, PrintWriter out, PrintWriter err) {
        assert sample != null;
        if (!Files.isRegularFile(sample)) {
            err.println("  sample         no such file: " + sample);
            return;
        }
        var factory = InputAdapterFactory.of(spec.inputSpec()).orElse(null);
        if (factory == null) {
            findings.add("no adapter on the module path reads '" + spec.inputSpec().mimeType() + "'");
            return;
        }
        InputAdapter adapter;
        try {
            adapter = factory.createInputAdapter(spec.inputSpec());
        } catch (RuntimeException e) {
            findings.add("the adapter refuses this input spec: " + e.getMessage());
            return;
        }
        out.printf("  sample         %s (%d bytes)%n", sample.getFileName(), sizeOf(sample));

        for (var mapping : spec.recordMappingSpecs()) {
            readOne(adapter, mapping, out, err);
        }
    }

    /**
     * One mapping's records, counted and shown.
     * <p>
     * The file is opened once per mapping, as the loader does: every record
     * selector reads the whole input and keeps its own, so two of them means two
     * passes rather than one pass shared.
     */
    private void readOne(InputAdapter adapter, RecordMappingSpec mapping,
                         PrintWriter out, PrintWriter err) {
        assert sample != null;
        var fieldNames = new LinkedHashSet<String>();
        collectFieldNames(mapping, fieldNames);

        try (var source = Files.newInputStream(sample)) {
            var result = adapter.parse(source, mapping.recordSelector(), Set.copyOf(fieldNames));
            var shown = new ArrayList<List<String>>();
            long matched;
            try (var stream = result.rows()) {
                var counter = new long[1];
                stream.forEach(row -> {
                    if (counter[0]++ < rows) {
                        shown.add(describe(row, fieldNames));
                    }
                });
                matched = counter[0];
            }
            out.printf("  '%s'%s -> %s: %d record(s) matched%n",
                    mapping.recordSelector(),
                    " ".repeat(Math.max(1, 12 - mapping.recordSelector().length())),
                    mapping.table(), matched);
            if (matched == 0) {
                findings.add("record selector '" + mapping.recordSelector()
                        + "' matches nothing in " + sample.getFileName()
                        + ", so this mapping would load no rows");
            }
            shown.forEach(row -> out.println("      " + String.join("  ", row)));
        } catch (IOException | RuntimeException e) {
            findings.add("reading '" + mapping.recordSelector() + "' from the sample failed: "
                    + e.getMessage());
        }
    }

    /**
     * What one record would contribute, as values and their Java types.
     * <p>
     * The types are the point. A date read under the wrong pattern is still a
     * date and a German decimal read as a plain one is still a number, so
     * nothing refuses either - but {@code 2026-04-03} where the file said
     * {@code 03.04.2026} is visible at a glance, and it is the failure this
     * whole command is least able to catch any other way.
     */
    private static List<String> describe(Row row, Set<String> fieldNames) {
        var described = new ArrayList<String>(fieldNames.size());
        for (var name : fieldNames) {
            Object value;
            try {
                value = row.get(name);
            } catch (RuntimeException e) {
                described.add(name + "=<" + e.getClass().getSimpleName() + ">");
                continue;
            }
            described.add(name + "=" + (value == null
                    ? "null"
                    : value + " (" + value.getClass().getSimpleName() + ")"));
        }
        return described;
    }

    /** the field selectors this mapping reads, including those inside a lookup key */
    private static void collectFieldNames(RecordMappingSpec mapping, Set<String> into) {
        mapping.fieldMappings().forEach(fm -> collectFieldNames(fm.source(), into));
    }

    private static void collectFieldNames(ValueSource source, Set<String> into) {
        switch (source) {
            case ValueSource.Field(var name) -> into.add(name);
            case ValueSource.Lookup(_, _, _, var key) -> collectFieldNames(key, into);
            // a constant needs no record, a var is evaluated once per load, and an
            // expression's names are resolved by the loader against several scopes
            case ValueSource.Constant _, ValueSource.Var _, ValueSource.Expr _ -> {
            }
        }
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1;
        }
    }
}
