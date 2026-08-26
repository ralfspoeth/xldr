# 12. When it goes wrong

[← types and notation](11-types.md) · [index](README.md) · [next: drafting one with an assistant →](13-with-an-assistant.md)

Every page so far has worked. This one is about two ways to find a mistake before it costs anything, and the three
moments at which one shows up if you did not - and the moment is the useful thing, because it tells you where to
look.

## Before you save: let the editor check it

Both formats have a published schema, and pointing at it is the single most useful line you can put in a spec. In
JSON, add `$schema` as the first member:

    {
      "$schema": "https://ralfspoeth.github.io/xldr/schema/mapping-spec-0.43.json",
      "input": { ... }
    }

In XML, a schema location on the root element:

    <mappingSpec xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                 xsi:noNamespaceSchemaLocation="https://ralfspoeth.github.io/xldr/schema/mapping-spec-0.43.xsd">

IntelliJ and VS Code validate and autocomplete from these. Both are ignored by the readers - `$schema` is just a
member nobody recognises, and `xsi:` attributes mean nothing to a document with no namespace of its own - so a
spec carrying one loads exactly as it would without.

A schema is named after the release that last changed the format, so `mapping-spec-0.43` is the current one and a
release that changes nothing keeps the previous. The full list is on the [schema page](https://ralfspoeth.github.io/xldr/).

What this catches is what a schema can: a misspelled member, a `type` that is not one of the five, a field mapping
with no source or with two, an `nth` that is not a whole number. What it cannot catch is a `selector` that does not
match your file - nothing knows that until a file arrives. Which is the next section.

## Before you deploy: `xldr check`

The schema reads your document. `check` reads it against your file and your database:

    xldr check spec.json --sample customers.csv --url jdbc:h2:./tutorial

Point it at the spec from [page 11](11-types.md) and the file that page shows, and it says:

    checking spec.json
      input          text/csv, 1 record selector(s)
      mappings       1, over 1 declared record selector(s)
      columns        checked against jdbc:h2:./tutorial
      sample         customers.csv (77 bytes)
      'customers'    -> customer: 2 record(s) matched
          id=1 (Long)  name=Alice (String)  since=2026-03-01T00:00 (LocalDateTime)  balance=1234.56 (BigDecimal)
          id=2 (Long)  name=Bob (String)  since=2026-03-15T00:00 (LocalDateTime)  balance=98.00 (BigDecimal)

    no findings.

Four things are compared, and each of them is a mistake that a schema-valid spec can contain:

- a `mapping` naming a `recordSelector` the `input` never declared - refused, but not until a file arrives;
- a `column` your table has not got - a SQL error on the first insert;
- a `lookup` whose reference table, returned column or key column is not there;
- a record selector that matches nothing in a file you call representative - which nothing refuses at all: the
  load succeeds and inserts no rows.

A finding names the thing and what would have been right:

    1 finding(s):
      - table 'customer' has no column 'blance'; it has [BALANCE, ID, NAME, SINCE]

Nothing is written. The connection is opened to ask the database what the table holds, and your file is parsed in
memory, so it is safe against whatever database has the table - including the only one that does.

Every argument but the spec is optional. Without `--url` the database is not consulted; without `--sample` the file
is not read; with neither, the spec is still checked against itself.

**Read the values, not just the last line.** The two rows above are the reason to run this rather than a reason to
skim it. The file said `01.03.2026` and `1.234,56`; the output says the first of March and one thousand two hundred
and thirty-four. If `dateFormat` were `MM.dd.yyyy` that first date would read as the third of January - a real
date, a valid load, and every row silently wrong. No check can catch that, because nothing but you knows what your
producer meant. Seeing the parsed value is the whole remedy.

One limit worth knowing. Those are the *field selectors* - what the file gives. A constant, a `var`, an `expr` or
what a lookup *resolves to* does not appear, because working those out is the load rather than a reading of the
file. A lookup's key shows up, being a field selector like any other.

For the mapping half, `check` prints the plan - where each column's value comes from, in one place:

      customer <- 'customers'
          id           field     id
          name         field     name
          source_cd    var       src
          loaded_from  expr      ${xldr.filename}
          region_id    lookup    region.id where city = field name

Nothing here is evaluated either. What it is for is the question your spec does not answer anywhere: a spec with
forty columns spreads them over a hundred lines, each source nested inside its own object, and a column wired to
the wrong one validates, loads, and is wrong in every row. Reading the plan against the table you meant to fill is
about ten seconds and catches that.

## Checking a spec against its other self

[Page 3](03-in-xml.md) wrote the same spec twice, once in each format. If you keep both - or convert one to the
other - `check` will tell you whether they still say the same thing:

    xldr check spec.json --same-as spec.xml

Both are read into the same in-memory form, so the comparison is exact rather than textual: whitespace, member
order and the order of record selectors do not matter, and a genuine difference is named -

    1 finding(s):
      - spec.xml: mapping 'customers' differs:
          RecordMappingSpec[recordSelector=customers, table=customer, ...]
          RecordMappingSpec[recordSelector=customers, table=customers, ...]

## When the spec is read: the feed goes quiet

A spec that parses but cannot be right is refused when the server reads it, and the feed stays inactive. Files
accumulate in `in/` and nothing happens to them, which is the symptom to recognise.

The reason is in the server's log, and it names the feed. The commonest are worth knowing by sight:

* **a selector that names no column of the file.** A field saying `"selector": "id"` against a file whose header
  has no `id`. The message lists the columns the header did carry and the separator they were split on, because
  the usual cause is the separator: read a tab-separated file with commas and the whole header becomes one column.
* **a selector where the format has nowhere to point**, or an `nth` where it has nothing to count. Page 4 lists
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

The cure is to fix the file, or the spec, and move the file back into `in/`. Nothing is lost while it sits there,
and retrying is safe: the load that failed committed nothing, so the tables are as they were.

**Retrying a file that failed is safe. Loading one that succeeded is not.** XLDR inserts and does not merge - it
has no notion of a natural key, so the same file loaded twice gives you the rows twice. That is deliberate: the
target is a landing zone, and what happens to the rows afterwards is the application's business, because only it
knows which columns identify a row and whether a later delivery supersedes an earlier one. The README explains the
division under [Loading twice](../../README.md#loading-twice), along with a way to make a repeated delivery fail
loudly rather than duplicate.

## Two things worth setting up early

**A `limit` while you are drafting.** A record mapping may cap how many records it loads:

    {"recordSelector": "customers", "table": "customer", "limit": 100, "fieldMapping": [ ... ]}

which turns a fifteen-minute round trip against a million-row file into a few seconds. Take it out before the spec
goes to production, or leave it and be surprised later - it is not a sample, it is a stop.

**Watch the counters.** The server publishes what it has loaded over JMX - loads succeeded, loads failed, records
loaded, when the last one was. Connect with any JMX console under `io.github.ralfspoeth.xldr`. It is the quickest
way to tell "nothing arrived" from "something arrived and was refused", which from the outside look identical.

---

## That is the format

You can now write a spec that reads a file of any of the supported formats, converts values from a producer's
notation, fills columns from four different kinds of source, and separates several kinds of record into several
tables - and, as importantly, read one and say whether it is right.

Which is what the [last page](13-with-an-assistant.md) needs: having a language model draft a spec is a reasonable
way to start, and worth nothing at all unless you can review what comes back.

The rest is reference, in the [README](../../README.md): the properties of each adapter, XML and JSON and Excel and
fixed-length inputs, the server's configuration, how files are delivered, and how to embed the loader in an
application rather than running the server at all.

[← types and notation](11-types.md) · [index](README.md) · [next: drafting one with an assistant →](13-with-an-assistant.md)
