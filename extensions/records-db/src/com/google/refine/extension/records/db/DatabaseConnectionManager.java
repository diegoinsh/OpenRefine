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
            Connection conn = DriverManager.getConnection(url, username, password);
            if (logger.isDebugEnabled()) {
                logger.debug("Database connection established");
            }
            return conn;
        } catch (SQLException e) {
            logger.error("Failed to connect to database: {}", e.getMessage());
            throw e;
        }
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

