/*
 * Query Executor
 */

package com.google.refine.extension.records.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.records.db.model.SchemaProfile;
import com.google.refine.util.JSONUtilities;
import com.google.refine.util.ParsingUtilities;

/**
 * Executes database queries and returns results
 */
public class QueryExecutor {

    private static final Logger logger = LoggerFactory.getLogger("QueryExecutor");

    /**
     * Execute a SELECT query and return results as JSON
     */
    public static ObjectNode executeQuery(Connection conn, SchemaProfile profile, 
            Map<String, Object> filters, int offset, int limit) throws Exception {
        
        if (logger.isDebugEnabled()) {
            logger.debug("Executing query with offset={}, limit={}", offset, limit);
        }

        // Build the query
        String query = QueryBuilder.buildSelectQueryWithPagination(profile, offset, limit);
        
        if (logger.isDebugEnabled()) {
            logger.debug("Query: {}", query);
        }

        Statement stmt = null;
        ResultSet rs = null;
        
        try {
            stmt = conn.createStatement();
            rs = stmt.executeQuery(query);
            
            // Get column metadata
            ResultSetMetaData metadata = rs.getMetaData();
            int columnCount = metadata.getColumnCount();
            
            // Build column list
            ArrayNode columns = ParsingUtilities.mapper.createArrayNode();
            for (int i = 1; i <= columnCount; i++) {
                ObjectNode col = ParsingUtilities.mapper.createObjectNode();
                JSONUtilities.safePut(col, "name", metadata.getColumnName(i));
                JSONUtilities.safePut(col, "type", metadata.getColumnTypeName(i));
                columns.add(col);
            }
            
            // Build rows
            ArrayNode rows = ParsingUtilities.mapper.createArrayNode();
            while (rs.next()) {
                ObjectNode row = ParsingUtilities.mapper.createObjectNode();
                
                for (int i = 1; i <= columnCount; i++) {
                    String columnName = metadata.getColumnName(i);
                    Object value = rs.getObject(i);

                    // Handle JSON fields
                    if (isJsonField(profile, columnName)) {
                        if (value != null) {
                            String jsonStr = value.toString();
                            try {
                                Object jsonValue = ParsingUtilities.mapper.readValue(jsonStr, Object.class);
                                row.set(columnName, ParsingUtilities.mapper.valueToTree(jsonValue));
                            } catch (Exception e) {
                                JSONUtilities.safePut(row, columnName, jsonStr);
                            }
                        } else {
                            JSONUtilities.safePut(row, columnName, (String) null);
                        }
                    } else {
                        // Convert value to appropriate type
                        if (value == null) {
                            JSONUtilities.safePut(row, columnName, (String) null);
                        } else if (value instanceof String) {
                            JSONUtilities.safePut(row, columnName, (String) value);
                        } else if (value instanceof Integer) {
                            JSONUtilities.safePut(row, columnName, ((Integer) value).longValue());
                        } else if (value instanceof Long) {
                            JSONUtilities.safePut(row, columnName, (Long) value);
                        } else if (value instanceof Double) {
                            JSONUtilities.safePut(row, columnName, (Double) value);
                        } else if (value instanceof Float) {
                            JSONUtilities.safePut(row, columnName, ((Float) value).doubleValue());
                        } else if (value instanceof Boolean) {
                            JSONUtilities.safePut(row, columnName, (Boolean) value);
                        } else {
                            JSONUtilities.safePut(row, columnName, value.toString());
                        }
                    }
                }
                
                rows.add(row);
            }
            
            // Build result
            ObjectNode result = ParsingUtilities.mapper.createObjectNode();
            JSONUtilities.safePut(result, "status", "ok");
            JSONUtilities.safePut(result, "columns", columns);
            JSONUtilities.safePut(result, "rows", rows);
            JSONUtilities.safePut(result, "rowCount", rows.size());
            
            return result;
            
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (Exception e) {
                    logger.warn("Error closing ResultSet: {}", e.getMessage());
                }
            }
            if (stmt != null) {
                try {
                    stmt.close();
                } catch (Exception e) {
                    logger.warn("Error closing Statement: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Get total row count
     */
    public static long getRowCount(Connection conn, SchemaProfile profile) throws Exception {
        String query = QueryBuilder.buildCountQuery(profile);
        
        if (logger.isDebugEnabled()) {
            logger.debug("Count query: {}", query);
        }

        Statement stmt = null;
        ResultSet rs = null;
        
        try {
            stmt = conn.createStatement();
            rs = stmt.executeQuery(query);
            
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0;
            
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (Exception e) {
                    logger.warn("Error closing ResultSet: {}", e.getMessage());
                }
            }
            if (stmt != null) {
                try {
                    stmt.close();
                } catch (Exception e) {
                    logger.warn("Error closing Statement: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Check if a column is a JSON field
     */
    private static boolean isJsonField(SchemaProfile profile, String columnName) {
        if (profile == null || profile.getFieldMappings() == null) {
            return false;
        }
        
        for (SchemaProfile.FieldMapping mapping : profile.getFieldMappings()) {
            if (columnName.equals(mapping.getColumnName()) && mapping.isJsonField()) {
                return true;
            }
        }
        return false;
    }
}

