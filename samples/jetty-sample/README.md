# sample

A webapp that deploys [`xlet`](../../xlet), runnable with no container installed.

**Install the toolkit into your local repository first**, from the root of the
checkout:

    mvn install -DskipTests

Every sample depends on `xlet` and an adapter at `${project.version}`, which
during development is a snapshot that exists nowhere until something puts it
there. Skip this and the build stops at *Could not find artifact
io.github.ralfspoeth.xldr:xlet:jar:…-SNAPSHOT*. Note that `-pl` alone does not
help: it narrows the reactor to this one module, so the siblings are still not
built.

Then, from that same root:

    mvn -pl samples/jetty-sample jetty:run

If you copy this sample elsewhere, take `.mvn/jvm.config` with it. Jetty's XML
parser validates `jetty-env.xml` against the DTD that file declares, and JAXP
refuses to resolve it unless `-Djavax.xml.accessExternalSchema=all` is set on
the Maven JVM. The two go together: drop the flag and the parse fails with
*Schemadokument '' konnte nicht gelesen werden*; drop the `DOCTYPE` and it
fails instead with *cvc-elt.1.a: cannot find the declaration of element
Configure*. Both are reported against the `<Configure>` start-tag rather than
against the line at fault, which is what makes them worth knowing in advance.

then, from another shell and also at the root,

    curl -i --data-binary @samples/jetty-sample/src/test/resources/customers.csv \
         -H 'Content-Type: text/csv' \
         'http://localhost:8080/load?spec=customers'

and the rows are in the in-memory H2 the webapp brought with it. `Ctrl-C` stops
Jetty and takes the database with it.

## What it is for

Two things that prose cannot do.

**It is the deployment `xlet/README.md` describes, assembled.** Every file here
corresponds to a paragraph there - the `web.xml`, the specs under `/WEB-INF/specs/`,
the `DataSource` looked up by JNDI name - and reading them together is quicker than
reading either alone.

**It is the proof that a classpath deployment works.** Until 1.2.0 it did not: the
adapters declared themselves only in their `module-info`, which `ServiceLoader` reads
only for a resolved named module, so a war found no adapter at all and refused every
request for a spec that was perfectly correct. `AdapterRegistrationIT` checks the two
declarations agree; this webapp is the thing that actually loads a file through one,
on a classpath, in a container.

It is in the reactor for that reason. A sample nobody builds is a sample that goes
stale between one release and the next.

## Copying it

Change two things. The `<parent>` becomes your own or nothing, and `${project.version}`
becomes the release you are using. Everything else transfers as it stands.

And add the `security-constraint` that this one deliberately omits. Anyone who can
POST to that endpoint can write to your tables; the sample leaves it out so that a
plain `curl` works, and `web.xml` says so where the block would have gone. The one in
`xlet/README.md` is the one to copy.

## What is where

    pom.xml                                   war packaging, xlet, two adapters
    src/main/webapp/WEB-INF/jetty-env.xml     the DataSource, in Jetty's spelling
    src/main/webapp/WEB-INF/web.xml           the servlet, its mapping, its params
    src/main/webapp/WEB-INF/specs/customers.json   one spec; ?spec=customers names it
    src/test/resources/customers.csv          something to POST

The `jetty-env.xml` is the only Jetty-specific file. Tomcat declares the same
`DataSource` as a `<Resource>` in `context.xml`, WildFly in its own configuration, and
the servlet neither knows nor cares: it looks up `java:comp/env/jdbc/xldr` and uses
whatever the container bound there.

## The spec

`customers.json` reads two columns from the file and takes a third from the
deployment - `${env.source}`, which `web.xml` supplies as an init-param. That is the
same `env.` convention a feed's `env.properties` uses in the file server, which is the
point: the spec is unchanged between the two, and only where the value comes from
differs.
