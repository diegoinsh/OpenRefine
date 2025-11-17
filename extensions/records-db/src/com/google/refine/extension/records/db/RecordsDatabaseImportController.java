/*
 * Records Database Import Controller
 *
 * Implements the ImportingController interface for Catalog Mode database import
 */

package com.google.refine.extension.records.db;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.RefineServlet;
import com.google.refine.commands.HttpUtilities;
import com.google.refine.extension.records.db.model.SchemaProfile;
import com.google.refine.importing.ImportingController;
import com.google.refine.util.JSONUtilities;
import com.google.refine.util.ParsingUtilities;

public class RecordsDatabaseImportController implements ImportingController {

    private static final Logger logger = LoggerFactory.getLogger("RecordsDatabaseImportController");
    protected RefineServlet servlet;
    public static int DEFAULT_PREVIEW_LIMIT = 100;

    @Override
    public void init(RefineServlet servlet) {
        this.servlet = servlet;
        logger.info("RecordsDatabaseImportController initialized");
    }

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        HttpUtilities.respond(response, "error", "GET not implemented");
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        if (logger.isDebugEnabled()) {
            logger.debug("doPost Query String::{}", request.getQueryString());
        }
        response.setCharacterEncoding("UTF-8");
        Map<String, String> parameters = ParsingUtilities.parseParameters(request);

        String subCommand = parameters.get("subCommand");

        if (logger.isDebugEnabled()) {
            logger.info("doPost::subCommand::{}", subCommand);
        }

