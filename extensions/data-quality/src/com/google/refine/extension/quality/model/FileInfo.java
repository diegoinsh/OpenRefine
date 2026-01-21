package com.google.refine.extension.quality.model;

public class FileInfo {
    private String resourcePath;
    private String fileName;
    
    public FileInfo(String resourcePath, String fileName) {
        this.resourcePath = resourcePath;
        this.fileName = fileName;
    }
    
    public String getResourcePath() {
        return resourcePath;
    }
    
    public void setResourcePath(String resourcePath) {
        this.resourcePath = resourcePath;
    }
    
    public String getFileName() {
        return fileName;
    }
    
    public void setFileName(String fileName) {
        this.fileName = fileName;
    }
}
