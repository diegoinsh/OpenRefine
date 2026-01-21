package com.google.refine.extension.quality.checker;

import java.util.HashMap;
import java.util.Map;

public class FileStatistics {
    private int totalFolders;
    private int totalFiles;
    private int imageFiles;
    private int otherFiles;
    private int blankPages;
    private int emptyFolders;
    private Map<String, Integer> pageSizeDistribution;

    public FileStatistics() {
        this.totalFolders = 0;
        this.totalFiles = 0;
        this.imageFiles = 0;
        this.otherFiles = 0;
        this.blankPages = 0;
        this.emptyFolders = 0;
        this.pageSizeDistribution = new HashMap<>();
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

    public int getBlankPages() {
        return blankPages;
    }

    public void setBlankPages(int blankPages) {
        this.blankPages = blankPages;
    }

    public void incrementBlankPages(int count) {
        this.blankPages += count;
    }

    public int getEmptyFolders() {
        return emptyFolders;
    }

    public void setEmptyFolders(int emptyFolders) {
        this.emptyFolders = emptyFolders;
    }

    public void incrementEmptyFolders(int count) {
        this.emptyFolders += count;
    }

    public Map<String, Integer> getPageSizeDistribution() {
        return pageSizeDistribution;
    }

    public void setPageSizeDistribution(Map<String, Integer> pageSizeDistribution) {
        this.pageSizeDistribution = pageSizeDistribution;
    }

    public void addPageSize(String pageSize) {
        if (pageSize == null || pageSize.isEmpty()) {
            pageSize = "UNKNOWN";
        }
        pageSizeDistribution.put(pageSize, pageSizeDistribution.getOrDefault(pageSize, 0) + 1);
    }
}
