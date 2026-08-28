# 13. Drafting one with an assistant

[← when it goes wrong](12-when-it-goes-wrong.md) · [index](README.md)

A mapping spec is a structured document with a published schema, derived mechanically from a file and a table you
can both show. That is close to the ideal shape for a language model to draft, and Claude, Copilot, ChatGPT and the
rest will all produce something plausible from three inputs.

Plausible is the operative word, and it is why this page is last rather than first. A generated spec is worth
having only if you can read it, and reading it is what the eleven pages before this one were for. Someone who
cannot yet tell `nth` from `selector` cannot tell a correct spec from a confident one, and the failure mode is not
an error message - it is a load that runs and puts the wrong column in the wrong place.

## What to give it

Three things, and the third is the one people leave out.

**A few lines of the real file**, including the header row if there is one. Five lines is plenty. Invent the values
if the real ones are sensitive - what the model needs is the shape, the separator, the date format, the record
types, not your customers.

**The `create table`.** Column names and types. Without it you will get a spec that maps `name` to `name` and
guesses the rest.

**The schema, and its version.** This is the one that decides whether the answer is any good:

    Write an XLDR mapping spec (spec.json) valid against
    https://ralfspoeth.github.io/xldr/schema/mapping-spec-0.47.json

Say the version. The vocabulary changed at 0.32, and a model that learned from anything written before that - or
that is generalising from other ETL tools, which is what it is really doing - will reach for the older words with
complete confidence. If the assistant cannot fetch a URL, paste the schema itself; it is one file and it is the
most useful thing in the prompt.

A prompt worth copying:

    Write an XLDR mapping spec as spec.json, valid against the JSON schema at
    https://ralfspoeth.github.io/xldr/schema/mapping-spec-0.47.json

    The file arriving looks like this (text/csv):

        id,name,city,since
        1,Alice,Berlin,01.03.2026

    Dates are written dd.MM.yyyy. It loads into:

        create table customer(id integer, name varchar(50),
                              home_city varchar(50), since date)

    Include the $schema member. Explain any field where you had to guess.

That last line earns its place. An assistant told to flag its guesses will usually flag the right ones - the type
of a column it could not see, whether a header is present - and those are exactly the lines to check first.

## What to check in the answer

Six things go wrong far more often than the rest, and all six come from the same place: every other tool in this
space uses slightly different words, and a model is averaging over all of them.

**`column` where `nth` belongs.** The commonest by a distance. Every other ETL format calls a numbered field a
column, and so does a *field mapping* here - for the database side. If you see `"column": 3` inside a field
*selector*, that is the old spelling and this format does not have it. It is `"nth": 3`.

**A record selector with a `selector` on a flat file.** Before 0.32 a CSV record selector said `"selector": "O"` to
mean *lines whose first field is O*. That form is refused now, and the replacement says more:
`"discriminator": {"nth": 1, "equals": "O"}`.

**Counting from zero.** `nth` counts from one. A model that has just written some Python is prone to this.

**`"nth": "1"` in quotes.** A string is a name, a number is a count. The schema types it, so this one is caught for
you - which is the argument for the next section.

**Invented properties.** `skipRows`, `encoding`, `delimiter`, `hasHeader` - all plausible, none of them real. The
actual names are in the [README](../../README.md#feed-configuration), and the ones this tutorial used are
`fieldSeparator`, `header`, `charset`, `dateFormat`, `numberFormat` and `locale`.

**Old type names.** `STRING`, `INTEGER` and `FLOAT` were renamed at 0.21 and are refused. It is `TEXT`,
`INTEGRAL`, `FP`, plus `DECIMAL` and `TEMPORAL`.

## Then let the machine check it

Everything above is mechanical, so do not do it by eye. Save the spec with its `$schema` member, open it in
IntelliJ or VS Code, and read the squiggles - as [page 12](12-when-it-goes-wrong.md) describes. The schema catches
every one of the six except the first, and catches a good deal else besides.

Then run `check`, as [page 12](12-when-it-goes-wrong.md#before-you-deploy-xldr-check) describes:

    xldr check spec.json --sample sample.csv --url jdbc:h2:./tutorial

What it compares is exactly what an assistant gets wrong once the schema has had its say. A model that invented a
`column`, or referred to a record selector by a name it did not declare, or wrote a `lookup` against a table it
imagined, produces a spec that validates perfectly and fails on the first delivery. And a record selector that
matches nothing in your file does not fail at all - it loads no rows, quietly, which is the one an assistant is
most likely to hand you with confidence.

Which brings us to what neither the schema nor `check` nor the assistant can decide for you. A spec can be entirely
valid, pass every check, load without complaint, and be wrong - `home_city` filled from the wrong field, or a date
that parsed under the default because nobody said `dd.MM.yyyy` and `01.03.2026` happened not to throw. That is why
`check` prints the parsed values with their types rather than only counting them: the file said `01.03.2026` and
the line above says the first of March, which is either what your producer meant or is not. Nothing in the
toolchain knows which. One look is the whole of the remedy, and it is worth the two minutes on a spec you wrote
yourself, let alone on one you did not.

## Where an assistant is genuinely better than a person

Not at the interesting parts. It is very good at the tedious ones: forty field selectors transcribed from a layout
document without a transposition; the same spec written out in the other format, which is mechanical and which
[page 3](03-in-xml.md) gives it the rules for; and reading an error message you have not seen before, since the
refusals in this toolkit are written in sentences and say what to write instead.

It is worst at exactly what you would expect - deciding which of two plausible readings of an ambiguous file is the
right one. That is a question about the feed rather than about the format, and the answer is upstream, in whoever
sends you the file.

---

That really is the end of the tutorial. The [README](../../README.md) has the rest.

[← when it goes wrong](12-when-it-goes-wrong.md) · [index](README.md)
