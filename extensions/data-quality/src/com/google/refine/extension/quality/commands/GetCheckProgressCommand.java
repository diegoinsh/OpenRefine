/*
 * Data Quality Extension - Get Check Progress Command
 * Returns the current progress of an async quality check task
 */
package com.google.refine.extension.quality.commands;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.google.refine.commands.Command;
import com.google.refine.extension.quality.task.QualityCheckTask;

/**
 * Command to get the progress of a quality check task.
 */
public class GetCheckProgressCommand extends Command {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        String taskId = request.getParameter("taskId");

        ObjectNode responseNode = mapper.createObjectNode();

        if (taskId == null || taskId.isEmpty()) {
            responseNode.put("code", "error");
            responseNode.put("message", "Missing taskId parameter");
            respondJSON(response, responseNode);
            return;
        }

        QualityCheckTask task = QualityCheckTask.getTask(taskId);

        if (task == null) {
            responseNode.put("code", "error");
            responseNode.put("message", "Task not found: " + taskId);
            respondJSON(response, responseNode);
            return;
        }

        responseNode.put("code", "ok");
        responseNode.put("taskId", task.getTaskId());
        responseNode.put("status", task.getStatus().name());
        responseNode.put("currentPhase", task.getCurrentPhase());
        responseNode.put("progress", task.getProgressPercent());
        responseNode.put("totalRows", task.getTotalRows());
        responseNode.put("processedRows", task.getProcessedRows());
        responseNode.put("contentCheckTotal", task.getContentCheckTotal());
        responseNode.put("contentCheckProcessed", task.getContentCheckProcessed());
        responseNode.put("formatErrors", task.getFormatErrors());
        responseNode.put("resourceErrors", task.getResourceErrors());
        responseNode.put("contentErrors", task.getContentErrors());

        if (task.getErrorMessage() != null) {
            responseNode.put("errorMessage", task.getErrorMessage());
        }
        if (task.getErrorKey() != null) {
            responseNode.put("errorKey", task.getErrorKey());
        }

        // If completed, include the full result
        if (task.getStatus() == QualityCheckTask.TaskStatus.COMPLETED && task.getResult() != null) {
            responseNode.set("result", mapper.valueToTree(task.getResult()));
        }

        respondJSON(response, responseNode);
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        doGet(request, response);
    }
}

