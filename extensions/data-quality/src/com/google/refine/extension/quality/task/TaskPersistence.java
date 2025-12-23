/*
 * Data Quality Extension - Task Persistence
 * Handles saving and loading task state to/from project directory
 */
package com.google.refine.extension.quality.task;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.ProjectManager;
import com.google.refine.ProjectMetadata;

/**
 * Handles task persistence to project directory.
 * Tasks are saved to: {project-dir}/quality/tasks/{task-id}.json
 */
public class TaskPersistence {

    private static final Logger logger = LoggerFactory.getLogger(TaskPersistence.class);
    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    
    private static final String QUALITY_DIR = "quality";
    private static final String TASKS_DIR = "tasks";

    /**
     * Get the tasks directory for a project
     */
    public static File getTasksDir(long projectId) {
        if (!(ProjectManager.singleton instanceof com.google.refine.io.FileProjectManager)) {
            logger.warn("ProjectManager is not FileProjectManager, cannot get project directory");
            return null;
        }
        com.google.refine.io.FileProjectManager fpm = (com.google.refine.io.FileProjectManager) ProjectManager.singleton;
        File projectDir = fpm.getProjectDir(projectId);
        if (projectDir == null) {
            return null;
        }
        File qualityDir = new File(projectDir, QUALITY_DIR);
        File tasksDir = new File(qualityDir, TASKS_DIR);
        return tasksDir;
    }

    /**
     * Ensure the tasks directory exists
     */
    private static File ensureTasksDir(long projectId) {
        File tasksDir = getTasksDir(projectId);
        if (tasksDir != null && !tasksDir.exists()) {
            tasksDir.mkdirs();
        }
        return tasksDir;
    }

    /**
     * Save task state to disk
     */
    public static void saveTask(QualityCheckTask task) {
        if (task == null) return;
        
        try {
            File tasksDir = ensureTasksDir(task.getProjectId());
            if (tasksDir == null) {
                logger.warn("Cannot save task - project directory not found: " + task.getProjectId());
                return;
            }
            
            File taskFile = new File(tasksDir, task.getTaskId() + ".json");
            mapper.writeValue(taskFile, task);
            logger.info("Saved task to: " + taskFile.getAbsolutePath());
        } catch (IOException e) {
            logger.error("Failed to save task: " + task.getTaskId(), e);
        }
    }

    /**
     * Load task state from disk
     */
    public static QualityCheckTask loadTask(long projectId, String taskId) {
        File tasksDir = getTasksDir(projectId);
        if (tasksDir == null || !tasksDir.exists()) {
            return null;
        }
        
        File taskFile = new File(tasksDir, taskId + ".json");
        if (!taskFile.exists()) {
            return null;
        }
        
        try {
            QualityCheckTask task = mapper.readValue(taskFile, QualityCheckTask.class);
            // Register in memory cache
            QualityCheckTask.registerTask(task);
            logger.info("Loaded task from: " + taskFile.getAbsolutePath());
            return task;
        } catch (IOException e) {
            logger.error("Failed to load task: " + taskId, e);
            return null;
        }
    }

    /**
     * Load all tasks for a project
     */
    public static List<QualityCheckTask> loadAllTasks(long projectId) {
        List<QualityCheckTask> tasks = new ArrayList<>();
        File tasksDir = getTasksDir(projectId);
        
        if (tasksDir == null || !tasksDir.exists()) {
            return tasks;
        }
        
        File[] taskFiles = tasksDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (taskFiles == null) {
            return tasks;
        }
        
        for (File taskFile : taskFiles) {
            try {
                QualityCheckTask task = mapper.readValue(taskFile, QualityCheckTask.class);
                QualityCheckTask.registerTask(task);
                tasks.add(task);
            } catch (IOException e) {
                logger.error("Failed to load task file: " + taskFile.getName(), e);
            }
        }
        
        logger.info("Loaded " + tasks.size() + " tasks for project: " + projectId);
        return tasks;
    }

    /**
     * Delete task file from disk
     */
    public static void deleteTask(long projectId, String taskId) {
        File tasksDir = getTasksDir(projectId);
        if (tasksDir == null || !tasksDir.exists()) {
            return;
        }
        
        File taskFile = new File(tasksDir, taskId + ".json");
        if (taskFile.exists()) {
            if (taskFile.delete()) {
                logger.info("Deleted task file: " + taskFile.getAbsolutePath());
            } else {
                logger.warn("Failed to delete task file: " + taskFile.getAbsolutePath());
            }
        }
        
        // Also remove from memory
        QualityCheckTask.removeTask(taskId);
    }

    /**
     * Get the latest task for a project (most recent by createdAt)
     */
    public static QualityCheckTask getLatestTask(long projectId) {
        List<QualityCheckTask> tasks = loadAllTasks(projectId);
        if (tasks.isEmpty()) {
            return null;
        }

        QualityCheckTask latest = null;
        for (QualityCheckTask task : tasks) {
            if (latest == null || task.getCreatedAt() > latest.getCreatedAt()) {
                latest = task;
            }
        }
        return latest;
    }

    /**
     * Load all tasks from all projects
     */
    public static List<QualityCheckTask> loadAllTasksFromAllProjects() {
        List<QualityCheckTask> allTasks = new ArrayList<>();

        if (ProjectManager.singleton == null) {
            logger.warn("ProjectManager not initialized");
            return allTasks;
        }

        Map<Long, ProjectMetadata> projects = ProjectManager.singleton.getAllProjectMetadata();
        if (projects == null || projects.isEmpty()) {
            return allTasks;
        }

        for (Long projectId : projects.keySet()) {
            List<QualityCheckTask> projectTasks = loadAllTasks(projectId);
            allTasks.addAll(projectTasks);
        }

        logger.info("Loaded " + allTasks.size() + " tasks from " + projects.size() + " projects");
        return allTasks;
    }
}

