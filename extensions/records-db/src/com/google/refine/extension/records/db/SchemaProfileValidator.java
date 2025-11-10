/*
 * Schema Profile Validator
 */

package com.google.refine.extension.records.db;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.records.db.model.SchemaProfile;
import com.google.refine.extension.records.db.model.SchemaProfile.FieldMapping;

/**
 * Validates Schema Profile configuration
 */
public class SchemaProfileValidator {
    
    private static final Logger logger = LoggerFactory.getLogger("SchemaProfileValidator");
    
    /**
     * Validate Schema Profile
     * Returns list of validation errors (empty if valid)
     */
    public static List<String> validate(SchemaProfile profile) {
        List<String> errors = new ArrayList<>();
        
        if (profile == null) {
            errors.add("Schema Profile cannot be null");
            return errors;
        }
        
        // Validate basic configuration
        if (profile.getMode() == null || profile.getMode().isEmpty()) {
            errors.add("Mode is required");
        } else if (!isValidMode(profile.getMode())) {
            errors.add("Invalid mode: " + profile.getMode() + ". Must be 'catalog' or 'sql'");
        }
        
        // Validate database connection
        if (profile.getDialect() == null || profile.getDialect().isEmpty()) {
            errors.add("Database dialect is required");
        } else if (!isValidDialect(profile.getDialect())) {
            errors.add("Invalid dialect: " + profile.getDialect());
        }
        
        if (profile.getHost() == null || profile.getHost().isEmpty()) {
            errors.add("Database host is required");
        }
        
        if (profile.getPort() <= 0) {
            errors.add("Database port must be greater than 0");
        }
        
        if (profile.getDatabase() == null || profile.getDatabase().isEmpty()) {
            errors.add("Database name is required");
        }
        
        if (profile.getUsername() == null || profile.getUsername().isEmpty()) {
            errors.add("Database username is required");
        }
        
        // Validate table mapping
        if (profile.getMainTable() == null || profile.getMainTable().isEmpty()) {
            errors.add("Main table is required");
        }
        
        if (profile.getRecordIdColumn() == null || profile.getRecordIdColumn().isEmpty()) {
            errors.add("Record ID column is required");
        }
        
        // Validate field mappings
        if (profile.getFieldMappings() == null || profile.getFieldMappings().isEmpty()) {
            errors.add("At least one field mapping is required");
        } else {
            for (int i = 0; i < profile.getFieldMappings().size(); i++) {
                FieldMapping mapping = profile.getFieldMappings().get(i);
                List<String> fieldErrors = validateFieldMapping(mapping, i);
                errors.addAll(fieldErrors);
            }
        }
        
        // Validate pagination
        if (profile.getPageSize() <= 0) {
            errors.add("Page size must be greater than 0");
        }
        
        if (profile.getMaxRows() <= 0) {
            errors.add("Max rows must be greater than 0");
        }
        
        if (logger.isDebugEnabled()) {
            if (errors.isEmpty()) {
                logger.debug("Schema Profile validation passed");
            } else {
                logger.debug("Schema Profile validation failed with {} errors", errors.size());
                for (String error : errors) {
                    logger.debug("  - {}", error);
                }
            }
        }
        
        return errors;
    }
    
    /**
     * Validate a single field mapping
     */
    private static List<String> validateFieldMapping(FieldMapping mapping, int index) {
        List<String> errors = new ArrayList<>();
        
        if (mapping == null) {
            errors.add("Field mapping at index " + index + " is null");
            return errors;
        }
        
        if (mapping.getColumnName() == null || mapping.getColumnName().isEmpty()) {
            errors.add("Field mapping at index " + index + ": column name is required");
        }
        
        if (mapping.getColumnLabel() == null || mapping.getColumnLabel().isEmpty()) {
            errors.add("Field mapping at index " + index + ": column label is required");
        }
        
        if (mapping.getDataType() == null || mapping.getDataType().isEmpty()) {
            errors.add("Field mapping at index " + index + ": data type is required");
        } else if (!isValidDataType(mapping.getDataType())) {
            errors.add("Field mapping at index " + index + ": invalid data type: " + mapping.getDataType());
        }
        
        // If it's a JSON field, jsonPath is required
        if (mapping.isJsonField() && (mapping.getJsonPath() == null || mapping.getJsonPath().isEmpty())) {
            errors.add("Field mapping at index " + index + ": jsonPath is required for JSON fields");
        }
        
        return errors;
    }
    
    /**
     * Check if mode is valid
     */
    private static boolean isValidMode(String mode) {
        return "catalog".equals(mode) || "sql".equals(mode);
    }
    
    /**
     * Check if dialect is valid
     */
    private static boolean isValidDialect(String dialect) {
        return "mysql".equals(dialect) || 
               "postgresql".equals(dialect) || 
               "mariadb".equals(dialect) || 
               "sqlite".equals(dialect);
    }
    
    /**
     * Check if data type is valid
     */
    private static boolean isValidDataType(String dataType) {
        return "string".equals(dataType) || 
               "number".equals(dataType) || 
               "date".equals(dataType) || 
               "json".equals(dataType) || 
               "boolean".equals(dataType) || 
               "text".equals(dataType);
    }
}

