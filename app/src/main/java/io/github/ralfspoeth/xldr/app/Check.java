package io.github.ralfspoeth.xldr.app;

import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.ia.Row;
import io.github.ralfspoeth.xldr.ldr.Loader;
import io.github.ralfspoeth.xldr.server.Config;
import io.github.ralfspoeth.xldr.server.FeedSpec;
import io.github.ralfspoeth.xldr.server.Jdbc;
import io.github.ralfspoeth.xldr.spec.*;
import io.github.ralfspoeth.xldr.spec.io.MappingSpecReader;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Function;
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

    @Parameters(index = "0", arity = "0..1", paramLabel = "SPEC",
            description = "the spec.json or spec.xml to check; without one, every feed below the "
                    + "xldr.roots of " + App.CONFIG_FILE + " is swept, each against the newest file "
                    + "it has archived")
    @Nullable
    private Path specFile;

    @Option(names = {"-s", "--sample"}, paramLabel = "FILE",
            description = "a sample input file; without one only the spec and the database are compared")
    @Nullable
    private Path sample;

    @Option(names = {"-u", "--url"}, paramLabel = "JDBC_URL",
            description = "the target database; without one the jdbc.url in " + App.CONFIG_FILE
                    + " is used, and without that only the spec and the sample are compared")
    @Nullable
    private String url;

    @Option(names = {"-d", "--dir"}, paramLabel = "DIR", defaultValue = ".",
            description = "where to look for " + App.CONFIG_FILE + " when --url is not given; "
                    + "the working directory by default")
    @Nullable
    private Path directory;

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
     * The feed's own file of deployment values, beside the spec. Named here
     * rather than borrowed from {@code server}'s {@code LoadJob}, which is
     * package-private - it is a name a deployment types into a directory, and
     * both places document it.
     */
    private static final String ENV_FILE = "env.properties";

    /** the prefix expressions address those values by, as {@code ${env.clientNumber}} */
    private static final String ENV_PREFIX = "env.";

    /**
     * Everything wrong with the spec, in one run.
     * <p>
     * Collected rather than thrown at the first, because a draft usually has
     * several and fixing them one build at a time is the slow way. The exit code
     * is the count, capped, so a script can branch on it.
     */
    private final List<String> findings = new ArrayList<>();

    /**
     * Where to connect, once {@link #resolveDatabase} has worked it out, and null
     * where there is nowhere.
     */
    @Nullable
    private Jdbc jdbc;

    /**
     * The file {@link #jdbc} came out of, or null when it came from {@code --url}.
     * <p>
     * This is the difference between the two failure modes and not only something
     * to print. A {@code --url} that cannot be reached is a finding, because the
     * database was asked for and not consulted; an inferred one that cannot be
     * reached is a line on stderr, because omitting {@code --url} has never made
     * this command fail for a database's sake and a convenience should not be the
     * thing that changes that.
     */
    @Nullable
    private String jdbcFrom;

    /** so that two checks failing on one unreachable database say so once */
    private boolean unreachableReported;

    /**
     * The spec being checked at this moment, and the sample to check it against.
     * <p>
     * Separate from the {@code --sample} option and the {@code SPEC} argument
     * because a sweep supplies both per feed. The options say what a single run
     * was asked for; these say what is being looked at now, and only the second
     * question has an answer when there are forty feeds.
     */
    @Nullable
    private Path currentSpec;

    @Nullable
    private Path currentSample;

    @Override
    public Integer call() throws Exception {
        assert commandSpec != null;
        var out = commandSpec.commandLine().getOut();
        var err = commandSpec.commandLine().getErr();
        resolveDatabase(err);
        return specFile == null ? sweep(out, err) : one(out, err);
    }

    /** the named spec, with the flags as given: what this command has always done */
    private int one(PrintWriter out, PrintWriter err) {
        assert specFile != null;
        currentSpec = specFile;
        currentSample = sample;
        if (checkCurrent(out, err) == null) {
            return 2;
        }
        out.println();
        if (findings.isEmpty()) {
            out.println("no findings.");
            return 0;
        }
        out.println(findings.size() + " finding(s):");
        findings.forEach(f -> out.println("  - " + f));
        return Math.min(findings.size(), 100);
    }

    /**
     * One spec, into {@code out}. Returns null where the spec could not be read
     * at all, there being nothing to cross-check against and the reader having
     * already said what is wrong with it.
     */
    private @Nullable MappingSpec checkCurrent(PrintWriter out, PrintWriter err) {
        assert currentSpec != null;
        if (!Files.isRegularFile(currentSpec)) {
            err.println("no such spec: " + currentSpec);
            return null;
        }
        MappingSpec spec;
        try {
            spec = MappingSpecReader.readSpec(currentSpec);
        } catch (IOException | RuntimeException e) {
            err.println("cannot read " + currentSpec + ": " + e.getMessage());
            return null;
        }
        out.println("checking " + currentSpec);
        out.printf("  input          %s, %d record selector(s)%n",
                spec.inputSpec().mimeType(), spec.inputSpec().recordSelectors().size());

        checkRecordSelectorsExist(spec, out);
        checkFunctionsExist(spec, out);
        checkAmbientNamesAreSupplied(spec, out, err);
        if (jdbc != null) {
            checkColumnsExist(spec, out, err);
            checkRoutinesExist(spec, out, err);
        } else {
            var why = "no --url given and no " + Jdbc.URL_KEY + " in " + configFile();
            out.println("  columns        not checked, " + why);
            out.println("  routines       not checked, " + why);
        }
        if (sameAs != null) {
            compareWith(spec, out, err);
        }
        if (currentSample != null) {
            checkAgainstTheSample(spec, out, err);
        } else {
            out.println("  sample         not checked, " + (specFile == null
                    ? "this feed has archived nothing to check against"
                    : "no --sample given"));
        }

        printPlan(spec, out);
        return spec;
    }

    // ---- the spec against itself ---------------------------------------------

    /**
     * Whether every function an expression calls exists.
     * <p>
     * Asked of the loader rather than answered here, because the set of built-ins
     * is the loader's and a second copy of it in this command would be a second
     * copy to keep current. {@code Loader#refuseUnknownFunctions} is offered for
     * exactly this, the way {@code refuseUnusableTarget} is: the load asks the
     * same question while planning, and a front end may ask it once when a spec
     * is read.
     * <p>
     * Needs no {@code --url} and no sample. It is provable from the document
     * alone - no arrangement of the input, and no database, could make
     * {@code ${coalese(a, b)}} work - which is the line this command draws
     * between what it always checks and what it checks when given something to
     * check against.
     */
    private void checkFunctionsExist(MappingSpec spec, PrintWriter out) {
        try {
            Loader.refuseUnknownFunctions(spec);
            out.println("  functions      ok");
        } catch (RuntimeException e) {
            findings.add(e.getMessage());
        }
    }

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
                    .mapToInt(fm -> fm.column().name().length())
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
        if (!spec.transforms().isEmpty()) {
            out.println();
            out.println("  transforms, called once after the load and before the commit:");
            for (var transform : spec.transforms()) {
                out.printf("      %s(%s)%n", transform.name(), transform.arguments().stream()
                        .map(a -> describe(a).replaceAll("\\s{2,}", " ").strip())
                        .collect(Collectors.joining(", ")));
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
            case ValueSource.Lookup(var table, var column, var conditions) ->
                    "lookup    " + table + "." + column + " where " + conditions.entrySet().stream()
                            .map(c -> c.getKey() + " = "
                                    + describe(c.getValue()).replaceAll("\\s{2,}", " ").strip())
                            .collect(Collectors.joining(" and "));
            case ValueSource.FunctionCall(var name, var returnType, var parameters) ->
                    "call      " + name + "(" + parameters.stream()
                            .map(p -> describe(p).replaceAll("\\s{2,}", " ").strip())
                            .collect(Collectors.joining(", ")) + ") -> " + returnType;
            case ValueSource.Regex(var subject, var pattern, var group) ->
                    "regex     group " + group + " of /" + pattern + "/ over "
                            + describe(subject).replaceAll("\\s{2,}", " ").strip();
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
            // our own URL rather than the driver's: this one may have come out of
            // a file the reader never opened, and some drivers hand back a URL
            // with the password still in the query string
            assert jdbc != null;
            out.printf("  columns        checked against %s%s%n", jdbc.displayUrl(), provenance());
        } catch (SQLException e) {
            databaseUnreachable("columns", e, err);
        }
    }

    /**
     * Every routine a spec asks the database to run: the functions a var calls
     * and the procedures a transform runs.
     * <p>
     * The gap this closes is the one the tutorial page had to admit to. A
     * lookup's table and columns have been checked since the command was
     * written, while a misspelled function name went unmentioned until the load
     * - and the load is the worst place to learn it, the feed being deployed by
     * then and a producer waiting.
     * <p>
     * <strong>A finding only where an absence means something.</strong> Three
     * things make it not mean anything, and each is reported as not-checked
     * rather than as a fault:
     * <ul>
     *   <li>a driver whose metadata lists no routines at all. Then a name is
     *       missing from an empty list, which says nothing about the database;</li>
     *   <li>a qualified name. {@code pkg_load.next_id} is a schema-qualified
     *       function in PostgreSQL and a member of a package in Oracle, and
     *       {@code getFunctions} cannot tell the two apart from the name;</li>
     *   <li>metadata that throws, which some drivers do for these calls.</li>
     * </ul>
     * <p>
     * Functions and procedures are looked for in one set rather than two.
     * Whether a routine is reported by {@code getFunctions} or by
     * {@code getProcedures} is a matter a product decides for itself - H2 lists
     * an alias among the procedures whatever it is called with - and this is
     * asking whether the thing is there, not what kind of thing it is.
     */
    private void checkRoutinesExist(MappingSpec spec, PrintWriter out, PrintWriter err) {
        var called = new TreeSet<String>();
        for (var v : spec.inputSpec().vars()) {
            collectCalls(v.source(), called);
        }
        for (var mapping : spec.recordMappingSpecs()) {
            for (var fm : mapping.fieldMappings()) {
                collectCalls(fm.source(), called);
            }
        }
        for (var transform : spec.transforms()) {
            called.add(transform.name());
            transform.arguments().forEach(argument -> collectCalls(argument, called));
        }
        if (called.isEmpty()) {
            return;
        }
        try (var conn = connect()) {
            var meta = conn.getMetaData();
            var lower = meta.storesLowerCaseIdentifiers();
            var known = routineNames(meta, lower);
            if (known.isEmpty()) {
                out.printf("  routines       not checked: %s reports none, so an absence says nothing%n",
                        meta.getDatabaseProductName());
                return;
            }
            var qualified = called.stream().filter(name -> name.contains(".")).toList();
            for (var name : called) {
                if (!qualified.contains(name) && !known.contains(normalize(new SqlIdentifier(name), lower))) {
                    findings.add("no function or procedure '" + name + "' in the target database");
                }
            }
            out.printf("  routines       %d checked against %s%n", called.size() - qualified.size(), meta.getURL());
            if (!qualified.isEmpty()) {
                out.printf("                 %s not checked: a qualified name may be a schema or a package,%n"
                                + "                 and the metadata cannot say which%n", qualified);
            }
        } catch (SQLException e) {
            databaseUnreachable("routines", e, err);
        }
    }

    /** what the database says it has, functions and procedures together */
    private Set<String> routineNames(DatabaseMetaData meta, boolean lower) throws SQLException {
        var names = new LinkedHashSet<String>();
        var inCatalog = catalog == null ? null : normalize(catalog, lower);
        var inSchema = schema == null ? null : normalize(schema, lower);
        try (var rs = meta.getFunctions(inCatalog, inSchema, null)) {
            while (rs.next()) {
                names.add(rs.getString("FUNCTION_NAME"));
            }
        }
        try (var rs = meta.getProcedures(inCatalog, inSchema, null)) {
            while (rs.next()) {
                names.add(rs.getString("PROCEDURE_NAME"));
            }
        }
        return names;
    }

    /** the name of every call in a source, however deeply it is buried */
    private static void collectCalls(ValueSource source, Set<String> into) {
        switch (source) {
            case ValueSource.FunctionCall(var name, _, var arguments) -> {
                into.add(name);
                arguments.forEach(argument -> collectCalls(argument, into));
            }
            case ValueSource.Lookup(_, _, var conditions) ->
                    conditions.values().forEach(key -> collectCalls(key, into));
            // a regex calls nothing itself, but whatever it reads might
            case ValueSource.Regex(var subject, _, _) -> collectCalls(subject, into);
            case ValueSource.Field _, ValueSource.Constant _, ValueSource.Var _, ValueSource.Expr _ -> {
                // none of them reaches the database for anything it has to have
            }
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
        if (source instanceof ValueSource.Lookup(var table, var column, var conditions)) {
            var actual = columnsOf(conn, normalize(table, lower));
            if (actual.isEmpty()) {
                findings.add("a lookup reads table '" + table
                        + "', which is not in the target database");
            } else {
                requireColumn(actual, table, column, lower);
                // every column it matches on, not just the first: a composite key
                // with one good column and one misspelled one reads as valid and
                // then matches nothing, which is a column of NULLs and no error
                conditions.keySet().forEach(keyColumn -> requireColumn(actual, table, keyColumn, lower));
            }
            for (var key : conditions.values()) {
                checkLookups(conn, key, lower);
            }
        }
    }

    private void requireColumn(Set<String> actual, SqlIdentifier table, SqlIdentifier column, boolean lower) {
        if (!actual.contains(normalize(column, lower))) {
            findings.add("table '" + table + "' has no column '" + column
                    + "'; it has " + new TreeSet<>(actual));
        }
    }

    private Connection connect() throws SQLException {
        assert jdbc != null;
        return jdbc.user() == null
                ? DriverManager.getConnection(jdbc.url())
                : DriverManager.getConnection(jdbc.url(), jdbc.user(), jdbc.password());
    }

    /**
     * Where to connect: {@code --url} if it was given, otherwise the
     * {@code jdbc.url} in the {@code xldr.properties} of {@code --dir}.
     * <p>
     * The point of the second is that a deployment already says which database
     * it feeds, and retyping it into every {@code check} is both tedious and a
     * chance to check the wrong one. {@code --user} and {@code --password}
     * override whatever the file says, but where they are absent the file
     * supplies those too: a URL from one place and credentials from another is a
     * combination nobody means, and the commonest way to arrive at it by accident
     * is to have the file supply only some of the three.
     * <p>
     * {@link Config#load} is deliberately not used. It insists on
     * {@code xldr.roots}, which is right for a server about to watch those
     * directories and wrong here - a spec is often checked on a laptop against a
     * configuration copied from a host, and failing because that host's feed
     * roots are not present locally would be failing for a reason the reader
     * cannot act on and does not care about.
     * The {@code env.} names a spec reads, against the {@code env.properties}
     * beside it.
     * <p>
     * That file holds what differs between deployments - a client number, a
     * source-system code - and lives next to the spec precisely so the spec can
     * travel from test to production unchanged. Which means the spec and the file
     * are edited by different people at different times, and the failure this
     * catches is the ordinary one: a spec gains {@code ${env.clientNumber}} and
     * the deployment it is promoted into never gained the key.
     * <p>
     * Until now nothing noticed. The loader throws {@code unknown ambient
     * variable} when it evaluates the template - on the first record of the first
     * file, with the feed deployed and a producer waiting - which is exactly the
     * moment this command exists to come before. It is the same finding as a
     * misspelled function and is made by walking the same spec.
     * <p>
     * {@code xldr.} names are the loader's own - {@code xldr.filename},
     * {@code xldr.rowsLoaded} - so they are supplied by definition and not
     * looked for here.
     */
    private void checkAmbientNamesAreSupplied(MappingSpec spec, PrintWriter out, PrintWriter err) {
        var wanted = Loader.ambientNames(spec).stream().filter(n -> n.startsWith(ENV_PREFIX)).toList();
        if (wanted.isEmpty()) {
            out.println("  env            not used by this spec");
            return;
        }
        assert currentSpec != null;
        var envFile = currentSpec.toAbsolutePath().getParent().resolve(ENV_FILE);
        Set<String> supplied;
        try {
            supplied = environment(envFile);
        } catch (IOException e) {
            // there and unreadable is a finding: the spec needs it, so a load
            // would fail on exactly this
            findings.add("cannot read " + envFile + ": " + e.getMessage());
            return;
        }
        var missing = wanted.stream().filter(n -> !supplied.contains(n)).toList();
        if (missing.isEmpty()) {
            out.printf("  env            %d name(s), all supplied by %s%n",
                    wanted.size(), envFile.getFileName());
        } else {
            for (var name : missing) {
                findings.add("the spec reads ${" + name + "} and "
                        + (Files.isRegularFile(envFile)
                        ? envFile.getFileName() + " does not supply it"
                        : "there is no " + ENV_FILE + " beside the spec to supply it")
                        + "; the load would fail on the first record");
            }
        }
    }

    /**
     * @return the keys of the feed's {@code env.properties}, each under the
     * {@code env.} prefix that expressions address it by - the same move
     * {@code LoadJob} makes, and read as UTF-8 for the same reason: these are
     * written by hand and reach a database column verbatim
     */
    private static Set<String> environment(Path envFile) throws IOException {
        if (!Files.isRegularFile(envFile)) {
            return Set.of();
        }
        var props = new Properties();
        try (var in = Files.newBufferedReader(envFile, StandardCharsets.UTF_8)) {
            props.load(in);
        }
        return props.stringPropertyNames().stream()
                .map(name -> ENV_PREFIX + name)
                .collect(Collectors.toSet());
    }

    /**
     * Every feed a deployment would register, checked against the newest file it
     * has already loaded.
     * <p>
     * This is the preflight the single-spec form cannot be: a deployment has
     * forty feeds and nobody checks forty specs by hand, so the ones that rot are
     * the ones nobody has touched lately - a column dropped from a table, an
     * {@code env.} name removed, a producer that changed its date format two
     * months ago and whose feed has been hospitalising files since.
     * <p>
     * <strong>Why this one does go through {@link Config}</strong>, where the
     * single-spec form deliberately does not. {@code Config} insists on
     * {@code xldr.roots}, which the single-spec form has no use for and should
     * not demand - and which is the whole input here, there being nothing to
     * sweep without it. Two modes, two requirements, and a missing
     * {@code xldr.roots} now fails the one that actually needed it.
     * <p>
     * The sample is the newest archived file rather than anything in {@code in/}:
     * an inbox is empty on a healthy server, the watcher having taken everything,
     * and what is in {@code hospital/} is by definition the file that broke. What
     * loaded most recently is the truest sample there is - a real file from the
     * real producer that really went in.
     */
    private int sweep(PrintWriter out, PrintWriter err) {
        if (sample != null || sameAs != null) {
            err.println("--sample and --same-as name one spec's file, so they need a SPEC;"
                    + " without one every feed brings its own");
            return 2;
        }
        var configFile = configFile();
        if (!Files.isRegularFile(configFile)) {
            err.println("no SPEC given and no " + configFile + " to sweep from - name a spec,"
                    + " or run where the server's configuration is, or point --dir at it");
            return 2;
        }
        Config config;
        try {
            config = Config.load(configFile);
        } catch (IOException | RuntimeException e) {
            err.println("cannot read " + configFile + ": " + e.getMessage());
            return 2;
        }
        var feeds = FeedSpec.under(config.roots());
        if (feeds.isEmpty()) {
            out.println("no feed below " + config.roots() + " holds a spec.json or spec.xml");
            return 0;
        }
        out.printf("checking %d feed(s) below %s%n%n", feeds.size(), config.roots());

        var unreadable = 0;
        for (var feed : feeds) {
            var before = findings.size();
            currentSpec = feed.specFile();
            currentSample = feed.newestArchived().orElse(null);
            // buffered, so that a feed with nothing wrong costs one line and a
            // feed with something wrong still shows the whole story. Forty clean
            // feeds printed in full is a screenful nobody reads, which is how a
            // finding in the fortieth gets missed
            var buffer = new StringWriter();
            MappingSpec read;
            try (var into = new PrintWriter(buffer, true)) {
                read = checkCurrent(into, err);
            }
            if (read == null) {
                unreadable++;
                out.printf("  %-24s could not be read - see above%n", feed.name());
                continue;
            }
            var mine = findings.subList(before, findings.size());
            if (mine.isEmpty()) {
                out.printf("  %-24s ok%s%n", feed.name(), feed.newestArchived().isPresent()
                        ? "" : "  (nothing archived yet, so the spec was not read against a file)");
            } else {
                out.printf("  %-24s %d finding(s)%n", feed.name(), mine.size());
                buffer.toString().lines().forEach(l -> out.println("    " + l));
                mine.forEach(f -> out.println("    - " + f));
                out.println();
            }
        }

        out.println();
        if (findings.isEmpty() && unreadable == 0) {
            out.println("no findings.");
            return 0;
        }
        out.printf("%d finding(s) across %d feed(s)%s.%n", findings.size(), feeds.size(),
                unreadable == 0 ? "" : ", and " + unreadable + " spec(s) that could not be read");
        return Math.min(Math.max(findings.size(), unreadable), 100);
    }

    private void resolveDatabase(PrintWriter err) {
        if (url != null) {
            if (url.isBlank()) {
                // Jdbc's own complaint names jdbc.url, which is the right name
                // for the file and the wrong one for somebody who typed a flag
                err.println("  --url is empty; give a JDBC URL or leave the option out");
                return;
            }
            jdbc = new Jdbc(url, user, password);
            return;
        }
        var file = configFile();
        try {
            Jdbc.in(file).ifPresent(found -> {
                jdbc = new Jdbc(found.url(),
                        user != null ? user : found.user(),
                        password != null ? password : found.password());
                jdbcFrom = file.toString();
            });
        } catch (IOException | IllegalArgumentException e) {
            // the file is there and unreadable, or there and malformed. Not a
            // finding - the spec is not what is wrong - but not silent either,
            // since the reader is about to be told the database was not checked
            // and would otherwise have to guess why
            err.println("  " + file + " could not be read: " + e.getMessage());
        }
    }

    private Path configFile() {
        assert directory != null;
        return directory.resolve(App.CONFIG_FILE);
    }

    /** {@code " (from ./xldr.properties)"}, or nothing when {@code --url} said it */
    private String provenance() {
        return jdbcFrom == null ? "" : " (from " + jdbcFrom + ")";
    }

    /**
     * A database that was named and could not be reached, reported once however
     * many of the checks needed it.
     * <p>
     * Which way it is reported is the whole of {@link #jdbcFrom}'s purpose. An
     * explicit {@code --url} is a request, and a request that could not be
     * carried out is a finding, so the command exits non-zero rather than
     * printing "no findings" over a database it never saw. An inferred URL is a
     * convenience, and a convenience that fails leaves the command exactly where
     * it was before there was one: not checking the database, and saying so.
     */
    private void databaseUnreachable(String what, SQLException e, PrintWriter err) {
        if (jdbcFrom == null) {
            if (!unreachableReported) {
                findings.add("--url names a database that could not be reached: " + e.getMessage());
                unreachableReported = true;
            }
        } else {
            err.printf("  %-14s not checked, could not reach the database named in %s: %s%n",
                    what, jdbcFrom, e.getMessage());
        }
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
     * A name in the form {@link DatabaseMetaData} holds it, which is not the
     * form a statement carries.
     * <p>
     * The same rule as {@link SqlIdentifier#sql()} up to a point - a quoted name
     * is exact, an unquoted one is folded - and then deliberately not the same
     * call. {@code sql()} always folds up, because that is portable in a
     * statement: every target folds our upper-case name back onto whatever it
     * stored. The catalog is the other direction. It holds the name already
     * folded, in the direction this product chose, so a lookup has to fold the
     * way {@code storesLowerCaseIdentifiers} says rather than the way SQL is
     * written. Against PostgreSQL the two disagree, and asking {@code sql()}
     * here would find no columns at all.
     * <p>
     * A quoted name loses its quotes and its doubling for the same reason: the
     * catalog holds the column {@code a"b}, not the SQL {@code "a""b"} that
     * names it.
     */
    private static String normalize(SqlIdentifier name, boolean lowerCase) {
        if (name.quoted()) {
            return name.unquoted();
        }
        return lowerCase
                ? name.name().toLowerCase(Locale.ROOT)
                : name.name().toUpperCase(Locale.ROOT);
    }

    /** the same for the two parts of a target, which are text on the command line */
    private static String normalize(String name, boolean lowerCase) {
        return normalize(new SqlIdentifier(name), lowerCase);
    }

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
        assert currentSample != null;
        if (!Files.isRegularFile(currentSample)) {
            err.println("  sample         no such file: " + currentSample);
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
        out.printf("  sample         %s (%d bytes)%n", currentSample.getFileName(), sizeOf(currentSample));

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
        assert currentSample != null;
        var fieldNames = new LinkedHashSet<String>();
        collectFieldNames(mapping, fieldNames);

        try (var source = Files.newInputStream(currentSample)) {
            var result = adapter.parse(source, mapping.recordSelector(), Set.copyOf(fieldNames));
            var shown = new ArrayList<List<String>>();
            var unreadable = new ArrayList<String>();
            long matched;
            try (var stream = result.rows()) {
                var counter = new long[1];
                stream.forEach(row -> {
                    var at = ++counter[0];
                    // Every record, not only the printed ones. Until 1.1.0 the
                    // values of rows past --rows were never asked for, and
                    // several adapters convert inside Row.get - so a file whose
                    // forty thousandth record held an unparseable date passed
                    // this command silently. Reading is verification here, not a
                    // step on the way to printing, which is why it happens
                    // whether or not the row will be shown.
                    if (at <= rows) {
                        shown.add(describe(row, fieldNames, at, unreadable));
                    } else {
                        readQuietly(row, fieldNames, at, unreadable);
                    }
                });
                matched = counter[0];
            }
            reportUnreadable(mapping, unreadable);
            out.printf("  '%s'%s -> %s: %d record(s) matched%n",
                    mapping.recordSelector(),
                    " ".repeat(Math.max(1, 12 - mapping.recordSelector().length())),
                    mapping.table(), matched);
            if (matched == 0) {
                findings.add("record selector '" + mapping.recordSelector()
                        + "' matches nothing in " + currentSample.getFileName()
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
    private static List<String> describe(Row row, Set<String> fieldNames, long at,
                                         List<String> unreadable) {
        var described = new ArrayList<String>(fieldNames.size());
        for (var name : fieldNames) {
            Object value;
            try {
                value = row.get(name);
            } catch (RuntimeException e) {
                // Rendered *and* recorded. Until 1.1.0 it was only rendered, so a
                // spec whose very first record held a value that would not
                // convert printed <DateTimeParseException> in the middle of the
                // output and then said "no findings" and exited zero - a report
                // that showed the problem and denied it in the same breath.
                described.add(name + "=<" + e.getClass().getSimpleName() + ">");
                unreadable.add(describeFailure(name, at, e));
                continue;
            }
            described.add(name + "=" + (value == null
                    ? "null"
                    : value + " (" + value.getClass().getSimpleName() + ")"));
        }
        return described;
    }

    /**
     * The same read, for a record that will not be printed: the values are asked
     * for and thrown away, because asking is the point.
     */
    private static void readQuietly(Row row, Set<String> fieldNames, long at,
                                    List<String> unreadable) {
        for (var name : fieldNames) {
            try {
                row.get(name);
            } catch (RuntimeException e) {
                unreadable.add(describeFailure(name, at, e));
            }
        }
    }

    private static String describeFailure(String field, long at, RuntimeException e) {
        return "field '" + field + "' at record " + at + ": " + e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : " - " + e.getMessage());
    }

    /**
     * One finding per mapping however many records are bad, because a file with a
     * systematically wrong date format has every record bad and forty thousand
     * findings would bury the other four. The first is quoted in full, since it
     * is the one somebody will go and look at.
     */
    private void reportUnreadable(RecordMappingSpec mapping, List<String> unreadable) {
        if (unreadable.isEmpty()) {
            return;
        }
        var first = unreadable.getFirst();
        findings.add("'" + mapping.recordSelector() + "': " + unreadable.size()
                + " value(s) in the sample will not convert to their declared type, the first being "
                + first + ". The load would send this file to the hospital");
    }

    /**
     * The field selectors this mapping reads, including those inside a lookup
     * key or under a regex.
     * <p>
     * This set is what the adapter is asked to resolve, so a source it misses is
     * a field {@code check} reads the sample without - and then the preview shows
     * a spec behaving differently from the way the loader will, which is the one
     * thing this command must not do.
     */
    private static void collectFieldNames(RecordMappingSpec mapping, Set<String> into) {
        mapping.fieldMappings().forEach(fm -> collectFieldNames(fm.source(), into));
    }

    private static void collectFieldNames(ValueSource source, Set<String> into) {
        switch (source) {
            case ValueSource.Field(var name) -> into.add(name);
            case ValueSource.Lookup(_, _, var conditions) ->
                    conditions.values().forEach(key -> collectFieldNames(key, into));
            // a constant needs no record, a var is evaluated once per load, and an
            // expression's names are resolved by the loader against several scopes
            case ValueSource.Constant _, ValueSource.Var _, ValueSource.Expr _ -> {
            }
            // a call is a var source, so it reads no record either - and the loader
            // refuses one in a column, which is the only place this walks
            case ValueSource.FunctionCall _ -> {
            }
            // a regex reads whatever it is applied to, and that may well be a
            // field: this is the walk that tells the adapter which to resolve
            case ValueSource.Regex(var subject, _, _) -> collectFieldNames(subject, into);
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
