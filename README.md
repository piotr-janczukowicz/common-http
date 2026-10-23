# common-http

Shared HTTP utilities for Java callers: immutable request/response types, a transport layer over OkHttp with retry, rate-limit, concurrency and caching decorators, and a client layer with authentication, redirect handling and polling.

## Dependency

Java library. Add Maven coordinates `pl.piotrjanczukowicz:common-http:1`.

```xml
<dependency>
  <groupId>pl.piotrjanczukowicz</groupId>
  <artifactId>common-http</artifactId>
  <version>1</version>
</dependency>
```

## Usage

| Package | Contents |
| --- | --- |
| `pl.piotrjanczukowicz.http` | `HttpRequest`, `HttpResponse`, `HttpStreamResponse`, `HttpHeaders`, `HttpMethod`, `HttpStatus`, `HttpBody` and related exceptions |
| `pl.piotrjanczukowicz.http.transport` | `HttpTransport` and its OkHttp implementation, plus decorators for retries, rate limits, concurrency limits, mutation pacing and conditional caching |
| `pl.piotrjanczukowicz.http.client` | `HttpClient`, authentication providers, redirect policy, origin guard, polling and download helpers |
| `pl.piotrjanczukowicz.http.uri` | `HttpUris` and `ParsedUrl` |

See the Javadoc of each type for the contract.

## License

MIT License
