/*
 * Generic JSON Preset Configuration
 */

package com.google.refine.extension.records.db.preset;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.google.refine.extension.records.db.model.SchemaProfile;
import com.google.refine.util.ParsingUtilities;

/**
 * Generic JSON preset for tables with JSON fields
 * 
 * Generic JSON is a preset for importing data from tables that contain
 * JSON fields with nested data structures.
 */
public class GenericJsonPreset {

    /**
     * Get Generic JSON preset configuration
     */
    public static ObjectNode getPresetConfig() {
        ObjectNode preset = ParsingUtilities.mapper.createObjectNode();
        
        // Basic info
        preset.put("name", "generic_json");
        preset.put("label", "Generic JSON");
        preset.put("description", "Configuration for tables with JSON fields containing nested data");
        
        // Database configuration
        ObjectNode dbConfig = preset.putObject("database");
        dbConfig.put("dialect", "mysql");
        dbConfig.put("host", "localhost");
        dbConfig.put("port", 3306);
        dbConfig.put("database", "");
        dbConfig.put("username", "");
        dbConfig.put("password", "");
        
        // Table mapping
        ObjectNode tableMapping = preset.putObject("tableMapping");
        tableMapping.put("mainTable", "");
        tableMapping.put("recordIdColumn", "id");
        tableMapping.put("recordTypeColumn", "");
        
        // Field mappings (empty, to be configured by user)
        ObjectNode fieldMappings = preset.putObject("fieldMappings");
        
        // File mapping (optional)
        ObjectNode fileMapping = preset.putObject("fileMapping");
        fileMapping.put("fileRootColumn", "");
        fileMapping.put("fileRootRawColumn", "");
        fileMapping.putArray("allowedRoots");
        
        // Pagination
        ObjectNode pagination = preset.putObject("pagination");
        pagination.put("pageSize", 100);
        pagination.put("maxRows", 10000);
        
        return preset;
    }

    /**
     * Apply Generic JSON preset to schema profile
     */
    public static void applyPreset(SchemaProfile profile) {
        ObjectNode config = getPresetConfig();
        
        // Apply database settings
        profile.setDialect(config.get("database").get("dialect").asText());
        profile.setHost(config.get("database").get("host").asText());
        profile.setPort(config.get("database").get("port").asInt());
        
        // Apply table mapping
        profile.setRecordIdColumn(config.get("tableMapping").get("recordIdColumn").asText());
        
        // Set pagination
        profile.setPageSize(config.get("pagination").get("pageSize").asInt());
        profile.setMaxRows(config.get("pagination").get("maxRows").asInt());
    }
}

