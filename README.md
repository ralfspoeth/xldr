# XLDR

## Project Description

The idea of the XLDR toolkit is to provide a flexible yet simple engine to load files of different formats and layout or
structure into database tables.

The toolkit provides adapters for different file types that can be loaded as modules and utilize the service framework
of JPMS.

Loading data from a file into one or more database tables is guarded by a *mapping specification* which comprises an *
input specification*, a *mapping*, and an *output specification*. The *input specification* tells the engine how to
parse of given file and to load *records* and *fields*. The *mapping* provides - as its name implies - a mapping from
records to database tables and from fields to database columns. The *output specification* provides connection
information that the engine needs to connect to the target database.

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

    xldr.root     = /var/lib/xldr
    jdbc.url      = jdbc:oracle:thin:@//host:1521/sid
    jdbc.user     = dbuser
    jdbc.password = secret
    pool.maximumPoolSize = 4

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
in the database specified in the output specification, followed by an array of field mappings of a field selector
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
