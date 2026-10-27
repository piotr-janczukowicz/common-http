module common.http {
    requires transitive okhttp3;
    requires okio;
    requires org.slf4j;
    requires common.util;
    requires transitive common.json;
    requires static org.jspecify;

    exports io.github.piotr_janczukowicz.http;
    exports io.github.piotr_janczukowicz.http.client;
    exports io.github.piotr_janczukowicz.http.client.authentication;
    exports io.github.piotr_janczukowicz.http.transport;
    exports io.github.piotr_janczukowicz.http.uri;
    exports io.github.piotr_janczukowicz.uri;
}
