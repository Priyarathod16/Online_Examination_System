package com.onlineexam.util;

import com.onlineexam.exception.DatabaseConfigException;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Loads database settings from a file that is NOT committed to version control.
 * Lookup order: environment variables EXAM_DB_URL / EXAM_DB_USER / EXAM_DB_PASSWORD, then the file named by
 * -Dexam.config=..., environment variable EXAM_DB_CONFIG, ./config/db.properties, ./db.properties.
 */
public final class AppConfig {
    /** Used when no administrator registration key is configured (change it - see config/db.properties.example). */
    public static final String DEFAULT_ADMIN_KEY = "EXAM-ADMIN-2026";

    private static volatile AppConfig instance;
    private static volatile String adminKeyOverride;

    private final String url;
    private final String user;
    private final String password;

    private AppConfig(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    public String getUrl() { return url; }

    public String getUser() { return user; }

    public String getPassword() { return password; }

    public static AppConfig get() throws DatabaseConfigException {
        AppConfig local = instance;
        if (local == null) {
            synchronized (AppConfig.class) {
                if (instance == null) {
                    instance = load();
                }
                local = instance;
            }
        }
        return local;
    }

    /** Replaces the configuration (used by tests). */
    public static void override(String url, String user, String password) {
        instance = new AppConfig(url, user, password);
    }

    /** Replaces the administrator registration key (used by tests); null restores normal lookup. */
    public static void overrideAdminKey(String key) { adminKeyOverride = key; }

    /**
     * The secret that must be typed to create an administrator account. Lookup order: environment variable
     * EXAM_ADMIN_KEY, -Dexam.admin.key=..., "admin.registration.key" in the same properties file as the database
     * settings, then {@link #DEFAULT_ADMIN_KEY}. It does not need the database settings to be valid.
     */
    public static String adminRegistrationKey() {
        String override = adminKeyOverride;
        if (override != null) return override;
        String env = System.getenv("EXAM_ADMIN_KEY");
        if (env != null && !env.trim().isEmpty()) return env.trim();
        String prop = System.getProperty("exam.admin.key");
        if (prop != null && !prop.trim().isEmpty()) return prop.trim();
        List<Path> candidates = new ArrayList<Path>();
        String file = System.getProperty("exam.config");
        if (file != null && !file.trim().isEmpty()) candidates.add(Paths.get(file.trim()));
        String envFile = System.getenv("EXAM_DB_CONFIG");
        if (envFile != null && !envFile.trim().isEmpty()) candidates.add(Paths.get(envFile.trim()));
        candidates.add(Paths.get("config", "db.properties"));
        candidates.add(Paths.get("db.properties"));
        for (Path path : candidates) {
            if (!Files.isRegularFile(path)) continue;
            Properties props = new Properties();
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                props.load(reader);
            } catch (IOException e) {
                continue;
            }
            String key = props.getProperty("admin.registration.key");
            if (key != null && !key.trim().isEmpty()) return key.trim();
        }
        return DEFAULT_ADMIN_KEY;
    }

    /** Forces the configuration to be re-read on next use. */
    public static void reset() { instance = null; }

    private static AppConfig load() throws DatabaseConfigException {
        String envUrl = System.getenv("EXAM_DB_URL");
        if (envUrl != null && !envUrl.trim().isEmpty()) {
            String u = System.getenv("EXAM_DB_USER");
            String p = System.getenv("EXAM_DB_PASSWORD");
            return new AppConfig(envUrl.trim(), u == null ? "root" : u, p == null ? "" : p);
        }
        List<Path> candidates = new ArrayList<Path>();
        String prop = System.getProperty("exam.config");
        if (prop != null && !prop.trim().isEmpty()) candidates.add(Paths.get(prop.trim()));
        String env = System.getenv("EXAM_DB_CONFIG");
        if (env != null && !env.trim().isEmpty()) candidates.add(Paths.get(env.trim()));
        candidates.add(Paths.get("config", "db.properties"));
        candidates.add(Paths.get("db.properties"));

        for (Path path : candidates) {
            if (Files.isRegularFile(path)) {
                Properties props = new Properties();
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    props.load(reader);
                } catch (IOException e) {
                    throw new DatabaseConfigException("The database configuration file could not be read: " + path, e);
                }
                String url = props.getProperty("db.url");
                if (url == null || url.trim().isEmpty()) {
                    throw new DatabaseConfigException("db.url is missing in " + path);
                }
                return new AppConfig(url.trim(), props.getProperty("db.user", "root").trim(),
                        props.getProperty("db.password", ""));
            }
        }
        throw new DatabaseConfigException("Database configuration not found. Copy config/db.properties.example "
                + "to config/db.properties and enter your MySQL settings.");
    }
}
