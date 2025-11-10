/*
 * Schema Profile Parser
 */

package com.google.refine.extension.records.db;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.records.db.model.SchemaProfile;
import com.google.refine.extension.records.db.model.SchemaProfile.FieldMapping;
import com.google.refine.util.ParsingUtilities;

/**
 * Parses Schema Profile from JSON configuration
 */
public class SchemaProfileParser {
    
    private static final Logger logger = LoggerFactory.getLogger("SchemaProfileParser");
    
    /**
     * Parse Schema Profile from JSON string
     */
    public static SchemaProfile parse(String jsonString) throws Exception {
        ObjectNode jsonNode = ParsingUtilities.evaluateJsonStringToObjectNode(jsonString);
        return parse(jsonNode);
    }
    
    /**
     * Parse Schema Profile from JSON node
     */
    public static SchemaProfile parse(JsonNode jsonNode) throws Exception {
        if (jsonNode == null) {
            throw new IllegalArgumentException("Schema Profile JSON cannot be null");
        }
        
        SchemaProfile profile = new SchemaProfile();
        
        // Basic configuration
        if (jsonNode.has("name")) {
            profile.setName(jsonNode.get("name").asText());
        }
        if (jsonNode.has("description")) {
            profile.setDescription(jsonNode.get("description").asText());
        }
        if (jsonNode.has("mode")) {
            profile.setMode(jsonNode.get("mode").asText());
        } else {
            profile.setMode("catalog"); // Default mode
        }
        if (jsonNode.has("preset")) {
            profile.setPreset(jsonNode.get("preset").asText());
        }
        
        // Database connection
        if (jsonNode.has("dialect")) {
            profile.setDialect(jsonNode.get("dialect").asText());
        }
        if (jsonNode.has("host")) {
            profile.setHost(jsonNode.get("host").asText());
        }
        if (jsonNode.has("port")) {
            profile.setPort(jsonNode.get("port").asInt());
        }
        if (jsonNode.has("database")) {
            profile.setDatabase(jsonNode.get("database").asText());
        }
        if (jsonNode.has("username")) {
            profile.setUsername(jsonNode.get("username").asText());
        }
        if (jsonNode.has("password")) {
            profile.setPassword(jsonNode.get("password").asText());
        }
        
        // Table mapping
        if (jsonNode.has("mainTable")) {
            profile.setMainTable(jsonNode.get("mainTable").asText());
        }
        if (jsonNode.has("recordIdColumn")) {
            profile.setRecordIdColumn(jsonNode.get("recordIdColumn").asText());
        }
        if (jsonNode.has("recordTypeColumn")) {
            profile.setRecordTypeColumn(jsonNode.get("recordTypeColumn").asText());
        }
        
        // Field mappings
        if (jsonNode.has("fieldMappings") && jsonNode.get("fieldMappings").isArray()) {
            List<FieldMapping> fieldMappings = new ArrayList<>();
            for (JsonNode fieldNode : jsonNode.get("fieldMappings")) {
                FieldMapping mapping = parseFieldMapping(fieldNode);
                fieldMappings.add(mapping);
            }
            profile.setFieldMappings(fieldMappings);
        }
        
        // File/Asset mapping
        if (jsonNode.has("fileRootColumn")) {
            profile.setFileRootColumn(jsonNode.get("fileRootColumn").asText());
        }
        if (jsonNode.has("fileRootRawColumn")) {
            profile.setFileRootRawColumn(jsonNode.get("fileRootRawColumn").asText());
        }
        if (jsonNode.has("allowedRoots") && jsonNode.get("allowedRoots").isArray()) {
            List<String> allowedRoots = new ArrayList<>();
            for (JsonNode root : jsonNode.get("allowedRoots")) {
                allowedRoots.add(root.asText());
            }
            profile.setAllowedRoots(allowedRoots);
        }
        
        // Pagination
        if (jsonNode.has("pageSize")) {
            profile.setPageSize(jsonNode.get("pageSize").asInt());
        }
        if (jsonNode.has("maxRows")) {
            profile.setMaxRows(jsonNode.get("maxRows").asInt());
        }
        
        if (logger.isDebugEnabled()) {
            logger.debug("Parsed Schema Profile: {}", profile.getName());
        }
        
        return profile;
    }
    
    /**
     * Parse a single field mapping
     */
    private static FieldMapping parseFieldMapping(JsonNode fieldNode) {
        FieldMapping mapping = new FieldMapping();
        
        if (fieldNode.has("columnName")) {
            mapping.setColumnName(fieldNode.get("columnName").asText());
        }
        if (fieldNode.has("columnLabel")) {
            mapping.setColumnLabel(fieldNode.get("columnLabel").asText());
        }
        if (fieldNode.has("dataType")) {
            mapping.setDataType(fieldNode.get("dataType").asText());
        }
        if (fieldNode.has("isJsonField")) {
            mapping.setJsonField(fieldNode.get("isJsonField").asBoolean());
        }
        if (fieldNode.has("jsonPath")) {
            mapping.setJsonPath(fieldNode.get("jsonPath").asText());
        }
        if (fieldNode.has("isExportFlag")) {
            mapping.setExportFlag(fieldNode.get("isExportFlag").asBoolean());
        }
        
        return mapping;
    }
}

