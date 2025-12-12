/*
 * Data Quality Extension - Quality Check Task
 * Manages async quality check task state and progress
 */
package com.google.refine.extension.quality.task;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents an async quality check task with progress tracking.
 */
public class QualityCheckTask {

    public enum TaskStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    // Static task storage
    private static final Map<String, QualityCheckTask> tasks = new ConcurrentHashMap<>();

    private final String taskId;
    private final long projectId;
    private volatile TaskStatus status;
    private volatile String currentPhase;
    private final AtomicInteger processedRows;
    private volatile int totalRows;
    private volatile int formatErrors;
    private volatile int resourceErrors;
    private volatile int contentErrors;
    private volatile String errorMessage;
    private volatile Object result;
    private final long createdAt;
    private volatile long completedAt;

    // Content check specific progress
    private volatile int contentCheckTotal;
    private final AtomicInteger contentCheckProcessed;

    public QualityCheckTask(String taskId, long projectId) {
        this.taskId = taskId;
        this.projectId = projectId;
        this.status = TaskStatus.PENDING;
        this.currentPhase = "初始化";
        this.processedRows = new AtomicInteger(0);
        this.totalRows = 0;
        this.createdAt = System.currentTimeMillis();
        this.contentCheckTotal = 0;
        this.contentCheckProcessed = new AtomicInteger(0);

        // Register task
        tasks.put(taskId, this);
    }

    // Static methods for task management
    public static QualityCheckTask getTask(String taskId) {
        return tasks.get(taskId);
    }

    public static void removeTask(String taskId) {
        tasks.remove(taskId);
    }

    public static String generateTaskId(long projectId) {
        return "qc-" + projectId + "-" + System.currentTimeMillis();
    }

    // Progress calculation
    public int getProgressPercent() {
        if (status == TaskStatus.COMPLETED) return 100;
        if (status == TaskStatus.PENDING) return 0;

        // Weight: format(20%) + resource(20%) + content(60%)
        int baseProgress = 0;

        if ("格式检查".equals(currentPhase)) {
            baseProgress = 0;
            if (totalRows > 0) {
                return Math.min(19, baseProgress + (processedRows.get() * 20 / totalRows));
            }
        } else if ("资源检查".equals(currentPhase)) {
            baseProgress = 20;
            if (totalRows > 0) {
                return Math.min(39, baseProgress + (processedRows.get() * 20 / totalRows));
            }
        } else if ("内容检查".equals(currentPhase)) {
            baseProgress = 40;
            if (contentCheckTotal > 0) {
                return Math.min(99, baseProgress + (contentCheckProcessed.get() * 60 / contentCheckTotal));
            }
        }

        return baseProgress;
    }

    public void incrementContentCheckProcessed() {
        contentCheckProcessed.incrementAndGet();
    }

    // Getters and setters
    public String getTaskId() { return taskId; }
    public long getProjectId() { return projectId; }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }
    public String getCurrentPhase() { return currentPhase; }
    public void setCurrentPhase(String phase) { this.currentPhase = phase; }
    public int getProcessedRows() { return processedRows.get(); }
    public void incrementProcessedRows() { processedRows.incrementAndGet(); }
    public int getTotalRows() { return totalRows; }
    public void setTotalRows(int totalRows) { this.totalRows = totalRows; }
    public int getFormatErrors() { return formatErrors; }
    public void setFormatErrors(int formatErrors) { this.formatErrors = formatErrors; }
    public int getResourceErrors() { return resourceErrors; }
    public void setResourceErrors(int resourceErrors) { this.resourceErrors = resourceErrors; }
    public int getContentErrors() { return contentErrors; }
    public void setContentErrors(int contentErrors) { this.contentErrors = contentErrors; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Object getResult() { return result; }
    public void setResult(Object result) { this.result = result; }
    public long getCreatedAt() { return createdAt; }
    public long getCompletedAt() { return completedAt; }
    public void setCompletedAt(long completedAt) { this.completedAt = completedAt; }
    public int getContentCheckTotal() { return contentCheckTotal; }
    public void setContentCheckTotal(int total) { this.contentCheckTotal = total; }
    public int getContentCheckProcessed() { return contentCheckProcessed.get(); }
}

