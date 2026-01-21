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
        final String dialect = profile.getDialect();
        final String mainAlias = "m";
        final String exportedJoinAlias = "je";

        // Build join alias map for reuse (needed for fileMapping)
        java.util.Map<String, String> joinAliasMap = new java.util.HashMap<String, String>();

        // First, build the FROM and JOIN clauses to populate joinAliasMap
        StringBuilder fromClause = new StringBuilder();
        fromClause.append(" FROM ").append(escapeTableName(profile.getMainTable(), dialect)).append(" ").append(mainAlias);

        // Optional JOIN + WHERE for exclude-exported and custom conditions
        String exWhere = buildExcludeExportedClause(profile, mainAlias, exportedJoinAlias, dialect, fromClause);
        String condWhere = buildCustomConditionsClause(profile, mainAlias, dialect, fromClause, joinAliasMap);

        // Now build the SELECT clause with all columns including file path
        StringBuilder selectClause = new StringBuilder();
        selectClause.append("SELECT ");

        // Add all field mappings (qualified with main table alias)
        List<FieldMapping> fieldMappings = profile.getFieldMappings();
        boolean anySelected = false;
        if (fieldMappings != null && !fieldMappings.isEmpty()) {
            for (int i = 0; i < fieldMappings.size(); i++) {
                FieldMapping mapping = fieldMappings.get(i);
                String expr = renderSelectExpression(mapping, mainAlias, dialect);
                if (expr == null || expr.isEmpty()) {
                    continue;
                }
                if (anySelected) selectClause.append(", ");
                selectClause.append(expr);
                anySelected = true;
            }
        }
        if (!anySelected) {
            // Fallback to select all
            selectClause.append(mainAlias).append(".*");
            anySelected = true;
        }

        // Add file root columns if present (deprecated - for backward compatibility)
        if (profile.getFileRootColumn() != null && !profile.getFileRootColumn().isEmpty()) {
            selectClause.append(", ").append(mainAlias).append(".").append(escapeColumnName(profile.getFileRootColumn(), dialect));
        }
        if (profile.getFileRootRawColumn() != null && !profile.getFileRootRawColumn().isEmpty()) {
            selectClause.append(", ").append(mainAlias).append(".").append(escapeColumnName(profile.getFileRootRawColumn(), dialect));
        }

        // Add file path column from fileMapping (new structure)
        // Now joinAliasMap is populated, so we can safely reference join aliases
        String filePathExpr = buildFilePathExpression(profile, mainAlias, exportedJoinAlias, joinAliasMap, dialect);
        if (filePathExpr != null && !filePathExpr.isEmpty()) {
            selectClause.append(", ").append(filePathExpr);
        }

        // Combine SELECT and FROM clauses
        StringBuilder query = new StringBuilder();
        query.append(selectClause);
        query.append(fromClause);

        // Add WHERE clause
        String combinedWhere = null;
        if (exWhere != null && !exWhere.isEmpty() && condWhere != null && !condWhere.isEmpty()) {
            combinedWhere = "(" + exWhere + ") AND (" + condWhere + ")";
        } else if (exWhere != null && !exWhere.isEmpty()) {
            combinedWhere = exWhere;
        } else if (condWhere != null && !condWhere.isEmpty()) {
            combinedWhere = condWhere;
        }
        if (combinedWhere != null && !combinedWhere.isEmpty()) {
            query.append(" WHERE ").append(combinedWhere);
        }

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
        final String dialect = profile.getDialect();
        final String mainAlias = "m";
        final String exportedJoinAlias = "je";

        StringBuilder query = new StringBuilder();
        query.append("SELECT COUNT(*) as total FROM ")
             .append(escapeTableName(profile.getMainTable(), dialect)).append(" ").append(mainAlias);

        // Optional JOIN + WHERE for exclude-exported and custom conditions
        String exWhere = buildExcludeExportedClause(profile, mainAlias, exportedJoinAlias, dialect, query);
        String condWhere = buildCustomConditionsClause(profile, mainAlias, dialect, query, null);
        String combinedWhere = null;
        if (exWhere != null && !exWhere.isEmpty() && condWhere != null && !condWhere.isEmpty()) {
            combinedWhere = "(" + exWhere + ") AND (" + condWhere + ")";
        } else if (exWhere != null && !exWhere.isEmpty()) {
            combinedWhere = exWhere;
        } else if (condWhere != null && !condWhere.isEmpty()) {
            combinedWhere = condWhere;
        }
        if (combinedWhere != null && !combinedWhere.isEmpty()) {
            query.append(" WHERE ").append(combinedWhere);
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Built COUNT query: {}", query.toString());
        }
        return query.toString();
    }

    /**
     * Build a DISTINCT values query for a given field, honoring filters and joins.
     * Used by distinct-values endpoint (e.g., JSON auto-flatten) so that sampling
     * respects the same WHERE/JOIN logic as preview.
     */
    public static String buildDistinctValuesQuery(SchemaProfile profile, String source, String field, int limit) {
        if (profile == null || profile.getMainTable() == null) {
            throw new IllegalArgumentException("Schema profile and main table are required");
        }
        final String dialect = profile.getDialect();
        final String mainAlias = "m";
        final String exportedJoinAlias = "je";

        // For distinct values used by JSON auto-flatten we always sample from the main table alias.
        String colExpr = mainAlias + "." + escapeColumnName(field, dialect);

        StringBuilder query = new StringBuilder();
        query.append("SELECT DISTINCT ").append(colExpr).append(" AS v");
        query.append(" FROM ").append(escapeTableName(profile.getMainTable(), dialect)).append(" ").append(mainAlias);

        // Reuse the same JOIN + WHERE logic as main select/count queries
        String exWhere = buildExcludeExportedClause(profile, mainAlias, exportedJoinAlias, dialect, query);
        String condWhere = buildCustomConditionsClause(profile, mainAlias, dialect, query, null);

        StringBuilder where = new StringBuilder();
        if (exWhere != null && !exWhere.isEmpty()) {
            where.append("(").append(exWhere).append(")");
        }
        if (condWhere != null && !condWhere.isEmpty()) {
            if (where.length() > 0) {
                where.append(" AND ");
            }
            where.append("(").append(condWhere).append(")");
        }

        // Always require the target column to be non-null
        String notNullFilter = colExpr + " IS NOT NULL";
        if (where.length() > 0) {
            where.append(" AND ").append(notNullFilter);
        } else {
            where.append(notNullFilter);
        }

        if (where.length() > 0) {
            query.append(" WHERE ").append(where.toString());
        }

        query.append(" ORDER BY ").append(colExpr);
        if (limit > 0) {
            query.append(" LIMIT ").append(limit);
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Built DISTINCT query: {}", query.toString());
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
     * Render SELECT expression for a field mapping, supporting Plan B (DB JSON extraction with alias)
     */
    private static String renderSelectExpression(FieldMapping mapping, String mainAlias, String dialect) {
        if (mapping == null || mapping.getColumnName() == null || mapping.getColumnName().isEmpty()) {
            return null;
        }
        String baseCol = mainAlias + "." + escapeColumnName(mapping.getColumnName(), dialect);
        String alias = mapping.getColumnLabel() != null && !mapping.getColumnLabel().isEmpty()
                ? mapping.getColumnLabel()
                : mapping.getColumnName();
        String aliasEsc = escapeColumnName(alias, dialect);

        // Plan B: JSON extraction pushed down to DB
        if (mapping.isJsonField() && mapping.getJsonPath() != null && !mapping.getJsonPath().isEmpty()) {
            String key = mapping.getJsonPath();
            if ("mysql".equals(dialect) || "mariadb".equals(dialect)) {
                String keyEsc = key.replace("\"", "\\\"");
                return "JSON_UNQUOTE(JSON_EXTRACT(" + baseCol + ", '$.\"" + keyEsc + "\"')) AS " + aliasEsc;
            } else if ("postgresql".equals(dialect)) {
                String keyEsc = key.replace("'", "''");
                return "(" + baseCol + "::json->>'" + keyEsc + "') AS " + aliasEsc;
            } else if ("sqlite".equals(dialect)) {
                String keyEsc = key.replace("\"", "\\\"");
                return "json_extract(" + baseCol + ", '$.\"" + keyEsc + "\"') AS " + aliasEsc;
            }
        }

        // Default: direct column, alias if label differs
        if (!alias.equals(mapping.getColumnName())) {
            return baseCol + " AS " + aliasEsc;
        }
        return baseCol;
    }

    /**
     * Build JOIN and WHERE clause for exclude-exported (P0)
     * Potentially appends LEFT JOIN to the query builder and returns WHERE expression string
     */
    private static String buildExcludeExportedClause(SchemaProfile profile, String mainAlias, String joinAlias, String dialect, StringBuilder query) {
        Map<String, Object> filters = profile.getFilters();
        if (filters == null) return null;
        Object ex = filters.get("excludeExported");
        boolean exclude = (ex instanceof Boolean && (Boolean) ex) || (ex != null && "true".equalsIgnoreCase(String.valueOf(ex)));
        if (!exclude) return null;
        Object ejObj = filters.get("exportedJoin");
        if (!(ejObj instanceof Map)) return null;
        Map<?, ?> ej = (Map<?, ?>) ejObj;
        String joinTable = stringOrEmpty(ej.get("joinTable"));
        String mainCol  = stringOrEmpty(ej.get("mainColumn"));
        String joinCol  = stringOrEmpty(ej.get("joinColumn"));
        String flagCol  = stringOrEmpty(ej.get("flagField"));
        String value    = stringOrEmpty(ej.get("value"));
        boolean includeNull = truthy(ej.get("includeNull"));
        if (joinTable.isEmpty() || mainCol.isEmpty() || joinCol.isEmpty() || flagCol.isEmpty()) {
            return null;
        }
        // LEFT JOIN join table
        query.append(" LEFT JOIN ").append(escapeTableName(joinTable, dialect)).append(" ").append(joinAlias)
             .append(" ON ").append(mainAlias).append(".").append(escapeColumnName(mainCol, dialect))
             .append(" = ").append(joinAlias).append(".").append(escapeColumnName(joinCol, dialect));
        // WHERE part
        StringBuilder where = new StringBuilder();
        if (!value.isEmpty()) {
            where.append(joinAlias).append(".").append(escapeColumnName(flagCol, dialect))
                 .append(" = ").append(formatLiteral(value));
        }
        if (includeNull) {
            if (where.length() > 0) where.append(" OR ");
            where.append(joinAlias).append(".").append(escapeColumnName(flagCol, dialect)).append(" IS NULL");
        }
        return where.toString();
    }


    /**
     * Build WHERE clause from custom conditions (including join-table conditions) and ensure JOINs exist when needed.
     * Supports multiple join tables: each join-source condition may define its own joinTable/mainKey/joinKey.
     * For legacy profiles where those keys are missing, falls back to single exportedJoin configuration.
     * Returns a WHERE expression string without the leading WHERE keyword, or null if none.
     *
     * @param joinAliasMap Output parameter - map to store join aliases for reuse (key = joinTable|mainKey|joinKey, value = alias)
     */
    @SuppressWarnings("unchecked")
    private static String buildCustomConditionsClause(SchemaProfile profile, String mainAlias, String dialect, StringBuilder query, java.util.Map<String, String> joinAliasMap) {
        Map<String, Object> filters = profile.getFilters();
        if (filters == null) return null;
        Object condsObj = filters.get("conditions");
        if (!(condsObj instanceof List)) return null;
        List<?> conds = (List<?>) condsObj;
        if (conds.isEmpty()) return null;

        StringBuilder where = new StringBuilder();
        boolean first = true;

        // Use provided joinAliasMap or create new one
        java.util.Map<String, String> joinAliases = joinAliasMap != null ? joinAliasMap : new java.util.HashMap<String, String>();
        int joinIndex = 1;

        for (Object cObj : conds) {
            if (!(cObj instanceof Map)) continue;
            Map<String, Object> c = (Map<String, Object>) cObj;
            String source = stringOrEmpty(c.get("source"));
            if (source.isEmpty()) source = "main";
            String field = stringOrEmpty(c.get("field"));
            if (field.isEmpty()) continue;
            String operator = stringOrEmpty(c.get("operator"));
            if (operator.isEmpty()) operator = "=";
            String value = stringOrEmpty(c.get("value"));
            String logic = stringOrEmpty(c.get("logic"));
            if (logic.isEmpty()) logic = "AND";

            String alias = mainAlias;

            if (!"main".equalsIgnoreCase(source)) {
                String joinTable = stringOrEmpty(c.get("joinTable"));
                String mainKey  = stringOrEmpty(c.get("mainKey"));
                String joinKey  = stringOrEmpty(c.get("joinKey"));

                if (!joinTable.isEmpty() && !mainKey.isEmpty() && !joinKey.isEmpty()) {
                    String key = joinTable + "|" + mainKey + "|" + joinKey;
                    String existingAlias = joinAliases.get(key);
                    if (existingAlias == null) {
                        String newAlias = "j" + joinIndex++;
                        // Avoid accidentally reusing an alias that is already present in the query
                        try {
                            while (query.toString().contains(" " + newAlias + " ")) {
                                newAlias = "j" + joinIndex++;
                            }
                        } catch (Exception ignore) {}
                        query.append(" LEFT JOIN ").append(escapeTableName(joinTable, dialect)).append(" ").append(newAlias)
                             .append(" ON ").append(mainAlias).append(".").append(escapeColumnName(mainKey, dialect))
                             .append(" = ").append(newAlias).append(".").append(escapeColumnName(joinKey, dialect));
                        joinAliases.put(key, newAlias);
                        alias = newAlias;
                    } else {
                        alias = existingAlias;
                    }
                } else {
                    // Legacy fallback: use exportedJoin configuration as a single join
                    alias = ensureLegacyJoinForCustomConditions(profile, mainAlias, dialect, query);
                    if (alias == null) {
                        // Cannot build a valid join; skip this condition
                        continue;
                    }
                }
            }

            String col = alias + "." + escapeColumnName(field, dialect);
            String expr;
            if ("IN".equalsIgnoreCase(operator)) {
                String[] parts = value.split(",");
                StringBuilder in = new StringBuilder();
                boolean firstVal = true;
                for (String p : parts) {
                    String t = p == null ? "" : p.trim();
                    if (t.isEmpty()) continue;
                    if (!firstVal) in.append(", ");
                    in.append(formatLiteral(t));
                    firstVal = false;
                }
                expr = firstVal ? null : (col + " IN (" + in + ")");
            } else if ("LIKE".equalsIgnoreCase(operator)) {
                expr = col + " LIKE " + formatLiteral(value);
            } else {
                expr = col + " " + operator + " " + formatLiteral(value);
            }
            if (expr == null || expr.isEmpty()) continue;

            if (!first) {
                where.append(" ").append(logic).append(" ");
            }
            where.append("(").append(expr).append(")");
            first = false;
        }

        String out = where.toString();
        return out.isEmpty() ? null : out;
    }

    /**
     * Legacy helper: ensure a single JOIN based on filters.exportedJoin exists and return its alias.
     * Preserves previous behavior for profiles that only support one join table.
     */
    private static String ensureLegacyJoinForCustomConditions(SchemaProfile profile, String mainAlias, String dialect, StringBuilder query) {
        final String joinAlias = "j";
        try {
            if (query.toString().contains(" " + joinAlias + " ")) {
                return joinAlias;
            }
        } catch (Exception ignore) {}
        Map<String, Object> filters = profile.getFilters();
        if (filters == null) return null;
        Object ejObj = filters.get("exportedJoin");
        if (!(ejObj instanceof Map)) return null;
        Map<?, ?> ej = (Map<?, ?>) ejObj;
        String joinTable = stringOrEmpty(ej.get("joinTable"));
        String mainCol  = stringOrEmpty(ej.get("mainColumn"));
        String joinCol  = stringOrEmpty(ej.get("joinColumn"));
        if (joinTable.isEmpty() || mainCol.isEmpty() || joinCol.isEmpty()) return null;
        query.append(" LEFT JOIN ").append(escapeTableName(joinTable, dialect)).append(" ").append(joinAlias)
             .append(" ON ").append(mainAlias).append(".").append(escapeColumnName(mainCol, dialect))
             .append(" = ").append(joinAlias).append(".").append(escapeColumnName(joinCol, dialect));
        return joinAlias;
    }

    private static boolean truthy(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean) return (Boolean) o;
        String s = String.valueOf(o);
        return "1".equals(s) || "true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s) || "on".equalsIgnoreCase(s);
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String formatLiteral(String v) {
        if (v == null) return "NULL";
        try {
            // numeric?
            Double.parseDouble(v);
            return v; // as-is
        } catch (Exception ignore) {
            String esc = v.replace("'", "''");
            return "'" + esc + "'";
        }
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

    /**
     * Build file path expression from fileMapping configuration.
     * Returns a SELECT expression like: '/root/path' || '/' || table_alias.field AS column_label
     * or null if fileMapping is not configured.
     */
    @SuppressWarnings("unchecked")
    private static String buildFilePathExpression(SchemaProfile profile, String mainAlias, String exportedJoinAlias,
                                                   java.util.Map<String, String> joinAliasMap, String dialect) {
        Map<String, Object> fileMapping = profile.getFileMapping();
        if (fileMapping == null || fileMapping.isEmpty()) {
            return null;
        }

        String source = stringOrEmpty(fileMapping.get("source"));
        String field = stringOrEmpty(fileMapping.get("field"));
        String rootPath = stringOrEmpty(fileMapping.get("rootPath"));
        String columnLabel = stringOrEmpty(fileMapping.get("columnLabel"));

        if (field.isEmpty()) {
            return null; // field is required
        }

        if (columnLabel.isEmpty()) {
            columnLabel = "file_path"; // default column name
        }

        // Determine table alias based on source
        String tableAlias = mainAlias;
        if ("exportedJoin".equalsIgnoreCase(source)) {
            tableAlias = exportedJoinAlias;
        } else if ("join".equalsIgnoreCase(source)) {
            // Need to find the join alias from joinAliasMap
            String joinTable = stringOrEmpty(fileMapping.get("joinTable"));
            String mainKey = stringOrEmpty(fileMapping.get("mainKey"));
            String joinKey = stringOrEmpty(fileMapping.get("joinKey"));

            if (!joinTable.isEmpty() && !mainKey.isEmpty() && !joinKey.isEmpty() && joinAliasMap != null) {
                String key = joinTable + "|" + mainKey + "|" + joinKey;
                String alias = joinAliasMap.get(key);
                if (alias != null) {
                    tableAlias = alias;
                } else {
                    // Join not found in map - this shouldn't happen if fileMapping references a valid join from filters
                    logger.warn("File mapping references join that doesn't exist in filters: {}", key);
                    return null;
                }
            } else {
                logger.warn("File mapping with source=join missing required join configuration");
                return null;
            }
        }
        // else: source is "main" or empty, use mainAlias

        String fieldExpr = tableAlias + "." + escapeColumnName(field, dialect);

        // Build final expression with optional rootPath concatenation
        String finalExpr;
        if (!rootPath.isEmpty()) {
            // Escape rootPath for SQL string literal
            String escapedRoot = rootPath.replace("'", "''");

            // Dialect-specific concatenation
            if ("postgresql".equalsIgnoreCase(dialect)) {
                finalExpr = "'" + escapedRoot + "' || '/' || " + fieldExpr;
            } else {
                // MySQL, MariaDB, SQLite use CONCAT
                finalExpr = "CONCAT('" + escapedRoot + "', '/', " + fieldExpr + ")";
            }
        } else {
            finalExpr = fieldExpr;
        }

        return finalExpr + " AS " + escapeColumnName(columnLabel, dialect);
    }
}

