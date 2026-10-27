[![Codacy Badge](https://app.codacy.com/project/badge/Grade/3b0b8aea9e364668b3a2c42c1d750037)](https://app.codacy.com/gh/piotr-janczukowicz/common-http/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade)
[![Codacy Coverage](https://app.codacy.com/project/badge/Coverage/3b0b8aea9e364668b3a2c42c1d750037)](https://app.codacy.com/gh/piotr-janczukowicz/common-http/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_coverage)
[![Build](https://github.com/piotr-janczukowicz/common-http/actions/workflows/main.yml/badge.svg)](https://github.com/piotr-janczukowicz/common-http/actions/workflows/main.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.piotr-janczukowicz/common-http)](https://central.sonatype.com/artifact/io.github.piotr-janczukowicz/common-http)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Java 21](https://img.shields.io/badge/Java-21-orange.svg)
# common-http

Shared HTTP utilities for Java callers: immutable request/response types, a transport layer over OkHttp with retry, rate-limit, concurrency and caching decorators, and a client layer with authentication, redirect handling and polling.

## Dependency

Java library. Add Maven coordinates `io.github.piotr-janczukowicz:common-http:4`.

```xml
<dependency>
  <groupId>io.github.piotr-janczukowicz</groupId>
  <artifactId>common-http</artifactId>
  <version>4</version>
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
