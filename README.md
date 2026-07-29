# XLDR

## Project Description

The idea of the XLDR toolkit is to provide a flexible yet simple engine to load files of different formats and layout or
structure into database tables.

The toolkit provides adapters for different file types that can be loaded as modules and utilize the service framework
of JPMS.

> **Pre-1.0.** The API and the mapping-spec format are still settling and may change in any release before `1.0`,
> including in ways that break existing code and existing specs. Such changes are listed in the
> [changelog](CHANGELOG.md) under *Breaking*, but no deprecation period is kept. From `1.0` on, breaking changes will
> be confined to major releases.

## Getting Started

Java 25 or later is required.

### Running the server

The server is not published as an artifact; build the distribution from a checkout:

    mvn install
    tar xzf app/target/xldr-<version>-dist.tar.gz

Then set up a feed - a directory below a root, holding a mapping spec:

    mkdir -p /var/lib/xldr/people
    cat > /var/lib/xldr/people/spec.json <<'EOF'
    {
      "input": {
        "mimeType": "text/csv",
        "accepts": "glob:*.csv",
        "properties": { "fieldSeparator": "," },
        "recordSelectors": [
          { "name": "people", "fieldSelectors": [
              {"name": "id",   "selector": "id",   "type": "INTEGER"},
              {"name": "name", "selector": "name", "type": "STRING"}
          ] }
        ]
      },
      "mapping": [
        { "recordSelector": "people", "table": "person", "fieldMapping": [
            {"fieldSelector": "id",   "column": "id"},
            {"fieldSelector": "name", "column": "name"}
        ] }
      ]
    }
    EOF

Point the server at the root and start it; it creates the working directories and picks the feed up:

    printf 'xldr.roots=/var/lib/xldr\njdbc.url=jdbc:postgresql://localhost:5432/xldr\n' > conf/xldr.properties
    bin/xldr conf/xldr.properties

