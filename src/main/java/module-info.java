module common.http {
    requires transitive okhttp3;
    requires okio;
    requires org.slf4j;
    requires common.util;
    requires transitive common.json;
    requires static org.jspecify;
    requires static com.github.spotbugs.annotations;

    exports pl.piotrjanczukowicz.http;
    exports pl.piotrjanczukowicz.http.client;
    exports pl.piotrjanczukowicz.http.client.authentication;
    exports pl.piotrjanczukowicz.http.transport;
    exports pl.piotrjanczukowicz.http.uri;
    exports pl.piotrjanczukowicz.uri;
}
