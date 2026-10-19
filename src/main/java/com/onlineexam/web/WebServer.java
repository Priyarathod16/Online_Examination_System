package com.onlineexam.web;

import com.onlineexam.dao.DBConnection;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Starts the website: static files from the web/ folder at "/" and the JSON API at "/api".
 *
 * Options (command line or system properties / environment):
 *   --port 8080         (-Dexam.port / EXAM_PORT)
 *   --host 127.0.0.1    (-Dexam.host / EXAM_HOST)  use 0.0.0.0 to allow other computers to connect
 *   --web web           (-Dexam.web  / EXAM_WEB)   folder that contains index.html
 *   --secure-cookie     (-Dexam.secureCookie=true) mark the session cookie Secure (use behind HTTPS)
 */
public final class WebServer {
    private final HttpServer server;
    private final SessionStore sessions = new SessionStore();

    public WebServer(String host, int port, Path webRoot, boolean secureCookie) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/api", new ApiHandler(sessions, secureCookie));
        server.createContext("/", new StaticHandler(webRoot));
        ExecutorService pool = Executors.newFixedThreadPool(24);
        server.setExecutor(pool);
    }

    public void start() { server.start(); }

    public void stop() { server.stop(0); }

    /** Exposed for tests. */
    public SessionStore getSessions() { return sessions; }

    public int getPort() { return server.getAddress().getPort(); }

    private static String option(String[] args, String flag, String property, String env, String fallback) {
        for (int i = 0; i + 1 < args.length; i++) if (flag.equals(args[i])) return args[i + 1];
        String p = System.getProperty(property);
        if (p != null && !p.isEmpty()) return p;
        String e = System.getenv(env);
        return e != null && !e.isEmpty() ? e : fallback;
    }

    public static void main(String[] args) throws Exception {
        String host = option(args, "--host", "exam.host", "EXAM_HOST", "127.0.0.1");
        int port = Integer.parseInt(option(args, "--port", "exam.port", "EXAM_PORT", "8080"));
        Path web = Paths.get(option(args, "--web", "exam.web", "EXAM_WEB", "web"));
        boolean secure = Boolean.parseBoolean(System.getProperty("exam.secureCookie", "false"));
        for (String a : args) if ("--secure-cookie".equals(a)) secure = true;
        if (!Files.isRegularFile(web.resolve("index.html"))) {
            System.err.println("Cannot find " + web.toAbsolutePath().resolve("index.html")
                    + " - run from the project folder or pass --web <folder>.");
            System.exit(1);
        }
        WebServer s = new WebServer(host, port, web, secure);
        s.start();
        System.out.println("Online Examination System website running.");
        System.out.println("Open  http://" + ("0.0.0.0".equals(host) ? "localhost" : host) + ":" + s.getPort() + "/index.html");
        String problem = DBConnection.checkAvailability();
        System.out.println(problem == null ? "Database: connection OK" : "WARNING - database not ready: " + problem);
        System.out.println("Press Ctrl+C to stop.");
    }
}
