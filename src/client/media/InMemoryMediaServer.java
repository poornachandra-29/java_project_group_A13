package client.media;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;

/**
 * InMemoryMediaServer
 * -------------------
 * - Streams media directly from memory (RAM) via internal HTTP.
 * - JavaFX Media/MediaPlayer only accepts URLs (file:// or http://), not raw byte arrays.
 * - This class creates an HttpServer on 127.0.0.1:<port>, manages a map of <id,Stream>, and accepts chunks
 *   to stream playback directly using chunked transfer.
 *
 * Usage:
 *   InMemoryMediaServer srv = InMemoryMediaServer.get();
 *   String id = "your-file-id";
 *   srv.open(id, "audio/mpeg"); // or "video/mp4", "audio/wav", ...
 *   srv.write(id, chunkBytes, false); // multiple chunks
 *   srv.write(id, lastChunkBytes, true); // final chunk
 *   String url = srv.url(id); // "http://127.0.0.1:<port>/stream/your-file-id"
 *   Media media = new Media(url);
 *   MediaPlayer player = new MediaPlayer(media);
 *
 *   // If abort is needed:
 *   srv.abort(id);
 */
public final class InMemoryMediaServer {

    /** Stream information: queue of byte[] chunks and completion status */
    private static final class Stream {
        final String id;
        final String mime;
        final BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
        volatile boolean closed = false;
        Stream(String id, String mime) {
            this.id = id;
            this.mime = (mime == null || mime.isBlank()) ? "application/octet-stream" : mime;
        }
    }

    // Singleton
    private static volatile InMemoryMediaServer INSTANCE;

    public static InMemoryMediaServer get() {
        InMemoryMediaServer local = INSTANCE;
        if (local == null) {
            synchronized (InMemoryMediaServer.class) {
                local = INSTANCE;
                if (local == null) {
                    try {
                        local = new InMemoryMediaServer();
                    } catch (IOException e) {
                        throw new RuntimeException("Cannot start InMemoryMediaServer", e);
                    }
                    INSTANCE = local;
                }
            }
        }
        return local;
    }

    private final HttpServer server;
    private final int port;
    private final Map<String, Stream> streams = new ConcurrentHashMap<>();

    private InMemoryMediaServer() throws IOException {
        // Port 0: OS assigns an available ephemeral port
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/stream", new Handler());
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mem-media-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        System.out.println("[MEDIA] InMemoryMediaServer started at http://127.0.0.1:" + port);
    }

    /** Open a new stream for id (overwrites if already exists) */
    public void open(String id, String mime) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is empty");
        streams.put(id, new Stream(id, mime));
    }

    /** Write one data chunk into stream; last=true marks the final chunk */
    public void write(String id, byte[] data, boolean last) {
        Stream s = streams.get(id);
        if (s == null) return; // silent return if stream was aborted
        if (data != null && data.length > 0) {
            s.chunks.offer(data);
        } else {
            // avoid blocking when player is waiting without data
            s.chunks.offer(new byte[0]);
        }
        if (last) s.closed = true;
    }

    /** Abort stream and stop playback if open */
    public void abort(String id) {
        Stream s = streams.remove(id);
        if (s != null) {
            s.closed = true;
            // send empty marker to release blocked consumer
            s.chunks.offer(new byte[0]);
        }
    }

    /** Get playback URL for id */
    public String url(String id) {
        return "http://127.0.0.1:" + port + "/stream/" + encode(id);
    }

    // ========== HTTP HANDLER ==========

    private final class Handler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                String path = ex.getRequestURI().getPath(); // /stream/<id>
                String[] parts = path.split("/", 3);
                if (parts.length != 3 || parts[2] == null || parts[2].isEmpty()) {
                    notFound(ex);
                    return;
                }
                String id = decode(parts[2]);
                Stream s = streams.get(id);
                if (s == null) {
                    notFound(ex);
                    return;
                }

                ex.getResponseHeaders().add("Content-Type", s.mime);
                // Enable chunked transfer streaming
                ex.sendResponseHeaders(200, 0);

                try (OutputStream os = ex.getResponseBody()) {
                    // stream until closed and queue is empty
                    while (true) {
                        byte[] chunk = s.chunks.poll(30, TimeUnit.SECONDS);
                        if (chunk == null) {
                            // chunk wait timeout: exit if closed and queue is empty
                            if (s.closed) break;
                            else continue;
                        }
                        if (chunk.length > 0) {
                            os.write(chunk);
                            os.flush();
                        }
                        // exit if stream is closed with no remaining data
                        if (s.closed && s.chunks.isEmpty()) break;
                    }
                } finally {
                    // remove stream once playback completes
                    streams.remove(id);
                }
            } catch (Exception e) {
                try { ex.close(); } catch (Exception ignore) {}
            }
        }
    }

    // ========== Utils ==========

    private static void notFound(HttpExchange ex) throws IOException {
        byte[] msg = "404 Not Found".getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(404, msg.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(msg);
        } finally {
            ex.close();
        }
    }

    private static String encode(String s) {
        // encode spaces as %20 for UUID ids
        return s.replace(" ", "%20");
    }

    private static String decode(String s) {
        return s.replace("%20", " ");
    }
}
