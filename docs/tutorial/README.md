# XLDR tutorial

A path through XLDR for the people who write `spec.json` and `spec.xml`. You have a file and you have a table; a
mapping spec is the document that connects them. Each page below adds one thing to a spec you have already seen, so
the series reads as a diff rather than as a catalogue.

1. [Setting up](01-setup.md) - the distribution, a database, a running server
2. [Your first spec](02-first-spec.md) - `spec.json` and `delivery.properties`, and a file loaded
3. [The same spec in XML](03-in-xml.md) - `spec.xml`, and how to read either format as the other
4. [A file with no header](04-no-header.md) - counting components instead of naming them
5. [Several kinds of record](05-record-types.md) - one file, two tables, told apart by a discriminator
6. [Constants](06-constants.md) - a fixed value from the spec
7. [Variables](07-vars.md) - a value computed once per load
8. [Lookups](08-lookups.md) - a value resolved against another table
9. [Expressions](09-expressions.md) - `${...}`, the file's own name, a sequence
10. [Calling a function](10-calling-a-function.md) - a value the database hands out, and a procedure it runs after
11. [Types and notation](11-types.md) - what a value is, and how the producer wrote it
12. [When it goes wrong](12-when-it-goes-wrong.md) - `xldr check` before you deploy, and the hospital after
13. [Drafting one with an assistant](13-with-an-assistant.md) - what to give a model, and what to check in the answer

The [README](../../README.md) is the reference: every adapter's properties, every field type, the full expression
grammar, the server's configuration. This is the path, not the map.

## How to read it

Each page shows whole files rather than fragments, so anything you copy is something you can put straight into a
feed and move a file at. A page changes only what it is about - the page on lookups shows the new tables and the
new spec, and does not reprint the input file for the fifth time - so reading them in order is reading one spec
grow.

Most pages use `customers.csv`, loaded into a `customer` table that gains a column or two as the pages go on.
Pages 4 and 5 are the headerless pair: the same file with its header row taken away, and then a file of orders and
their lines - two kinds of record interleaved, told apart by a column rather than by a name, and going to two
tables. They come early on purpose. That shape is the one this toolkit was written for, and a reader who has such
a file in hand should not have to work through six pages about values before finding out whether it can be read.

Nothing here needs anything but the distribution, a JDBC driver and a database you can create a table in.