A file moved into `/var/lib/xldr/people/in/` is now loaded into `person` and filed away under `archive/`. See
[Configuration](#configuration) for the full set of settings, and [Delivering files](#delivering-files) for why the
file must be *moved* rather than written in place.

### Using the toolkit as a library

The library modules are published to Maven Central under the group `io.github.ralfspoeth.xldr`. Import the `bom` to
fix their versions in one place:

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>io.github.ralfspoeth.xldr</groupId>
                <artifactId>bom</artifactId>
                <version>0.12</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

Then take the loader plus the adapters for the formats you read, without repeating the version; each adapter brings
`ia` and `spec` with it:

    <dependency>
        <groupId>io.github.ralfspoeth.xldr</groupId>
        <artifactId>ldr</artifactId>
    </dependency>
    <dependency>
        <groupId>io.github.ralfspoeth.xldr</groupId>
        <artifactId>csv</artifactId>
    </dependency>

The published modules are annotated for nullness with [JSpecify](https://jspecify.dev): `@NullMarked` at module
level, so every type is non-null unless it carries `@Nullable`. The annotations are compile-only - `requires static`
and `provided` scope - so nothing is added to your runtime; a null checker will read them, and a build without one is
unaffected.

The `bom` manages exactly the published artifacts - `spec`, `ia`, `ldr` and the adapters `csv`, `xml`, `xlsx`, `flt`
and `json` - and deliberately no third-party versions, so importing it does not bind you to the POI, HikariCP or JDBC
driver versions this build happens to use. `app` and `it` are not published at all. Both the spec readers and the
adapters are found through `ServiceLoader`, so each need only be on the module path - naming the spec file is enough
to read it, since its name says which format it is in:

    var spec = readSpec(Path.of("/var/lib/xldr/people/spec.json"));
    var factory = ServiceLoader.load(InputAdapterFactory.class).stream()
            .map(ServiceLoader.Provider::get)
            .filter(f -> f.reads(spec.inputSpec()))
            .findFirst().orElseThrow();
    var adapter = factory.createInputAdapter(spec.inputSpec());

    try (var loader = new Loader(spec, connection)) {
        for (var mapping : spec.recordMappingSpecs()) {
            try (var in = Files.newInputStream(file)) {
                loader.loadInput(adapter, in, mapping);
            }
        }
    }   // commits, or rolls back if any mapping failed

`readSpec` is named to be static-imported, which is how it reads best - `readSpec(specFile)` at the call site, from
`import static io.github.ralfspoeth.xldr.spec.io.MappingSpecReader.readSpec`. `MappingSpecReader.of(Path)` is the
same lookup without the reading, for asking whether a file is a spec this build can read at all; `readSpec` insists,
refusing an unsupported extension with an `IllegalArgumentException` before it opens anything.

## Building and Releasing

### Modules and building

The whole toolkit is one reactor under the `xldr` parent POM and builds with a single `mvn install`, which orders the
modules by their dependencies:

* `spec`, `ia`, `ldr` - the core: the mapping-spec model and readers, the input-adapter SPI, and the JDBC loader;
* `bom` - a bill of materials fixing the versions of the published modules in one import;
* `csv`, `xml`, `xlsx`, `flt`, `json` - the input adapters, each an `InputAdapterFactory` provider discovered through
  `ServiceLoader`;
* `app` - the server. It does not `requires` any adapter; the adapters are `provided` dependencies, so they are on the
  module path (JPMS service binding then pulls them into the graph via the `uses` in `app` and the `provides` in each
  adapter) without being bundled into `app`'s own runtime footprint. A deployment supplies the adapter set it needs;
* `it` - integration tests exercising the whole pipeline end to end against a local H2 database.

`revision` is a CI-friendly version property resolved by the `flatten-maven-plugin`, so the installed and deployed POMs
carry the concrete version rather than a literal `${revision}`.

### Distribution

`mvn package` on `app` builds a runnable distribution (`app/target/xldr-<version>-dist.{tar.gz,zip}`) via the
`maven-assembly-plugin`. Unpacked, it is

    xldr-<version>/
        bin/xldr, bin/xldr.bat   launchers
        lib/                     the application and every module jar it needs
        conf/                    sample xldr.properties and logging.properties
        README.md

and runs with

    bin/xldr conf/xldr.properties

The launcher puts `lib/` on the module path (`java -p lib -m io.github.ralfspoeth.xldr.app/...`); JPMS service binding then resolves
the input adapters (via the `uses`/`provides` of `InputAdapterFactory`) and the JDBC driver (via `java.sql`'s
`uses java.sql.Driver`) straight from `lib/`. The adapters and all three drivers are `provided` dependencies bundled
into `lib/` so the package is self-contained; drop the drivers you do not target. Do that before passing a
distribution on to anyone else - the Oracle driver is proprietary and not yours to redistribute.

`jlink` is deliberately not used: several runtime dependencies - the Oracle JDBC driver, 
HikariCP, picocli, SLF4J, POI - are automatic modules, which `jlink` cannot link into an image. The module-path distribution sidesteps that while
keeping the modular layout and its service binding intact.

### Releasing

Publishing goes through the Central Portal via the `central-publishing-maven-plugin`, inherited from the `plumbum`
parent. The plugin bundles the whole reactor into a single deployment, so the `xldr` parent POM, the `bom` and the
eight library modules - `spec`, `ia`, `ldr`, `csv`, `xml`, `xlsx`, `flt`, `json` - are published together. `app` (an
executable, not a library) and `it` (integration tests) each set `skipPublishing` on the plugin, so they are left out
of the bundle.

A plain deploy therefore publishes everything in one go:

    mvn deploy

or as a tagged release, which additionally builds and tests everything first:

    mvn release:prepare release:perform

Publishing needs a Central Portal user token in `settings.xml` under the server id `central` (generate it at
https://central.sonatype.com/account). `autoPublish` is on, so a valid deployment is released without a manual step.

Loading data from a file into one or more database tables is guarded by a *mapping specification* which comprises an
*input specification* and a *mapping*. The *input specification* tells the engine how to parse a given file and to
load *records* and *fields*. The *mapping* provides - as its name implies - a mapping from records to database tables
and from fields to database columns. The whole input is loaded in one transaction, committed when the file has been
read in full or rolled back entirely if any record mapping fails; the target database itself is configured on the
application, not in the mapping.

The mapping specification can be constructed programmatically or can be provided through some source text in one of the
following formats:

* .json: JSON format
* .xml: well-formed XML complying to a schema described below

A spec may carry more than the members the reader consumes. Anything a reader does not recognise - a JSON member, an
XML element or attribute, at any level - is ignored, so an author is free to annotate a spec with, say, a
`"comments": "..."` member without deviating from the format. The one exception is `load`: that name is **reserved**.
It carried the commit policy in an earlier version and may return, so it must not be repurposed for the author's own
data; a spec that still contains an old `load` block is simply ignored today.

### Validating a spec while writing it

Both formats have a published schema, so an editor can check a spec before it ever reaches a server - which otherwise
only reports a broken spec in its log, by leaving the feed inactive. Point at the schema from the spec itself:

    {
      "$schema": "https://ralfspoeth.github.io/xldr/schema/mapping-spec-0.10.json",
      "input": { ... }
    }

    <mappingSpec xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                 xsi:noNamespaceSchemaLocation="https://ralfspoeth.github.io/xldr/schema/mapping-spec-0.10.xsd">

Both are ignored by the readers - `$schema` is just another unrecognised member, and `xsi:` attributes carry no
meaning for a spec that has no namespace of its own. IntelliJ and VS Code both validate and autocomplete from them.

The schemas catch what a schema can: missing or misspelled names, a `type` that is not one of the five, a delivery
pattern without its `glob:`/`regex:` prefix, and - in JSON - an input that declares both `accepts` and `sentinel` or
neither, a field mapping with no source or several, and a var that reads a field. The rest is checked when the spec
is read, in particular that every selector compiles.

The XSD is the more permissive of the two, because XSD 1.0 cannot state either of those exactly-one rules. Nor can it
allow arbitrary extra elements next to the named ones, so annotate an XML spec with XML comments rather than with
elements of your own; a JSON spec takes an extra member anywhere.

A schema is published whenever the format changes, and is named after the release that changed it:
`mapping-spec-0.10` describes the format of 0.10, and of 0.11 and 0.12, neither of which changed it;
`mapping-spec-0.9` that of 0.9, and so on. An earlier one stays where it is, so a spec pinned to it keeps
validating.

What a schema cannot see is whether the spec makes sense as a whole - whether a mapping names a record selector the
input actually declares, or whether the adapter accepts the selectors. The distribution checks that:

    bin/xldr validate /var/lib/xldr/people/spec.json
    bin/xldr validate /var/lib/xldr/*/spec.json

It reads each spec the way the server would, then reports everything wrong with it rather than only the first thing:
a delivery rule that is missing or doubled, a pattern without its prefix, a MIME type no adapter on the module path
reads, a selector that adapter refuses to compile, and any record selector, field selector or var a mapping names but
the input does not declare. It also reports the one mistake that would otherwise pass for a healthy load: a CSV
record selector given a discriminator although the feed has a header, which matches no line and loads nothing.
Nothing is loaded and no database is touched. The exit code is 0 when every spec is good
and 1 otherwise, so it fits a CI job or a pre-commit hook for whoever authors the specs.

Reading different file types is supported by providing a specific adapter per MIME type. There may be more than one
adapter per MIME type on the module path; it's then however unspecified which one will be selected. A future enhancement
will allow require features to be implemented by the adapter. The adapters shipped with the toolkit are

| MIME type | Adapter | Input |
|-----------|---------|-------|
| `text/csv` | `csv` | separated columns, with or without a header row |
| `text/xml`, `application/xml` | `xml` | XML, selected with XPath |
| `application/vnd.ms-excel`, `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` | `xlsx` | Excel, both `.xls` and `.xlsx` |
| `text/plain` | `flt` | fixed length records, addressed by character position |
| `application/json`, `text/json` | `json` | JSON, selected with Greyson pointers |

Selecting records and fields depends on the type and structure of the input file. An adapter has to provide
implementations for *record selectors* and *field selectors*.

A *mapping* maps records, identified by the name of the record selector, to one or more database tables. A record maybe
mapped multiple times. Each mapping of a record to database table contains a field mapping that maps the fields of a
record to a database column.

## Implementation Details

### The Input Specification

An input specification contains the following pieces of information:

* the MIME type, which selects the adapter;
* exactly one of `accepts` or `sentinel`, the [delivery rule](#delivering-files) saying which arriving file is a
  complete one - required by the server, and the only part of the spec that is about files rather than about content;
* record selectors, each of which
    * is identified by a name,
    * has a selector specification - optional, and left out where the whole file holds one kind of record, as in a CSV
      with a header or a fixed-length file - and
    * has related field selectors, which in turn
        * are identified by a name,
        * a selector description,
        * and, optionally, a [data type](#field-types);
* optionally [variables](#variables), values computed once per load;
* optionally `properties`, the [settings of the adapter](#feed-configuration) the MIME type selects.

The meaning of a selector is the adapter's: an XPath for XML, a column position or discriminator for CSV, a character
range for a fixed-length file, a pointer for JSON, a cell range for a spreadsheet.

Example:

    "input": {
        "mimeType": "text/xml",
        "accepts": "glob:*.xml",
        "recordSelectors": [
            {
                "name": "xx",
                "selector": "//xx",
                "fieldSelectors": [
                    {
                        "name": "id",
                        "type": "STRING",
                        "selector": "@xxid"
                    }
                ]
            },
            {
                ...
            }
        ]
    }

### Field types

A field selector's `type` is one of `STRING`, `INTEGER`, `FLOAT`, `DECIMAL` or `DATE` (matched case-insensitively),
and decides the Java type the adapter delivers and therefore what the loader binds: `String`, `Long`, `Double`,
`BigDecimal` and `LocalDateTime`. It is optional; a field without one is read as text.

Values are read in their canonical form: an ungrouped literal such as `1234.56` for the numeric types, ISO-8601 for
`DATE` - a plain date (`2026-07-22`) as well as a timestamp (`2026-07-21T14:30`), a plain date being midnight of that
day. Surrounding whitespace is stripped, so a padded fixed-length column or an indented XML element needs no special
handling, and **a value that is blank is absent**: it becomes `null` and the loader binds SQL NULL. That holds for the
numeric types too, where a blank column is a missing value rather than a zero or a parse error.

Input in another notation is a property of the feed rather than of the mapping, and is configured on the adapter with
`dateFormat`, `numberFormat` and `locale` - see [Feed configuration](#feed-configuration).

### Variables

Alongside the record selectors, an input may declare `vars`: named values evaluated **once per load** and then
referenced from any field mapping by `{"var": "name"}`. A value looked up from a reference table is read a single
time and stamped onto every row of every table in the file, rather than re-resolved per row; a constant can be named
once and reused across mappings.

A var is row-independent by construction, so its source is a `constant`, `lookup`, or another `var` declared earlier -
never a `fieldSelector`. Vars are evaluated in declaration order.

    "input": {
        "mimeType": "text/csv",
        "vars": [
            {"name": "source", "constant": "PD"},
            {"name": "batchId", "lookup": {"table": "load_batch", "column": "id",
                                           "keyColumn": "feed", "constant": "funds"}}
        ],
        "recordSelectors": [ ... ]
    }

    "mapping": [
        {
            "recordSelector": "rows",
            "table": "t",
            "fieldMapping": [
                {"var": "source",  "column": "source_cd"},
                {"var": "batchId", "column": "batch_id"}
            ]
        }
    ]

### Expressions

An `expr` source is a `${...}` template, evaluated in the JVM and bound as a parameter - it never emits SQL. It is
interpolation plus a small set of functions, with no operators. Each `${...}` hole is either a name or a function
call, and adjacent holes concatenate:

    {"name": "generatedId", "expr": "${xldr.filename}-${nextval('doc')}"}
    {"expr": "${now()}",             "column": "loaded_at"}
    {"expr": "${nextval('rownum')}", "column": "line_no"}

the first as a `var` in the input, the other two as field mappings.

A **name** resolves in order: `${xldr.*}` for the application-provided ambient values (currently just
`xldr.filename`), then a declared `var`, then - in a field mapping - a field of the record. The two **functions** are:

* `nextval('name'[, start[, inc]])` - the next value of an in-memory sequence that lives for the one load, shared by
  name. The first draw is `start` (default 1), each later one adds `inc` (default 1). Sequences never touch the
  database;
* `now()` - the current instant (`java.time.Instant`);
* `format(value, 'pattern')` - a date or timestamp as text, in the pattern language of `DateTimeFormatter`. An
  instant is rendered at the JVM's zone, having none of its own;
* `parse(text, 'pattern')` - the reverse: a date or timestamp read from text in a notation no adapter recognises, for
  the one column that needs it rather than for the whole feed the way the `dateFormat` property does. What the
  pattern reads decides the type - a date and a time give a `LocalDateTime`, a date alone a `LocalDate`, a time alone
  a `LocalTime`.

An argument may itself be a name or a call, so `${format(now(), 'yyyy-MM-dd')}` and `${format(birthdate, 'yyyy')}`
both say what they look like; a name inside a call is resolved exactly as `${name}` would be, fields included. A null
value formats to null, so an absent date stays a SQL NULL rather than becoming the text `null`.

**Typing:** a template that is a single hole keeps that value's native type - `${nextval('r')}` binds an integer,
`${now()}` a timestamp, `${format(now(), 'yyyy')}` a string; anything with literal text or several holes concatenates
to a string.

An `Instant` is not one of the `java.time` types JDBC 4.2 requires a driver to accept - an instant carries no calendar
to write into a column - so the loader binds it as an `OffsetDateTime` at the JVM's zone. Without that, Oracle rejects
`${now()}` outright, whatever the target column is. Against a *text* column the driver then renders the timestamp its
own way, which under Oracle follows the session's NLS settings rather than ISO-8601 - so where a timestamp goes into a
text column, `${format(now(), 'yyyy-MM-dd HH:mm:ss')}` says what will be stored, and nothing else does.

Where it is used decides how often it runs: as a `var` it is evaluated **once per load** (one generated id for the
whole file, one sequence draw); as a field mapping it is evaluated **per row** (so `${nextval('rownum')}` numbers the
rows). A `var` expression has no record in scope, so it may not reference a field.

### Committing

The whole input is one transaction: the loader commits when the file has been read in full, or rolls everything back
if any record mapping fails - all or nothing. This keeps the file the unit of work, so a failed load leaves the target
tables untouched and the file can be corrected and retried.

Which database is fed, and how it is pooled, is configured on the application rather than in the mapping - see
[Configuration](#configuration). No connection information lives in the spec, so the same mapping can be promoted from
test to production unchanged.

### The Record Mapping Specification

The record mapping specification is an array of record mappings, each naming a record selector from the input
specification, the target table, and an array of field mappings from a source to a target column. Every field mapping
carries exactly one of these sources:

* `fieldSelector` - a field of the record, resolved by the adapter and bound as a parameter (the ordinary case);
* `constant` - a fixed value from the spec, bound as a parameter. In JSON its type follows the literal (string, number,
  boolean), and `null` loads a SQL NULL; in XML, an attribute, it is always a string and there is no way to write a
  null;
* `lookup` - a value read from a reference table, emitted as an inline scalar subquery
  `(select column from table where keyColumn = key)`. The `key` is itself a `fieldSelector`, `constant` or `var`;
  a key that matches no row, or a key that is null, yields NULL;
* `var` - a reference by name to an input [variable](#variables), bound as a parameter;
* `expr` - a [`${...}` template](#expressions) evaluated in the JVM, bound as a parameter.

Every value reaches the database as a bound parameter or a normalized identifier; a spec never contributes raw SQL.

A record mapping may also carry a `limit`, the maximum number of records inserted for it.

A lookup example - translate an ISO code carried in the input to a surrogate key:

    {
        "lookup": {
            "table": "country",
            "column": "id",
            "keyColumn": "iso",
            "fieldSelector": "country_code"
        },
        "column": "country_id"
    }

The two `column`s are at different levels and mean what their level says: the inner one is the column read *from* the
reference table, the outer one the column of the target table written *to*.

Example:

    "mapping": [
        {
            "recordSelector": "xx",
            "table": "tab_xx",
            "limit": 1000,
            "fieldMapping": [
                { "fieldSelector": "id", "column": "col_id" },
                { "constant": "PD",      "column": "source_cd" }
            ]
        },
        ...
    ]

A mapping specification as a whole is therefore an `input` and a `mapping`:

    {
        "input": { ... },
        "mapping": [ ... ]
    }

The order of the elements is unspecified.

### The XML Format

The same specification in XML. Everything is carried in attributes and the element and attribute names are those of
the JSON format, so a spec can be transliterated between the two without renaming anything. What is optional in JSON
is optional here.

    <mappingSpec>
        <input mimeType="text/xml" accepts="glob:*.xml">
            <properties ns.f="http://example.com/funds" dateFormat="dd.MM.yyyy"/>
            <var name="source" constant="PD"/>
            <recordSelector name="fund" selector="/root/fund">
                <fieldSelector name="id" selector="@id" type="STRING"/>
                <fieldSelector name="nav" selector="nav" type="DECIMAL"/>
            </recordSelector>
        </input>
        <mapping recordSelector="fund" table="snmandat" limit="1000">
            <fieldMapping fieldSelector="id" column="ident1_txt"/>
            <fieldMapping var="source" column="source_cd"/>
            <fieldMapping expr="${xldr.filename}" column="loaded_from"/>
            <fieldMapping constant="X" column="status_cd"/>
            <fieldMapping column="country_id">
                <lookup table="country" column="id" keyColumn="iso" fieldSelector="c"/>
            </fieldMapping>
        </mapping>
    </mappingSpec>

A value source is one attribute of a `fieldMapping` - `fieldSelector`, `constant`, `var` or `expr` - except for a
`lookup`, which is a child element of the mapping and carries its own source attribute for the key. A constant in XML
is always a string, since an attribute has no type of its own; the `null` a JSON spec can write has no XML form. Where
a column must be given a NULL from an XML spec, leave the mapping out - an unmapped column keeps whatever default the
table gives it.

## The Server

The application runs as a server watching a number of configured *roots*. A root is the only place in which feeds may
be created; a feed is a directory exactly one level below a root that contains a mapping spec.

    <root>/<feed>/
        spec.json           one of spec.json | spec.xml; its presence activates the feed
        in/                 producers move input files in here
        work/               claimed, currently being loaded
        archive/2026/07/22/ loaded successfully
        hospital/           failed, together with an error log

Creating a feed is `mkdir` plus dropping a spec in it; the four working directories are created by the server. Removing
the spec deactivates the feed, replacing it reloads it - no restart in either case. Exactly one spec file must be
present: two of them is refused rather than resolved by precedence, because loading through the wrong spec is worse
than not loading at all.

### Delivering files

A file must not be read while it is still being written. The server does not guess at this with size or timeout
heuristics - the producer states when a file is complete. Each feed declares **exactly one** of two delivery rules,
`accepts` or `sentinel`; a spec with both, or neither, does not activate a feed at all. Both patterns are passed
straight to Java's `FileSystem.getPathMatcher`, so each carries its own `glob:` or `regex:` prefix and matches against
the file name.

**Atomic delivery** (`"accepts": "glob:abc*.csv"`). A file whose name matches the pattern *is* the trigger, so it must
appear atomically: write it under an ignored name (`*.part`, `*.tmp`, or a dot-file) and rename it in place, or write it
outside `in/` and move it in. A same-filesystem rename is atomic; a plain write into `in/` is not, and risks a truncated
load. A file that does not match is left in `in/` untouched.

**Sentinel delivery** (`"sentinel": "glob:*.done"`). The producer writes the data file at leisure, then a marker file
matching the pattern. Only the marker's arrival triggers the load; the data file's own arrival is ignored. The data
file is the marker name minus its last dotted suffix, so `report.csv.done` loads `report.csv` (glob alternation, as in
`glob:*.{ok,ready,done}`, is comma-separated). The data file is claimed first and the marker deleted after, so a crash
in between leaves the data safely in `work/` and at worst an orphaned marker, which the next scan cleans up.

Either way the `mimeType` still selects the adapter. The server claims a file by moving it to `work/`, which is also
what stops two threads, or two server processes on the same tree, from loading it twice.

A load that fails leaves the input in `hospital/` beside a log naming the feed, the spec, the input, and the record
the loader was on - `record 7 of 'people' into PERSON: Value too long for column ID`. The record is the seventh the
*mapping* produced, which is not the seventh line of the file when a discriminator or a `limit` is in play, so it is
worth reading as "the seventh record this mapping loaded" rather than as a line number. Should a driver decline to
say which statement of a batch failed, the log names the range the batch covered instead.

Files left in `work/` at startup were claimed by a run that died. Whether their transaction committed is unknown, so
they are moved to `hospital/` for inspection rather than retried - a blind retry could duplicate rows, the loader
being insert only. Files in `hospital/` are never retried automatically either; moving one back into `in/` is a
deliberate operator action.

### Watching

Three levels are watched: each root, so a new feed directory is noticed; each feed directory, so a spec appearing,
changing or being removed takes effect immediately; and the `in/` of every active feed. Because a feed lives exactly
one level below a root, `work/`, `archive/` and `hospital/` are never watched and the archive tree cannot accumulate
watches as it grows.

Watch events only reduce latency. The guarantee is `xldr.scanInterval`, a periodic reconciliation that re-derives the
whole state from the file system, so a lost event, an event overflow or a subtree moved in complete with content
costs a few seconds rather than a feed that never comes up.

## Configuration

There are two places to configure: the server, one properties file per process, and each feed, which is its mapping
spec and nothing else. Everything about an input - which adapter reads it, how that adapter is set up, which files it
claims, what is extracted and where it goes - is in the one spec document.

### Server configuration

A single properties file, passed as the sole argument to the application. Connection settings live here, not in the
mapping specs, so a spec can be promoted between environments unchanged and no credentials sit in the watched tree.

| Key | Required | Default | Meaning |
|-----|----------|---------|---------|
| `xldr.roots` | yes | – | The directories in which feeds may be created, separated by the platform path separator (`:` on Unix, `;` on Windows). Each must exist at startup and none may be nested in another. |
| `xldr.scanInterval` | no | `30` | Seconds between full reconciliations of the tree; watch events only react sooner. |
| `xldr.maxConcurrentLoads` | no | `4` | Upper bound on files loaded at once, and the size of the connection pool: a load borrows exactly one connection for one file, so the pool is sized to match and never becomes a second, lower limit. |
| `jdbc.url` | yes | – | JDBC URL of the one target database. |
| `jdbc.user`, `jdbc.password` | no | – | Credentials, if the URL does not carry them. |
| `pool.*` | no | – | Passed through to HikariCP's `HikariConfig` under the key without the `pool.` prefix, e.g. `pool.connectionTimeout`. Setting `pool.maximumPoolSize` overrides the size derived from `xldr.maxConcurrentLoads`, for a database that will not grant that many sessions. |

    xldr.roots              = /var/lib/xldr:/mnt/feeds
    xldr.scanInterval       = 30
    xldr.maxConcurrentLoads = 4
    jdbc.url      = jdbc:oracle:thin:@//host:1521/sid
    jdbc.user     = dbuser
    jdbc.password = secret

The JDBC drivers for Oracle and PostgreSQL are `provided` dependencies: the deployment supplies the one matching its
target database.

### Feed configuration

A feed directory holds a mapping spec - `spec.json` or `spec.xml`, exactly one - and nothing else.

The settings of the adapter sit in the input's `properties`, next to the `mimeType` that chooses it - grouped rather
than spread out, because which of them mean anything depends on that MIME type:

    "input": {
        "mimeType": "text/csv",
        "accepts": "glob:*.csv",
        "properties": {
            "fieldSeparator": ";",
            "header": false,
            "dateFormat": "dd.MM.yyyy"
        },
        "recordSelectors": [ ... ]
    }

A value is taken as its text, so `false` and `2` may be written as themselves and arrive as `"false"` and `"2"`. In
XML the same settings are the attributes of a `<properties>` child of `<input>`:

    <properties fieldSeparator=";" header="false" dateFormat="dd.MM.yyyy"/>

An adapter ignores any setting it does not recognise, so the tables below list what each one reads.

**Every text adapter** (CSV, XML, fixed length, JSON) understands the same conversion settings. They say how the
*input* writes its values; the [field type](#field-types) says what the value *is*. Without them values are read in
their canonical form.

| Key | Default | Meaning |
|-----|---------|---------|
| `dateFormat` | ISO-8601 | `DateTimeFormatter` pattern for `DATE` fields, e.g. `yyyyMMdd` or `dd.MM.yyyy HH:mm`. A pattern without a time of day yields midnight. |
| `numberFormat` | plain literal | `DecimalFormat` pattern for `INTEGER`, `FLOAT` and `DECIMAL`, e.g. `#,##0.00` for grouped input. `DECIMAL` stays exact - it is never rounded through a double. |
| `locale` | `ROOT` (`1234.56`) | Language tag, e.g. `de-DE`, selecting the decimal and grouping separators of `numberFormat` and the symbols of `dateFormat`. |

Excel needs none of these: a spreadsheet carries typed cells, so a date or a number arrives as one already.

**CSV** (`text/csv`):

| Key | Default | Meaning |
|-----|---------|---------|
| `fieldSeparator` | tab | Column separator. |
| `header` | `present` | Whether the first row names the columns: `present`/`true`, or `absent`/`false`. With the header absent, field selectors are 1-based column positions (`"1"` → first column). Anything else is refused rather than read as absent. |
| `quote` | `"` | What opens and closes a quoted field. Empty switches quoting off, leaving quotes as ordinary characters. |
| `comment` | none | What begins a comment outside a quoted field. Unset, no character does. |
| `emptyLine` | `skip` | What an empty line means: `skip`, or `stop` to end the data there. |
| `charset` | platform default | Character set, e.g. `UTF-8`. |

A record is a line, and there is nothing to configure about that: a file may end its lines with `\n`, `\r\n` or `\r`
and is read the same way, so a file written on Windows loads on Linux unchanged. The lines are read as the loader
consumes them, so the size of a file is not the size of the memory it needs.

Inside a **quoted field** the separator and the line break are ordinary characters, and a doubled quote is one
literal quote - so `"Doe, Alice"` is one value, `"she said ""no"""` is `she said "no"`, and a record runs over as
many lines as a quoted field needs. That last part is the only thing that makes a record more than a line, and it is
what a spreadsheet export produces.

A quote is structural **only where a field begins** - right after a separator, or at the start of the record.
Anywhere else it is data, so `5" pipe` and `he said "no"` read as they are written. The strict reading would call
those an error; this one leaves files that load today loading. Where a value genuinely starts with a quote that is
data, set `quote` to nothing and no quote is special anywhere.

A quoted field that is never closed would otherwise swallow the rest of the file into a single record and report a
load of one row, so a record that stays open for more than a thousand lines is refused, naming the line that opened
it.

A **comment** runs from the comment character to the end of the record, and only outside a quoted field - inside one
the character is data, which is why the comment is found by the same scan that reads the fields rather than by
looking at the line. Nothing is a comment character unless the feed names one: a value like `#12345` is common enough
that the setting has to be asked for. A line that is nothing but a comment is not a record, and a banner of them at
the top of a generated file is looked past to find the header:

    "properties": { "fieldSeparator": ",", "comment": "#" }

    # produced 2026-07-28 by the nightly job
    id,name
    1,Alice          # this trailing comment is cut off
    2,"a # inside quotes is data"

An **empty line** is nothing at all by default and the file goes on. With `emptyLine = stop` it ends the data
instead, for a feed that writes a trailer - a checksum, a record count - after a blank line. A comment line never
stops anything, whatever is left of it once the comment is taken off.

For CSV a record selector's `selector` is a *first-column discriminator*. Headerless feeds often interleave several
record types in one file, the first column naming the type and the columns that follow varying in number, meaning and
type per type. A line belongs to a record selector only when its first column equals that selector's `selector`; an
absent or empty `selector` matches every line, which is the single-record-type case and what a feed with a header
almost always wants - giving one a `selector` there quietly loads nothing. Positions stay absolute, so `"1"`
is the discriminator column itself and a type's payload fields usually start at `"2"`. Several record selectors thus
partition one file, each mapping its own type to its own table:

    "recordSelectors": [
        { "name": "orders", "selector": "O",
          "fieldSelectors": [ {"name": "2", ...}, {"name": "3", ...}, {"name": "4", ...} ] },
        { "name": "lines",  "selector": "L",
          "fieldSelectors": [ {"name": "2", ...}, {"name": "3", ...}, {"name": "4", ...}, {"name": "5", ...} ] }
    ]

**XML** (`text/xml`, `application/xml`):

| Key | Default | Meaning |
|-----|---------|---------|
| `ns.<prefix>` | – | Binds a namespace prefix for the selectors, e.g. `ns.f = http://example.com/funds` to make `//f:fund` match. XPath 1.0 has no default namespace, so a document with one is reachable only through a bound prefix. |

XML differs from the other adapters in two deliberate ways. A `STRING` field keeps an empty string rather than
becoming null, because XPath cannot tell "no such element" from "an element that is empty". And a `FLOAT` is taken
through XPath's own numeric evaluation rather than from its text - which is why `INTEGER` and `DECIMAL` are not:
XPath 1.0 knows only doubles, so it would round a long integer and turn a decimal into a binary approximation.

**Fixed length** (`text/plain`):

| Key | Default | Meaning |
|-----|---------|---------|
| `linesPerRecord` | `1` | How many lines make up one record. Lines are joined, and the field bounds address the joined text, so a field may sit on the second line. A file that ends mid-record is an error. |
| `charset` | platform default | Character set, e.g. `UTF-8`. |

A field selector is a half-open character range `left:right` over the record, counted from zero, so `0:3` is the
first three characters. The left bound may be omitted, in which case the field starts where the previous one ended -
a layout can therefore be written as a list of end positions:

    "fieldSelectors": [
        {"name": "id",   "selector": "0:3",  "type": "STRING"},
        {"name": "name", "selector": ":23",  "type": "STRING"},
        {"name": "qty",  "selector": ":27",  "type": "INTEGER"}
    ]

A line that stops short of a field's bounds is not an error: the value is whatever the line still holds, and a field
beyond the end of the line is null. Together with the stripping every type does, that makes a producer's trailing
padding irrelevant. The adapter expects exactly one record selector; its `selector` is not used and is best left out.

**JSON** (`application/json`, `text/json`): no settings of its own, and deliberately no charset - JSON exchanged
between systems is UTF-8 by definition (RFC 8259), so a document is always read as such.

Both kinds of selector are pointers in Greyson's syntax: slash separated steps, where a step is a member name, `[n]`
for the n-th element of an array (`[-1]` counting from the end), or `#regex` to match a member by pattern. The record
selector addresses the records - `orders`, or `data/orders` in a nested document, an absent or empty selector being
the whole document. An array there yields one record per element, a single object exactly one record. A field selector is then
applied to the record, so `id` reads one of its members, `customer/address/city` reaches into a nested object and
`tags/[0]` into a nested array:

    "recordSelectors": [
        { "name": "orders", "selector": "data/orders", "fieldSelectors": [
            {"name": "id",   "selector": "id",                   "type": "STRING"},
            {"name": "city", "selector": "customer/address/city", "type": "STRING"},
            {"name": "net",  "selector": "amounts/net",          "type": "DECIMAL"}
        ] }
    ]

A member that is absent, or that holds `null`, is an absent value. JSON carries its own types, so a number arrives as
a number - exactly, never rounded through a double - and the shared `dateFormat` and `numberFormat` settings apply
only to values written as strings.

**Excel** (`application/vnd.ms-excel`, `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`): no
properties. One adapter serves both `.xls` and `.xlsx`; the format is detected from the file itself.

A record selector is a range, `[Sheet!]ref:ref`, one record per row:

* `A:C` - columns A to C of the first sheet, every data row;
* `Sheet1!B2:C4` - the cell rectangle rows 2 to 4, columns B to C, of the named sheet. Use this to leave a header row
  out of the records.

A field selector addresses a cell of the record, either absolutely by column - `A`, `B` - or relatively to the
record's anchor, the first column of the range: `R-1C+1` is one row up and one column right, which is how a record
reaches a heading or a neighbouring cell.

A spreadsheet carries typed cells, so no conversion settings apply: a date or a number arrives as one already, and a
cell that holds text where the spec wants a number is converted from that text.

### Monitoring

The server registers an MXBean at `io.github.ralfspoeth.xldr:type=Server`, so what it is doing can be read with
`jconsole`, VisualVM, or a Prometheus JMX exporter - no agent, no dependency, nothing to enable. Everything on it is
read-only: the file system remains the way to make the server do anything.

| Attribute | Meaning |
|-----------|---------|
| `ActiveFeeds` | How many feeds have a readable spec. A feed that disappears from this number has a spec the server refused. |
| `LoadsInProgress` | Files being loaded at this moment. Bounded by `xldr.maxConcurrentLoads`. |
| `LoadsSucceeded`, `LoadsFailed`, `RecordsLoaded` | Counted since the process started, so they are rates to be differenced. |
| `LastLoad`, `LastFailure` | Instants, or empty. A `LastLoad` that stops advancing on a feed that should be busy is the quiet failure worth catching. |
| `FilesWaiting` | Files sitting in any `in/`. Should fall back to zero; a number that does not is a feed not claiming what arrives - a delivery rule that matches nothing, say. |
| `FilesInHospital` | Files a load failed on, not counting the `.log` written beside each. Nothing puts a file there but a failure and nothing removes one but an operator, so this is the alert to raise. |
| `Feeds` | The same, per feed, so a failing feed can be told from a quiet one. |

HikariCP's own pool statistics are separate and off by default; `pool.registerMbeans = true` in the server
configuration turns them on, since every `pool.*` key is passed through.

### Logging

The application logs through `System.Logger`; HikariCP and POI log through SLF4J, which the distribution binds with
`slf4j-jdk14`. Everything therefore ends up in `java.util.logging`, and a single JUL configuration covers the whole
process; no second logging framework is involved.

A default `logging.properties` is bundled and applied at startup unless the deployment points `java.util.logging` at a
configuration of its own:

    java -Djava.util.logging.config.file=/etc/xldr/logging.properties -p <module-path> -m io.github.ralfspoeth.xldr.app config.properties

## License

XLDR is released under the [MIT License](LICENSE) - use it, embed it, ship it, with or without your own source. The
libraries it is built on are permissive too: Greyson, filews and SLF4J are MIT, POI, HikariCP and picocli Apache-2.0.

The JDBC drivers are not xldr's to license, and none is pulled in transitively - they are `provided` dependencies, so
a consumer of the libraries supplies the driver for the database it feeds and accepts that driver's own terms. Note
that the [distribution](#distribution) does bundle them into `lib/` for convenience; the Oracle driver in particular
is proprietary, so a distribution you pass on to anyone else should have `lib/ojdbc*.jar` removed.
