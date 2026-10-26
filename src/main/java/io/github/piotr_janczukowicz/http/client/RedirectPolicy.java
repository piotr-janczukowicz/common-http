package io.github.piotr_janczukowicz.http.client;

import static io.github.piotr_janczukowicz.http.HttpHeaderNames.ACCEPT;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.AUTHORIZATION;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.COOKIE;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.PROXY_AUTHORIZATION;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.USER_AGENT;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireAtLeast;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.piotr_janczukowicz.http.HttpMethod;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Redirect following: hop limit, methods allowed to leave the origin, and headers kept on a cross-origin hop.
 *
 * <p>The constructor throws {@link NullPointerException} for a {@code null} set or header name, and
 * {@link IllegalArgumentException} when {@code maxHops} is below one or {@code crossOriginHeaderNames} contains a
 * credential header.
 *
 * @param maxHops maximum redirects followed
 * @param crossOriginMethods methods allowed to follow a different origin
 * @param crossOriginHeaderNames header names kept after leaving the origin (case-insensitive); never
 *     {@code Authorization}, {@code Proxy-Authorization} or {@code Cookie}, which carry credentials that must not reach
 *     another origin
 */
@SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "crossOrigin sets are copied to unmodifiable sets")
public record RedirectPolicy(int maxHops, Set<HttpMethod> crossOriginMethods, Set<String> crossOriginHeaderNames) {

    private static final Set<String> CREDENTIAL_HEADERS = Set.of(
            AUTHORIZATION.toLowerCase(Locale.ROOT),
            PROXY_AUTHORIZATION.toLowerCase(Locale.ROOT),
            COOKIE.toLowerCase(Locale.ROOT));

    public RedirectPolicy {
        requireAtLeast(maxHops, 1, "maxHops");
        crossOriginMethods = Set.copyOf(requireNonNull(crossOriginMethods, "crossOriginMethods"));
        requireNonNull(crossOriginHeaderNames, "crossOriginHeaderNames");
        crossOriginHeaderNames = crossOriginHeaderNames.stream()
                .map(name -> requireNonNull(name, "header").toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        for (final String name : crossOriginHeaderNames) {
            if (CREDENTIAL_HEADERS.contains(name)) {
                throw new IllegalArgumentException("crossOriginHeaderNames must not contain " + name);
            }
        }
    }

    /**
     * Five hops, GET/HEAD may leave origin, keep {@code Accept} and {@code User-Agent}.
     *
     * @return default policy
     */
    public static RedirectPolicy defaults() {
        return new RedirectPolicy(5, Set.of(HttpMethod.GET, HttpMethod.HEAD), Set.of(ACCEPT, USER_AGENT));
    }
}
