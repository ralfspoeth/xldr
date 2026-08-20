# 1. Setting up

[← index](README.md) · [next: your first spec →](02-first-spec.md)

Three things before you can write a spec and watch it work: the distribution, a database, and a running server.
None of this is the interesting part - it is twenty minutes once, and every page after this one is about the spec.

## The distribution

Take the archive from the [latest release](https://github.com/ralfspoeth/xldr/releases/latest) and unpack it. Java
25 or later is the only thing you need installed:

    tar xzf xldr-<version>-dist.tar.gz        # or: unzip xldr-<version>-dist.zip
    cd xldr-<version>

If you would rather build it, the same archive comes out of a checkout under `app/target`, named after the module
that assembled it rather than after the product:

    git clone https://github.com/ralfspoeth/xldr
    cd xldr && mvn install
    tar xzf app/target/app-<version>-dist.tar.gz

Inside:

    bin/          xldr and xldr.cmd, the start scripts
    lib/          the application and the toolkit - remove anything here and nothing starts
    modules/      the input adapters, one jar per format
    xl/           Apache POI, needed only if you read spreadsheets
    drivers/      JDBC drivers - H2 and PostgreSQL are already there

The three directories after `lib/` are the choices a deployment makes. Delete `xl/` if no feed of yours reads
Excel and the Excel adapter simply stops being offered; nothing else notices. That is JPMS service binding rather
than configuration - what is on the module path is what the server can read.

## A database

Any database with a JDBC driver, and the distribution already carries two: H2 and PostgreSQL. For working
through this tutorial H2 in file mode is the least trouble - nothing to install and nothing to start, since in file
mode it runs inside the server's own JVM and writes to a file you name in the URL. The data survives a restart, so
you can look at what a load actually did.

If you would rather use something else, copy that driver's jar into `drivers/` and adjust the URL below; nothing
else in this tutorial changes. Removing the drivers you do not target is the same operation in reverse, and the
server neither notices nor cares - what is in that directory is what it can connect to.

Create the table the next page loads into, using the H2 jar that is already there:

    java -cp drivers/h2-*.jar org.h2.tools.Shell \
        -url jdbc:h2:/tmp/xldr-tutorial -user sa

and then:

    create table customer(id varchar(10), name varchar(50), city varchar(50));

Nothing in a spec ever creates a table. A spec says where values come from and where they go; the schema is yours,
and XLDR expects to find it already there.

## A root, and a feed

A **feed** is a directory holding the two files that describe one kind of arriving file. Feeds live below a
**root**, and the server watches the roots.

    mkdir -p /var/lib/xldr/customers

That is all for now - the two files go in it on the next page.

## Configuration, and starting

The server reads `xldr.properties` from the directory it is started in, or from the one `--dir` names. The two
settings that matter here are which roots to watch and which database to load into:

    cd /path/to/xldr-<version>
    cat > xldr.properties <<'EOF'
    xldr.roots = /var/lib/xldr
    jdbc.url   = jdbc:h2:/tmp/xldr-tutorial
    jdbc.user  = sa
    EOF

Start it:

    bin/xldr                    # bin\xldr.cmd on Windows

It creates `in/`, `archive/` and `hospital/` inside every feed it finds, and waits. Leave it running in one
terminal and work in another; it picks up a new or changed spec without a restart.

If it exits at once, it will have said why - a root that does not exist, a database it cannot reach, a feed whose
spec does not parse. The full set of settings is in the
[README](../../README.md#configuration).

## What you have

    /var/lib/xldr/customers/
        in/            files arrive here
        archive/       and are moved here once loaded
        hospital/      or here, with a note, if the load failed

The next page fills the feed with the two files that make it do something.

---

[← index](README.md) · [next: your first spec →](02-first-spec.md)
