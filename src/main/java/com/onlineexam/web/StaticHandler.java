package com.onlineexam.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Serves the website files (index.html, css, js) from a folder, refusing anything outside it. */
public final class StaticHandler implements HttpHandler {
    private static final Map<String, String> TYPES = new HashMap<String, String>();

    static {
        TYPES.put("html", "text/html; charset=utf-8");
        TYPES.put("css", "text/css; charset=utf-8");
        TYPES.put("js", "text/javascript; charset=utf-8");
        TYPES.put("mjs", "text/javascript; charset=utf-8");
        TYPES.put("json", "application/json; charset=utf-8");
        TYPES.put("svg", "image/svg+xml");
        TYPES.put("png", "image/png");
        TYPES.put("jpg", "image/jpeg");
        TYPES.put("ico", "image/x-icon");
        TYPES.put("txt", "text/plain; charset=utf-8");
    }

    private final Path root;

    public StaticHandler(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                send(ex, 405, "text/plain; charset=utf-8", "Method not allowed".getBytes("UTF-8"), false);
                return;
            }
            String path = ex.getRequestURI().getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) path = "/index.html";
            if (path.indexOf('\0') >= 0 || path.contains("\\")) {
                send(ex, 400, "text/plain; charset=utf-8", "Bad request".getBytes("UTF-8"), false);
                return;
            }
            Path file = root.resolve(path.substring(1)).normalize();
            boolean hidden = false;
            for (Path part : root.relativize(file)) {
                if (part.toString().startsWith(".")) hidden = true;
            }
            if (!file.startsWith(root) || hidden) {
                send(ex, 403, "text/plain; charset=utf-8", "Forbidden".getBytes("UTF-8"), false);
                return;
            }
            if (!Files.isRegularFile(file)) {
                // unknown path with no file extension: let the single-page app handle it
                send(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes("UTF-8"), false);
                return;
            }
            String name = file.getFileName().toString();
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
            String type = TYPES.containsKey(ext) ? TYPES.get(ext) : "application/octet-stream";
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            send(ex, 200, type, Files.readAllBytes(file), "HEAD".equals(method));
        } finally {
            ex.close();
        }
    }

    static void securityHeaders(HttpExchange ex) {
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("X-Frame-Options", "DENY");
        ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        ex.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                        + "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body, boolean headOnly) throws IOException {
        securityHeaders(ex);
        ex.getResponseHeaders().set("Content-Type", type);
        if (headOnly) {
            ex.getResponseHeaders().set("Content-Length", String.valueOf(body.length));
            ex.sendResponseHeaders(status, -1);
            return;
        }
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }
}
