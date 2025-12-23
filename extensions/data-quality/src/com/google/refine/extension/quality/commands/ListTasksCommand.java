/*
 * Data Quality Extension - List Tasks Command
 * Returns a list of quality check tasks for a project
 */
package com.google.refine.extension.quality.commands;

import java.io.IOException;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.commands.Command;
import com.google.refine.extension.quality.task.QualityCheckTask;
import com.google.refine.extension.quality.task.TaskPersistence;

/**
 * Command to list quality check tasks for a project.
 */
public class ListTasksCommand extends Command {

    private static final Logger logger = LoggerFactory.getLogger(ListTasksCommand.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        ObjectNode responseNode = mapper.createObjectNode();

        try {
            String projectParam = request.getParameter("project");

            List<QualityCheckTask> tasks;

            if (projectParam == null || projectParam.isEmpty() || "all".equals(projectParam)) {
                // Get all tasks from all projects (load from persistence)
                tasks = TaskPersistence.loadAllTasksFromAllProjects();

                // Also include any in-memory tasks that might not be persisted yet
                List<QualityCheckTask> memoryTasks = QualityCheckTask.getAllTasks();
                for (QualityCheckTask memTask : memoryTasks) {
                    boolean found = false;
                    for (QualityCheckTask diskTask : tasks) {
                        if (diskTask.getTaskId().equals(memTask.getTaskId())) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        tasks.add(memTask);
                    }
                }

                responseNode.put("code", "ok");
                responseNode.put("projectId", "all");
            } else {
                // Get tasks for specific project
                long projectId = Long.parseLong(projectParam);

                // Load tasks from persistence (also registers them in memory)
                tasks = TaskPersistence.loadAllTasks(projectId);

                // Also include any in-memory tasks that might not be persisted yet
                List<QualityCheckTask> memoryTasks = QualityCheckTask.getTasksByProject(projectId);
                for (QualityCheckTask memTask : memoryTasks) {
                    boolean found = false;
                    for (QualityCheckTask diskTask : tasks) {
                        if (diskTask.getTaskId().equals(memTask.getTaskId())) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        tasks.add(memTask);
                    }
                }

                responseNode.put("code", "ok");
                responseNode.put("projectId", projectId);
            }

            ArrayNode tasksArray = mapper.createArrayNode();
            for (QualityCheckTask task : tasks) {
                ObjectNode taskNode = mapper.createObjectNode();
                taskNode.put("taskId", task.getTaskId());
                taskNode.put("projectId", task.getProjectId());
                taskNode.put("status", task.getStatus().name());
                taskNode.put("currentPhase", task.getCurrentPhase());
                taskNode.put("progress", task.getProgressPercent());
                taskNode.put("totalRows", task.getTotalRows());
                taskNode.put("processedRows", task.getProcessedRows());
                taskNode.put("formatErrors", task.getFormatErrors());
                taskNode.put("resourceErrors", task.getResourceErrors());
                taskNode.put("contentErrors", task.getContentErrors());
                taskNode.put("createdAt", task.getCreatedAt());
                taskNode.put("completedAt", task.getCompletedAt());
                taskNode.put("pausedAt", task.getPausedAt());

                if (task.getErrorMessage() != null) {
                    taskNode.put("errorMessage", task.getErrorMessage());
                }
                if (task.getRuleId() != null) {
                    taskNode.put("ruleId", task.getRuleId());
                }

                tasksArray.add(taskNode);
            }

            responseNode.set("tasks", tasksArray);
            responseNode.put("count", tasks.size());

            logger.info("Listed " + tasks.size() + " tasks");

        } catch (Exception e) {
            logger.error("Error listing tasks", e);
            responseNode.put("code", "error");
            responseNode.put("message", e.getMessage());
        }

        respondJSON(response, responseNode);
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        doGet(request, response);
    }
}

