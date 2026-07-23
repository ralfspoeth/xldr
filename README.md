# XLDR

## Project Description

The idea of the XLDR toolkit is to provide a flexible yet simple engine to load files of different formats and layout or
structure into database tables.

The toolkit provides adapters for different file types that can be loaded as modules and utilize the service framework
of JPMS.

Loading data from a file into one or more database tables is guarded by a *mapping specification* which comprises an
*input specification*, a *mapping*, and a *load specification*. The *input specification* tells the engine how to
parse a given file and to load *records* and *fields*. The *mapping* provides - as its name implies - a mapping from
records to database tables and from fields to database columns. The *load specification* says how the load is carried
out; the target database itself is configured on the application, not in the mapping.

The mapping specification can be constructed programmatically or can be provided through some source text in one of the
following formats:

* .properties: Java's standard properties format
* .xml: well-formed XML complying to a schema described below
* .json: JSON format

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

### The Load Specification

The load specification says *how* a load is carried out, currently only when it is committed. It deliberately carries no
connection information: which database is fed is a deployment concern configured on the application, so that the same
mapping specification can be promoted from test to production unchanged and no credentials live in the input tree.

`commitPolicy` is either `ON_CLOSE` - one transaction for the whole input, rolled back entirely if any record mapping
fails - or `PER_MAPPING`, which commits after each record mapping that succeeded. The element as a whole is optional and
defaults to `ON_CLOSE`.

Example:

    "load": {
        "commitPolicy": "ON_CLOSE"
    }

The application is configured separately with the target database. Connections are pooled with HikariCP; every
`pool.*` key is handed to `HikariConfig` under its own name, so the full pool configuration is available without the
application having to mirror it.

    xldr.roots              = /var/lib/xldr:/mnt/feeds
    xldr.scanInterval       = 30
    xldr.maxConcurrentLoads = 4
    jdbc.url      = jdbc:oracle:thin:@//host:1521/sid
    jdbc.user     = dbuser
    jdbc.password = secret
    pool.maximumPoolSize = 4

Each file is loaded on a virtual thread of its own; `xldr.maxConcurrentLoads` is a semaphore bounding how many loads
run at once. Keep it at or below `pool.maximumPoolSize` - otherwise the pool becomes the real limit and the surplus
threads merely queue inside `getConnection()`, which is far harder to reason about than a permit count.

The JDBC drivers for Oracle and PostgreSQL are `provided` dependencies: the deployment supplies the driver matching its
target database.

### Logging

HikariCP logs through SLF4J. The application binds `slf4j-jdk14`, so everything ends up in `java.util.logging` and a
single JUL configuration covers the whole process; no second logging framework is involved. The application module
`requires org.slf4j.jul` because a service provider module is otherwise never resolved into the module graph.

A default `logging.properties` is bundled and applied at startup unless the deployment sets one of the standard system
properties itself:

    java -Djava.util.logging.config.file=/etc/xldr/logging.properties ...

### The Record Mapping Specification

The record mapping specification is provided by an array of record mappings each of which specifies the name of the
record selector as defined in the input specification, a database table name which is the target of the mapping residing
in the target database, followed by an array of field mappings of a field selector
defined in the respective record selector and a target database column available in the target database table.

Example:

    "recordMapping": [
        {
            "recordSelector": "xx",
            "databaseTable": "tab_xx",
            "fieldMapping": [
                {
                    "fieldName": "id",
                    "databaseColumn": "col_id"
                },
                ...
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
        <load commitPolicy="ON_CLOSE"/>
    </mappingSpec>

## The Server

The application runs as a server watching a number of configured *roots*. A root is the only place in which feeds may
be created; a feed is a directory exactly one level below a root that contains a mapping spec.

    <root>/<feed>/
        spec.json           one of spec.json | spec.xml | spec.properties; its presence activates the feed
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
heuristics - the producer states when a file is complete, in one of two ways chosen per feed by the input spec's
`sentinel` setting.

**Atomic delivery** (no `sentinel`). The appearance of the file *is* the signal that it is complete, so it must appear
atomically: write it under an ignored name (`*.part`, `*.tmp`, or a dot-file) and rename it in place, or write it
outside `in/` and move it in. A same-filesystem rename is atomic; a plain write into `in/` is not, and risks a
truncated load.

**Sentinel delivery** (`"sentinel": "glob:*.done"`). The producer writes the data file at leisure, then a marker file
matching the pattern. Only the marker's arrival triggers the load; the data file's own arrival is ignored. The pattern
uses the `glob:` or `regex:` prefixes of Java's `FileSystem.getPathMatcher`, matched against the file name, and names
the data file in one of two ways:

- `glob:*.{ok,ready,done}` — the data file is the marker name minus its last dotted suffix, so `report.csv.done` loads
  `report.csv`. (Glob alternation is comma-separated.)
- `regex:(x.*\.xml)\.done` — the data file is capturing group 1, so `x123.xml.done` loads `x123.xml`. A regex with no
  capturing group falls back to the suffix rule.

The data file is claimed first and the marker deleted after, so a crash in between leaves the data safely in `work/`
and at worst an orphaned marker, which the next scan cleans up.

The server claims a file by moving it to `work/`, which is also what stops two threads, or two server processes on the
same tree, from loading it twice.

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
