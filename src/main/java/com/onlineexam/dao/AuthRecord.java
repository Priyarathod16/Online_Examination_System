package com.onlineexam.dao;

/** Credential data read for login verification; never passed to the UI. */
public final class AuthRecord {
    private final int id;
    private final String displayName;
    private final String passwordHash;

    public AuthRecord(int id, String displayName, String passwordHash) {
        this.id = id;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
    }

    public int getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getPasswordHash() { return passwordHash; }
}
