/*
 * JSON Field Extractor for P0 Strategy
 */

package com.google.refine.extension.records.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.util.ParsingUtilities;

/**
 * Extracts values from JSON fields using JSONPath
 * P0 strategy: Server-side JSON parsing and field extraction
 */
public class JsonFieldExtractor {
    
    private static final Logger logger = LoggerFactory.getLogger("JsonFieldExtractor");
    
    /**
     * Extract value from JSON string using JSONPath
     * Supports simple dot notation: "field.subfield.value"
     */
    public static Object extractValue(String jsonString, String jsonPath) {
        if (jsonString == null || jsonString.isEmpty() || jsonPath == null || jsonPath.isEmpty()) {
            return null;
        }
        
        try {
            JsonNode jsonNode = ParsingUtilities.mapper.readTree(jsonString);
            return extractValue(jsonNode, jsonPath);
        } catch (Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Error extracting value from JSON: {}", e.getMessage());
            }
            return null;
        }
    }
    
    /**
     * Extract value from JsonNode using JSONPath
     */
    public static Object extractValue(JsonNode jsonNode, String jsonPath) {
        if (jsonNode == null || jsonPath == null || jsonPath.isEmpty()) {
            return null;
        }
        
        // Split path by dots
        String[] pathParts = jsonPath.split("\\.");
        JsonNode current = jsonNode;
        
        for (String part : pathParts) {
            if (current == null) {
                return null;
            }
            
            // Handle array indices like "items[0]"
            if (part.contains("[")) {
                String fieldName = part.substring(0, part.indexOf("["));
                int index = Integer.parseInt(part.substring(part.indexOf("[") + 1, part.indexOf("]")));
                
                current = current.get(fieldName);
                if (current != null && current.isArray()) {
                    current = current.get(index);
                }
            } else {
                current = current.get(part);
            }
        }
        
        if (current == null) {
            return null;
        }
        
        // Convert JsonNode to appropriate Java type
        return jsonNodeToObject(current);
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
        
        if (node.isArray()) {
            // Return as JSON string for array
            return node.toString();
        }
        
        if (node.isObject()) {
            // Return as JSON string for object
            return node.toString();
        }
        
        return node.asText();
    }
    
    /**
     * Extract all fields from a JSON object
     */
    public static ObjectNode extractAllFields(String jsonString) {
        if (jsonString == null || jsonString.isEmpty()) {
            return ParsingUtilities.mapper.createObjectNode();
        }
        
        try {
            JsonNode jsonNode = ParsingUtilities.mapper.readTree(jsonString);
            if (jsonNode.isObject()) {
                return (ObjectNode) jsonNode;
            }
        } catch (Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Error extracting all fields from JSON: {}", e.getMessage());
            }
        }
        
        return ParsingUtilities.mapper.createObjectNode();
    }
    
    /**
     * Check if a string is valid JSON
     */
    public static boolean isValidJson(String jsonString) {
        if (jsonString == null || jsonString.isEmpty()) {
            return false;
        }
        
        try {
            ParsingUtilities.mapper.readTree(jsonString);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Flatten a nested JSON object to a single level
     * Example: {"user": {"name": "John"}} -> {"user.name": "John"}
     */
    public static ObjectNode flattenJson(JsonNode jsonNode, String prefix) {
        ObjectNode result = ParsingUtilities.mapper.createObjectNode();
        
        if (jsonNode == null) {
            return result;
        }
        
        if (jsonNode.isObject()) {
            jsonNode.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                String newKey = prefix.isEmpty() ? key : prefix + "." + key;
                
                if (value.isObject()) {
                    ObjectNode nested = flattenJson(value, newKey);
                    nested.fields().forEachRemaining(nestedEntry -> {
                        result.put(nestedEntry.getKey(), nestedEntry.getValue());
                    });
                } else {
                    result.put(newKey, value);
                }
            });
        }
        
        return result;
    }
}

