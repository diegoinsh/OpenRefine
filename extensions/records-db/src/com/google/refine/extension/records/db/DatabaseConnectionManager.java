/*
 * Database Connection Manager
 */

package com.google.refine.extension.records.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.records.db.model.SchemaProfile;

/**
 * Manages database connections
 */
public class DatabaseConnectionManager {

    private static final Logger logger = LoggerFactory.getLogger("DatabaseConnectionManager");

    /**
     * Get a database connection from SchemaProfile
     */
    public static Connection getConnection(SchemaProfile profile) throws SQLException {
        if (profile == null) {
            throw new SQLException("SchemaProfile is null");
        }

        String dialect = profile.getDialect();
        String host = profile.getHost();
        int port = profile.getPort();
        String database = profile.getDatabase();
        String username = profile.getUsername();
        String password = profile.getPassword();

        String url = buildConnectionUrl(dialect, host, port, database);

        if (logger.isDebugEnabled()) {
            logger.debug("Connecting to database: {} (dialect: {})", url, dialect);
        }

        try {
            // Ensure JDBC driver is loaded similar to database extension
            ensureDriverLoaded(dialect);
            DriverManager.setLoginTimeout(10);

            Connection conn = DriverManager.getConnection(url, username, password);
            if (logger.isDebugEnabled()) {
                logger.debug("Database connection established");
            }
            return conn;
        } catch (SQLException e) {
            // MySQL specific: auto-handle "Public Key Retrieval is not allowed"
            if ("mysql".equalsIgnoreCase(dialect) && isPublicKeyRetrievalNotAllowed(e)) {
                String retryUrl = addOrUpdateParam(url, "allowPublicKeyRetrieval", "true");
                try {
                    if (!retryUrl.equals(url)) {
                        logger.warn("MySQL 'Public Key Retrieval is not allowed' - retrying with allowPublicKeyRetrieval=true");
                        Connection conn = DriverManager.getConnection(retryUrl, username, password);
                        if (logger.isDebugEnabled()) {
                            logger.debug("Database connection established after enabling allowPublicKeyRetrieval");
                        }
                        return conn;
                    }
                } catch (SQLException e2) {
                    logger.error("Retry with allowPublicKeyRetrieval=true also failed: {}", e2.getMessage());
                    throw e2;
                }
            }

            logger.error("Failed to connect to database: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Ensure the JDBC driver for the given dialect is loaded
     */
    private static void ensureDriverLoaded(String dialect) throws SQLException {
        if (dialect == null) {
            return;
        }
        try {
            switch (dialect.toLowerCase()) {
                case "mysql":
                    Class.forName("com.mysql.cj.jdbc.Driver");
                    break;
                case "postgresql":
                    Class.forName("org.postgresql.Driver");
                    break;
                case "mariadb":
                    Class.forName("org.mariadb.jdbc.Driver");
                    break;
                case "sqlite":
                    Class.forName("org.sqlite.JDBC");
                    break;
                default:
                    // no-op
            }
        } catch (ClassNotFoundException e) {
            throw new SQLException("JDBC driver not found for dialect: " + dialect, e);
        }
    }

    /**
     * Detects MySQL "Public Key Retrieval is not allowed" error
     */
    private static boolean isPublicKeyRetrievalNotAllowed(SQLException e) {
        String msg = e != null ? e.getMessage() : null;
        return msg != null && msg.toLowerCase().contains("public key retrieval is not allowed");
    }

    /**
     * Add or update a query parameter in a JDBC URL
     */
    private static String addOrUpdateParam(String url, String key, String value) {
        if (url == null || key == null || key.isEmpty()) {
            return url;
        }
        int q = url.indexOf('?');
        if (q < 0) {
            return url + "?" + key + "=" + value;
        }
        String base = url.substring(0, q);
        String query = url.substring(q + 1);
        String[] parts = query.isEmpty() ? new String[0] : query.split("&");
        StringBuilder sb = new StringBuilder();
        boolean updated = false;
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) continue;
            int eq = p.indexOf('=');
            String k = eq >= 0 ? p.substring(0, eq) : p;
            if (k.equals(key)) {
                p = key + "=" + value;
                updated = true;
            }
            if (sb.length() > 0) sb.append('&');
            sb.append(p);
        }
        if (!updated) {
            if (sb.length() > 0) sb.append('&');
            sb.append(key).append('=').append(value);
        }
        return base + "?" + sb.toString();
    }


    /**
     * Build JDBC connection URL based on dialect
     */
    private static String buildConnectionUrl(String dialect, String host, int port, String database) {
        switch (dialect.toLowerCase()) {
            case "mysql":
                return String.format("jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC", host, port, database);
            case "postgresql":
                return String.format("jdbc:postgresql://%s:%d/%s", host, port, database);
            case "mariadb":
                return String.format("jdbc:mariadb://%s:%d/%s", host, port, database);
            case "sqlite":
                return String.format("jdbc:sqlite:%s", database);
            default:
                throw new IllegalArgumentException("Unsupported dialect: " + dialect);
        }
    }

    /**
     * Close a database connection
     */
    public static void closeConnection(Connection conn) {
        if (conn != null) {
            try {
                conn.close();
                if (logger.isDebugEnabled()) {
                    logger.debug("Database connection closed");
                }
            } catch (SQLException e) {
                logger.warn("Error closing database connection: {}", e.getMessage());
            }
        }
    }

    /**
     * Test database connection
     */
    public static boolean testConnection(SchemaProfile profile) {
        Connection conn = null;
        try {
            conn = getConnection(profile);
            return true;
        } catch (SQLException e) {
            logger.warn("Connection test failed: {}", e.getMessage());
            return false;
        } finally {
            closeConnection(conn);
        }
    }
}

