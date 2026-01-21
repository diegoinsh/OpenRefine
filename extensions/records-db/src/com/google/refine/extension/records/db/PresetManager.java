/*
 * Records Database Preset Manager
 */

package com.google.refine.extension.records.db;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.google.refine.util.JSONUtilities;
import com.google.refine.util.ParsingUtilities;

/**
 * Manages preset templates for quick configuration
 */
public class PresetManager {
    
    public static final String PRESET_KUBAO = "specific";
    public static final String PRESET_FLAT_TABLE = "flat_table";
    public static final String PRESET_GENERIC_JSON = "generic_json";
    
    /**
     * Get all available presets
     */
    public static ArrayNode getAvailablePresets() {
        ArrayNode presets = ParsingUtilities.mapper.createArrayNode();
        
        presets.add(createPresetInfo(PRESET_KUBAO, "Kubao Archival System", 
            "Preset for Kubao document management system with catalog and file support"));
        presets.add(createPresetInfo(PRESET_FLAT_TABLE, "Flat Table", 
            "Simple flat table import without nested structures"));
        presets.add(createPresetInfo(PRESET_GENERIC_JSON, "Generic JSON Fields", 
            "Generic preset for tables with JSON-encoded fields"));
        
        return presets;
    }
    
    /**
     * Get preset configuration by name
     */
    public static ObjectNode getPresetConfig(String presetName) {
        switch (presetName) {
            case PRESET_KUBAO:
                return getKubaoPreset();
            case PRESET_FLAT_TABLE:
                return getFlatTablePreset();
            case PRESET_GENERIC_JSON:
                return getGenericJsonPreset();
            default:
                return null;
        }
    }
    
    /**
     * Kubao preset configuration
     */
    private static ObjectNode getKubaoPreset() {
        ObjectNode preset = ParsingUtilities.mapper.createObjectNode();
        
        JSONUtilities.safePut(preset, "name", PRESET_KUBAO);
        JSONUtilities.safePut(preset, "mode", "catalog");
        JSONUtilities.safePut(preset, "mainTable", "records");
        JSONUtilities.safePut(preset, "recordIdColumn", "record_id");
        JSONUtilities.safePut(preset, "recordTypeColumn", "record_type");
        JSONUtilities.safePut(preset, "fileRootColumn", "file_root");
        JSONUtilities.safePut(preset, "fileRootRawColumn", "file_root_raw");
        
        // Default field mappings for Kubao
        ArrayNode fields = ParsingUtilities.mapper.createArrayNode();
        fields.add(createFieldMapping("record_id", "Record ID", "string", false));
        fields.add(createFieldMapping("record_name", "Record Name", "string", false));
        fields.add(createFieldMapping("record_type", "Record Type", "string", false));
        fields.add(createFieldMapping("metadata", "Metadata", "json", true));
        fields.add(createFieldMapping("file_root", "File Root", "string", false));
        
        JSONUtilities.safePut(preset, "fieldMappings", fields);
        
        return preset;
    }
    
    /**
     * Flat table preset configuration
     */
    private static ObjectNode getFlatTablePreset() {
        ObjectNode preset = ParsingUtilities.mapper.createObjectNode();
        
        JSONUtilities.safePut(preset, "name", PRESET_FLAT_TABLE);
        JSONUtilities.safePut(preset, "mode", "catalog");
        JSONUtilities.safePut(preset, "mainTable", "data");
        
        ArrayNode fields = ParsingUtilities.mapper.createArrayNode();
        JSONUtilities.safePut(preset, "fieldMappings", fields);
        
        return preset;
    }
    
    /**
     * Generic JSON preset configuration
     */
    private static ObjectNode getGenericJsonPreset() {
        ObjectNode preset = ParsingUtilities.mapper.createObjectNode();
        
        JSONUtilities.safePut(preset, "name", PRESET_GENERIC_JSON);
        JSONUtilities.safePut(preset, "mode", "catalog");
        JSONUtilities.safePut(preset, "mainTable", "data");
        
        ArrayNode fields = ParsingUtilities.mapper.createArrayNode();
        JSONUtilities.safePut(preset, "fieldMappings", fields);
        
        return preset;
    }
    
    /**
     * Create preset info object
     */
    private static ObjectNode createPresetInfo(String name, String label, String description) {
        ObjectNode info = ParsingUtilities.mapper.createObjectNode();
        JSONUtilities.safePut(info, "name", name);
        JSONUtilities.safePut(info, "label", label);
        JSONUtilities.safePut(info, "description", description);
        return info;
    }
    
    /**
     * Create field mapping object
     */
    private static ObjectNode createFieldMapping(String columnName, String columnLabel, 
                                                  String dataType, boolean isJsonField) {
        ObjectNode mapping = ParsingUtilities.mapper.createObjectNode();
        JSONUtilities.safePut(mapping, "columnName", columnName);
        JSONUtilities.safePut(mapping, "columnLabel", columnLabel);
        JSONUtilities.safePut(mapping, "dataType", dataType);
        JSONUtilities.safePut(mapping, "isJsonField", isJsonField);
        return mapping;
    }
    
    /**
     * Get available database dialects
     */
    public static ArrayNode getAvailableDialects() {
        ArrayNode dialects = ParsingUtilities.mapper.createArrayNode();
        dialects.add("mysql");
        dialects.add("postgresql");
        dialects.add("mariadb");
        dialects.add("sqlite");
        return dialects;
    }
    
    /**
     * Get available modes
     */
    public static ArrayNode getAvailableModes() {
        ArrayNode modes = ParsingUtilities.mapper.createArrayNode();
        modes.add("catalog");
        modes.add("sql");
        return modes;
    }
}

