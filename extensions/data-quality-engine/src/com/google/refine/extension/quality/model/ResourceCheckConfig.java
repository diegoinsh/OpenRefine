/*
 * Data Quality Extension - Resource Check Configuration Model
 */
package com.google.refine.extension.quality.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Configuration for resource/file association checks.
 */
public class ResourceCheckConfig {

    @JsonProperty("basePath")
    private String basePath;

    @JsonProperty("pathFields")
    private List<String> pathFields;

    @JsonProperty("pathMode")
    private String pathMode;

    @JsonProperty("separator")
    private String separator;

    @JsonProperty("template")
    private String template;

    @JsonProperty("folderChecks")
    private FolderChecks folderChecks;

    @JsonProperty("fileChecks")
    private FileChecks fileChecks;

    public ResourceCheckConfig() {
        this.basePath = "";
        this.pathFields = new ArrayList<>();
        this.pathMode = "separator";
        this.separator = "";
        this.template = "";
        this.folderChecks = new FolderChecks();
        this.fileChecks = new FileChecks();
    }

    @JsonCreator
    public ResourceCheckConfig(
            @JsonProperty("basePath") String basePath,
            @JsonProperty("pathFields") List<String> pathFields,
            @JsonProperty("pathMode") String pathMode,
            @JsonProperty("separator") String separator,
            @JsonProperty("template") String template,
            @JsonProperty("folderChecks") FolderChecks folderChecks,
            @JsonProperty("fileChecks") FileChecks fileChecks) {
        this.basePath = basePath != null ? basePath : "";
        this.pathFields = pathFields != null ? pathFields : new ArrayList<>();
        this.pathMode = pathMode != null ? pathMode : "separator";
        this.separator = separator != null ? separator : "";
        this.template = template != null ? template : "";
        this.folderChecks = folderChecks != null ? folderChecks : new FolderChecks();
        this.fileChecks = fileChecks != null ? fileChecks : new FileChecks();
    }

    // Getters and setters
    public String getBasePath() { return basePath; }
    public void setBasePath(String basePath) { this.basePath = basePath; }

    public List<String> getPathFields() { return pathFields; }
    public void setPathFields(List<String> pathFields) { this.pathFields = pathFields; }

    public String getPathMode() { return pathMode; }
    public void setPathMode(String pathMode) { this.pathMode = pathMode; }

    public String getSeparator() { return separator; }
    public void setSeparator(String separator) { this.separator = separator; }

    public String getTemplate() { return template; }
    public void setTemplate(String template) { this.template = template; }

    public FolderChecks getFolderChecks() { return folderChecks; }
    public void setFolderChecks(FolderChecks folderChecks) { this.folderChecks = folderChecks; }

    public FileChecks getFileChecks() { return fileChecks; }
    public void setFileChecks(FileChecks fileChecks) { this.fileChecks = fileChecks; }

    /**
     * Folder-level check configuration
     */
    public static class FolderChecks {
        @JsonProperty("existence")
        private boolean existence = true;

        @JsonProperty("dataExistence")
        private boolean dataExistence = true;

        @JsonProperty("nameFormat")
        private String nameFormat = "";

        @JsonProperty("sequential")
        private boolean sequential = true;

        @JsonProperty("emptyFolder")
        private boolean emptyFolder = true;

        public FolderChecks() {}

        @JsonCreator
        public FolderChecks(
                @JsonProperty("existence") boolean existence,
                @JsonProperty("dataExistence") boolean dataExistence,
                @JsonProperty("nameFormat") String nameFormat,
                @JsonProperty("sequential") boolean sequential,
                @JsonProperty("emptyFolder") boolean emptyFolder) {
            this.existence = existence;
            this.dataExistence = dataExistence;
            this.nameFormat = nameFormat != null ? nameFormat : "";
            this.sequential = sequential;
            this.emptyFolder = emptyFolder;
        }

        public boolean isExistence() { return existence; }
        public void setExistence(boolean existence) { this.existence = existence; }
        public boolean isDataExistence() { return dataExistence; }
        public void setDataExistence(boolean dataExistence) { this.dataExistence = dataExistence; }
        public String getNameFormat() { return nameFormat; }
        public void setNameFormat(String nameFormat) { this.nameFormat = nameFormat; }
        public boolean isSequential() { return sequential; }
        public void setSequential(boolean sequential) { this.sequential = sequential; }
        public boolean isEmptyFolder() { return emptyFolder; }
        public void setEmptyFolder(boolean emptyFolder) { this.emptyFolder = emptyFolder; }
    }

    /**
     * File-level check configuration
     */
    public static class FileChecks {
        @JsonProperty("countMatch")
        private boolean countMatch = true;

        @JsonProperty("countColumn")
        private String countColumn = "";

        @JsonProperty("nameFormat")
        private String nameFormat = "";

        @JsonProperty("sequential")
        private boolean sequential = true;

        public FileChecks() {}

        @JsonCreator
        public FileChecks(
                @JsonProperty("countMatch") boolean countMatch,
                @JsonProperty("countColumn") String countColumn,
                @JsonProperty("nameFormat") String nameFormat,
                @JsonProperty("sequential") boolean sequential) {
            this.countMatch = countMatch;
            this.countColumn = countColumn != null ? countColumn : "";
            this.nameFormat = nameFormat != null ? nameFormat : "";
            this.sequential = sequential;
        }

        public boolean isCountMatch() { return countMatch; }
        public void setCountMatch(boolean countMatch) { this.countMatch = countMatch; }
        public String getCountColumn() { return countColumn; }
        public void setCountColumn(String countColumn) { this.countColumn = countColumn; }
        public String getNameFormat() { return nameFormat; }
        public void setNameFormat(String nameFormat) { this.nameFormat = nameFormat; }
        public boolean isSequential() { return sequential; }
        public void setSequential(boolean sequential) { this.sequential = sequential; }
    }
}