        if ("initialize-ui".equals(subCommand)) {
            doInitializeUI(request, response, parameters);
        } else if ("parse-preview".equals(subCommand)) {
            doParsePreview(request, response, parameters);
        } else if ("create-project".equals(subCommand)) {
            doCreateProject(request, response, parameters);
        } else if ("test-connection".equals(subCommand)) {
            doTestConnection(request, response, parameters);
        } else if ("list-columns".equals(subCommand)) {
            doListColumns(request, response, parameters);
        } else if ("list-tables".equals(subCommand)) {
            doListTables(request, response, parameters);
        } else if ("list-dictionary".equals(subCommand)) {
            doListDictionary(request, response, parameters);
        } else if ("distinct-values".equals(subCommand)) {
            doListDistinctValues(request, response, parameters);
        } else {
            HttpUtilities.respond(response, "error", "Unknown subCommand: " + subCommand);
        }
    }

    /**
     * Initialize UI - returns available modes, presets, and dialects
     */
    private void doInitializeUI(HttpServletRequest request, HttpServletResponse response,
            Map<String, String> parameters) throws ServletException, IOException {
        if (logger.isDebugEnabled()) {
            logger.debug("::doInitializeUI::");
        }

        try {
            ObjectNode result = ParsingUtilities.mapper.createObjectNode();
            JSONUtilities.safePut(result, "status", "ok");

            // Add available modes
            JSONUtilities.safePut(result, "modes", PresetManager.getAvailableModes());

            // Add available presets
            JSONUtilities.safePut(result, "presets", PresetManager.getAvailablePresets());

            // Add available database dialects
            JSONUtilities.safePut(result, "dialects", PresetManager.getAvailableDialects());

            // Add default options
            ObjectNode options = ParsingUtilities.mapper.createObjectNode();
            JSONUtilities.safePut(options, "mode", "catalog");
            JSONUtilities.safePut(options, "preset", "kubao");
            JSONUtilities.safePut(options, "dialect", "mysql");
            JSONUtilities.safePut(options, "pageSize", DEFAULT_PREVIEW_LIMIT);
            JSONUtilities.safePut(result, "options", options);

            if (logger.isDebugEnabled()) {
                logger.debug("doInitializeUI:::{}", result.toString());
            }

            HttpUtilities.respond(response, result.toString());
        } catch (Exception e) {
            logger.error("Error in doInitializeUI", e);
            HttpUtilities.respond(response, "error", e.getMessage());
        }
    }

    /**
     * Parse preview - returns sample data for preview
     */
    private void doParsePreview(HttpServletRequest request, HttpServletResponse response,
            Map<String, String> parameters) throws ServletException, IOException {
        if (logger.isDebugEnabled()) {
            logger.debug("::doParsePreview::");
        }

        java.sql.Connection conn = null;
        try {
            // Get Schema Profile from request
            String schemaProfileJson = parameters.get("schemaProfile");
            if (schemaProfileJson == null || schemaProfileJson.isEmpty()) {
                HttpUtilities.respond(response, "error", "schemaProfile parameter is required");
                return;
            }

            // Parse Schema Profile
            SchemaProfile profile = SchemaProfileParser.parse(schemaProfileJson);

            // Validate Schema Profile
            List<String> errors = SchemaProfileValidator.validate(profile);
            if (!errors.isEmpty()) {
                ObjectNode result = ParsingUtilities.mapper.createObjectNode();
                JSONUtilities.safePut(result, "status", "error");
                JSONUtilities.safePut(result, "message", "Schema Profile validation failed");
                ArrayNode errorArray = ParsingUtilities.mapper.createArrayNode();
                for (String error : errors) {
                    errorArray.add(error);
                }
                result.set("errors", errorArray);
                HttpUtilities.respond(response, result.toString());
                return;
            }

            // Get database connection
            conn = DatabaseConnectionManager.getConnection(profile);

            // Execute query
            ObjectNode result = QueryExecutor.executeQuery(conn, profile, null, 0, DEFAULT_PREVIEW_LIMIT);

            if (logger.isDebugEnabled()) {
                logger.debug("doParsePreview:::{}", result.toString());
            }

            HttpUtilities.respond(response, result.toString());
        } catch (Exception e) {
            logger.error("Error in doParsePreview", e);
            ObjectNode result = ParsingUtilities.mapper.createObjectNode();
            JSONUtilities.safePut(result, "status", "error");
            JSONUtilities.safePut(result, "message", e.getMessage());
            HttpUtilities.respond(response, result.toString());
        } finally {
            DatabaseConnectionManager.closeConnection(conn);
        }
    }

    /**
     * Test database connection - validates only connection parameters
     */
    private void doTestConnection(HttpServletRequest request, HttpServletResponse response,
            Map<String, String> parameters) throws ServletException, IOException {
        if (logger.isDebugEnabled()) {
            logger.debug("::doTestConnection::");
        }

        try {
            String schemaProfileJson = parameters.get("schemaProfile");
            if (schemaProfileJson == null || schemaProfileJson.isEmpty()) {
                HttpUtilities.respond(response, "error", "schemaProfile parameter is required");
                return;
            }

            // Parse profile but do NOT run full schema validation here
            SchemaProfile profile = SchemaProfileParser.parse(schemaProfileJson);

            // Only attempt a connection
            boolean ok = DatabaseConnectionManager.testConnection(profile);

            ObjectNode result = ParsingUtilities.mapper.createObjectNode();
            JSONUtilities.safePut(result, "status", ok ? "ok" : "error");
            if (!ok) {
                JSONUtilities.safePut(result, "message", "Connection failed");
            }
            HttpUtilities.respond(response, result.toString());
        } catch (Exception e) {
            logger.error("Error in doTestConnection", e);
            ObjectNode result = ParsingUtilities.mapper.createObjectNode();
            JSONUtilities.safePut(result, "status", "error");
            JSONUtilities.safePut(result, "message", e.getMessage());
            HttpUtilities.respond(response, result.toString());
        }
    }
    /**
     * List tables in the connected database using only connection info
     */
    private void doListTables(HttpServletRequest request, HttpServletResponse response,
            Map<String, String> parameters) throws ServletException, IOException {
        if (logger.isDebugEnabled()) {
            logger.debug("::doListTables::");
        }

        java.sql.Connection conn = null;
        java.sql.Statement stmt = null;
        java.sql.ResultSet rs = null;
        try {
            String schemaProfileJson = parameters.get("schemaProfile");
            if (schemaProfileJson == null || schemaProfileJson.isEmpty()) {
                HttpUtilities.respond(response, "error", "schemaProfile parameter is required");
                return;
            }

            SchemaProfile profile = SchemaProfileParser.parse(schemaProfileJson);
            String dialect = profile.getDialect() != null ? profile.getDialect().toLowerCase() : null;
            if (dialect == null || dialect.isEmpty()) {
                HttpUtilities.respond(response, "error", "Database dialect is required");
                return;
            }
            // Validate minimal connection fields (sqlite can skip host/port/username)
            if (!"sqlite".equals(dialect)) {
                if (profile.getHost() == null || profile.getHost().isEmpty()) {
                    HttpUtilities.respond(response, "error", "Database host is required");
                    return;
                }
                if (profile.getPort() <= 0) {
                    HttpUtilities.respond(response, "error", "Database port must be greater than 0");
                    return;
                }
                if (profile.getDatabase() == null || profile.getDatabase().isEmpty()) {
                    HttpUtilities.respond(response, "error", "Database name is required");
                    return;
                }
                if (profile.getUsername() == null || profile.getUsername().isEmpty()) {
                    HttpUtilities.respond(response, "error", "Database username is required");
                    return;
                }
            }

            conn = DatabaseConnectionManager.getConnection(profile);

            ArrayNode tables = ParsingUtilities.mapper.createArrayNode();
            try {
                java.sql.DatabaseMetaData meta = conn.getMetaData();
                String[] types = new String[]{"TABLE"};
                java.sql.ResultSet trs;
                if ("mysql".equals(dialect) || "mariadb".equals(dialect)) {
                    trs = meta.getTables(profile.getDatabase(), null, "%", types);
                } else if ("postgresql".equals(dialect)) {
                    trs = meta.getTables(null, "public", "%", types);
                } else {
                    trs = meta.getTables(null, null, "%", types);
                }
                while (trs.next()) {
                    String name = trs.getString("TABLE_NAME");
                    if (name != null && !name.isEmpty()) {
                        String schema = null;
                        String remarks = null;
                        try { schema = trs.getString("TABLE_SCHEM"); } catch (Exception ignore) {}
                        try { remarks = trs.getString("REMARKS"); } catch (Exception ignore) {}
                        String label = (schema != null && !schema.isEmpty() ? (schema + ".") : "") + name;
                        if (remarks != null && !remarks.isEmpty() && !remarks.equalsIgnoreCase(name)) {
                            label = label + " (" + remarks + ")";
                        }
                        ObjectNode t = ParsingUtilities.mapper.createObjectNode();
                        JSONUtilities.safePut(t, "name", name);
                        if (schema != null) JSONUtilities.safePut(t, "schema", schema);
                        JSONUtilities.safePut(t, "label", label);
                        tables.add(t);
                    }
                }
                try { trs.close(); } catch (Exception ignore) {}
            } catch (Exception metaEx) {
                logger.warn("MetaData list tables fallback due to: {}", metaEx.getMessage());
                // Fallback to information_schema/sqlite_master
                String sql;
                if ("mysql".equals(dialect) || "mariadb".equals(dialect)) {
                    String db = profile.getDatabase().replace("'", "''");
                    sql = "SELECT table_name, table_comment FROM information_schema.tables WHERE table_schema='" + db + "' ORDER BY table_name";
                } else if ("postgresql".equals(dialect)) {
                    sql = "SELECT table_name FROM information_schema.tables WHERE table_schema IN (current_schema(), 'public') ORDER BY table_name";
                } else { // sqlite
                    sql = "SELECT name AS table_name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name";
                }
                stmt = conn.createStatement();
                rs = stmt.executeQuery(sql);
                while (rs.next()) {
                    String name = rs.getString(1);
                    if (name != null && !name.isEmpty()) {
                        ObjectNode t = ParsingUtilities.mapper.createObjectNode();
                        JSONUtilities.safePut(t, "name", name);
                        if ("mysql".equals(dialect) || "mariadb".equals(dialect)) {
                            String remarks = null;
                            try { remarks = rs.getString(2); } catch (Exception ignore) {}
                            String label = name;
                            if (remarks != null && !remarks.isEmpty() && !remarks.equalsIgnoreCase(name)) {
                                label = name + " (" + remarks + ")";
                            }
                            JSONUtilities.safePut(t, "label", label);
                        } else {
                            JSONUtilities.safePut(t, "label", name);
                        }
                        tables.add(t);
                    }
                }
            }

            ObjectNode result = ParsingUtilities.mapper.createObjectNode();
            JSONUtilities.safePut(result, "status", "ok");
            result.set("tables", tables);
            HttpUtilities.respond(response, result.toString());
        } catch (Exception e) {
            logger.error("Error in doListTables", e);
            ObjectNode result = ParsingUtilities.mapper.createObjectNode();
            JSONUtilities.safePut(result, "status", "error");
            JSONUtilities.safePut(result, "message", e.getMessage());
            HttpUtilities.respond(response, result.toString());
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignore) {}
            try { if (stmt != null) stmt.close(); } catch (Exception ignore) {}
            DatabaseConnectionManager.closeConnection(conn);
        }
    }

    /**
     * List dictionary fields (code/name) from a given dictionary table
     * Parameters: dictTable, codeColumn, nameColumn, bindColumn (optional), bindValue (optional)
     */
    private void doListDictionary(HttpServletRequest request, HttpServletResponse response,
            Map<String, String> parameters) throws ServletException, IOException {
        if (logger.isDebugEnabled()) {
            logger.debug("::doListDictionary::");
        }

        java.sql.Connection conn = null;
        java.sql.Statement stmt = null;
        java.sql.ResultSet rs = null;
        try {
            String schemaProfileJson = parameters.get("schemaProfile");
            if (schemaProfileJson == null || schemaProfileJson.isEmpty()) {
                HttpUtilities.respond(response, "error", "schemaProfile parameter is required");
                return;
            }
            SchemaProfile profile = SchemaProfileParser.parse(schemaProfileJson);
            String dialect = profile.getDialect() != null ? profile.getDialect().toLowerCase() : null;
            if (dialect == null || dialect.isEmpty()) {
                HttpUtilities.respond(response, "error", "Database dialect is required");
                return;
            }

            String dictTable = parameters.get("dictTable");
            String codeColumn = parameters.get("codeColumn");
            String nameColumn = parameters.get("nameColumn");
            String bindColumn = parameters.get("bindColumn"); // e.g., bind_table_id
            String bindValue = parameters.get("bindValue");   // e.g., selected bind_table_id

            if (dictTable == null || dictTable.isEmpty() || codeColumn == null || codeColumn.isEmpty() || nameColumn == null || nameColumn.isEmpty()) {
                HttpUtilities.respond(response, "error", "dictTable, codeColumn and nameColumn are required");
                return;
            }

            conn = DatabaseConnectionManager.getConnection(profile);

            StringBuilder sql = new StringBuilder();
            sql.append("SELECT ")
               .append(QueryBuilder.escapeColumnName(codeColumn, dialect)).append(" AS code, ")
               .append(QueryBuilder.escapeColumnName(nameColumn, dialect)).append(" AS name ")
               .append("FROM ").append(QueryBuilder.escapeTableName(dictTable, dialect));
            if (bindColumn != null && !bindColumn.isEmpty() && bindValue != null && !bindValue.isEmpty()) {
                // simple numeric detection; otherwise quote as string
                String literal;
                try { Double.parseDouble(bindValue); literal = bindValue; } catch (Exception ex) { literal = "'" + bindValue.replace("'", "''") + "'"; }
                sql.append(" WHERE ").append(QueryBuilder.escapeColumnName(bindColumn, dialect)).append(" = ").append(literal);
            }
            sql.append(" ORDER BY ").append(QueryBuilder.escapeColumnName(codeColumn, dialect));

            stmt = conn.createStatement();
            rs = stmt.executeQuery(sql.toString());

            ArrayNode items = ParsingUtilities.mapper.createArrayNode();
            while (rs.next()) {
                ObjectNode item = ParsingUtilities.mapper.createObjectNode();
                JSONUtilities.safePut(item, "code", rs.getString(1));
                JSONUtilities.safePut(item, "name", rs.getString(2));
                items.add(item);
            }

            ObjectNode result = ParsingUtilities.mapper.createObjectNode();
            JSONUtilities.safePut(result, "status", "ok");
            result.set("items", items);
            HttpUtilities.respond(response, result.toString());
        } catch (Exception e) {
            logger.error("Error in doListDictionary", e);
            ObjectNode result = ParsingUtilities.mapper.createObjectNode();
            JSONUtilities.safePut(result, "status", "error");
            JSONUtilities.safePut(result, "message", e.getMessage());
            HttpUtilities.respond(response, result.toString());
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignore) {}
            try { if (stmt != null) stmt.close(); } catch (Exception ignore) {}
            DatabaseConnectionManager.closeConnection(conn);
        }
    }


    /**
     * List distinct values for a given table and field
     * Params: schemaProfile, source (main|join), field, joinTable (if source=join), limit (optional)
     */
    private void doListDistinctValues(HttpServletRequest request, HttpServletResponse response,
                                     Map<String, String> parameters) throws ServletException, IOException {
        if (logger.isDebugEnabled()) {
            logger.debug("::doListDistinctValues::");
        }

        java.sql.Connection conn = null;
        java.sql.Statement stmt = null;
        java.sql.ResultSet rs = null;
        try {
            String schemaProfileJson = parameters.get("schemaProfile");
            if (schemaProfileJson == null || schemaProfileJson.isEmpty()) {
                HttpUtilities.respond(response, "error", "schemaProfile parameter is required");
                return;
            }
            SchemaProfile profile = SchemaProfileParser.parse(schemaProfileJson);
            String dialect = profile.getDialect() != null ? profile.getDialect().toLowerCase() : null;
            if (dialect == null || dialect.isEmpty()) {
                HttpUtilities.respond(response, "error", "Database dialect is required");
                return;
            }
            if (!"sqlite".equals(dialect)) {
                if (profile.getHost() == null || profile.getHost().isEmpty()) {
                    HttpUtilities.respond(response, "error", "Database host is required");
                    return;
                }
                if (profile.getPort() <= 0) {
                    HttpUtilities.respond(response, "error", "Database port must be greater than 0");
                    return;
                }
                if (profile.getDatabase() == null || profile.getDatabase().isEmpty()) {
                    HttpUtilities.respond(response, "error", "Database name is required");
                    return;
                }
                if (profile.getUsername() == null || profile.getUsername().isEmpty()) {
                    HttpUtilities.respond(response, "error", "Database username is required");
                    return;
                }
            }

            String source = parameters.get("source");
            String field = parameters.get("field");
            String joinTable = parameters.get("joinTable");
            String limitStr = parameters.get("limit");
            int limit = 200; // sensible default
            try { if (limitStr != null && !limitStr.isEmpty()) limit = Integer.parseInt(limitStr); } catch (Exception ignore) {}

            if (field == null || field.isEmpty()) {
                HttpUtilities.respond(response, "error", "field parameter is required");
                return;
            }

            String tableName = null;
            if ("join".equalsIgnoreCase(source)) {
                tableName = (joinTable != null && !joinTable.isEmpty()) ? joinTable : null;
            } else {
                tableName = profile.getMainTable();
            }
            if (tableName == null || tableName.isEmpty()) {
                HttpUtilities.respond(response, "error", "Table name is required");
                return;
            }

            conn = DatabaseConnectionManager.getConnection(profile);

            String sql;
            if ("join".equalsIgnoreCase(source)) {
                // For join-source distinct values, query directly from the join table without applying profile filters
                String col = QueryBuilder.escapeColumnName(field, dialect);
                String from = QueryBuilder.escapeTableName(tableName, dialect);
                sql = "SELECT DISTINCT " + col + " AS v FROM " + from +
                        " WHERE " + col + " IS NOT NULL ORDER BY " + col + " LIMIT " + limit;
            } else {
                // For main-source distinct values (including JSON flatten), honor all filters defined in the profile
                sql = QueryBuilder.buildDistinctValuesQuery(profile, source, field, limit);
            }

            stmt = conn.createStatement();
            rs = stmt.executeQuery(sql);

            ArrayNode values = ParsingUtilities.mapper.createArrayNode();
            while (rs.next()) {
                String v = rs.getString(1);
                if (v != null) {
                    values.add(v);
                }
            }

            ObjectNode result = ParsingUtilities.mapper.createObjectNode();
            JSONUtilities.safePut(result, "status", "ok");
            result.set("values", values);
            HttpUtilities.respond(response, result.toString());
        } catch (Exception e) {
            logger.error("Error in doListDistinctValues", e);
            ObjectNode result = ParsingUtilities.mapper.createObjectNode();
            JSONUtilities.safePut(result, "status", "error");
            JSONUtilities.safePut(result, "message", e.getMessage());
            HttpUtilities.respond(response, result.toString());
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignore) {}
            try { if (stmt != null) stmt.close(); } catch (Exception ignore) {}
            DatabaseConnectionManager.closeConnection(conn);
        }
    }

    /**
     * List columns for a given main table using only connection info (no full schema validation)
     */
    private void doListColumns(HttpServletRequest request, HttpServletResponse response,
            Map<String, String> parameters) throws ServletException, IOException {
        if (logger.isDebugEnabled()) {
            logger.debug("::doListColumns::");
        }

        java.sql.Connection conn = null;
        java.sql.Statement stmt = null;
        java.sql.ResultSet rs = null;
        try {
            String schemaProfileJson = parameters.get("schemaProfile");
            if (schemaProfileJson == null || schemaProfileJson.isEmpty()) {
                HttpUtilities.respond(response, "error", "schemaProfile parameter is required");
                return;
            }

            // Parse profile but DO NOT run full validation; we only need connection + mainTable
            SchemaProfile profile = SchemaProfileParser.parse(schemaProfileJson);
            if (profile.getDialect() == null || profile.getDialect().isEmpty()) {
                HttpUtilities.respond(response, "error", "Database dialect is required");
                return;
            }
            if (profile.getHost() == null || profile.getHost().isEmpty()) {
                HttpUtilities.respond(response, "error", "Database host is required");
                return;
            }
            if (profile.getPort() <= 0) {
                HttpUtilities.respond(response, "error", "Database port must be greater than 0");
                return;
            }
            if (profile.getDatabase() == null || profile.getDatabase().isEmpty()) {
                HttpUtilities.respond(response, "error", "Database name is required");
                return;
            }
            if (profile.getUsername() == null || profile.getUsername().isEmpty()) {
                HttpUtilities.respond(response, "error", "Database username is required");
                return;
            }

            String mainTable = profile.getMainTable();
            if (mainTable == null || mainTable.isEmpty()) {
                HttpUtilities.respond(response, "error", "Main table is required for listing columns");
                return;
            }

            conn = DatabaseConnectionManager.getConnection(profile);

            String sql = "SELECT * FROM " + QueryBuilder.escapeTableName(mainTable, profile.getDialect()) + " LIMIT 1";
            stmt = conn.createStatement();
            rs = stmt.executeQuery(sql);
            java.sql.ResultSetMetaData meta = rs.getMetaData();

            ArrayNode columns = ParsingUtilities.mapper.createArrayNode();
            int colCount = meta.getColumnCount();
            for (int i = 1; i <= colCount; i++) {
                String name = meta.getColumnLabel(i);
                if (name == null || name.isEmpty()) {
                    name = meta.getColumnName(i);
                }
                String type = null;
                try {
                    type = meta.getColumnTypeName(i);
                } catch (Exception ignore) {
                    // some drivers may not support getColumnTypeName reliably
                }
                ObjectNode c = ParsingUtilities.mapper.createObjectNode();
                JSONUtilities.safePut(c, "name", name);
                if (type != null) {
                    JSONUtilities.safePut(c, "type", type);
                }
                columns.add(c);
            }

            ObjectNode result = ParsingUtilities.mapper.createObjectNode();
            JSONUtilities.safePut(result, "status", "ok");
            result.set("columns", columns);
            HttpUtilities.respond(response, result.toString());
        } catch (Exception e) {
            logger.error("Error in doListColumns", e);
            ObjectNode result = ParsingUtilities.mapper.createObjectNode();
            JSONUtilities.safePut(result, "status", "error");
            JSONUtilities.safePut(result, "message", e.getMessage());
            HttpUtilities.respond(response, result.toString());
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignore) {}
            try { if (stmt != null) stmt.close(); } catch (Exception ignore) {}
            DatabaseConnectionManager.closeConnection(conn);
        }
    }


    /**
     * Create project - creates an OpenRefine project from the imported data
     */
    private void doCreateProject(HttpServletRequest request, HttpServletResponse response,
            Map<String, String> parameters) throws ServletException, IOException {
        if (logger.isDebugEnabled()) {
            logger.debug("::doCreateProject::");
        }

        try {
            // Get parameters
            String schemaProfileJson = parameters.get("schemaProfile");
            String projectName = parameters.get("projectName");
            String maxRowsStr = parameters.get("maxRows");

            if (schemaProfileJson == null || schemaProfileJson.isEmpty()) {
                HttpUtilities.respond(response, "error", "schemaProfile parameter is required");
                return;
            }

            if (projectName == null || projectName.isEmpty()) {
                HttpUtilities.respond(response, "error", "projectName parameter is required");
                return;
            }

            int maxRows = DEFAULT_PREVIEW_LIMIT * 100; // Default to 10000 rows
            if (maxRowsStr != null && !maxRowsStr.isEmpty()) {
                try {
                    maxRows = Integer.parseInt(maxRowsStr);
                } catch (NumberFormatException e) {
                    logger.warn("Invalid maxRows value: {}", maxRowsStr);
                }
            }

            // Parse and validate Schema Profile
            SchemaProfile profile = SchemaProfileParser.parse(schemaProfileJson);
            List<String> errors = SchemaProfileValidator.validate(profile);
            if (!errors.isEmpty()) {
                ObjectNode result = ParsingUtilities.mapper.createObjectNode();
                JSONUtilities.safePut(result, "status", "error");
                JSONUtilities.safePut(result, "message", "Schema Profile validation failed");
                ArrayNode errorArray = ParsingUtilities.mapper.createArrayNode();
                for (String error : errors) {
                    errorArray.add(error);
                }
                result.set("errors", errorArray);
                HttpUtilities.respond(response, result.toString());
                return;
            }

            // Prepare and start project creation
            ObjectNode result = ProjectCreator.prepareProjectCreation(projectName, profile, maxRows);

            if (logger.isDebugEnabled()) {
                logger.debug("doCreateProject:::{}", result.toString());
            }

            HttpUtilities.respond(response, result.toString());
        } catch (Exception e) {
            logger.error("Error in doCreateProject", e);
            ObjectNode result = ParsingUtilities.mapper.createObjectNode();
            JSONUtilities.safePut(result, "status", "error");
            JSONUtilities.safePut(result, "message", e.getMessage());
            HttpUtilities.respond(response, result.toString());
        }
    }
}

