/*
 * Filter Applier for P0 Strategy
 */

package com.google.refine.extension.records.db;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.util.ParsingUtilities;

/**
 * Applies filters to data rows
 * P0 strategy: Server-side filtering after data retrieval
 */
public class FilterApplier {
    
    private static final Logger logger = LoggerFactory.getLogger("FilterApplier");
    
    /**
     * Apply filters to a list of rows
     */
    public static List<ObjectNode> applyFilters(List<ObjectNode> rows, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return rows;
        }
        
        List<ObjectNode> filtered = new ArrayList<>();
        
        for (ObjectNode row : rows) {
            if (matchesFilters(row, filters)) {
                filtered.add(row);
            }
        }
        
        if (logger.isDebugEnabled()) {
            logger.debug("Applied filters: {} rows -> {} rows", rows.size(), filtered.size());
        }
        
        return filtered;
    }
    
    /**
     * Check if a row matches all filters
     */
    public static boolean matchesFilters(ObjectNode row, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        
        for (Map.Entry<String, Object> filter : filters.entrySet()) {
            if (!matchesFilter(row, filter.getKey(), filter.getValue())) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Check if a row matches a single filter
     */
    public static boolean matchesFilter(ObjectNode row, String fieldName, Object filterValue) {
        if (row == null || fieldName == null) {
            return false;
        }
        
        JsonNode fieldNode = row.get(fieldName);
        
        if (fieldNode == null) {
            return filterValue == null;
        }
        
        Object rowValue = jsonNodeToObject(fieldNode);
        
        return compareValues(rowValue, filterValue);
    }
    
    /**
     * Compare two values for equality
     */
    private static boolean compareValues(Object rowValue, Object filterValue) {
        if (rowValue == null && filterValue == null) {
            return true;
        }
        
        if (rowValue == null || filterValue == null) {
            return false;
        }
        
        // String comparison
        if (filterValue instanceof String) {
            String filterStr = (String) filterValue;
            String rowStr = rowValue.toString();
            
            // Support wildcard matching with %
            if (filterStr.contains("%")) {
                return matchesWildcard(rowStr, filterStr);
            }
            
            return rowStr.equals(filterStr);
        }
        
        // Number comparison
        if (filterValue instanceof Number) {
            try {
                double filterNum = ((Number) filterValue).doubleValue();
                double rowNum = Double.parseDouble(rowValue.toString());
                return rowNum == filterNum;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        
        // Boolean comparison
        if (filterValue instanceof Boolean) {
            return Boolean.parseBoolean(rowValue.toString()) == (Boolean) filterValue;
        }
        
        // Default: string comparison
        return rowValue.toString().equals(filterValue.toString());
    }
    
    /**
     * Match string with wildcard pattern (% = any characters)
     */
    private static boolean matchesWildcard(String text, String pattern) {
        // Convert SQL LIKE pattern to regex
        String regex = pattern
            .replace(".", "\\.")
            .replace("%", ".*")
            .replace("_", ".");
        
        return text.matches(regex);
    }
    
    /**
     * Convert JsonNode to appropriate Java object
     */
    private static Object jsonNodeToObject(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        
        if (node.isTextual()) {
            return node.asText();
        }
        
        if (node.isNumber()) {
            if (node.isIntegralNumber()) {
                return node.asLong();
            } else {
                return node.asDouble();
            }
        }
        
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        
        return node.asText();
    }
    
    /**
     * Apply field selection (column filtering)
     */
    public static List<ObjectNode> selectFields(List<ObjectNode> rows, List<String> selectedFields) {
        if (selectedFields == null || selectedFields.isEmpty()) {
            return rows;
        }
        
        List<ObjectNode> result = new ArrayList<>();
        
        for (ObjectNode row : rows) {
            ObjectNode selectedRow = selectFieldsFromRow(row, selectedFields);
            result.add(selectedRow);
        }
        
        if (logger.isDebugEnabled()) {
            logger.debug("Selected {} fields from {} rows", selectedFields.size(), rows.size());
        }
        
        return result;
    }
    
    /**
     * Select specific fields from a single row
     */
    public static ObjectNode selectFieldsFromRow(ObjectNode row, List<String> selectedFields) {
        ObjectNode result = ParsingUtilities.mapper.createObjectNode();

        for (String field : selectedFields) {
            JsonNode value = row.get(field);
            if (value != null) {
                result.set(field, value);
            }
        }

        return result;
    }
    
    /**
     * Limit number of rows
     */
    public static List<ObjectNode> limitRows(List<ObjectNode> rows, int limit) {
        if (limit <= 0) {
            return rows;
        }
        
        if (rows.size() <= limit) {
            return rows;
        }
        
        return rows.subList(0, limit);
    }
}

