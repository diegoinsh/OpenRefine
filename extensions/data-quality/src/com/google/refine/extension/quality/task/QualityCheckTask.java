/*
 * Data Quality Extension - Quality Check Task
 * Manages async quality check task state and progress
 * Supports pause/resume/cancel and checkpoint persistence
 */
package com.google.refine.extension.quality.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.google.refine.extension.quality.model.CheckResult;

/**
 * Represents an async quality check task with progress tracking.
 * Supports pause/resume/cancel operations and checkpoint persistence.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class QualityCheckTask {

    public enum TaskStatus {
        PENDING,
        RUNNING,
        PAUSED,      // Added: task is paused, can be resumed
        COMPLETED,
        FAILED,
        CANCELLED
    }

    // Static task storage (in-memory cache)
    @JsonIgnore
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
    private volatile int imageQualityErrors;
    private volatile int checkedRows;
    private volatile int passedRows;
    private volatile int failedRows;
    private volatile String errorMessage;
    private volatile String errorKey;
    @JsonIgnore
    private volatile Object result;
    private final long createdAt;
    private volatile long completedAt;
    private volatile long pausedAt;
    private volatile long resumedAt;

    // Content check specific progress
    private volatile int contentCheckTotal;
    private final AtomicInteger contentCheckProcessed;

    // Image quality check specific progress
    private volatile int imageQualityCheckTotal;
    private final AtomicInteger imageQualityCheckProcessed;

    // Checkpoint for resume support
    private volatile Checkpoint checkpoint;

    // Control flags for pause/cancel
    @JsonIgnore
    private volatile boolean pauseRequested;
    @JsonIgnore
    private volatile boolean cancelRequested;

    // Intermediate results storage for resume
    @JsonIgnore
    private volatile CheckResult formatResult;
    @JsonIgnore
    private volatile CheckResult resourceResult;
    @JsonIgnore
    private volatile CheckResult contentResult;
    @JsonIgnore
    private volatile CheckResult imageQualityResult;

    // Rule ID for reference
    private volatile String ruleId;

    /**
     * Constructor for normal task creation
     */
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
        this.imageQualityCheckTotal = 0;
        this.imageQualityCheckProcessed = new AtomicInteger(0);
        this.pauseRequested = false;
        this.cancelRequested = false;
        this.checkedRows = 0;
        this.passedRows = 0;
        this.failedRows = 0;

        // Register task in memory
        tasks.put(taskId, this);
    }

    /**
     * Constructor for Jackson deserialization
     */
    @JsonCreator
    public QualityCheckTask(
            @JsonProperty("taskId") String taskId,
            @JsonProperty("projectId") long projectId,
            @JsonProperty("status") TaskStatus status,
            @JsonProperty("currentPhase") String currentPhase,
            @JsonProperty("processedRows") int processedRows,
            @JsonProperty("totalRows") int totalRows,
            @JsonProperty("formatErrors") int formatErrors,
            @JsonProperty("resourceErrors") int resourceErrors,
            @JsonProperty("contentErrors") int contentErrors,
            @JsonProperty("imageQualityErrors") int imageQualityErrors,
            @JsonProperty("checkedRows") int checkedRows,
            @JsonProperty("passedRows") int passedRows,
            @JsonProperty("failedRows") int failedRows,
            @JsonProperty("errorMessage") String errorMessage,
            @JsonProperty("errorKey") String errorKey,
            @JsonProperty("createdAt") long createdAt,
            @JsonProperty("completedAt") long completedAt,
            @JsonProperty("pausedAt") long pausedAt,
            @JsonProperty("resumedAt") long resumedAt,
            @JsonProperty("contentCheckTotal") int contentCheckTotal,
            @JsonProperty("contentCheckProcessed") int contentCheckProcessed,
            @JsonProperty("imageQualityCheckTotal") int imageQualityCheckTotal,
            @JsonProperty("imageQualityCheckProcessed") int imageQualityCheckProcessed,
            @JsonProperty("checkpoint") Checkpoint checkpoint,
            @JsonProperty("ruleId") String ruleId
    ) {
        this.taskId = taskId;
        this.projectId = projectId;
        this.status = status != null ? status : TaskStatus.PENDING;
        this.currentPhase = currentPhase != null ? currentPhase : "初始化";
        this.processedRows = new AtomicInteger(processedRows);
        this.totalRows = totalRows;
        this.formatErrors = formatErrors;
        this.resourceErrors = resourceErrors;
        this.contentErrors = contentErrors;
        this.imageQualityErrors = imageQualityErrors;
        this.checkedRows = checkedRows;
        this.passedRows = passedRows;
        this.failedRows = failedRows;
        this.errorMessage = errorMessage;
        this.errorKey = errorKey;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
        this.pausedAt = pausedAt;
        this.resumedAt = resumedAt;
        this.contentCheckTotal = contentCheckTotal;
        this.contentCheckProcessed = new AtomicInteger(contentCheckProcessed);
        this.imageQualityCheckTotal = imageQualityCheckTotal;
        this.imageQualityCheckProcessed = new AtomicInteger(imageQualityCheckProcessed);
        this.checkpoint = checkpoint;
        this.ruleId = ruleId;
        this.pauseRequested = false;
        this.cancelRequested = false;
        // Note: Don't register in memory during deserialization, caller should do it
    }

    // Static methods for task management
    public static QualityCheckTask getTask(String taskId) {
        return tasks.get(taskId);
    }

    public static void removeTask(String taskId) {
        tasks.remove(taskId);
    }

    public static void registerTask(QualityCheckTask task) {
        if (task != null && task.getTaskId() != null) {
            tasks.put(task.getTaskId(), task);
        }
    }

    public static List<QualityCheckTask> getTasksByProject(long projectId) {
        List<QualityCheckTask> projectTasks = new ArrayList<>();
        for (QualityCheckTask task : tasks.values()) {
            if (task.getProjectId() == projectId) {
                projectTasks.add(task);
            }
        }
        return projectTasks;
    }

    /**
     * Get the most recent active task for a project (RUNNING or PAUSED)
     */
    public static QualityCheckTask getTaskByProject(long projectId) {
        QualityCheckTask activeTask = null;
        long latestTime = 0;

        for (QualityCheckTask task : tasks.values()) {
            if (task.getProjectId() == projectId) {
                TaskStatus status = task.getStatus();
                if (status == TaskStatus.RUNNING || status == TaskStatus.PAUSED) {
                    if (task.getCreatedAt() > latestTime) {
                        activeTask = task;
                        latestTime = task.getCreatedAt();
                    }
                }
            }
        }
        return activeTask;
    }

    public static List<QualityCheckTask> getAllTasks() {
        return new ArrayList<>(tasks.values());
    }

    public static String generateTaskId(long projectId) {
        return "qc-" + projectId + "-" + System.currentTimeMillis();
    }

    // Control methods
    public void requestPause() {
        if (status == TaskStatus.RUNNING) {
            this.pauseRequested = true;
        }
    }

    public void requestCancel() {
        if (status == TaskStatus.RUNNING || status == TaskStatus.PAUSED) {
            this.cancelRequested = true;
            if (status == TaskStatus.PAUSED) {
                // If paused, set to cancelled immediately
                this.status = TaskStatus.CANCELLED;
                this.completedAt = System.currentTimeMillis();
            }
        }
    }

    public void resume() {
        if (status == TaskStatus.PAUSED) {
            this.pauseRequested = false;
            this.resumedAt = System.currentTimeMillis();
            this.status = TaskStatus.RUNNING;
        }
    }

    public boolean shouldPause() {
        return pauseRequested;
    }

    public boolean shouldCancel() {
        return cancelRequested;
    }

    public void markPaused() {
        this.status = TaskStatus.PAUSED;
        this.pausedAt = System.currentTimeMillis();
        saveCheckpoint();
    }

    public void markCancelled() {
        this.status = TaskStatus.CANCELLED;
        this.completedAt = System.currentTimeMillis();
    }

    // Checkpoint management
    public void saveCheckpoint() {
        this.checkpoint = new Checkpoint(
            processedRows.get(),
            currentPhase,
            contentCheckProcessed.get(),
            System.currentTimeMillis()
        );
    }

    public Checkpoint getCheckpoint() {
        return checkpoint;
    }

    public void setCheckpoint(Checkpoint checkpoint) {
        this.checkpoint = checkpoint;
        if (checkpoint != null) {
            this.processedRows.set(checkpoint.getLastProcessedRowIndex());
            this.contentCheckProcessed.set(checkpoint.getContentCheckProcessed());
            this.currentPhase = checkpoint.getLastPhase();
        }
    }

    // Progress calculation
    public int getProgressPercent() {
        if (status == TaskStatus.COMPLETED) return 100;
        if (status == TaskStatus.PENDING) return 0;

        // Weight: format(15%) + resource(15%) + content(35%) + imageQuality(35%)
        int baseProgress = 0;

        if ("格式检查".equals(currentPhase)) {
            baseProgress = 0;
            if (totalRows > 0) {
                return Math.min(14, baseProgress + (processedRows.get() * 15 / totalRows));
            }
        } else if ("资源检查".equals(currentPhase)) {
            baseProgress = 15;
            if (totalRows > 0) {
                return Math.min(29, baseProgress + (processedRows.get() * 15 / totalRows));
            }
        } else if ("内容检查".equals(currentPhase)) {
            baseProgress = 30;
            if (contentCheckTotal > 0) {
                return Math.min(64, baseProgress + (contentCheckProcessed.get() * 35 / contentCheckTotal));
            }
        } else if ("图像质量检查".equals(currentPhase)) {
            baseProgress = 65;
            if (imageQualityCheckTotal > 0) {
                return Math.min(99, baseProgress + (imageQualityCheckProcessed.get() * 35 / imageQualityCheckTotal));
            }
        }

        return baseProgress;
    }

    public void incrementContentCheckProcessed() {
        contentCheckProcessed.incrementAndGet();
    }

    public void setProcessedRows(int rows) {
        processedRows.set(rows);
    }

    public void setContentCheckProcessed(int processed) {
        contentCheckProcessed.set(processed);
    }

    // Getters and setters
    @JsonProperty("taskId")
    public String getTaskId() { return taskId; }

    @JsonProperty("projectId")
    public long getProjectId() { return projectId; }

    @JsonProperty("status")
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }

    @JsonProperty("currentPhase")
    public String getCurrentPhase() { return currentPhase; }
    public void setCurrentPhase(String phase) { this.currentPhase = phase; }

    @JsonProperty("processedRows")
    public int getProcessedRows() { return processedRows.get(); }
    public void incrementProcessedRows() { processedRows.incrementAndGet(); }

    @JsonProperty("totalRows")
    public int getTotalRows() { return totalRows; }
    public void setTotalRows(int totalRows) { this.totalRows = totalRows; }

    @JsonProperty("formatErrors")
    public int getFormatErrors() { return formatErrors; }
    public void setFormatErrors(int formatErrors) { this.formatErrors = formatErrors; }

    @JsonProperty("resourceErrors")
    public int getResourceErrors() { return resourceErrors; }
    public void setResourceErrors(int resourceErrors) { this.resourceErrors = resourceErrors; }

    @JsonProperty("contentErrors")
    public int getContentErrors() { return contentErrors; }
    public void setContentErrors(int contentErrors) { this.contentErrors = contentErrors; }

    @JsonProperty("imageQualityErrors")
    public int getImageQualityErrors() { return imageQualityErrors; }
    public void setImageQualityErrors(int imageQualityErrors) { this.imageQualityErrors = imageQualityErrors; }

    @JsonProperty("checkedRows")
    public int getCheckedRows() { return checkedRows; }
    public void setCheckedRows(int checkedRows) { this.checkedRows = checkedRows; }

    @JsonProperty("passedRows")
    public int getPassedRows() { return passedRows; }
    public void setPassedRows(int passedRows) { this.passedRows = passedRows; }

    @JsonProperty("failedRows")
    public int getFailedRows() { return failedRows; }
    public void setFailedRows(int failedRows) { this.failedRows = failedRows; }

    @JsonProperty("errorMessage")
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    @JsonProperty("errorKey")
    public String getErrorKey() { return errorKey; }
    public void setErrorKey(String errorKey) { this.errorKey = errorKey; }

    @JsonIgnore
    public Object getResult() { return result; }
    public void setResult(Object result) { this.result = result; }

    @JsonProperty("createdAt")
    public long getCreatedAt() { return createdAt; }

    @JsonProperty("completedAt")
    public long getCompletedAt() { return completedAt; }
    public void setCompletedAt(long completedAt) { this.completedAt = completedAt; }

    @JsonProperty("pausedAt")
    public long getPausedAt() { return pausedAt; }
    public void setPausedAt(long pausedAt) { this.pausedAt = pausedAt; }

    @JsonProperty("resumedAt")
    public long getResumedAt() { return resumedAt; }
    public void setResumedAt(long resumedAt) { this.resumedAt = resumedAt; }

    @JsonProperty("contentCheckTotal")
    public int getContentCheckTotal() { return contentCheckTotal; }
    public void setContentCheckTotal(int total) { this.contentCheckTotal = total; }

    @JsonProperty("contentCheckProcessed")
    public int getContentCheckProcessed() { return contentCheckProcessed.get(); }

    @JsonProperty("imageQualityCheckTotal")
    public int getImageQualityCheckTotal() { return imageQualityCheckTotal; }
    public void setImageQualityCheckTotal(int total) { this.imageQualityCheckTotal = total; }

    @JsonProperty("imageQualityCheckProcessed")
    public int getImageQualityCheckProcessed() { return imageQualityCheckProcessed.get(); }
    public void incrementImageQualityCheckProcessed() { imageQualityCheckProcessed.incrementAndGet(); }
    public void setImageQualityCheckProcessed(int processed) { imageQualityCheckProcessed.set(processed); }

    @JsonProperty("ruleId")
    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }

    // Intermediate results for resume
    @JsonIgnore
    public CheckResult getFormatResult() { return formatResult; }
    public void setFormatResult(CheckResult formatResult) { this.formatResult = formatResult; }

    @JsonIgnore
    public CheckResult getResourceResult() { return resourceResult; }
    public void setResourceResult(CheckResult resourceResult) { this.resourceResult = resourceResult; }

    @JsonIgnore
    public CheckResult getContentResult() { return contentResult; }
    public void setContentResult(CheckResult contentResult) { this.contentResult = contentResult; }

    @JsonIgnore
    public CheckResult getImageQualityResult() { return imageQualityResult; }
    public void setImageQualityResult(CheckResult imageQualityResult) { this.imageQualityResult = imageQualityResult; }

    // Methods for checker interruption support
    /**
     * Check if the task should stop (cancelled)
     */
    public boolean shouldStop() {
        return cancelRequested;
    }

    /**
     * Check if the task is currently paused
     */
    public boolean isPaused() {
        return status == TaskStatus.PAUSED || pauseRequested;
    }

    /**
     * Set format check checkpoint for resume
     */
    public void setFormatCheckpoint(int rowIndex) {
        this.checkpoint = new Checkpoint(rowIndex, "格式检查", contentCheckProcessed.get(), System.currentTimeMillis());
    }

    /**
     * Set format check progress
     */
    public void setFormatCheckProgress(int processed, int total) {
        this.processedRows.set(processed);
        this.totalRows = total;
    }

    /**
     * Set content check checkpoint for resume
     */
    public void setContentCheckpoint(int processedIndex) {
        this.checkpoint = new Checkpoint(processedRows.get(), "内容检查", processedIndex, System.currentTimeMillis());
    }

    /**
     * Set resource check checkpoint for resume
     */
    public void setResourceCheckpoint(int rowIndex) {
        this.checkpoint = new Checkpoint(rowIndex, "资源检查", contentCheckProcessed.get(), System.currentTimeMillis());
    }

    /**
     * Set image quality check checkpoint for resume
     */
    public void setImageQualityCheckpoint(int processedIndex) {
        this.checkpoint = new Checkpoint(processedRows.get(), "图像质量检查", processedIndex, System.currentTimeMillis());
    }

    /**
     * Checkpoint class for saving task progress
     */
    public static class Checkpoint {
        private int lastProcessedRowIndex;
        private String lastPhase;
        private int contentCheckProcessed;
        private long savedAt;

        public Checkpoint() {}

        public Checkpoint(int lastProcessedRowIndex, String lastPhase, int contentCheckProcessed, long savedAt) {
            this.lastProcessedRowIndex = lastProcessedRowIndex;
            this.lastPhase = lastPhase;
            this.contentCheckProcessed = contentCheckProcessed;
            this.savedAt = savedAt;
        }

        @JsonProperty("lastProcessedRowIndex")
        public int getLastProcessedRowIndex() { return lastProcessedRowIndex; }
        public void setLastProcessedRowIndex(int lastProcessedRowIndex) { this.lastProcessedRowIndex = lastProcessedRowIndex; }

        @JsonProperty("lastPhase")
        public String getLastPhase() { return lastPhase; }
        public void setLastPhase(String lastPhase) { this.lastPhase = lastPhase; }

        @JsonProperty("contentCheckProcessed")
        public int getContentCheckProcessed() { return contentCheckProcessed; }
        public void setContentCheckProcessed(int contentCheckProcessed) { this.contentCheckProcessed = contentCheckProcessed; }

        @JsonProperty("savedAt")
        public long getSavedAt() { return savedAt; }
        public void setSavedAt(long savedAt) { this.savedAt = savedAt; }
    }
}

