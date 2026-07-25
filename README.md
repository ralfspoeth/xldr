# XLDR

## Project Description

The idea of the XLDR toolkit is to provide a flexible yet simple engine to load files of different formats and layout or
structure into database tables.

The toolkit provides adapters for different file types that can be loaded as modules and utilize the service framework
of JPMS.

### Modules and building

The whole toolkit is one reactor under the `xldr` parent POM and builds with a single `mvn install`, which orders the
modules by their dependencies:

* `spec`, `ia`, `ldr` - the core: the mapping-spec model and readers, the input-adapter SPI, and the JDBC loader;
* `csv`, `xml`, `xlsx` - the input adapters, each an `InputAdapterFactory` provider discovered through `ServiceLoader`;
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
into `lib/` so the package is self-contained; drop the drivers you do not target.

`jlink` is deliberately not used: several runtime dependencies - the Oracle JDBC driver, HikariCP, picocli, SLF4J, POI
- are automatic modules, which `jlink` cannot link into an image. The module-path distribution sidesteps that while
keeping the modular layout and its service binding intact.

### Releasing

Publishing goes through the Central Portal via the `central-publishing-maven-plugin`, inherited from the `plumbum`
parent. The plugin bundles the whole reactor into a single deployment, so the `xldr` parent POM and the six library
modules - `spec`, `ia`, `ldr`, `csv`, `xml`, `xlsx` - are published together. `app` (an executable, not a library)
and `it` (integration tests) each set `skipPublishing` on the plugin, so they are left out of the bundle.

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

Reading different file types is supported by providing a specific adapter per MIME type. There may be more than one
adapter per MIME type on the module path; it's then however unspecified which one will be selected. A future enhancement
will allow require features to be implemented by the adapter.

Selecting records and fields depends on the type and structure of the input file. An adapter has to provide
implementations for *record selectors* and *field selectors*.

A *mapping* maps records, identified by the name of the record selector, to one or more database tables. A record maybe
mapped multiple times. Each mapping of a record to database table contains a field mapping that maps the fields of a
record to a database column.

## Implementation Details

### The Input Specification

An input specification contains the following pieces of information:

* the MIME type (String)
* record selectors, each of which
    * is identified by a name,
    * has a selector specification, and
    * has related field selectors, which in turn
        * are identified by a name,
        * a selector description,
        * and some data type.

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
                        "type": "String", 
                        "selector": "@xxid"
                    }
                ]
            },
            {
                ...
            }
        ]
    }

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
            "databaseTable": "t",
            "fieldMapping": [
                {"var": "source",  "databaseColumn": "source_cd"},
                {"var": "batchId", "databaseColumn": "batch_id"}
            ]
        }
    ]

### Expressions

An `expr` source is a `${...}` template, evaluated in the JVM and bound as a parameter - it never emits SQL. It is
interpolation plus a small set of functions, with no operators. Each `${...}` hole is either a name or a function
call, and adjacent holes concatenate:

    {"generated-id": {"expr": "${xldr.filename}-${nextval('doc')}"}}
    {"expr": "${now()}",           "databaseColumn": "loaded_at"}
    {"expr": "${nextval('rownum')}", "databaseColumn": "line_no"}

A **name** resolves in order: `${xldr.*}` for the application-provided ambient values (currently just
`xldr.filename`), then a declared `var`, then - in a field mapping - a field of the record. The two **functions** are:

* `nextval('name'[, start[, inc]])` - the next value of an in-memory sequence that lives for the one load, shared by
  name. The first draw is `start` (default 1), each later one adds `inc` (default 1). Sequences never touch the
  database;
* `now()` - the current instant (`java.time.Instant`).

**Typing:** a template that is a single hole keeps that value's native type - `${nextval('r')}` binds an integer,
`${now()}` a timestamp; anything with literal text or several holes concatenates to a string.

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
  boolean); in XML, an attribute, it is always a string;
* `lookup` - a value read from a reference table, emitted as an inline scalar subquery
  `(select column from table where keyColumn = key)`. The `key` is itself a `fieldSelector`, `constant` or `var`;
  a key that matches no row yields NULL;
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
        "databaseColumn": "country_id"
    }

Example:

    "recordMapping": [
        {
            "recordSelector": "xx",
            "databaseTable": "tab_xx",
            "limit": 1000,
            "fieldMapping": [
                { "fieldSelector": "id", "databaseColumn": "col_id" },
                { "constant": "PD",      "databaseColumn": "source_cd" }
            ]
        },
        ...
    ]

The mapping specification as a whole is specified by the three elements input, load, and record mapping; example:

    {
        "input": {...}
        "load": {...}
        "recordMapping": []
    }

