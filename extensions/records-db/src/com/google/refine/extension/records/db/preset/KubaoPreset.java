/*
 * Kubao Preset Configuration
 */

package com.google.refine.extension.records.db.preset;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.google.refine.extension.records.db.model.SchemaProfile;
import com.google.refine.util.ParsingUtilities;

/**
 * Kubao preset for archival management system
 * 
 * Kubao is a document/archival management system with:
 * - 案卷 (Cases/Dossiers) - main records
 * - 文件 (Files) - sub-records within cases
 * - Metadata fields for each record type
 * - File storage with directory structure
 */
public class KubaoPreset {

    /**
     * Get Kubao preset configuration
     */
    public static ObjectNode getPresetConfig() {
        ObjectNode preset = ParsingUtilities.mapper.createObjectNode();
        
        // Basic info
        preset.put("name", "kubao");
        preset.put("label", "Kubao Archival System");
        preset.put("description", "Configuration for Kubao document/archival management system");
        
        // Database configuration
        ObjectNode dbConfig = preset.putObject("database");
        dbConfig.put("dialect", "mysql");
        dbConfig.put("host", "localhost");
        dbConfig.put("port", 3306);
        dbConfig.put("database", "kubao");
        dbConfig.put("username", "");
        dbConfig.put("password", "");
        
        // Table mapping
        ObjectNode tableMapping = preset.putObject("tableMapping");
        tableMapping.put("mainTable", "t_case");
        tableMapping.put("recordIdColumn", "case_id");
        tableMapping.put("recordTypeColumn", "record_type");
        
        // Field mappings for case records
        ObjectNode fieldMappings = preset.putObject("fieldMappings");
        
        // Case fields
        fieldMappings.putObject("case_id")
            .put("columnName", "case_id")
            .put("columnLabel", "Case ID")
            .put("dataType", "string")
            .put("isJsonField", false)
            .put("isExportFlag", false);
            
        fieldMappings.putObject("case_name")
            .put("columnName", "case_name")
            .put("columnLabel", "Case Name")
            .put("dataType", "string")
            .put("isJsonField", false)
            .put("isExportFlag", false);
            
        fieldMappings.putObject("metadata")
            .put("columnName", "metadata")
            .put("columnLabel", "Metadata")
            .put("dataType", "json")
            .put("isJsonField", true)
            .put("jsonPath", "")
            .put("isExportFlag", false);
            
        fieldMappings.putObject("file_root")
            .put("columnName", "file_root")
            .put("columnLabel", "File Root Path")
            .put("dataType", "string")
            .put("isJsonField", false)
            .put("isExportFlag", false);
            
        fieldMappings.putObject("export_flag")
            .put("columnName", "export_flag")
            .put("columnLabel", "Export Flag")
            .put("dataType", "boolean")
            .put("isJsonField", false)
            .put("isExportFlag", true);
        
        // File mapping
        ObjectNode fileMapping = preset.putObject("fileMapping");
        fileMapping.put("fileRootColumn", "file_root");
        fileMapping.put("fileRootRawColumn", "file_root_raw");
        
        // Allowed roots (must be configured by user)
        fileMapping.putArray("allowedRoots");
        
        // Pagination
        ObjectNode pagination = preset.putObject("pagination");
        pagination.put("pageSize", 100);
        pagination.put("maxRows", 10000);
        
        return preset;
    }

    /**
     * Apply Kubao preset to schema profile
     */
    public static void applyPreset(SchemaProfile profile) {
        ObjectNode config = getPresetConfig();
        
        // Apply database settings
        profile.setDialect(config.get("database").get("dialect").asText());
        profile.setHost(config.get("database").get("host").asText());
        profile.setPort(config.get("database").get("port").asInt());
        profile.setDatabase(config.get("database").get("database").asText());
        
        // Apply table mapping
        profile.setMainTable(config.get("tableMapping").get("mainTable").asText());
        profile.setRecordIdColumn(config.get("tableMapping").get("recordIdColumn").asText());
        profile.setRecordTypeColumn(config.get("tableMapping").get("recordTypeColumn").asText());
        
        // Apply file mapping
        profile.setFileRootColumn(config.get("fileMapping").get("fileRootColumn").asText());
        profile.setFileRootRawColumn(config.get("fileMapping").get("fileRootRawColumn").asText());
    }
}

