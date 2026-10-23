package pl.piotrjanczukowicz.http.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public final class DropMidRequestServer implements AutoCloseable {

    private static final int DROP_AFTER_BYTES = 64;
    private static final String CONTENT_LENGTH = "content-length:";
    private static final byte[] OK_RESPONSE = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n"
            .getBytes(StandardCharsets.US_ASCII);

    private final ServerSocket server;
    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicInteger resets = new AtomicInteger();

    public DropMidRequestServer() throws IOException {
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        final Thread acceptor = new Thread(this::acceptLoop, "drop-mid-request-server");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public URI uri() {
        return URI.create("http://127.0.0.1:" + server.getLocalPort() + "/drop");
    }

    public int connections() {
        return connections.get();
    }

    @Override
    public void close() throws IOException {
        server.close();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                final Socket socket = server.accept();
                connections.incrementAndGet();
                final Thread handler = new Thread(() -> serve(socket), "drop-mid-request-connection");
                handler.setDaemon(true);
                handler.start();
            } catch (final IOException ex) {
                if (server.isClosed()) {
                    return;
                }
            }
        }
    }

    private void serve(final Socket socket) {
        try (socket) {
            final InputStream in = socket.getInputStream();
            final OutputStream out = socket.getOutputStream();
            final long contentLength = readHeaders(in);
            in.skipNBytes(contentLength);
            out.write(OK_RESPONSE);
            out.flush();

            in.readNBytes(DROP_AFTER_BYTES);
        } catch (final IOException ex) {
            resets.incrementAndGet();
        }
    }

    private static long readHeaders(final InputStream in) throws IOException {
        final StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            final int next = in.read();
            if (next < 0) {
                throw new IOException("closed before headers ended");
            }
            head.append((char) next);
        }
        for (final String line : head.toString().split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith(CONTENT_LENGTH)) {
                return Long.parseLong(line.substring(CONTENT_LENGTH.length()).trim());
            }
        }
        return 0;
    }
}
