package com.google.refine.extension.quality.checker;

public class FileStatistics {
    private int totalFolders;
    private int totalFiles;
    private int imageFiles;
    private int otherFiles;

    public FileStatistics() {
        this.totalFolders = 0;
        this.totalFiles = 0;
        this.imageFiles = 0;
        this.otherFiles = 0;
    }

    public int getTotalFolders() {
        return totalFolders;
    }

    public void setTotalFolders(int totalFolders) {
        this.totalFolders = totalFolders;
    }

    public int getTotalFiles() {
        return totalFiles;
    }

    public void setTotalFiles(int totalFiles) {
        this.totalFiles = totalFiles;
    }

    public int getImageFiles() {
        return imageFiles;
    }

    public void setImageFiles(int imageFiles) {
        this.imageFiles = imageFiles;
    }

    public int getOtherFiles() {
        return otherFiles;
    }

    public void setOtherFiles(int otherFiles) {
        this.otherFiles = otherFiles;
    }

    public void incrementFolders(int count) {
        this.totalFolders += count;
    }

    public void incrementFiles(int count) {
        this.totalFiles += count;
    }

    public void incrementImageFiles(int count) {
        this.imageFiles += count;
    }

    public void incrementOtherFiles(int count) {
        this.otherFiles += count;
    }
}
