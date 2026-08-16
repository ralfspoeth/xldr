# xlet

An [xldr](https://github.com/ralfspoeth/xldr) front end for a servlet container: the
same loading, entered over HTTP instead of by dropping a file into a directory.

> Written as a design note before any code existed, so that the decisions could be
> argued with in a diff rather than in a chat window. It is now a description of
> what is there - and the reasons stayed attached, which was the point.

## What it is

`server` is xldr's file-based orchestration: it watches roots, promotes directories
to feeds, claims arriving files by moving them, loads them, and files them away. All
of that is one answer to a single question - *when is an input ready, and which
mapping does it belong to*.

An HTTP request answers that question by itself. The request **is** the delivery, so
it needs no `delivery.properties`, no sentinel and no atomic move; and it names the
mapping it wants, so it needs no feed directory. What remains is the part both have
in common: read this input through that spec, in one transaction, into the database.

So `xlet` is not a port of `server`. It is the other half of it - and most of what
`server` does has no counterpart here at all.

## How it corresponds

| file server | xlet |
|---|---|
| `<root>/<feed>/spec.json` | `/WEB-INF/specs/<name>.json` (or `.xml`) |
| the feed's `in/` directory | `POST …?spec=<name>` |
| `delivery.properties` | the request; an HTTP body is complete or it is not |
| `env.properties` | the servlet's init- and context-params, under the same `env.` prefix |
| `jdbc.*` in `xldr.properties` | a `DataSource` from JNDI |
| `Watcher` + `FeedRegistry` reconciliation | `init()`, once |
| `archive/` | the `200` response |
| `hospital/` + `.log` | the `4xx`/`5xx` response and its body |
| claim by atomic move | *nothing* - see [What is not inherited](#what-is-not-inherited) |

The specs are read once at `init()` and never again: a redeploy is how they change,
which is what makes them deployment configuration rather than state. A spec that
will not parse fails initialisation - the servlet does not start - which is the
analogue of a feed that does not activate, and it is loud in the same way.

## The request

    POST <context>/<servlet>?spec=statements
    Content-Type: text/csv

    id,name
    1,Alice

**The spec is named by a request parameter, not by the path**, and the difference
from the file server is worth stating because it looks at first like an
inconsistency. There, the location *is* the configuration: a file is dropped into
`<root>/statements/in/` and the parent of `in/` is what knows the rest, so the
caller names a place and the place carries the meaning. Here there is no place. The
caller names an operation - load this - and the spec is an argument to it. A path
segment would be dressing an argument up as a location that does not exist.

`spec` is required; its absence is a `400` rather than a default, since there is no
sensible feed to guess.

**A form-encoded request is refused before anything else.** `getParameter` is
answered from the query string *and*, for `application/x-www-form-urlencoded`, from
the body - which the container must read to do it, leaving `getInputStream` empty
and the load silently importing nothing. Refusing that one content type up front
closes the hole, and costs nothing: no adapter reads form-encoded data, so such a
request could never have loaded anything anyway. The same reasoning is why the
servlet is not annotated `@MultipartConfig` - without it, a multipart body is not
parsed for parameters either.

**Path info is refused too.** The servlet answers at its own mapping and nowhere
below it. Silently ignoring a trailing path would let `…/typo?spec=x` work and teach
the next reader that the path means something.

**The spec chooses the adapter; the request's content type is only checked.** As
everywhere else in xldr, the adapter comes from the spec's `mimeType`, and the
selectors were written for that adapter. The request's `Content-Type` is then put to
the chosen factory - `reads(contentType)` - and a `415` follows if it says no. The
check is the factory's own, not string equality, so a spec saying `application/xml`
accepts a request saying `text/xml` without either side having to know about the
other. Parameters after a `;` are stripped first.

| status | when |
|---|---|
| `200` | loaded; the body reports how many rows |
| `400` | no `spec` parameter, or path info where none belongs, or the input did not parse |
| `404` | no spec of that name |
| `415` | form-encoded, or the adapter the spec names does not read the request's content type |
| `500` | the load failed; the transaction rolled back and nothing was inserted |

Nothing is retained on failure. The caller still has the data and the response says
what was wrong, which is the whole of what `hospital/` exists to preserve when there
is nobody to tell.

## What is not inherited

**Loads are at-least-once here, not at-most-once.** The file server claims a file by
moving it, and that move is the lock: two callbacks, or two processes over one tree,
cannot both take it. HTTP has no such thing. A client that times out and retries will
load the same input twice, and the second load will succeed. Until there is an
idempotency key, that is the contract, and it is written here rather than discovered.

**The body can be read only once.** A mapping spec may carry several record mappings,
and the loader runs each over the whole input, reopening the stream per mapping - a
file can be reopened, a socket cannot. So `doPost` spools the body to a temporary
file first and hands that to the same code the file server uses. Buffering in memory
would work equally well for small inputs and not at all for large ones.

**There is no `put`.** Deliberately, and not as an omission to be filled in later.
Table and column names from a spec are concatenated into the SQL rather than bound,
so whoever can install a spec can write into any table the connection can reach.
In the file server that is safe, because writing into a feed directory is already a
privileged act that the file system enforces. Over a socket there is no such
protection, so specs arrive the way the rest of the deployment does: in the WAR,
under `/WEB-INF/`, where the container's own access control covers them.

## Configuration

| where | what |
|---|---|
| `/WEB-INF/specs/*.{json,xml}` | one file per feed; the base name is the feed name |
| `java:comp/env/jdbc/...` | the `DataSource`, named by an init-param |
| init- and context-params `env.*` | what a spec's `${env.…}` expressions resolve against |

The `env.` convention is unchanged from the file server, so a spec moves between the
two without editing: only where the values come from differs, a properties file there
and the container's environment here.

## What it stands on

The load itself is `Loader.load(spec, source, ambient, connection)` in xldr's `ldr`
module - find the adapter for the input spec, run every record mapping, commit or
roll back. It used to be private to `server`, wrapped around a feed directory, and
came down in xldr 0.25 so that both front ends could reach it without one depending
on the other. The file server passes a file; this passes the spooled request.

The input is an `InputSource` rather than a stream, because a spec may carry several
record mappings and each is run over the whole input - a file reopens, a socket does
not, which is what the spooling is for.

## Monitoring

*Decided, not yet built.* An MXBean, as in `server`, and the same `Statistics`
behind it.

`Statistics` turns out to divide cleanly: it holds only load counters - starts,
finishes, rows, failures, last load, per feed and in total - while every file-shaped
gauge (`filesWaiting`, `filesInHospital`, the per-feed map) is computed in
`ServerStatus` from the `FeedRegistry` rather than stored. So it moves down with the
load operation and carries no file concepts along; `server` keeps its gauges, and
this module exposes the counters plus whatever HTTP has to add.

Two things a container demands that a single process does not:

**The object name must carry the context path.** `ServerStatus` registers a fixed
name, which is right for one JVM running one server and wrong here: two deployments
of the same WAR, or a WAR beside the standalone server, and the second registration
throws `InstanceAlreadyExistsException`. `…:type=Loader,context=/xldr` is the
convention the containers use for their own beans.

**And it must be unregistered in `destroy()`.** Otherwise the platform
`MBeanServer` holds a strong reference to a class loaded by the web application's
loader, and every redeploy leaks a classloader. `ServerStatus.register` already
returns an `AutoCloseable` for exactly this; it only has to be closed.

Why JMX and not something newer: it costs no dependency, which is the same reason
this project uses `System.Logger` and `ServiceLoader`, and it forecloses nothing -
a Prometheus JMX exporter reads it without either side knowing about the other.
Micrometer would be the ecosystem's answer and is precisely the dependency `app`
exists to keep out of `server`. As of 2026 only JMX's management *applets* are
deprecated for removal, explicitly without effect on the agent or on tooling,
though the OpenJDK JMX Group was dissolved in January 2026 - maintained rather than
evolving. JFR is the one genuinely modern JDK-native alternative and is better than
counters for load *history*, per-load events with durations; it cannot answer what
is true right now, so it would complement these gauges rather than replace them.

## Concurrency

A permit, as in `server`, but it fails fast instead of queueing.

`FileProcessor`'s semaphore is *fair* and `acquire()` blocks for as long as it takes,
because "a file cannot be starved by a steady stream of newer arrivals" - correct
when nothing is waiting, since a file in `in/` does not mind waiting four minutes.
Here a client is waiting with a timeout of its own, and blocking backpressure
produces the one outcome worth avoiding: the client gives up, retries, and because
loads here are at-least-once the same data is now queued twice.

So `tryAcquire` with a short wait - a second or two, so that brief bursts still
smooth out - and then `503` with `Retry-After`. A program handles that correctly; a
timeout it cannot.

**The limit exists for the neighbours.** Two bounds are already present - the
container's thread pool and the `DataSource` - and this module could rely on them.
It should not. A load is long and expensive next to an ordinary request, so an
unbounded burst of them occupies the shared thread pool and starves the rest of the
web application this servlet is deployed into. That is the failure nobody can
diagnose afterwards. And the bound the pool does impose degrades badly on its own:
exceeding it parks in `getConnection()` and surfaces as a `500` once the pool times
out, indistinguishable from a real fault, when what was meant was "busy, come back".

`maxConcurrentLoads` is an init-param, defaulting to something small, and must be no
larger than the `DataSource`'s maximum - the same relationship the file server's
`xldr.maxConcurrentLoads` has to its pool, stated here because `javax.sql.DataSource`
offers no portable way to ask.

**The body is the other resource.** Each concurrent request spools to disk, so
concurrency multiplies by body size, and an unbounded body is a denial of service by
itself - Tomcat's `maxPostSize` covers only form-encoded requests, so nothing else
catches it. Hence a maximum content length, a `413` beyond it, and the temporary
file deleted in a `finally` that also covers a client vanishing mid-upload.

**Two things deliberately not done.** No serialising per spec: the file server
already runs concurrent loads through one feed - claim-by-move stops the same *file*
twice, not the same feed - so xldr tolerates this and a lock would invent a
constraint. And no fairness: fairness matters when a queue is long-lived, and here
the queue should barely exist.

Virtual threads, if the container offers them, do not change any of this. They make
blocking cheap; the constraint is the client's patience, not the thread's cost.

The cost of failing fast is that a caller who would have waited 300ms is sometimes
told `503`. The acquire timeout is the dial, and `loadsInProgress` together with a
count of rejections is how you would know it was set wrong.
