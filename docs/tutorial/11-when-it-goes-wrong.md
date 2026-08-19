# 11. When it goes wrong

[← several kinds of record](10-record-types.md) · [index](README.md)

Every page so far has worked. This one is about the three ways a spec fails, and they fail at three different
moments - which is the useful thing to know, because the moment tells you where to look.

## Before you save: let the editor check it

Both formats have a published schema, and pointing at it is the single most useful line you can put in a spec. In
JSON, add `$schema` as the first member:

    {
      "$schema": "https://ralfspoeth.github.io/xldr/schema/mapping-spec-0.32.json",
      "input": { ... }
    }

In XML, a schema location on the root element:

    <mappingSpec xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                 xsi:noNamespaceSchemaLocation="https://ralfspoeth.github.io/xldr/schema/mapping-spec-0.32.xsd">

IntelliJ and VS Code validate and autocomplete from these. Both are ignored by the readers - `$schema` is just a
member nobody recognises, and `xsi:` attributes mean nothing to a document with no namespace of its own - so a
spec carrying one loads exactly as it would without.

A schema is named after the release that last changed the format, so `mapping-spec-0.32` is the current one and a
release that changes nothing keeps the previous. The full list is on the [schema page](https://ralfspoeth.github.io/xldr/).

What this catches is what a schema can: a misspelled member, a `type` that is not one of the five, a field mapping
with no source or with two, an `nth` that is not a whole number. What it cannot catch is a `selector` that does not
match your file - nothing knows that until a file arrives.

## When the spec is read: the feed goes quiet

A spec that parses but cannot be right is refused when the server reads it, and the feed stays inactive. Files
accumulate in `in/` and nothing happens to them, which is the symptom to recognise.

The reason is in the server's log, and it names the feed. The commonest are worth knowing by sight:

* **a selector that names no column of the file.** A field saying `"selector": "id"` against a file whose header
  has no `id`. The message lists the columns the header did carry and the separator they were split on, because
  the usual cause is the separator: read a tab-separated file with commas and the whole header becomes one column.
* **a selector where the format has nowhere to point**, or an `nth` where it has nothing to count. Page 9 lists
  which formats have which.
* **a pattern that does not compile**, from a `matches` discriminator.
* **a record selector with both a `selector` and a `discriminator`.**

All of these are refusals rather than warnings, and deliberately so. The alternative to refusing a selector that
matches nothing is a load that reports success over a column of nulls, which is worse - it is wrong and it is
quiet.

## When the file is read: the hospital

If the spec is fine and the file is not, the load fails and the file is moved to `hospital/` with a note beside it
saying what happened and at which record. Nothing is committed: the whole file is one transaction, so a failure at
record 40,000 leaves the table as it was.

    /var/lib/xldr/customers/hospital/
        customers.csv
        customers.csv.error

Typical contents: a value that will not convert to its declared type, a row the database rejected for a constraint
or a length, a quoted field never closed.

The cure is to fix the file, or the spec, and move the file back into `in/`. Nothing is lost while it sits there.

## Two things worth setting up early

**A `limit` while you are drafting.** A record mapping may cap how many records it loads:

    {"recordSelector": "customers", "table": "customer", "limit": 100, "fieldMapping": [ ... ]}

which turns a fifteen-minute round trip against a million-row file into a few seconds. Take it out before the spec
goes to production, or leave it and be surprised later - it is not a sample, it is a stop.

**Watch the counters.** The server publishes what it has loaded over JMX - loads succeeded, loads failed, records
loaded, when the last one was. Connect with any JMX console under `io.github.ralfspoeth.xldr`. It is the quickest
way to tell "nothing arrived" from "something arrived and was refused", which from the outside look identical.

---

## That is the tutorial

You can now write a spec that reads a file of any of the supported formats, converts values from a producer's
notation, fills columns from four different kinds of source, and separates several kinds of record into several
tables.

What is left is reference, and it is in the [README](../../README.md): the properties of each adapter, XML and JSON
and Excel and fixed-length inputs, the server's configuration, how files are delivered, and how to embed the loader
in an application rather than running the server at all.

[← several kinds of record](10-record-types.md) · [index](README.md)
