/*
 * Records Database Schema Profile Model
 */

package com.google.refine.extension.records.db.model;

import java.util.List;
import java.util.Map;

/**
 * Represents a Schema Profile configuration for database import
 * Describes the mapping between database tables/columns and OpenRefine project structure
 */
public class SchemaProfile {
    
    private String name;
    private String description;
    private String mode; // "catalog" or "sql"
    private String preset; // "kubao", "flat_table", "generic_json", etc.
    
    // Database connection info
    private String dialect; // "mysql", "postgresql", "mariadb", "sqlite"
    private String host;
    private int port;
    private String database;
    private String username;
    private String password;
    
    // Table mapping
    private String mainTable;
    private String recordIdColumn;
    private String recordTypeColumn;
    
    // Field mapping
    private List<FieldMapping> fieldMappings;
    
    // File/Asset mapping
    private String fileRootColumn;
    private String fileRootRawColumn;
    private List<String> allowedRoots;
    
    // Filtering
    private Map<String, Object> filters;
    
    // Pagination
    private int pageSize;
    private int maxRows;
    
    public SchemaProfile() {
        this.pageSize = 100;
        this.maxRows = 10000;
    }
    
    // Getters and Setters
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    public String getMode() {
        return mode;
    }
    
    public void setMode(String mode) {
        this.mode = mode;
    }
    
    public String getPreset() {
        return preset;
    }
    
    public void setPreset(String preset) {
        this.preset = preset;
    }
    
    public String getDialect() {
        return dialect;
    }
    
    public void setDialect(String dialect) {
        this.dialect = dialect;
    }
    
    public String getHost() {
        return host;
    }
    
    public void setHost(String host) {
        this.host = host;
    }
    
    public int getPort() {
        return port;
    }
    
    public void setPort(int port) {
        this.port = port;
    }
    
    public String getDatabase() {
        return database;
    }
    
    public void setDatabase(String database) {
        this.database = database;
    }
    
    public String getUsername() {
        return username;
    }
    
    public void setUsername(String username) {
        this.username = username;
    }
    
    public String getPassword() {
        return password;
    }
    
    public void setPassword(String password) {
        this.password = password;
    }
    
    public String getMainTable() {
        return mainTable;
    }
    
    public void setMainTable(String mainTable) {
        this.mainTable = mainTable;
    }
    
    public String getRecordIdColumn() {
        return recordIdColumn;
    }
    
    public void setRecordIdColumn(String recordIdColumn) {
        this.recordIdColumn = recordIdColumn;
    }
    
    public String getRecordTypeColumn() {
        return recordTypeColumn;
    }
    
    public void setRecordTypeColumn(String recordTypeColumn) {
        this.recordTypeColumn = recordTypeColumn;
    }
    
    public List<FieldMapping> getFieldMappings() {
        return fieldMappings;
    }
    
    public void setFieldMappings(List<FieldMapping> fieldMappings) {
        this.fieldMappings = fieldMappings;
    }
    
    public String getFileRootColumn() {
        return fileRootColumn;
    }
    
    public void setFileRootColumn(String fileRootColumn) {
        this.fileRootColumn = fileRootColumn;
    }
    
    public String getFileRootRawColumn() {
        return fileRootRawColumn;
    }
    
    public void setFileRootRawColumn(String fileRootRawColumn) {
        this.fileRootRawColumn = fileRootRawColumn;
    }
    
    public List<String> getAllowedRoots() {
        return allowedRoots;
    }
    
    public void setAllowedRoots(List<String> allowedRoots) {
        this.allowedRoots = allowedRoots;
    }
    
    public Map<String, Object> getFilters() {
        return filters;
    }
    
    public void setFilters(Map<String, Object> filters) {
        this.filters = filters;
    }
    
    public int getPageSize() {
        return pageSize;
    }
    
    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }
    
    public int getMaxRows() {
        return maxRows;
    }
    
    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }
    
    /**
     * Field mapping configuration
     */
    public static class FieldMapping {
        private String columnName;
        private String columnLabel;
        private String dataType; // "string", "number", "date", "json", etc.
        private boolean isJsonField;
        private String jsonPath; // For extracting from JSON fields
        private boolean isExportFlag;
        
        public FieldMapping() {
        }
        
        public FieldMapping(String columnName, String columnLabel) {
            this.columnName = columnName;
            this.columnLabel = columnLabel;
            this.dataType = "string";
            this.isJsonField = false;
            this.isExportFlag = false;
        }
        
        // Getters and Setters
        public String getColumnName() {
            return columnName;
        }
        
        public void setColumnName(String columnName) {
            this.columnName = columnName;
        }
        
        public String getColumnLabel() {
            return columnLabel;
        }
        
        public void setColumnLabel(String columnLabel) {
            this.columnLabel = columnLabel;
        }
        
        public String getDataType() {
            return dataType;
        }
        
        public void setDataType(String dataType) {
            this.dataType = dataType;
        }
        
        public boolean isJsonField() {
            return isJsonField;
        }
        
        public void setJsonField(boolean jsonField) {
            isJsonField = jsonField;
        }
        
        public String getJsonPath() {
            return jsonPath;
        }
        
        public void setJsonPath(String jsonPath) {
            this.jsonPath = jsonPath;
        }
        
        public boolean isExportFlag() {
            return isExportFlag;
        }
        
        public void setExportFlag(boolean exportFlag) {
            isExportFlag = exportFlag;
        }
    }
}

