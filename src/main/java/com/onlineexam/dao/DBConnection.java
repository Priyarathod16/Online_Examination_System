package com.onlineexam.dao;

import com.onlineexam.exception.DatabaseConfigException;
import com.onlineexam.util.AppConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Creates JDBC connections from the external configuration (see {@link AppConfig}). */
public final class DBConnection {
    private static volatile boolean driverLoaded;

    private DBConnection() { }

    public static Connection getConnection() throws SQLException {
        AppConfig config = AppConfig.get();
        loadDriver();
        DriverManager.setLoginTimeout(5);
        return DriverManager.getConnection(config.getUrl(), config.getUser(), config.getPassword());
    }

    private static void loadDriver() throws DatabaseConfigException {
        if (driverLoaded) return;
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            driverLoaded = true;
        } catch (ClassNotFoundException e) {
            throw new DatabaseConfigException("The MySQL JDBC driver (mysql-connector-j) was not found. "
                    + "Place the jar in the lib/ folder or add the Maven dependency (see README).", e);
        }
    }

    /** Returns null when the database is reachable, otherwise a user-safe description of the problem. */
    public static String checkAvailability() {
        try (Connection c = getConnection()) {
            return c.isValid(3) ? null : "The database connection is not valid.";
        } catch (DatabaseConfigException e) {
            return e.getMessage();
        } catch (SQLException e) {
            return "Cannot connect to MySQL. Check that the server is running and that config/db.properties is correct.";
        }
    }
}
