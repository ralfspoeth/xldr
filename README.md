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

### The Output Specification

The output specification is comprised of the JNDI name of a `DataSource` plus generic properties provided by name and
value. The supported properties are JDBC driver specific.

Resolving the name is the responsibility of the application, not of the toolkit: the `spec` module keeps the data source
as an opaque string so that a mapping specification remains plain, serializable configuration with no JDBC dependency.
The `app` module looks the name up - as given, then below `java:comp/env/` - and hands the resulting connection to the
loader.

Example:

    "output": {
        "dataSource": "jdbc/prod/orcl",
        "info": {
            "user": "dbuser",
            "password": "secret"
        }
    }

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

The mapping specification as a whole is specified by the three elements input, output, and record mapping; example:

    {
        "input": {...}
        "output": {...}
        "recordMapping": []
    }

The order of the elements is unspecified.
