[![Codacy Badge](https://app.codacy.com/project/badge/Grade/3b0b8aea9e364668b3a2c42c1d750037)](https://app.codacy.com/gh/piotr-janczukowicz/common-http/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade)
[![Codacy Coverage](https://app.codacy.com/project/badge/Coverage/3b0b8aea9e364668b3a2c42c1d750037)](https://app.codacy.com/gh/piotr-janczukowicz/common-http/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_coverage)
[![Build](https://github.com/piotr-janczukowicz/common-http/actions/workflows/main.yml/badge.svg)](https://github.com/piotr-janczukowicz/common-http/actions/workflows/main.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.piotr-janczukowicz/common-http)](https://central.sonatype.com/artifact/io.github.piotr-janczukowicz/common-http)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Java 21](https://img.shields.io/badge/Java-21-orange.svg)

# common-http

A small HTTP stack for Java 21 REST clients, built on OkHttp: immutable request and response types, a transport with
composable decorators for retries, rate limits, concurrency limits, write pacing and conditional caching, and a client that adds
a base URI, authentication, redirect handling, an origin guard, error mapping and polling. It is the base of
[`github-rest-client`](https://github.com/piotr-janczukowicz/github-rest-client) and works for any JSON API.

**Requirements:** Java 21. The library is a Java module, `common.http`, and also works on the class path. It is published on
[Maven Central](https://central.sonatype.com/artifact/io.github.piotr-janczukowicz/common-http):

```xml
<dependency>
  <groupId>io.github.piotr-janczukowicz</groupId>
  <artifactId>common-http</artifactId>
  <version>6</version>
</dependency>
```

OkHttp and [`common-json`](https://github.com/piotr-janczukowicz/common-json) come with it. The library has no JSON mapper of
its own: you pass a `JsonCodec` (the contract of `common-json`) that reads and writes bytes with the mapper you use.

## Layers

| Package | Contents |
| --- | --- |
| `io.github.piotr_janczukowicz.http` | `HttpRequest`, `HttpResponse`, `HttpStreamResponse`, `HttpHeaders`, `HttpMethod`, `HttpStatus`, `HttpBody`, `MultipartFormData` and their exceptions |
| `io.github.piotr_janczukowicz.http.transport` | `HttpTransport`, the OkHttp implementation (`HttpTransports`), the decorators, and the timeout, proxy, TLS and retry settings |
| `io.github.piotr_janczukowicz.http.client` | `HttpClient`, the callers that send an `HttpCall` and decode the answer, redirect policy, origin guard, polling and call observers |
| `io.github.piotr_janczukowicz.http.client.authentication` | `AuthenticationProviders`: bearer, renewable bearer, basic and none |
| `io.github.piotr_janczukowicz.http.uri`, `io.github.piotr_janczukowicz.uri` | `HttpUris`, `ParsedUrl`, `PathTemplate` and `PathSegments` |

The transport knows nothing about base URIs or credentials; the client knows nothing about connections. You compose the
transport, hand it to the client, and the client owns it from then on: `close()` closes the whole stack.

## Usage

### Send a request

```java
HttpTransport transport = new RetryingHttpTransport(HttpTransports.okHttp());

try (HttpClient client = HttpClient.builder(
                transport, HttpClientConfiguration.of(URI.create("https://api.example.com/v1/")))
        .auth(AuthenticationProviders.bearer(token))
        .build()) {

    HttpClientCaller caller = new HttpClientCaller(client, codec); // codec: your JsonCodec

    Widget widget = caller.fetch(HttpCall.get("widgets/42"), Widget.class);

    HttpCall create = caller.json(HttpCall.post("widgets"), new NewWidget("name"));
    HttpResponse created = caller.send(create);
}
```

A 2xx answer becomes an `HttpResponse` or a decoded value. Any other status becomes the unchecked exception that the client's
`HttpErrorMapper` builds (an `HttpException` by default), and a failure of the network stays an `IOException`. The caller
also has `fetchOptional` and `fetchOrEmpty` for answers that may be absent, `fetchResult` for the response headers next to
the value (an `ETag`, say), `fetchByStatus`, `isExisting`, `stream`, `download` and `redirectLocation`.

A client can be shared between threads when its transport, authentication provider and observers are.

### Compose the transport

Each decorator wraps another transport and adds one behavior. From the outside in, the order that works is:

| Decorator | What it does |
| --- | --- |
| `ConditionalCachingHttpTransport` | Keeps GET responses that carry an `ETag` or `Last-Modified` and answers a repeated GET from memory when the server replies 304, so the body is not sent again |
| `RetryingHttpTransport` | Retries failed attempts and waits out rate limits, with separate budgets for I/O failures, rate limits and 5xx; it remembers a reported limit so later calls of the same credential do not send a doomed request |
| `MutationPacingHttpTransport` | Keeps a minimum interval between the starts of POST, PUT, PATCH and DELETE for APIs that throttle writes; GET is never delayed |
| `ConcurrencyLimitingHttpTransport` | Lets at most N requests run at once, since the OkHttp transport sends on the caller's thread |
| OkHttp (`HttpTransports.okHttp`) | The network, with timeouts, optional proxy, TLS trust and certificate pins |

```java
HttpTransport transport = new RetryingHttpTransport(
        new ConcurrencyLimitingHttpTransport(HttpTransports.okHttp(), 4),
        RetryConfiguration.defaults());
```

Retries use an I/O budget and a 5xx budget (GET, HEAD and OPTIONS by default) and a rate-limit budget that covers every
method. A limit that names its wait through `Retry-After` or a reset time is waited out when it is at most five minutes;
without a named wait the transport waits 60, 120 and 240 seconds with some jitter. Every value is in `RetryConfiguration`.

### Authenticate

| Provider | Use it for |
| --- | --- |
| `AuthenticationProviders.bearer(token)` | A fixed token |
| `AuthenticationProviders.bearer(supplier)` | A token read on every request |
| `AuthenticationProviders.bearer(supplier, isRenewable)` | A token that expires: after a 401 the client sends the request once more if a different token is available |
| `AuthenticationProviders.basic(user, password)` | A user and password, or a client ID and secret |
| `AuthenticationProviders.none()` | No credentials |

Default headers never replace an authentication header. Only a header set on one call can, which is how a single request
sends another credential.

### Redirects and origins

The client follows up to five redirects by default. A GET or HEAD may leave the origin, and then only `Accept` and
`User-Agent` are kept: `Authorization`, `Proxy-Authorization` and `Cookie` are never forwarded to another origin, and a
`RedirectPolicy` that lists one of them is rejected. `HttpOriginGuard` accepts an absolute URI only when it matches the
base origin and path prefix.

### Wait for a result that is computed in the background

Some endpoints answer 202 until a report is ready. `fetchWhenReady` repeats the request a fixed number of times with a fixed
pause and returns `HttpComputed.Ready`, `NotReady` or `NoData`:

```java
HttpComputed<Report> report = caller.fetchWhenReady(
        HttpCall.get("reports/7"), Report.class, HttpAcceptedPolling.defaults());
```

### Observe calls

An `HttpCallObserver` receives one `HttpCallEvent` for every call, after retries and redirects, for metrics, tracing or audit
logs. It runs on the calling thread, and an exception it throws is logged and dropped, so observing never changes an outcome.

## Security

See [SECURITY.md](SECURITY.md) to report a vulnerability.

## License

See [LICENSE](LICENSE).
