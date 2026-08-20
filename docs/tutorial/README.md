# XLDR tutorial

A path through XLDR for the people who write `spec.json` and `spec.xml`. You have a file and you have a table; a
mapping spec is the document that connects them. Each page below adds one thing to the spec built by the page
before it, so the series reads as a diff rather than as a catalogue.

1. [Setting up](01-setup.md) - the distribution, a database, a running server
2. [Your first spec](02-first-spec.md) - `spec.json` and `delivery.properties`, and a file loaded
3. [The same spec in XML](03-in-xml.md) - `spec.xml`, and how to read either format as the other
4. [Constants](04-constants.md) - a fixed value from the spec
5. [Variables](05-vars.md) - a value computed once per load
6. [Lookups](06-lookups.md) - a value resolved against another table
7. [Expressions](07-expressions.md) - `${...}`, the file's own name, a sequence
8. [Types and notation](08-types.md) - what a value is, and how the producer wrote it
9. [A file with no header](09-no-header.md) - counting components instead of naming them
10. [Several kinds of record](10-record-types.md) - one file, two tables
11. [When it goes wrong](11-when-it-goes-wrong.md) - the hospital, and having your editor check the spec first
12. [Drafting one with an assistant](12-with-an-assistant.md) - what to give a model, and what to check in the answer

The [README](../../README.md) is the reference: every adapter's properties, every field type, the full expression
grammar, the server's configuration. This is the path, not the map.

## How to read it

Each page shows whole files rather than fragments, so anything you copy is something you can put straight into a
feed and move a file at. A page changes only what it is about - the page on lookups shows the new tables and the
new spec, and does not reprint the input file for the fifth time - so reading them in order is reading one spec
grow.

The examples all use the same two files, `customers.csv` and, on the last two pages, `orders.csv`, loaded into a
`customer` table that gains a column or two per page. Nothing here needs anything but the distribution, a JDBC
driver and a database you can create a table in.
