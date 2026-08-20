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

Download the distribution from the [latest release](https://github.com/ralfspoeth/xldr/releases/latest) and unpack
it. Java 25 or later is the only requirement - the archive carries the toolkit, the adapters, and JDBC drivers for
H2 and PostgreSQL:

    tar xzf xldr-<version>-dist.tar.gz        # or unzip xldr-<version>-dist.zip
    cd xldr-<version>

For Oracle, drop `ojdbc17` into `drivers/`; it is left out of the download rather than the build, so that
publishing the archive does not make this project a redistributor of it. That is the one respect in which the
download differs from a local build, and there is a note in `drivers/` saying so.

Or build it from a checkout, which produces the same archive - Oracle driver included - named after the module that
assembled it:

    mvn install
    tar xzf app/target/app-<version>-dist.tar.gz

Then set up a feed - a directory below a root, holding two files: how its files arrive, and what to do with them.

    mkdir -p /var/lib/xldr/people
    echo 'accepts = glob:*.csv' > /var/lib/xldr/people/delivery.properties
    cat > /var/lib/xldr/people/spec.json <<'EOF'
    {
      "input": {
        "mimeType": "text/csv",
        "properties": { "fieldSeparator": "," },
        "recordSelectors": [
          { "name": "people", "fieldSelectors": [
              {"name": "id",   "selector": "id",   "type": "INTEGRAL"},
              {"name": "name", "selector": "name", "type": "TEXT"}
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

Point the server at the root and start it; it creates the working directories and picks the feed up. The server
reads `xldr.properties` from the directory it is started in, or from the one `--dir` names:

    printf 'xldr.roots=/var/lib/xldr\njdbc.url=jdbc:postgresql://localhost:5432/xldr\n' > xldr.properties
    bin/xldr                        # this directory
    bin/xldr --dir /etc/xldr        # or that one

A file moved into `/var/lib/xldr/people/in/` is now loaded into `person` and filed away under `archive/`. See
[Configuration](#configuration) for the full set of settings, and [Delivering files](#delivering-files) for why the
file must be *moved* rather than written in place.

**Writing the spec is the actual work**, and this README is the reference rather than the path through it. For that,
see the [tutorial](docs/tutorial/README.md). It is twelve short pages, each adding one thing to the spec built by
the page before: constants, variables, lookups, expressions, types and notation, counting a headerless file's
components, separating several kinds of record into several tables, and - last, because it needs everything before
it - having a language model draft one and knowing what to check. Each page shows whole files rather than
fragments, so what you copy is something you can put straight into a feed.

### Using the toolkit as a library

The library modules are published to Maven Central under the group `io.github.ralfspoeth.xldr`. Import the `bom` to
fix their versions in one place:

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>io.github.ralfspoeth.xldr</groupId>
                <artifactId>bom</artifactId>
                <version>0.35</version>
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

The `bom` manages exactly the published artifacts - `spec`, `ia`, `ldr`, `server` and the adapters `csv`, `xml`,
`xlsx`, `flt` and `json` - and deliberately no third-party versions, so importing it does not bind you to the POI, HikariCP or JDBC
driver versions this build happens to use. `app`, `xlet` and `it` are not published at all: `app` is the
distribution rather than a library, `xlet` is a front end to read and adapt rather than to depend on, and what an
application would embed is `server`. Both the spec readers and the
adapters are found through `ServiceLoader`, so each need only be on the module path - naming the spec file is enough
to read it, since its name says which format it is in:

    var spec = readSpec(Path.of("/var/lib/xldr/people/spec.json"));
    int rows = Loader.load(spec, () -> Files.newInputStream(file), Map.of(), connection);
    // one transaction: committed, or rolled back if any mapping failed

That is the whole of loading one input. `Loader.load` finds the adapter for the spec's MIME type, runs every record
mapping over the input, commits, and closes the connection it was given. It is what the file server does with a file
that has arrived and what a web application does with a request body, so it lives in `ldr` rather than in either.

The input is an `InputSource` - openable, not opened - because a spec may carry several record mappings and each is
run over the whole input. A file simply reopens; anything read from a socket has to be spooled somewhere first, and
the interface says so rather than leaving it to be discovered from a load that quietly imported one mapping's worth
of rows.

The pieces underneath remain public for a caller that wants them: `InputAdapterFactory.of(inputSpec)` for the
adapter, and the `Loader` constructor with `loadInput(adapter, in, mapping)` for driving the mappings by hand -
useful when the mappings are not all wanted, or the transaction is not the whole input.

`readSpec` is named to be static-imported, which is how it reads best - `readSpec(specFile)` at the call site, from
`import static io.github.ralfspoeth.xldr.spec.io.MappingSpecReader.readSpec`. `MappingSpecReader.of(Path)` is the
same lookup without the reading, for asking whether a file is a spec this build can read at all; `readSpec` insists,
refusing an unsupported extension with an `IllegalArgumentException` before it opens anything.

Both service lookups - `MappingSpecReader.of` and `InputAdapterFactory.of` - resolve against the class loader that
defined the service rather than the thread context one, and a caller writing its own lookup should do the same. The
one-argument `ServiceLoader.load(Class)` uses the context loader, which a servlet container, a test runner or an
application framework will have set to something of its own; where that loader cannot see these modules the lookup
finds no providers and reports nothing at all.

## Building and Releasing

### Modules and building

The whole toolkit is one reactor under the `xldr` parent POM and builds with a single `mvn install`, which orders the
modules by their dependencies:

* `spec`, `ia`, `ldr` - the core: the mapping-spec model and readers, the input-adapter SPI, and the JDBC loader;
* `bom` - a bill of materials fixing the versions of the published modules in one import;
* `csv`, `xml`, `xlsx`, `flt`, `json` - the input adapters, each an `InputAdapterFactory` provider discovered through
  `ServiceLoader`;
* `server` - the watching and the loading: the `Watcher`, the feed registry, the file processor and the JMX
  statistics. It does not `requires` any adapter; JPMS service binding pulls them into the graph via the `uses` here
  and the `provides` in each adapter, so a deployment supplies the adapter set it needs on the module path. This is
  the module to depend on to embed the server in something else;
* `app` - the server as it is shipped: the command line, the connection pool and the logging setup around `server`,
  plus the distribution. Those are the choices a *runner* makes rather than the server's own, which is why they are
  separate - an embedder brings its own and should not inherit picocli and HikariCP for the privilege. The adapters
  are `provided` dependencies here, so they reach the module path without being bundled into `app`'s own footprint;
* `xlet` - the other front end: one input per HTTP request, loaded through a spec the deployment carries under
  `/WEB-INF/specs/`, for a servlet container. It is a peer of `app` rather than a part of `server` - the request *is*
  the delivery, so nothing there watches a directory or claims a file by moving it - and it reaches the same
  `Loader.load` from the other side, and reports what it has loaded through an MXBean of its own, named after the
  context it is deployed at so that two deployments do not collide. Not published: it is a front end to read and
  adapt to a deployment, not a library to depend on. Its own README argues the design;
* `it` - integration tests exercising the whole pipeline end to end against a local H2 database. It depends on
  `server` and the adapters, not on `app`: a test supplies its own `ConnectionSource` as a lambda, so what is
  exercised is the server rather than the way the distribution happens to run it. Tests that need no database and no
  server - where the configuration is looked for, and what is said when it is not there - live in `app` and run
  under surefire.

`revision` is a CI-friendly version property resolved by the `flatten-maven-plugin`, so the installed and deployed POMs
carry the concrete version rather than a literal `${revision}`.

### Distribution

`mvn package` on `app` builds a runnable distribution (`app/target/app-<version>-dist.{tar.gz,zip}`) via the
`maven-assembly-plugin`; the release workflow repacks the same tree as `xldr-<version>-dist`, the name the archive
unpacks to, and takes the Oracle driver out on the way. Unpacked, it is

    xldr-<version>/
        bin/xldr, bin/xldr.cmd   launchers
        lib/                     the application and the toolkit
        modules/                 the input adapters
        xl/                      the Excel adapter and Apache POI
        drivers/                 the JDBC drivers - H2 and PostgreSQL, Oracle too in a local build
        conf/                    sample xldr.properties and logging.properties
        README.md

and runs with

    cd /etc/xldr && /opt/xldr/bin/xldr        # xldr.properties here
    /opt/xldr/bin/xldr --dir /etc/xldr        # or named

**The division is between what has to be there and what a deployment chooses.** `lib/` is the first: remove anything
from it and nothing starts. The other three hold service providers, one directory per kind of choice - which formats,
whether Excel, which database - and the launcher puts all four on the module path. JPMS service binding then resolves
the input adapters (via the `uses`/`provides` of `InputAdapterFactory`) and the JDBC driver (via `java.sql`'s
`uses java.sql.Driver`), so choosing is a matter of moving jars and nothing else: no classpath to edit, no setting to
change. Each of the three may be empty, or absent altogether - choosing none of something is a choice, and a server
with no adapters starts and then refuses to activate any feed, which is loud in the right place.

**Installing your own driver is copying its jar into `drivers/`.** Removing the ones you do not target is the same
operation in reverse, and worth doing before passing a distribution on to anyone else: the Oracle driver is
proprietary and not yours to redistribute.

**`xl/` is Excel, kept apart for weight.** Apache POI brings xmlbeans, curvesapi, several commons libraries and
log4j-api, which together were most of the distribution and made it hard to see what the toolkit is actually made of.
It is named for the format rather than for the library, as `drivers/` is: what a deployment decides is whether it
reads spreadsheets, and POI is how that happens to be done. A deployment that reads none **deletes `xl/` whole**. The
`xlsx` adapter lives in there rather than in `modules/` with the other formats, which is what makes the directory
droppable rather than merely tidy - left among them with its `requires` unsatisfiable, it would stop the JVM before
`main`, since service binding resolves a provider's own dependencies and a missing one is a `FindException` rather
than a quietly absent format.

Nothing in the distribution is compile-time-only. `jspecify` is not shipped: every module declares
`requires static org.jspecify`, which is a claim made to the compiler, and no annotation of it is read at run time.

The launcher takes `java` from `JAVA_HOME` when that is set and from `PATH` otherwise, follows any symlink it was
invoked through - installing `/usr/local/bin/xldr` pointing into `/opt/xldr` works - and checks the JVM is new enough
before starting, so a wrong `JAVA_HOME` is reported as such rather than as an `UnsupportedClassVersionError` naming a
class file version. `JAVA_OPTS` carries extra VM options.

`jlink` is deliberately not used, and the reason has narrowed since it was first written down: HikariCP, picocli and
both SLF4J jars all carry a real `module-info` today, so `lib/` would link. What still would not are **the JDBC
drivers** - PostgreSQL, Oracle and H2 are all automatic modules, and no vendor ships a real one - and the tail of
POI's dependencies, `SparseBitSet`, `commons-math3` and `curvesapi`. `jlink` refuses an automatic module outright.

That is not a passing inconvenience but the same point `drivers/` and `xl/` are making: which database a deployment
talks to and whether it reads spreadsheets are the deployment's decisions, and an image would have to make both of
them at build time. An image is also built for one platform by the JDK that links it, where this archive runs
wherever there is a JVM. The module-path distribution keeps the modular layout and its service binding intact and
leaves both choices where they belong.

### Releasing

Publishing goes through the Central Portal via the `central-publishing-maven-plugin`, inherited from the `plumbum`
parent. The plugin bundles the whole reactor into a single deployment, so the `xldr` parent POM, the `bom` and the
nine library modules - `spec`, `ia`, `ldr`, `server`, `csv`, `xml`, `xlsx`, `flt`, `json` - are published together. `app` (an
executable, not a library) and `it` (integration tests) each set `skipPublishing` on the plugin, so they are left
out of the bundle.

A plain deploy therefore publishes everything in one go:

    mvn deploy

or as a tagged release, which additionally builds and tests everything first:

    mvn release:prepare release:perform

`release:prepare` runs `clean verify`, integration tests included, before the tag is cut - that is the gate.
`release:perform` then rebuilds the tagged source, and is configured with `<goals>deploy -DskipITs</goals>` so it does
not run them a second time against source that has just passed. `goals` is the only perform-only setting the release
plugin has: `arguments` and `releaseProfiles` are shared with prepare, and `useReleaseProfile` has defaulted to false
since 3.x. Should a `<site>` ever be added to `distributionManagement`, this override would need `site-deploy` adding
back to it.

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
XML element or attribute, at any level - is ignored, so an annotation never breaks a spec; the schemas, being
stricter, name `comment` for exactly that purpose. The one exception is `load`: that name is **reserved**.
It carried the commit policy in an earlier version and may return, so it must not be repurposed for the author's own
data; a spec that still contains an old `load` block is simply ignored today.

### Validating a spec while writing it

Both formats have a published schema, so an editor can check a spec before it ever reaches a server - which otherwise
only reports a broken spec in its log, by leaving the feed inactive. Point at the schema from the spec itself:

    {
      "$schema": "https://ralfspoeth.github.io/xldr/schema/mapping-spec-0.35.json",
      "input": { ... }
    }

    <mappingSpec xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                 xsi:noNamespaceSchemaLocation="https://ralfspoeth.github.io/xldr/schema/mapping-spec-0.35.xsd">

Both are ignored by the readers - `$schema` is just another unrecognised member, and `xsi:` attributes carry no
meaning for a spec that has no namespace of its own. IntelliJ and VS Code both validate and autocomplete from them.

The schemas catch what a schema can: missing or misspelled names, a `type` that is not one of the five, and - in
JSON - a field mapping with no source or several, and a var that reads a field. The rest is checked when the spec is
read, in particular that every selector compiles. How the feed's files arrive is no longer among them: that moved to
`delivery.properties`, which the server reads and no schema describes, so a spec still carrying `accepts` or
`sentinel` is refused rather than ignored.

The XSD is the more permissive of the two, because XSD 1.0 cannot state either of those exactly-one rules. Nor can it
allow arbitrary extra elements next to the named ones, so a longer note belongs in an XML comment rather than in an
element of your own.

Annotate a spec with `comment`, which every element and every object takes and both readers ignore:

    { "recordSelector": "people", "table": "person", "comment": "the nightly delivery", ... }
    <mapping recordSelector="people" table="person" comment="the nightly delivery">

The readers ignore any member or attribute they do not know, but the schemas name this one and go on refusing the
rest - because further down a spec an unknown name is far more often a misspelling than a note. `fieldSelector`
written for `fieldSelectors` costs a record every one of its fields, and no reader will say so: ignoring the
unknown is exactly what it promises.

A schema is published whenever the format changes, and is named after the release that changed it:
`mapping-spec-0.35` describes the format from 0.35, `mapping-spec-0.32` that of 0.32 to 0.34,
`mapping-spec-0.23` that of 0.23 to 0.31,
`mapping-spec-0.21` that of 0.21 to 0.22,
`mapping-spec-0.13` that of 0.13 to 0.20,
`mapping-spec-0.10` that of 0.10 to 0.12, and so on. An
earlier one stays where it is, so a spec pinned to it keeps validating.

What a schema cannot see is whether the spec makes sense as a whole - whether a mapping names a record selector the
input actually declares, or whether the adapter accepts the selectors. There was a `bin/xldr validate` for that,
removed in 0.30, because the checks worth having had migrated one by one to the places that know: an adapter refuses
a selector naming no column of the file it is reading, a feed that cannot activate says why, and `xlet` refuses to
deploy at all with a spec it cannot load. Each of those is earlier than a command, or better informed, and none of
them can be forgotten.

What went with it was one check nothing else makes - a CSV record selector given a discriminator although the file
has a header, which is legal and usually a mistake - and it went because *usually* is the problem: a headed file may
perfectly well carry a type column whose values are what the discriminator selects on. In 0.32 that stopped being a
grey area at all: a discriminator may name the component it tests, so a headed file with a type column is what the
feature is *for*.

Reading different file types is supported by providing a specific adapter per MIME type. There may be more than one
adapter per MIME type on the module path; it's then however unspecified which one will be selected. A future enhancement
will allow require features to be implemented by the adapter. The adapters shipped with the toolkit are

| MIME type                                                                                       | Adapter | Input                                                 |
|-------------------------------------------------------------------------------------------------|---------|-------------------------------------------------------|
| `text/csv`                                                                                      | `csv`   | separated columns, with or without a header row       |
| `text/xml`, `application/xml`                                                                   | `xml`   | XML, selected with XPath                              |
| `application/vnd.ms-excel`, `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` | `xlsx`  | Excel, both `.xls` and `.xlsx`                        |
| `text/plain`                                                                                    | `flt`   | fixed length records, addressed by character position |
| `application/json`, `text/json`                                                                 | `json`  | JSON, selected with Greyson pointers                  |

Selecting records and fields depends on the type and structure of the input file. An adapter has to provide
implementations for *record selectors* and *field selectors*.

A *mapping* maps records, identified by the name of the record selector, to one or more database tables. A record maybe
mapped multiple times. Each mapping of a record to database table contains a field mapping that maps the fields of a
record to a database column.

## Implementation Details

### The Input Specification

An input specification contains the following pieces of information:

* the MIME type, which selects the adapter;
* record selectors, each of which
    * is identified by a name,
    * says which records are its own - a `selector` for an input that has to be *pointed at*, or a
      [`discriminator`](#which-records-are-of-a-kind) for a flat one where every line is a candidate. Both are
      optional, and neither is written where the whole file holds one kind of record, as in a CSV with a header or a
      fixed-length file. No record selector carries both: no input is read both ways, and an adapter that locates
      its records refuses a discriminator by name rather than proceeding without it;
    * has related field selectors, which in turn
        * are identified by a name, distinct within that record selector - a mapping refers to a field by this
          name, so two of them cannot share it and a spec that repeats one is refused,
        * say where the value sits - a `selector` or an [`nth`](#where-a-value-sits), exactly one of the two,
        * and, optionally, a [data type](#field-types);
* optionally [variables](#variables), values computed once per load;
* optionally `properties`, the [settings of the adapter](#feed-configuration) the MIME type selects.

### Where a value sits

A field says it in one of two ways, and exactly one.

A **`selector`** is the adapter's own syntax: an XPath for XML, a character range for a fixed-length file, a pointer
for JSON, a cell reference for a spreadsheet, the name of a column for CSV.

An **`nth`** counts, from one. It is *the n-th component of the record the record selector identified*, and each
adapter only has to say what its records are made of:

| input | the n-th component |
|---|---|
| CSV, TSV | the n-th field of the line |
| Excel | the n-th column of the record's **range**, counted from the range's own first column |
| JSON | the n-th element, where the record is an array |
| XML | the n-th child element |
| fixed length | nothing - a fixed-length record has offsets and no components, so `nth` there is refused |

    { "name": "id", "selector": "id" }        <fieldSelector name="id" selector="id"/>
    { "name": "id", "nth": 1 }                <fieldSelector name="id" nth="1"/>

**Two names rather than one attribute of two types**, because the XML format cannot express the second: an attribute
is text, `selector="3"` is the only thing writable, and a reader deciding by *looks like a number* would have kept
exactly the ambiguity this removes - while a header that really does name a column `3` makes the guess wrong. Two
names cost nothing and let both schemas type `nth` as an integer, so `nth="first"` is refused before any adapter sees
it. And **not** `column`: a field *mapping* has always used that word for the database column it writes to, and the
two would have sat a line apart meaning opposite ends of the same value.

Where the *data* has no n-th component - a JSON record that turns out to be an object, which is unordered by
specification, or a line with fewer fields than that - the value is `null`, because only the data could have said so
and the next record may differ. Where the *format* has none, the spec is refused when the adapter is built, the spec
alone having proved it wrong.

Example:

    "input": {
        "mimeType": "text/xml",
        "recordSelectors": [
            {
                "name": "xx",
                "selector": "//xx",
                "fieldSelectors": [
                    {
                        "name": "id",
                        "type": "TEXT",
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

A field selector's `type` is one of `TEXT`, `INTEGRAL`, `FP`, `DECIMAL` or `DATE` (matched case-insensitively),
and decides the Java type the adapter delivers and therefore what the loader binds: `String`, `Long`, `Double`,
`BigDecimal` and `LocalDateTime`. It is optional; a field without one is read as text. The names are none of Java's
on purpose, so that `FP` is not read as `float` nor `INTEGRAL` as `int`, and so that the choice between `FP` and
`DECIMAL` - rounding or exact - is the one the reader is asked to make.

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

A **name** resolves in order: the two reserved prefixes `${xldr.*}` for what the application knows about the load
(currently just `xldr.filename`) and `${env.*}` for what the deployment supplies (see
[Deployment values](#deployment-values)), then a declared `var`, then - in a field mapping - a field of the record.
The prefixes are reserved rather than merged into that order on purpose: an unprefixed ambient name placed ahead of
the fields would silently shadow a column of the same name in every row, and placed behind them would be invisible in
exactly the mappings that have a record in scope. The **functions** are:

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

### Loading twice

**XLDR inserts. It does not merge, and this is deliberate.** A mapping has no notion of a natural key, so a file
loaded twice is loaded twice. What the loader guarantees is narrower and more useful than it first appears, and
what it leaves alone is left alone on purpose.

**What it guarantees.** A load is one transaction over the whole file. A failed load leaves the tables exactly as
they were, so *retrying a file that failed* is always safe - which is the idempotency that matters operationally,
because that is the case that actually happens at three in the morning. What is not safe is loading a file that
already succeeded.

**Why it stops there.** The target is a **landing zone**, and what happens next is the application's business:
either it knows how to ingest what has landed, or a stored procedure merges or replaces it. That boundary is where
it belongs, because merging needs things a mapping spec does not and should not know - which columns form the
natural key, whether a row is versioned or overwritten, what a soft delete looks like, whether a late correction
supersedes an earlier record or sits beside it. Expressing that in a spec means the format growing a key language,
then a conditional, then an ordering; and a configuration format that grows those has become a programming language
with none of the tools. The database already has one, and it is better at this.

So the division is: XLDR is responsible for the contents of the file arriving faithfully, completely and in one
transaction. Everything about what those rows *mean* against what is already there belongs downstream.

**What a landing table wants.** Three columns make the downstream job possible, and this is where the sources on a
field mapping stop being decorative:

    {"expr": "${xldr.filename}", "column": "loaded_from"},
    {"var": "loadedAt",          "column": "loaded_at"},
    {"var": "batch",             "column": "batch_id"}

The filename identifies the delivery, the timestamp orders two deliveries of the same name, and a batch number -
a [variable](#variables), so it is drawn once per load rather than once per row - groups the rows a merge should
consider together. Without at least the first, a landing table cannot answer "where did this row come from", and
neither can anyone reconciling it.

**Refusing a redelivery, if you want that.** Since a load is one transaction and a record mapping may cap itself
with `limit`, the same record selector can feed a control table exactly once per file:

    {"recordSelector": "customers", "table": "load_control", "limit": 1, "fieldMapping": [
        {"expr": "${xldr.filename}", "column": "filename"},
        {"var": "loadedAt",          "column": "loaded_at"}
    ]}

Put a unique constraint on `load_control.filename` and a second delivery of the same name fails on that insert,
which rolls the whole load back and sends the file to `hospital/` with the constraint violation beside it. A
duplicate load becomes a refusal rather than duplicated rows - the same trade this toolkit makes everywhere else,
and available without any support from the spec format. Whether a repeated filename really means a repeated
delivery is a question about your producer, which is why this is a pattern here rather than a feature.

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
        <input mimeType="text/xml">
            <properties ns.f="http://example.com/funds" dateFormat="dd.MM.yyyy"/>
            <var name="source" constant="PD"/>
            <recordSelector name="fund" selector="/root/fund">
                <fieldSelector name="id" selector="@id" type="TEXT"/>
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
        delivery.properties how files arrive; its presence makes the directory a feed
        spec.json           one of spec.json | spec.xml; what to do with what arrives
        env.properties      optional; what this deployment supplies to the spec
        in/                 producers move input files in here
        work/               claimed, currently being loaded
        archive/2026/07/22/ loaded successfully
        hospital/           failed, together with an error log

Creating a feed is `mkdir` plus two files; the four working directories are created by the server. The two have
different owners and need not arrive together. `delivery.properties` is what makes the directory a feed: with it alone
the feed is real - its directories exist and its producer may deliver - but nothing is loaded, and what arrives waits
in `in/` until a spec appears, at which point the backlog is loaded without being delivered again. A feed in that state
says so once in the log, at WARNING, rather than every scan.

Removing the spec deactivates the feed, replacing it reloads it - and the same goes for the delivery file, since
changing which files a feed claims is no more structural than changing a selector. No restart in either case. Exactly
one spec file must be present: two of them is refused rather than resolved by precedence, because loading through the
wrong spec is worse than not loading at all.

Deactivating takes effect for files as well as for the feed: a file already sitting in `in/` when the spec goes is
left there untouched, and so is a marker beside it. A load in flight when the spec is removed does run to the end -
it is a transaction, and abandoning it halfway is not an improvement - but nothing new is started. Switching a feed
off is therefore something an operator can rely on rather than a race against whatever is in the directory.

### Deployment values

A spec is meant to travel from test to production unchanged, so anything that must differ between the two cannot be in
it. A feed may therefore hold an optional `env.properties` beside its spec, and every key in it becomes an expression
name under the `env.` prefix:

    # <root>/prices/env.properties
    mandant  = 4711
    currency = CHF

    {"expr": "${env.mandant}",  "column": "mandant_nr"}
    {"expr": "${env.currency}", "column": "waehrung_cd"}

The same spec then loads under a different client number on the test box without being edited, which is the point.
This is not a second home for what the spec could say itself: how to *read* the file - separators, formats, selectors -
belongs in the spec and is the same everywhere, and putting it here instead only splits one description across two
files.

The file is read once per loaded file rather than cached with the feed, so an edit reaches the next load with no
reload in between. Having none is normal and silent; a spec that names an `env.` value the file does not supply fails
that load with the name it could not resolve, and the input goes to the hospital. Values are always text, so a
non-text column relies on the driver coercing, or on `parse(...)`.

Two things it is not for. **Secrets**: the database credentials live in `xldr.properties` outside the watched tree
precisely so that nobody who can drop a file into a feed can read them, and `env.properties` is inside it. **Adapter
properties**: `input.properties` is consumed when the adapter is built, before any expression is evaluated, so `env.`
cannot reach it.

### Delivering files

A file must not be read while it is still being written. The server does not guess at this with size or timeout
heuristics - the producer states when a file is complete.

This is deployment rather than mapping - which names a producer uses, and whether it writes a marker, differs between
test and production while the mapping does not - so it lives in the feed's `delivery.properties` and not in the spec:

    accepts = glob:*.csv

Each feed declares **exactly one** of two delivery rules, `accepts` or `sentinel`. A delivery file with both, with
neither, or with a key the reader does not know is refused, and the directory is then not a feed at all: its working
directories are never created, so a producer pointed at it finds nowhere to deliver rather than a hole that swallows
files. Unknown keys are refused rather than ignored because a properties file has no schema, and a misspelled
`acccepts` would otherwise leave a feed claiming nothing with nothing to say about why.

Both patterns are passed straight to Java's `FileSystem.getPathMatcher`, so each carries its own `glob:` or `regex:`
prefix and matches against the file name.

**Atomic delivery** (`accepts = glob:abc*.csv`). A file whose name matches the pattern *is* the trigger, so it must
appear atomically: write it under an ignored name (`*.part`, `*.tmp`, or a dot-file) and rename it in place, or write it
outside `in/` and move it in. A same-filesystem rename is atomic; a plain write into `in/` is not, and risks a truncated
load. A file that does not match is left in `in/` untouched.

**Sentinel delivery** (`sentinel = glob:*.done`). The producer writes the data file at leisure, then a marker file
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

A single file called `xldr.properties`, read from the directory the server is started in, or from the one `--dir`
(`-d`) names. Connection settings live here, not in the mapping specs, so a spec can be promoted between environments
unchanged and no credentials sit in the watched tree. A deployment is therefore a directory of its own - its
configuration, and whatever else it keeps beside it - rather than a path passed on every invocation.

A `logging.properties` in that same directory is picked up if it is there. Failing that the server uses the one in
the distribution's `conf/`, and failing that the copy inside the jar; pointing `java.util.logging.config.file` at a
file of your own still overrides the lot.

| Key                          | Required | Default | Meaning                                                                                                                                                                                                                                                               |
|------------------------------|----------|---------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `xldr.roots`                 | yes      | –       | The directories in which feeds may be created, separated by the platform path separator (`:` on Unix, `;` on Windows). Each must exist at startup and none may be nested in another.                                                                                  |
| `xldr.scanInterval`          | no       | `30`    | Seconds between full reconciliations of the tree; watch events only react sooner.                                                                                                                                                                                     |
| `xldr.maxConcurrentLoads`    | no       | `4`     | Upper bound on files loaded at once, and the size of the connection pool: a load borrows exactly one connection for one file, so the pool is sized to match and never becomes a second, lower limit.                                                                  |
| `jdbc.url`                   | yes      | –       | JDBC URL of the one target database.                                                                                                                                                                                                                                  |
| `jdbc.user`, `jdbc.password` | no       | –       | Credentials, if the URL does not carry them.                                                                                                                                                                                                                          |
| `pool.*`                     | no       | –       | Passed through to HikariCP's `HikariConfig` under the key without the `pool.` prefix, e.g. `pool.connectionTimeout`. Setting `pool.maximumPoolSize` overrides the size derived from `xldr.maxConcurrentLoads`, for a database that will not grant that many sessions. |

    xldr.roots              = /var/lib/xldr:/mnt/feeds
    xldr.scanInterval       = 30
    xldr.maxConcurrentLoads = 4
    jdbc.url      = jdbc:oracle:thin:@//host:1521/sid
    jdbc.user     = dbuser
    jdbc.password = secret

The JDBC drivers for Oracle and PostgreSQL are `provided` dependencies: the deployment supplies the one matching its
target database.

### Feed configuration

A feed directory holds a mapping spec - `spec.json` or `spec.xml`, exactly one - beside the
`delivery.properties` that made it a feed.

The settings of the adapter sit in the input's `properties`, next to the `mimeType` that chooses it - grouped rather
than spread out, because which of them mean anything depends on that MIME type:

    "input": {
        "mimeType": "text/csv",
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

| Key            | Default            | Meaning                                                                                                                                                        |
|----------------|--------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `dateFormat`   | ISO-8601           | `DateTimeFormatter` pattern for `DATE` fields, e.g. `yyyyMMdd` or `dd.MM.yyyy HH:mm`. A pattern without a time of day yields midnight.                         |
| `numberFormat` | plain literal      | `DecimalFormat` pattern for `INTEGRAL`, `FP` and `DECIMAL`, e.g. `#,##0.00` for grouped input. `DECIMAL` stays exact - it is never rounded through a double. |
| `locale`       | `ROOT` (`1234.56`) | Language tag, e.g. `de-DE`, selecting the decimal and grouping separators of `numberFormat` and the symbols of `dateFormat`.                                   |

Excel needs none of these: a spreadsheet carries typed cells, so a date or a number arrives as one already.

**CSV** (`text/csv`, `text/tab-separated-values`):

| Key                | Default          | Meaning                                                                                                                                                                                                                                                                                                                                          |
|--------------------|------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `fieldSeparator`   | `,`              | Column separator. A tab-separated file says `"\t"`.                                                                                                                                                                                                                                                                                              |
| `header`           | `present`        | Whether the first row names the columns: `present`/`true`, or `absent`/`false`. A field selector's `selector` names a column and so needs a header; its `nth` counts the fields and works either way, which is the only way to address a headerless file. Its `name` is what a mapping calls it by, as in every adapter. Anything else is refused rather than read as absent. |
| `quote`            | `"`              | What opens and closes a quoted field. Empty switches quoting off, leaving quotes as ordinary characters.                                                                                                                                                                                                                                         |
| `comment`          | none             | What begins a comment outside a quoted field. Unset, no character does.                                                                                                                                                                                                                                                                          |
| `fieldsFromHeader` | `false`          | Whether a field the record selector does not declare is the column of that name. Needs a header.                                                                                                                                                                                                                                                 |
| `emptyLine`        | `skip`           | What an empty line means: `skip`, or `stop` to end the data there.                                                                                                                                                                                                                                                                               |
| `charset`          | `UTF-8`          | Character set, e.g. `ISO-8859-1`. Not the platform default: the same file has to load the same way whatever the JVM was started with.                                                                                                                                                                                                            |

**The defaults are RFC 4180's**, so a spec that says nothing beyond `text/csv` reads the format the MIME type is
registered for. Two of them the RFC does not decide. It registers `header` as a MIME parameter and then says in as
many words that an implementation choosing not to use it must decide for itself; `present` is xldr's answer, because
a selector names a column and a headerless file has no names to offer. And by the RFC's own grammar a blank line is
a record of one empty field, which no implementation reads it as and nobody writing a file by hand means — hence
`emptyLine = skip`.

**`text/tab-separated-values` settles three of those by itself.** Its IANA registration is shorter than RFC 4180 and
stricter: a tab separates the fields, a field *cannot contain* a tab and so needs no quoting mechanism at all, and the
first line is the field names rather than optionally so. A spec naming that type therefore carries no properties:

    { "input": { "mimeType": "text/tab-separated-values", "recordSelectors": [ … ] } }

A spec may repeat what the type already says - a tab separator for a TSV file is redundant, not wrong - but one that
contradicts it is refused. The type is a claim about what the file is, so a spec naming TSV and then asking for
semicolons describes two different files, and obeying either would be a guess. A file that is tab-separated *without*
being TSV - quoted fields, or no header - is `text/csv` with `"fieldSeparator": "\t"`, which is what that type is for.
Everything the registration does not mention stays open: a comment character, `emptyLine`, `charset` and the
conversion settings.

**A selector that names no column of the file is refused**, rather than read as null for every row. A tab-separated
file read with commas has exactly one column, called the whole header line, so every selector misses and the load
would otherwise report success over a table of nulls. The message names the selector, lists the columns the header
actually carried and says which separator they were split on. A column merely missing from *some line* is still
null: that is a short line, not a spec that does not fit its file.

A record is a line, and there is nothing to configure about that: a file may end its lines with `\n`, `\r\n` or `\r`
and is read the same way, so a file written on Windows loads on Linux unchanged. That is more liberal than the RFC,
which says CRLF, and is the "be liberal in what you accept" its own interoperability note asks for. The lines are
read as the loader consumes them, so the size of a file is not the size of the memory it needs.

Inside a **quoted field** the separator and the line break are ordinary characters, and a doubled quote is one
literal quote - so `"Doe, Alice"` is one value, `"she said ""no"""` is `she said "no"`, and a record runs over as
many lines as a quoted field needs. That last part is the only thing that makes a record more than a line, and it is
what a spreadsheet export produces.

A quote is structural **only where a field begins** - right after a separator, or at the start of the record.
Anywhere else it is data, so `5" pipe` and `he said "no"` read as they are written. The strict reading would call
those an error; this one leaves files that load today loading. Where a value genuinely starts with a quote that is
data, set `quote` to nothing and no quote is special anywhere.

A quoted field that is never closed would otherwise swallow the rest of the file into a single record and report a
load of one row, so a record that stays open for more than 256 lines is refused, naming the line that opened
it.

With **`fieldsFromHeader`** a field the spec does not declare is looked for among the columns under its own
name, as if `{"name": "Id", "selector": "Id"}` had been written out - so a feed whose columns are already
named as the mapping wants them declares no field selectors at all:

    "properties": { "fieldSeparator": ",", "fieldsFromHeader": true },
    "recordSelectors": [ { "name": "people" } ]

A declared field still wins, which is how a column is renamed or given a type; an implicit one has no `type`
and so arrives as text. It is off by default because a mapping naming a field no record selector declares is
usually a mistake - it is what `fieldSelector` written for `fieldSelectors` looks like - and nothing can tell that
from a column name without a file in hand. Saying `fieldsFromHeader` in the spec is what tells it, for that feed
and no other.

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

### Which records are of a kind

A flat file has nowhere to point at - every line is a candidate - so a record selector for one carries a
**`discriminator`** instead: which component of the line to look at, and what its value has to be.

    "recordSelectors": [
        { "name": "orders",
          "discriminator": { "nth": 1, "equals": "O" },
          "fieldSelectors": [ {"name": "id", "nth": 2}, {"name": "date", "nth": 3} ] },
        { "name": "lines",
          "discriminator": { "nth": 1, "equals": "L" },
          "fieldSelectors": [ {"name": "id", "nth": 2}, {"name": "sku", "nth": 3}, {"name": "qty", "nth": 4} ] }
    ]

Headerless feeds often interleave several record types in one file, the first component naming the type and the ones
that follow varying in number, meaning and type per type. Several record selectors thus partition one file, each
mapping its own type to its own table. Counting stays absolute within the line, so component 1 is the discriminator
itself and a type's payload usually starts at 2.

Exactly one of `nth` and `selector` says **where** to look - so the discriminating component may be named where the
file has a header, which is what makes a *headed* file with a type column readable:

    "discriminator": { "selector": "kind", "equals": "O" }

And exactly one of `equals` and `matches` says **what for**. A pattern matches the whole value, `matches` rather than
`find`, so anchoring is not something to remember; it is compiled when the adapter is built, so one that will not
compile is a spec that does not deploy rather than a load that dies half way through a file.

A record selector with no discriminator takes every line, which is the single-record-type case and what a feed with a
header almost always wants. A discriminating component that names nothing in the file is refused rather than left to
match nothing for the length of a load.

**A fixed-length file discriminates on a character range**, that being what it has instead of components. The
record type in columns 1 to 2 is the classic layout, and it is written the way everything else about a fixed-length
field is written:

    { "name": "orders", "discriminator": { "selector": "0:2", "equals": "OR" },
      "fieldSelectors": [ {"name": "id", "selector": "2:6"}, ... ] }

`nth` is refused there, as it is on a field selector and for the same reason. So is a range that omits its left
bound: a field may continue where the previous one ended, and a discriminator has no previous field.

Each record selector carries its own layout, and that is load-bearing rather than tidy. A field may omit its left
bound and continue from the field before, which makes a layout a running total; when the record selectors shared
one, the total ran *across* them and the second record type came out anchored to the first one's last field.

**XML** (`text/xml`, `application/xml`):

| Key           | Default | Meaning                                                                                                                                                                                                           |
|---------------|---------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ns.<prefix>` | –       | Binds a namespace prefix for the selectors, e.g. `ns.f = http://example.com/funds` to make `//f:fund` match. XPath 1.0 has no default namespace, so a document with one is reachable only through a bound prefix. |

XML differs from the other adapters in two deliberate ways. A `TEXT` field keeps an empty string rather than
becoming null, because XPath cannot tell "no such element" from "an element that is empty". And an `FP` is taken
through XPath's own numeric evaluation rather than from its text - which is why `INTEGRAL` and `DECIMAL` are not:
XPath 1.0 knows only doubles, so it would round a long integer and turn a decimal into a binary approximation.

**Fixed length** (`text/plain`):

| Key | Default | Meaning |
|-----|---------|---------|
| `linesPerRecord` | `1` | How many lines make up one record. Lines are joined, and the field bounds address the joined text, so a field may sit on the second line. A file that ends mid-record is an error. |
| `charset` | `UTF-8` | Character set, e.g. `ISO-8859-1`. Not the platform default: the bounds are counted in characters, so the wrong charset does not merely garble a value, it moves every field after the first non-ASCII byte. |

A field selector is a half-open character range `left:right` over the record, counted from zero, so `0:3` is the
first three characters. The left bound may be omitted, in which case the field starts where the previous one ended -
a layout can therefore be written as a list of end positions. The right bound is not optional: a field says where it
ends, since the next one need not begin there and a record has no end of its own to fall back on. So:

    "fieldSelectors": [
        {"name": "id",   "selector": "0:3",  "type": "TEXT"},
        {"name": "name", "selector": ":23",  "type": "TEXT"},
        {"name": "qty",  "selector": ":27",  "type": "INTEGRAL"}
    ]

A line that stops short of a field's bounds is not an error: the value is whatever the line still holds, and a field
beyond the end of the line is null. Together with the stripping every type does, that makes a producer's trailing
padding irrelevant.

The adapter takes exactly one record selector, and a second is refused: every line of the file has the same layout,
so there is nothing to tell one kind from another - that is what a [`discriminator`](#which-records-are-of-a-kind)
would be for, and this adapter has none yet. That one record selector carries no `selector` either, a fixed-length
file having nowhere to point at, and one written there is refused rather than ignored. A field says `selector` and
never `nth`: a fixed-length record is a stretch of characters with declared bounds rather than components to count,
so counting too is refused when the adapter is built.

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
            {"name": "id",   "selector": "id",                   "type": "TEXT"},
            {"name": "city", "selector": "customer/address/city", "type": "TEXT"},
            {"name": "net",  "selector": "amounts/net",          "type": "DECIMAL"}
        ] }
    ]

A field may also say `nth` instead, which is the n-th element of a record that is an array - the same thing
`[n-1]` says as a pointer, written the way every other adapter writes it. A record that turns out to be an *object*
yields `null` for it: a JSON object is unordered by specification, so there is no n-th member to speak of, and since
only the document can say so it is a null rather than a refusal.

A member that is absent, or that holds `null`, is an absent value. JSON carries its own types, so a number arrives as
a number - exactly, never rounded through a double - and the shared `dateFormat` and `numberFormat` settings apply
only to values written as strings.

**Excel** (`application/vnd.ms-excel`, `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`): no
properties. One adapter serves both `.xls` and `.xlsx`; the format is detected from the file itself.

A record selector is a range, `[Sheet!]ref:ref`, one record per row:

* `A:C` - columns A to C of the first sheet, every data row;
* `Sheet1!B2:C4` - the cell rectangle rows 2 to 4, columns B to C, of the named sheet. Use this to leave a header row
  out of the records.

A field selector addresses a cell of the record. Its `selector` is either absolute by column - `A`, `B`, or a 1-based
index, `3` being the same column as `C` - or relative to the record's anchor, the current row at the first column of
the range: `R-1C+1` is one row up and one column right, which is how a record reaches a heading or a neighbouring
cell. Both offsets have to be written - `R0C+1`, not `C+1` - though the sign may be left off a positive one. A
relative reference that lands off the sheet, `R-1C+0` on the first row, is an absent value rather than an error, so a
field reaching for a heading that is not there loads a NULL.

**An `nth` counts from the range, not from the sheet**, which is where it parts company with the digit form of
`selector`. For a range at `data!C2:D3`, `nth: 1` is column C and `selector: "1"` is column A; they agree only for a
range starting at column A. `nth` is the one that means here what it means in every other adapter - the n-th
component of the record the record selector identified, not the n-th of whatever contains it - and the digit form is
kept for the specs that already use it.

A spreadsheet carries typed cells, so no conversion settings apply: a date or a number arrives as one already, and a
cell that holds text where the spec wants a number is converted from that text.

### Monitoring

The server registers an MXBean at `io.github.ralfspoeth.xldr:type=Server`, so what it is doing can be read with
`jconsole`, VisualVM, or a Prometheus JMX exporter - no agent, no dependency, nothing to enable. Everything on it is
read-only: the file system remains the way to make the server do anything.

| Attribute                                        | Meaning                                                                                                                                                                              |
|--------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ActiveFeeds`                                    | How many feeds can load, having both a readable `delivery.properties` and a readable spec. A feed that drops out of this number has lost one of the two - `Feeds` says which.        |
| `LoadsInProgress`                                | Files being loaded at this moment. Bounded by `xldr.maxConcurrentLoads`.                                                                                                             |
| `LoadsSucceeded`, `LoadsFailed`, `RecordsLoaded` | Counted since the process started, so they are rates to be differenced.                                                                                                              |
| `LastLoad`, `LastFailure`                        | Instants, or empty. A `LastLoad` that stops advancing on a feed that should be busy is the quiet failure worth catching.                                                             |
| `FilesWaiting`                                   | Files sitting in the `in/` of any registered feed, pending ones included. Should fall back to zero; a number that does not is a feed not claiming what arrives - a delivery rule that matches nothing, or a feed still waiting for its spec. |
| `FilesInHospital`                                | Files a load failed on, not counting the `.log` written beside each. Nothing puts a file there but a failure and nothing removes one but an operator, so this is the alert to raise. |
| `Feeds`                                          | The same, per feed, so a failing feed can be told from a quiet one. Every registered feed, each with a `state` of `ACTIVE` or `PENDING`, so the rows add up to the totals above. `PENDING` is a feed with a delivery file and no spec: it is logged once, when it gets there, and this is what still knows tomorrow. |

HikariCP's own pool statistics are separate and off by default; `pool.registerMbeans = true` in the server
configuration turns them on, since every `pool.*` key is passed through.

`xlet` registers its own bean, at `io.github.ralfspoeth.xldr:type=Loader,context="…",name="…"` - named after the
deployment, because a web application can be deployed twice in one JVM and a fixed name would let only the first of
them register. The load counters are the same ones, `Statistics` having moved into `ldr` so that both front ends
share it; what differs is that it has no files to count and two things to add, a refusal count and a rejection count.
Its own README says why those two are kept apart.

### Logging

The application logs through `System.Logger`; HikariCP and POI log through SLF4J, which the distribution binds with
`slf4j-jdk14`. Everything therefore ends up in `java.util.logging`, and a single JUL configuration covers the whole
process; no second logging framework is involved.

The binding belongs to the *distribution*, not to any library module. A binding is a deployment's choice, so no
published module requires one - a consumer taking `xlsx` from Maven Central gets POI and no opinion about where its
log records go.

`server` goes the same way for the JDK's own logging: it writes through `System.Logger`, which is in `java.base`, and
does not `requires java.logging`. Requiring it would pick JUL, because the default `LoggerFinder` routes there when
the module is resolved. An application embedding the server therefore chooses - `requires java.logging` for JUL, or
its own `System.LoggerFinder` for anything else. `app` requires it, which is why the distribution behaves as
described above.

A default `logging.properties` is bundled and applied at startup unless the deployment points `java.util.logging` at a
configuration of its own:

    java -Djava.util.logging.config.file=/etc/xldr/logging.properties -p <module-path> -m io.github.ralfspoeth.xldr.app --dir /etc/xldr

## License

XLDR is released under the [MIT License](LICENSE) - use it, embed it, ship it, with or without your own source. The
libraries it is built on are permissive too: Greyson, filews and SLF4J are MIT, POI, HikariCP and picocli Apache-2.0.

The JDBC drivers are not xldr's to license, and none is pulled in transitively - they are `provided` dependencies, so
a consumer of the libraries supplies the driver for the database it feeds and accepts that driver's own terms. Note
that the [distribution](#distribution) does bundle them into `lib/` for convenience; the Oracle driver in particular
is proprietary, so a distribution you pass on to anyone else should have `lib/ojdbc*.jar` removed.
