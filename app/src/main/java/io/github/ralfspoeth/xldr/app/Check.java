package io.github.ralfspoeth.xldr.app;

import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.ia.Row;
import io.github.ralfspoeth.xldr.spec.MappingSpec;
import io.github.ralfspoeth.xldr.spec.RecordMappingSpec;
import io.github.ralfspoeth.xldr.spec.RecordSelectorSpec;
import io.github.ralfspoeth.xldr.spec.ValueSource;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
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
        if (sample != null) {
            checkAgainstTheSample(spec, out, err);
        } else {
            out.println("  sample         not checked, no --sample given");
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

    // ---- the spec against the database ----------------------------------------

    /**
     * Every {@code table} exists and every {@code column} is one of its own.
     * <p>
     * Read through {@link java.sql.DatabaseMetaData} rather than by parsing DDL:
     * the database is the authority on what its tables hold, and a DDL file is a
     * statement about what they held when it was written.
     */
    private void checkColumnsExist(MappingSpec spec, PrintWriter out, PrintWriter err) {
        try (var conn = connect()) {
            var meta = conn.getMetaData();
            for (var mapping : spec.recordMappingSpecs()) {
                var table = normalize(mapping.table(), meta.storesLowerCaseIdentifiers());
                var actual = columnsOf(conn, table);
                if (actual.isEmpty()) {
                    findings.add("no table '" + mapping.table() + "' in the target database");
                    continue;
                }
                for (var fm : mapping.fieldMappings()) {
                    var column = normalize(fm.column(), meta.storesLowerCaseIdentifiers());
                    if (!actual.contains(column)) {
                        findings.add("table '" + mapping.table() + "' has no column '" + fm.column()
                                + "'; it has " + new TreeSet<>(actual));
                    }
                }
            }
            out.printf("  columns        checked against %s%n", conn.getMetaData().getURL());
        } catch (SQLException e) {
            err.println("  columns        not checked: " + e.getMessage());
        }
    }

    private Connection connect() throws SQLException {
        assert url != null;
        return user == null
                ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, user, password);
    }

    private static Set<String> columnsOf(Connection conn, String table) throws SQLException {
        var columns = new LinkedHashSet<String>();
        try (var rs = conn.getMetaData().getColumns(null, null, table, null)) {
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
