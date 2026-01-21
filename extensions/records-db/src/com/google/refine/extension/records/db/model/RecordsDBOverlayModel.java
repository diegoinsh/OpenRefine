/*
 * Records Database Overlay Model
 * Stores SchemaProfile configuration in the OpenRefine project
 */

package com.google.refine.extension.records.db.model;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.google.refine.model.OverlayModel;
import com.google.refine.model.Project;

/**
 * Overlay model for storing Records-DB import configuration in a project.
 * This allows the export feature to access the original file mapping configuration.
 */
public class RecordsDBOverlayModel implements OverlayModel {

    public static final String OVERLAY_MODEL_KEY = "recordsDBSchema";

    @JsonProperty("fileMapping")
    private Map<String, Object> fileMapping;

    @JsonProperty("fieldMappings")
    private List<SchemaProfile.FieldMapping> fieldMappings;

    @JsonProperty("dialect")
    private String dialect;

    @JsonProperty("mainTable")
    private String mainTable;

    @JsonProperty("preset")
    private String preset;

    public RecordsDBOverlayModel() {
    }

    @JsonCreator
    public RecordsDBOverlayModel(
            @JsonProperty("fileMapping") Map<String, Object> fileMapping,
            @JsonProperty("fieldMappings") List<SchemaProfile.FieldMapping> fieldMappings,
            @JsonProperty("dialect") String dialect,
            @JsonProperty("mainTable") String mainTable,
            @JsonProperty("preset") String preset) {
        this.fileMapping = fileMapping;
        this.fieldMappings = fieldMappings;
        this.dialect = dialect;
        this.mainTable = mainTable;
        this.preset = preset;
    }

    /**
     * Creates an overlay model from a SchemaProfile
     */
    public static RecordsDBOverlayModel fromSchemaProfile(SchemaProfile profile) {
        RecordsDBOverlayModel model = new RecordsDBOverlayModel();
        model.fileMapping = profile.getFileMapping();
        model.fieldMappings = profile.getFieldMappings();
        model.dialect = profile.getDialect();
        model.mainTable = profile.getMainTable();
        model.preset = profile.getPreset();
        return model;
    }

    // Getters
    public Map<String, Object> getFileMapping() {
        return fileMapping;
    }

    public List<SchemaProfile.FieldMapping> getFieldMappings() {
        return fieldMappings;
    }

    public String getDialect() {
        return dialect;
    }

    public String getMainTable() {
        return mainTable;
    }

    public String getPreset() {
        return preset;
    }

    // Setters
    public void setFileMapping(Map<String, Object> fileMapping) {
        this.fileMapping = fileMapping;
    }

    public void setFieldMappings(List<SchemaProfile.FieldMapping> fieldMappings) {
        this.fieldMappings = fieldMappings;
    }

    public void setDialect(String dialect) {
        this.dialect = dialect;
    }

    public void setMainTable(String mainTable) {
        this.mainTable = mainTable;
    }

    public void setPreset(String preset) {
        this.preset = preset;
    }

    // OverlayModel interface methods
    @Override
    public void onBeforeSave(Project project) {
        // No action needed before save
    }

    @Override
    public void onAfterSave(Project project) {
        // No action needed after save
    }

    @Override
    public void dispose(Project project) {
        // No cleanup needed
    }
}

