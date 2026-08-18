# Typed selectors, and a discriminator of its own

> **A design note, not documentation.** None of this is built. It is written down
> first so that it can be argued with in a diff rather than in a chat window, and
> so the decisions have their reasons attached when somebody asks in a year why it
> looks like this. When it is built, this file goes away and its arguments move
> into `README.md` and the schema.

Two changes to the mapping-spec format, proposed together because they are the
same change twice and would otherwise cost two schemas, two migrations and two
passes through the documentation.

## The problem: one string, several meanings

A selector is a `String`, and what it means depends on something that is not in
it. Three places where that has already cost something:

**A CSV field selector.** `"selector": "3"` is *the column named 3* where the
file has a header and *the third column* where it has not. Same text, two
meanings, decided by the `header` property several lines away. A file whose
header really does name a column `3` cannot be addressed by name at all.

**A CSV record selector.** `"selector": "O"` means *lines whose first column
equals `O`*. Three decisions are compressed into one string: which column (always
the first), which test (always equality), and against what. None of the first two
can be said, so a file that marks its record type in the second column cannot be
read, and `validate` grew a heuristic about it that was wrong often enough to be
deleted in 0.30.

**An Excel field selector.** `CellRef` already documents two notations in one
string - a column letter or *"a 1-based index such as `3` (= column C)"*, plus
relative `R-1C+2`. So a third adapter is improvising a column index inside text.

The pattern is that a column index is a real concept in this format, used by three
adapters, and it has never been spelled.

## Field selectors: `selector` or `column`, exactly one

```json
{ "name": "id", "column": 1 }
{ "name": "id", "selector": "id" }
```

```xml
<fieldSelector name="id" column="1"/>
<fieldSelector name="id" selector="id"/>
```

`selector` keeps its meaning exactly: the adapter's own syntax, an XPath, a
character range, a JSON pointer, a cell reference. `column` is new and means one
thing everywhere it is accepted - the 1-based position of a column.

In the model that is a sealed type, which the readers produce and the adapters
match on:

```java
public sealed interface Selector {
    /** the adapter's own syntax - an XPath, a character range, a JSON pointer */
    record Text(String value) implements Selector {}

    /** a 1-based column, for inputs whose records have columns */
    record Column(int index) implements Selector {}
}
```

### Why not `"selector": 3`

Because the XML format cannot say it. `selector` is an attribute and XML
attributes are text: `selector="3"` is the only thing writable, there being no
`selector=3`. The XML reader would have to decide by *looking like a number*,
which is the ambiguity this removes - so the two formats would stop meaning the
same thing and the XML one would keep the bug.

Two names cost nothing extra and gain something: the XSD can type `column` as
`xs:positiveInteger` and the JSON schema as `integer, minimum 1`, so **both**
formats refuse `column="first"` before any adapter sees it. Typing by JSON type
could only manage that in JSON.

It is also the idiom already here. `Delivery` carries exactly one of `accepts`
and `sentinel`; a field mapping carries exactly one value source; and the XSD
already uses 1.1 assertions to say so.

### What each adapter accepts

| adapter | `selector` | `column` |
|---|---|---|
| `csv` (and TSV) | a column name - **requires a header** | any file, header or not |
| `xlsx` | a cell reference: `A`, `AA`, `R-1C+2` | the same as a digit reference today, said properly |
| `flt` | a character range, `left:right` | refused - a fixed-length record has offsets, not columns |
| `xml` | an XPath | refused |
| `json` | a pointer | refused |

Two things fall out. A headerless CSV field selector that is *not* a number stops
being a `parseInt` returning `-1` and becomes a structural refusal: a name, for a
file that has no names. And `column` now works on a *headed* CSV too, which is
new - a file with duplicate column names becomes addressable.

## Record selectors: a `discriminator` of its own

The record selector's `selector` does two unrelated jobs. For XML, JSON and Excel
it **locates** records - an XPath, a pointer, a sheet range. For CSV it **filters**
lines, every line being a candidate. Giving the CSV job a `column` would deepen
that confusion rather than fix it, so it gets its own word:

```json
{ "name": "orders", "discriminator": { "column": 1, "equals": "O" } }
{ "name": "orders", "discriminator": { "selector": "type", "matches": "^O.*" } }
```

```xml
<recordSelector name="orders">
    <discriminator column="1" equals="O"/>
</recordSelector>
```

Exactly one of `column`/`selector`, exactly one of `equals`/`matches` - the same
rule twice, and the same `Selector` above, so `column` means in a discriminator
what it means in a field selector. That reuse is the whole argument for doing the
two changes together: if these landed a release apart, the second would be
choosing a word the first had already spent.

`matches` compiles when the adapter is built, so a bad pattern fails at
deployment rather than mid-file - the same shape as an XPath that will not
compile. And a discriminator naming a column the header has not got is refused by
the machinery added in 0.26, for free.

`selector` on a record selector keeps its locating meaning and is untouched for
every adapter that has one.

## What this breaks

Every headerless CSV spec, and every CSV spec with a discriminator:

```
"selector": "1"   with header absent   ->  "column": 1
"selector": "O"   on a record selector ->  "discriminator": { "column": 1, "equals": "O" }
```

Nothing else moves. XML, JSON and fixed-length specs are unchanged; headed CSV
specs are unchanged.

## The schema

A new `mapping-spec-<release>.{json,xsd}`, with `mapping-spec-0.23` left frozen -
this is the first format change since 0.23. It carries three things:

- `column` on a field selector and inside a discriminator, typed as an integer of
  at least 1 in both schemas;
- the `discriminator` element, with its two exactly-one-of rules, expressed the
  way the value-source rule already is;
- and the `mimeType` list gains `text/tab-separated-values`, which has been
  missing since 0.28 because publishing a schema for one advisory string was not
  worth it on its own. It is worth it now. I checked the other eight entries
  against the adapters: all are still read.

## Open questions

1. **`discriminator` as a word.** It is what the code and the README already call
   the thing. The alternative is `when`, which reads better in a spec
   (`"when": {"column": 1, "equals": "O"}`) and worse in prose.
2. **Should `column` default to 1** in a discriminator, so that today's common
   case stays a one-liner? It would make the migration `{"equals": "O"}`, which is
   shorter, at the price of the implicit first column this change exists to make
   explicit. I would require it.
3. **Does `flt` want `column`** after all, if a future fixed-length layout is
   given named columns? I have assumed not: offsets are what that format has.
4. **Does anything else want fixing while the schema is open?** A format change is
   the cheap moment, and the next one may be a long way off.
