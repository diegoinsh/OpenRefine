/*
 * Query Builder for P0 Strategy (Server-side JSON parsing)
 */

package com.google.refine.extension.records.db;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.records.db.model.SchemaProfile;
import com.google.refine.extension.records.db.model.SchemaProfile.FieldMapping;

/**
 * Builds SQL queries for P0 strategy (server-side JSON parsing)
 * P0 strategy: Fetch all data and parse JSON on server side
 */
public class QueryBuilder {
    
    private static final Logger logger = LoggerFactory.getLogger("QueryBuilder");
    
    /**
     * Build a basic SELECT query for the schema profile
     * P0 strategy: Select all columns from main table
     */
    public static String buildSelectQuery(SchemaProfile profile) {
        if (profile == null || profile.getMainTable() == null) {
            throw new IllegalArgumentException("Schema profile and main table are required");
        }
        
        StringBuilder query = new StringBuilder();
        query.append("SELECT ");
        
        // Add all field mappings
        List<FieldMapping> fieldMappings = profile.getFieldMappings();
        if (fieldMappings != null && !fieldMappings.isEmpty()) {
            for (int i = 0; i < fieldMappings.size(); i++) {
                if (i > 0) {
                    query.append(", ");
                }
                FieldMapping mapping = fieldMappings.get(i);
                query.append(escapeColumnName(mapping.getColumnName(), profile.getDialect()));
            }
        }
        
        // Add file root columns if present
        if (profile.getFileRootColumn() != null && !profile.getFileRootColumn().isEmpty()) {
            query.append(", ").append(escapeColumnName(profile.getFileRootColumn(), profile.getDialect()));
        }
        if (profile.getFileRootRawColumn() != null && !profile.getFileRootRawColumn().isEmpty()) {
            query.append(", ").append(escapeColumnName(profile.getFileRootRawColumn(), profile.getDialect()));
        }
        
        query.append(" FROM ").append(escapeTableName(profile.getMainTable(), profile.getDialect()));
        
        if (logger.isDebugEnabled()) {
            logger.debug("Built SELECT query: {}", query.toString());
        }
        
        return query.toString();
    }
    
    /**
     * Build a SELECT query with LIMIT and OFFSET for pagination
     */
    public static String buildSelectQueryWithPagination(SchemaProfile profile, int offset, int limit) {
        String baseQuery = buildSelectQuery(profile);
        
        StringBuilder query = new StringBuilder(baseQuery);
        query.append(" LIMIT ").append(limit);
        query.append(" OFFSET ").append(offset);
        
        if (logger.isDebugEnabled()) {
            logger.debug("Built paginated SELECT query: {}", query.toString());
        }
        
        return query.toString();
    }
    
    /**
     * Build a COUNT query to get total row count
     */
    public static String buildCountQuery(SchemaProfile profile) {
        if (profile == null || profile.getMainTable() == null) {
            throw new IllegalArgumentException("Schema profile and main table are required");
        }
        
        StringBuilder query = new StringBuilder();
        query.append("SELECT COUNT(*) as total FROM ");
        query.append(escapeTableName(profile.getMainTable(), profile.getDialect()));
        
        if (logger.isDebugEnabled()) {
            logger.debug("Built COUNT query: {}", query.toString());
        }
        
        return query.toString();
    }
    
    /**
     * Escape column name based on database dialect
     */
    public static String escapeColumnName(String columnName, String dialect) {
        if (columnName == null || columnName.isEmpty()) {
            return columnName;
        }
        
        if ("mysql".equals(dialect) || "mariadb".equals(dialect)) {
            return "`" + columnName + "`";
        } else if ("postgresql".equals(dialect)) {
            return "\"" + columnName + "\"";
        } else if ("sqlite".equals(dialect)) {
            return "\"" + columnName + "\"";
        }
        
        return columnName;
    }
    
    /**
     * Escape table name based on database dialect
     */
    public static String escapeTableName(String tableName, String dialect) {
        if (tableName == null || tableName.isEmpty()) {
            return tableName;
        }
        
        if ("mysql".equals(dialect) || "mariadb".equals(dialect)) {
            return "`" + tableName + "`";
        } else if ("postgresql".equals(dialect)) {
            return "\"" + tableName + "\"";
        } else if ("sqlite".equals(dialect)) {
            return "\"" + tableName + "\"";
        }
        
        return tableName;
    }
    
    /**
     * Build a WHERE clause from filters
     */
    public static String buildWhereClause(Map<String, Object> filters, String dialect) {
        if (filters == null || filters.isEmpty()) {
            return "";
        }
        
        StringBuilder where = new StringBuilder(" WHERE ");
        List<String> conditions = new ArrayList<>();
        
        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            String columnName = entry.getKey();
            Object value = entry.getValue();
            
            String condition = buildCondition(columnName, value, dialect);
            if (condition != null && !condition.isEmpty()) {
                conditions.add(condition);
            }
        }
        
        if (conditions.isEmpty()) {
            return "";
        }
        
        where.append(String.join(" AND ", conditions));
        
        if (logger.isDebugEnabled()) {
            logger.debug("Built WHERE clause: {}", where.toString());
        }
        
        return where.toString();
    }
    
    /**
     * Build a single condition for WHERE clause
     */
    private static String buildCondition(String columnName, Object value, String dialect) {
        if (value == null) {
            return escapeColumnName(columnName, dialect) + " IS NULL";
        }
        
        if (value instanceof String) {
            String stringValue = (String) value;
            // Escape single quotes
            stringValue = stringValue.replace("'", "''");
            return escapeColumnName(columnName, dialect) + " = '" + stringValue + "'";
        }
        
        if (value instanceof Number) {
            return escapeColumnName(columnName, dialect) + " = " + value;
        }
        
        if (value instanceof Boolean) {
            return escapeColumnName(columnName, dialect) + " = " + (((Boolean) value) ? 1 : 0);
        }
        
        return null;
    }
}