The order of the elements is unspecified.

### The XML Format

The same specification in XML. Everything is carried in attributes and the element names are those of the JSON
format, so a spec can be transliterated between the two without renaming anything. `type` and the whole `load`
element are optional.

    <mappingSpec>
        <input mimeType="text/xml">
            <recordSelector name="fund" selector="/root/fund">
                <fieldSelector name="id" selector="@id" type="STRING"/>
                <fieldSelector name="nav" selector="nav" type="DECIMAL"/>
            </recordSelector>
        </input>
        <mapping recordSelector="fund" databaseTable="snmandat">
            <fieldMapping fieldSelector="id" databaseColumn="ident1_txt"/>
        </mapping>
    </mappingSpec>

## The Server

The application runs as a server watching a number of configured *roots*. A root is the only place in which feeds may
be created; a feed is a directory exactly one level below a root that contains a mapping spec.

    <root>/<feed>/
        spec.json           one of spec.json | spec.xml; its presence activates the feed
        adapter.properties  optional, input adapter settings such as fieldSeparator for CSV
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

There are three places to configure, at decreasing scope: the server (one file per process), each feed (its spec and
an optional adapter-properties file), and the mapping spec itself (covered above).

### Server configuration

A single properties file, passed as the sole argument to the application. Connection settings live here, not in the
mapping specs, so a spec can be promoted between environments unchanged and no credentials sit in the watched tree.

| Key | Required | Default | Meaning |
|-----|----------|---------|---------|
| `xldr.roots` | yes | – | The directories in which feeds may be created, separated by the platform path separator (`:` on Unix, `;` on Windows). Each must exist at startup and none may be nested in another. |
| `xldr.scanInterval` | no | `30` | Seconds between full reconciliations of the tree; watch events only react sooner. |
| `xldr.maxConcurrentLoads` | no | `4` | Upper bound on files loaded at once. Keep it at or below `pool.maximumPoolSize`, or the pool becomes the real limit and surplus loads queue in `getConnection()`. |
| `jdbc.url` | yes | – | JDBC URL of the one target database. |
| `jdbc.user`, `jdbc.password` | no | – | Credentials, if the URL does not carry them. |
| `pool.*` | no | – | Passed through to HikariCP's `HikariConfig` under the key without the `pool.` prefix, e.g. `pool.maximumPoolSize`, `pool.connectionTimeout`. |

    xldr.roots              = /var/lib/xldr:/mnt/feeds
    xldr.scanInterval       = 30
    xldr.maxConcurrentLoads = 4
    jdbc.url      = jdbc:oracle:thin:@//host:1521/sid
    jdbc.user     = dbuser
    jdbc.password = secret
    pool.maximumPoolSize = 4

The JDBC drivers for Oracle and PostgreSQL are `provided` dependencies: the deployment supplies the one matching its
target database.

### Feed configuration

A feed directory holds a mapping spec - `spec.json` or `spec.xml`, exactly one - and, optionally,
an `adapter.properties` file with format-specific settings handed to the input adapter. The recognised keys depend on
the input spec's MIME type.

**CSV** (`text/csv`):

| Key | Default | Meaning |
|-----|---------|---------|
| `fieldSeparator` | tab | Column separator. |
| `rowSeparator` | platform line separator | Record separator. |
| `header` | `true` | Whether the first row names the columns. With `false`, field selectors are 1-based column positions (`"1"` → first column). |
| `textEnclosingQuotes` | `"` | Quote character. |
| `encoding` | platform default | Character set, e.g. `UTF-8`. |
| `locale` | platform default | Locale for number and date parsing. |

For CSV a record selector's `selector` is a *first-column discriminator*. Headerless feeds often interleave several
record types in one file, the first column naming the type and the columns that follow varying in number, meaning and
type per type. A line belongs to a record selector only when its first column equals that selector's `selector`; an
absent or empty `selector` matches every line, which is the single-record-type case. Positions stay absolute, so `"1"`
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
| `dateFormat` | ISO | Pattern for `DATE` fields; without it an ISO timestamp and a plain ISO date are both accepted. |

**Excel** (`application/vnd.ms-excel`, `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`): no
properties.

### Logging

HikariCP logs through SLF4J. The application binds `slf4j-jdk14`, so everything ends up in `java.util.logging` and a
single JUL configuration covers the whole process; no second logging framework is involved.

A default `logging.properties` is bundled and applied at startup unless the deployment points `java.util.logging` at a
configuration of its own:

    java -Djava.util.logging.config.file=/etc/xldr/logging.properties -p <module-path> -m io.github.ralfspoeth.xldr.app config.properties
