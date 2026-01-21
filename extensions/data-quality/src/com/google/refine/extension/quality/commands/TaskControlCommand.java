/*
 * Data Quality Extension - Task Control Command
 * Handles pause/resume/cancel operations for quality check tasks
 */
package com.google.refine.extension.quality.commands;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.commands.Command;
import com.google.refine.extension.quality.task.QualityCheckTask;
import com.google.refine.extension.quality.task.TaskPersistence;

/**
 * Command to control quality check tasks (pause/resume/cancel).
 * Actions:
 *   - pause: Pause a running task
 *   - resume: Resume a paused task
 *   - cancel: Cancel a running or paused task
 */
public class TaskControlCommand extends Command {

    private static final Logger logger = LoggerFactory.getLogger(TaskControlCommand.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        if (!hasValidCSRFToken(request)) {
            respondCSRFError(response);
            return;
        }

        String taskId = request.getParameter("taskId");
        String projectId = request.getParameter("project");
        String action = request.getParameter("action");

        ObjectNode responseNode = mapper.createObjectNode();

        if ((taskId == null || taskId.isEmpty()) && (projectId == null || projectId.isEmpty())) {
            responseNode.put("code", "error");
            responseNode.put("message", "Missing taskId or project parameter");
            respondJSON(response, responseNode);
            return;
        }

        if (action == null || action.isEmpty()) {
            responseNode.put("code", "error");
            responseNode.put("message", "Missing action parameter");
            respondJSON(response, responseNode);
            return;
        }

        // Find task by taskId or projectId
        QualityCheckTask task = null;
        if (taskId != null && !taskId.isEmpty()) {
            task = QualityCheckTask.getTask(taskId);
        } else if (projectId != null && !projectId.isEmpty()) {
            task = QualityCheckTask.getTaskByProject(Long.parseLong(projectId));
        }

        if (task == null) {
            responseNode.put("code", "error");
            responseNode.put("message", "Task not found: " + taskId);
            respondJSON(response, responseNode);
            return;
        }

        try {
            switch (action.toLowerCase()) {
                case "pause":
                    handlePause(task, responseNode);
                    break;
                case "resume":
                    handleResume(task, responseNode);
                    break;
                case "cancel":
                    handleCancel(task, responseNode);
                    break;
                default:
                    responseNode.put("code", "error");
                    responseNode.put("message", "Unknown action: " + action);
            }
        } catch (Exception e) {
            logger.error("Error handling task control action: " + action, e);
            responseNode.put("code", "error");
            responseNode.put("message", e.getMessage());
        }

        respondJSON(response, responseNode);
    }

    private void handlePause(QualityCheckTask task, ObjectNode responseNode) {
        if (task.getStatus() != QualityCheckTask.TaskStatus.RUNNING) {
            responseNode.put("code", "error");
            responseNode.put("message", "Task is not running, cannot pause");
            return;
        }

        task.requestPause();
        logger.info("Pause requested for task: " + task.getTaskId());
        
        responseNode.put("code", "ok");
        responseNode.put("message", "Pause requested");
        responseNode.put("taskId", task.getTaskId());
        responseNode.put("status", task.getStatus().name());
    }

    private void handleResume(QualityCheckTask task, ObjectNode responseNode) {
        if (task.getStatus() != QualityCheckTask.TaskStatus.PAUSED) {
            responseNode.put("code", "error");
            responseNode.put("message", "Task is not paused, cannot resume");
            return;
        }

        task.resume();
        logger.info("Resume requested for task: " + task.getTaskId());
        
        // Save task state
        TaskPersistence.saveTask(task);
        
        responseNode.put("code", "ok");
        responseNode.put("message", "Task resumed");
        responseNode.put("taskId", task.getTaskId());
        responseNode.put("status", task.getStatus().name());
    }

    private void handleCancel(QualityCheckTask task, ObjectNode responseNode) {
        QualityCheckTask.TaskStatus status = task.getStatus();
        if (status != QualityCheckTask.TaskStatus.RUNNING && 
            status != QualityCheckTask.TaskStatus.PAUSED) {
            responseNode.put("code", "error");
            responseNode.put("message", "Task cannot be cancelled in current state: " + status);
            return;
        }

        task.requestCancel();
        logger.info("Cancel requested for task: " + task.getTaskId());
        
        // Save task state
        TaskPersistence.saveTask(task);
        
        responseNode.put("code", "ok");
        responseNode.put("message", "Cancel requested");
        responseNode.put("taskId", task.getTaskId());
        responseNode.put("status", task.getStatus().name());
    }
}

