# common-http

Shared HTTP utilities for Java callers: immutable request/response types, a transport layer over OkHttp with retry, rate-limit, concurrency and caching decorators, and a client layer with authentication, redirect handling and polling.

## Dependency

Java library. Add Maven coordinates `io.github.piotr-janczukowicz:common-http:1`.

```xml
<dependency>
  <groupId>io.github.piotr-janczukowicz</groupId>
  <artifactId>common-http</artifactId>
  <version>1</version>
</dependency>
```

## Usage

| Package | Contents |
| --- | --- |
| `io.github.piotr_janczukowicz.http` | `HttpRequest`, `HttpResponse`, `HttpStreamResponse`, `HttpHeaders`, `HttpMethod`, `HttpStatus`, `HttpBody` and related exceptions |
| `io.github.piotr_janczukowicz.http.transport` | `HttpTransport` and its OkHttp implementation, plus decorators for retries, rate limits, concurrency limits, mutation pacing and conditional caching |
| `io.github.piotr_janczukowicz.http.client` | `HttpClient`, authentication providers, redirect policy, origin guard, polling and download helpers |
| `io.github.piotr_janczukowicz.http.uri` | `HttpUris` and `ParsedUrl` |

See the Javadoc of each type for the contract.

## License

MIT License
