package com.onlineexam.web;

import com.onlineexam.service.UserSession;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Server-side sessions: an unguessable random token (kept in an HttpOnly cookie) maps to a {@link UserSession}. */
public final class SessionStore {
    private static final long IDLE_MILLIS = 3L * 60 * 60 * 1000;      // 3 hours without any request
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final class Entry {
        final UserSession session;
        volatile long lastAccess;

        Entry(UserSession session) {
            this.session = session;
            this.lastAccess = System.currentTimeMillis();
        }
    }

    private final Map<String, Entry> sessions = new ConcurrentHashMap<String, Entry>();
    private volatile long lastPurge = System.currentTimeMillis();

    public String create(UserSession session) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        StringBuilder token = new StringBuilder(64);
        for (byte b : bytes) token.append(String.format("%02x", b & 0xff));
        sessions.put(token.toString(), new Entry(session));
        return token.toString();
    }

    /** Returns the session for a token and refreshes its idle timer, or null if unknown/expired. */
    public UserSession get(String token) {
        purgeIfDue();
        if (token == null) return null;
        Entry e = sessions.get(token);
        if (e == null) return null;
        long now = System.currentTimeMillis();
        if (now - e.lastAccess > IDLE_MILLIS) {
            sessions.remove(token);
            return null;
        }
        e.lastAccess = now;
        return e.session;
    }

    public void remove(String token) {
        if (token != null) sessions.remove(token);
    }

    private void purgeIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastPurge < 60000) return;
        lastPurge = now;
        for (Iterator<Map.Entry<String, Entry>> it = sessions.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue().lastAccess > IDLE_MILLIS) it.remove();
        }
    }
}
