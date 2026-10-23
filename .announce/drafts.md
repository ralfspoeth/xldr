# Announcement drafts

Three pieces, same material, three lengths. Not committed anywhere public yet.

---

## 1. foojay.io article

**Title:** The mapping does not belong in the file

**Standfirst:** Twenty years of loading files into database tables, and the one thing it took
that long to get right.

---

Here is a file that arrives every morning somewhere near you:

    O,1001,2026-03-01,1
    L,1001,widget,5
    L,1001,sprocket,2
    O,1002,2026-03-02,2

Two record types, two destination tables, one file, and nothing in it that names anything. No
header row to refer to, so fields are addressed by position. No single shape, so you cannot
hand it to a CSV reader and be done.

The usual answer is a hand-written pre-processing step that splits the file by record type
before anything general-purpose is allowed near it. That step is where the format knowledge
goes to hide: it is code, it lives apart from whatever does the loading, and the next person
to touch it discovers the layout by reading a loop.

[xldr](https://github.com/ralfspoeth/xldr) is a Java 25 toolkit that does this from a
declarative mapping specification instead. Four lines say which component to look at and what
its value has to be, and both tables are filled in one transaction, so there is no state in
which the orders are loaded and their lines are not. It also reads CSV, XML, JSON and Excel,
but the flat file above is what it was written for.

### Why not an ETL suite

Because of where the transformation lives.

In the architecture this came out of, external files land in staging tables, and application
code reads those tables and derives business objects. The transformation is downstream. The
load step's entire job is to get external bytes into landing tables faithfully - which is a
small, sharply bounded task that Talend, Pentaho and Informatica are not sized for.

Worse than oversized: taking up their offer would put business meaning in two places. Some in
application code under review in version control, some in a GUI-designed job that is not
diffable, not testable, and cannot be reasoned about in a pull request. A spec that declares
columns and types and refuses anything cleverer is a better fit precisely because it *cannot*
absorb logic belonging on the other side of the landing tables.

### The premise

**The mapping is configuration. The file is data.** Everything else follows from holding those
two apart.

A spec is a JSON or XML document in your repository. It diffs, it reviews, it branches with the
code that depends on it. `xldr check` validates one without a database and without an input
file, so a spec that names a column your schema does not have is a build failure rather than a
3 a.m. page. And because the file never names a table, the directory where files arrive is not
a privileged place: table and column names reach the SQL unbound, so a format that let the
*input* choose them would make every upload equivalent to a SQL console.

That last point is the one that took twenty years.

### Twenty years

Around 2006 I had an enterprise system with a report writer that was good at emitting text
files, and a staging area to fill. The report writer had a `SYSTEM` command - the same escape
hatch `sqlplus` calls `HOST` - so the first version generated a SQL file and executed it,
with `WHENEVER SQLERROR` set to roll back. It worked. It was also generating *code*, with all
that implies about quoting, about an apostrophe in a customer name, and about re-parsing every
statement.

So the next version emitted a line format instead - a header line declaring a table and its
columns, data lines carrying values, and a line per procedure call to merge the staged rows
into their targets - and a small loader read those lines, prepared statements once, and bound
parameters. Generating data rather than code. Faster, and immune to an apostrophe.

That loader was called xldr. The staging area it filled is still in production.

What it got wrong is that the mapping travelled *inside* the file. The header lines said which
table and which columns, so each file was a complete load instruction. That is convenient
exactly until you want to review one, validate one before running it, accept one from a party
you do not fully trust, or answer the question "what will this file do" without running it.

Moving the mapping out of the file and into a specification is the whole difference between
that loader and this one. It is not a large idea. It took me two decades and a format I had to
reject to see it clearly.

### The Java parts

The adapters are JPMS modules bound through `ServiceLoader`, so the set of formats a deployment
understands is the set of modules on its path - adding a format is adding a jar, and removing
one is removing a jar.

A lesson from that, free to whoever needs it: a `provides` directive in `module-info` is read
only for resolved *named* modules. Put the same jar on a classpath - in a war, or in anything
Quarkus builds - and it lands in the unnamed module and the directive is ignored entirely. Every
adapter therefore declares itself twice, in `module-info` and in `META-INF/services`. xldr
shipped a servlet front end for several releases that could never have worked in a war, and
nothing caught it, because the integration test shared a JVM with the module path.

There is also a TCK: a conformance kit a third-party adapter can be run against, covering what
the SPI actually obliges an implementation to do. It exists because "implement this interface"
is not a specification, and because the obligations that matter - an absent value is null, a
failure names the record it failed on - are exactly the ones nobody implements the same way
twice. It is the best place to start if you want to write an adapter for a format of your own.

### Where this leaves it

xldr is at 1.3.1, on Maven Central, MIT licensed, API and spec format settled since 1.0.

It is also written by one person, which you should weigh before depending on it, and which I
would rather say here than leave you to infer. There is a *Forking and stewardship* section in
the README that says what a fork inherits, what it does not - the Central namespace is tied to
a personal account and does not travel with a clone - and that a fork is wanted rather than
merely permitted.

If the flat file at the top of this article is one you recognise, I would like to hear how it
goes. And if the project turns out to be worth more than one maintainer, I would like to hear
that even more.

---

## 2. r/java

**Title:** xldr 1.3.1 - load flat, CSV, XML, JSON and Excel files into database tables from a declarative mapping spec (Java 25, JPMS)

**Body:**

A file that turns up constantly in enterprise integration and that no CSV library handles:

    O,1001,2026-03-01,1
    L,1001,widget,5
    L,1001,sprocket,2
    O,1002,2026-03-02,2

No header row, two record types interleaved, two destination tables, and a discriminator
column near the front saying which line is which. The usual answer is a hand-written splitter
that runs before the real loader, and that splitter is where the format knowledge ends up
living.

xldr does it from a spec instead - a JSON or XML document declaring two record selectors with
a discriminator each, and which field goes in which column. Both tables load in one
transaction. It also reads CSV, XML, JSON, Excel and fixed-length.

Things that might interest this sub specifically:

- Adapters are JPMS modules found through `ServiceLoader`, so the formats a deployment
  understands are the modules on its path. They declare themselves twice, in `module-info` and
  in `META-INF/services`, because `provides` is ignored for a jar on a classpath - which cost
  me a war deployment that could never have worked.
- There's a TCK, so you can write an adapter for your own format and run it against the SPI's
  actual obligations rather than guessing at them.
- `xldr check` validates a spec against a live schema without loading anything, so a column
  that doesn't exist is a build failure rather than a 3am page.
- Three front ends: a CLI, an embeddable directory-watching server, and a servlet. Java 25,
  MIT, on Maven Central, API and spec format frozen since 1.0.

Repo: https://github.com/ralfspoeth/xldr

Being straight about two things. I'm the only author - there's a stewardship section in the
README saying what a fork would inherit and that one is welcome. And the build tests against
H2 and PostgreSQL, so a report from any other database is genuinely useful to me.

---
