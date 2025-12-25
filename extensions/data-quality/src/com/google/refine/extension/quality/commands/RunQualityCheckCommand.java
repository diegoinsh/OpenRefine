/*
 * Data Quality Extension - Run Quality Check Command
 * Supports async execution with progress tracking
 */
package com.google.refine.extension.quality.commands;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.ProjectManager;
import com.google.refine.commands.Command;
import com.google.refine.extension.quality.checker.ContentChecker;
import com.google.refine.extension.quality.checker.FormatChecker;
import com.google.refine.extension.quality.checker.ResourceChecker;
import com.google.refine.extension.quality.model.CheckResult;
import com.google.refine.extension.quality.model.QualityRulesConfig;
import com.google.refine.extension.quality.operations.SaveQualityResultOperation;
import com.google.refine.extension.quality.operations.SaveQualityRulesOperation;
import com.google.refine.extension.quality.task.QualityCheckTask;
import com.google.refine.extension.quality.task.QualityCheckTask.TaskStatus;
import com.google.refine.extension.quality.task.TaskPersistence;
import com.google.refine.model.Project;

/**
 * Command to run quality check on project data.
 * Supports async execution with progress tracking.
 */
public class RunQualityCheckCommand extends Command {

    private static final Logger logger = LoggerFactory.getLogger(RunQualityCheckCommand.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        if (!hasValidCSRFToken(request)) {
            respondCSRFError(response);
            return;
        }

        try {
            Project project = getProject(request);
            if (project == null) {
                respondJSON(response, createErrorResponse("error-project-not-found", "Project not found"));
                return;
            }

            // Check if async mode is requested
            String asyncParam = request.getParameter("async");
            boolean async = "true".equals(asyncParam);

            // Get rules from project overlay
            QualityRulesConfig rules = (QualityRulesConfig) project.overlayModels.get(
                    SaveQualityRulesOperation.OVERLAY_MODEL_KEY);

            if (rules == null) {
                respondJSON(response, createErrorResponse("error-no-rules-configured", "No quality rules configured"));
                return;
            }

            // Debug: log rules info
            logger.info("Rules loaded - formatRules: " + (rules.getFormatRules() != null ? rules.getFormatRules().size() : 0) +
                       ", contentRules: " + (rules.getContentRules() != null ? rules.getContentRules().size() : 0) +
                       ", aimpConfig: " + (rules.getAimpConfig() != null ? rules.getAimpConfig().getServiceUrl() : "null"));

            // Get AIMP URL - priority: request param > global config > project rules config
            String aimpServiceUrl = request.getParameter("aimpServiceUrl");
            logger.info("aimpServiceUrl from request param: " + aimpServiceUrl);
            if (aimpServiceUrl == null || aimpServiceUrl.isEmpty()) {
                // Try global config first
                aimpServiceUrl = CheckAimpConnectionCommand.getConfiguredServiceUrl();
                logger.info("aimpServiceUrl from global config: " + aimpServiceUrl);
                
                // Fallback to project rules config
                if ((aimpServiceUrl == null || aimpServiceUrl.isEmpty()) && rules.getAimpConfig() != null) {
                    aimpServiceUrl = rules.getAimpConfig().getServiceUrl();
                    logger.info("aimpServiceUrl from rules config: " + aimpServiceUrl);
                }
            }

            if (async) {
                // Async mode: start task and return task ID
                String taskId = QualityCheckTask.generateTaskId(project.id);
                QualityCheckTask task = new QualityCheckTask(taskId, project.id);
                task.setTotalRows(project.rows.size());

                final String finalAimpUrl = aimpServiceUrl;
                executor.submit(() -> runCheckAsync(project, rules, finalAimpUrl, task));

                ObjectNode responseNode = mapper.createObjectNode();
                responseNode.put("code", "ok");
                responseNode.put("async", true);
                responseNode.put("taskId", taskId);
                respondJSON(response, responseNode);
            } else {
                // Sync mode: run and wait for result
                ObjectNode result = runCheckSync(project, rules, aimpServiceUrl);
                respondJSON(response, result);
            }

        } catch (Exception e) {
            logger.error("Error running quality check", e);
            respondJSON(response, createErrorResponse(e.getMessage()));
        }
    }

    /**
     * Run quality check synchronously
     */
    private ObjectNode runCheckSync(Project project, QualityRulesConfig rules, String aimpServiceUrl) {
        int totalRows = project.rows.size();

        // Run format checks
        FormatChecker formatChecker = new FormatChecker(project, rules);
        CheckResult formatResult = formatChecker.runCheck();

        // Run resource checks
        ResourceChecker resourceChecker = new ResourceChecker(project, rules);
        CheckResult resourceResult = resourceChecker.runCheck();

        // Run content checks
        logger.info("Content check AIMP service URL: " + aimpServiceUrl);
        CheckResult contentResult = new CheckResult("content");
        if (aimpServiceUrl != null && !aimpServiceUrl.isEmpty()) {
            logger.info("Running content check with AIMP service: " + aimpServiceUrl);
            ContentChecker contentChecker = new ContentChecker(project, rules, aimpServiceUrl);
            contentResult = contentChecker.runCheck();
            logger.info("Content check completed, errors: " + contentResult.getErrors().size());
        } else {
            logger.info("Skipping content check - no AIMP service URL configured");
            contentResult.complete();
        }

        return buildResponse(totalRows, formatResult, resourceResult, contentResult);
    }

    /**
     * Run quality check asynchronously with progress tracking
     */
    private void runCheckAsync(Project project, QualityRulesConfig rules, String aimpServiceUrl, QualityCheckTask task) {
        try {
            task.setStatus(TaskStatus.RUNNING);
            int totalErrors = 0;

            // Phase 1: Format checks
            task.setCurrentPhase("格式检查");
            FormatChecker formatChecker = new FormatChecker(project, rules);
            CheckResult formatResult = formatChecker.runCheck();
            task.setFormatErrors(formatResult.getErrors().size());
            totalErrors += formatResult.getErrors().size();

            // Phase 2: Resource checks
            task.setCurrentPhase("资源检查");
            ResourceChecker resourceChecker = new ResourceChecker(project, rules);
            CheckResult resourceResult = resourceChecker.runCheck();
            task.setResourceErrors(resourceResult.getErrors().size());
            totalErrors += resourceResult.getErrors().size();

            // Phase 3: Content checks
            task.setCurrentPhase("内容检查");
            CheckResult contentResult = new CheckResult("content");
            if (aimpServiceUrl != null && !aimpServiceUrl.isEmpty()) {
                logger.info("Running async content check with AIMP service: " + aimpServiceUrl);
                ContentChecker contentChecker = new ContentChecker(project, rules, aimpServiceUrl);
                contentChecker.setTask(task); // Set task for progress updates
                contentResult = contentChecker.runCheck();
                task.setContentErrors(contentResult.getErrors().size());
                totalErrors += contentResult.getErrors().size();
            } else {
                contentResult.complete();
            }

            // Build and store result
            ObjectNode result = buildResponse(project.rows.size(), formatResult, resourceResult, contentResult);
            task.setResult(result);
            task.setStatus(TaskStatus.COMPLETED);
            task.setCompletedAt(System.currentTimeMillis());

            // Persist result to project overlay for later retrieval
            try {
                CheckResult combinedResult = new CheckResult("combined");
                combinedResult.setTotalRows(project.rows.size());
                combinedResult.setCheckedRows(project.rows.size());
                // Add all errors to combined result
                for (CheckResult.CheckError err : formatResult.getErrors()) {
                    err.setCategory("format");
                    combinedResult.addError(err);
                }
                for (CheckResult.CheckError err : resourceResult.getErrors()) {
                    err.setCategory("resource");
                    combinedResult.addError(err);
                }
                for (CheckResult.CheckError err : contentResult.getErrors()) {
                    err.setCategory("content");
                    combinedResult.addError(err);
                }
                combinedResult.complete();

                synchronized (project) {
                    project.overlayModels.put(SaveQualityResultOperation.OVERLAY_MODEL_KEY, combinedResult);
                }
                project.getMetadata().updateModified();

                // Force save project to disk
                ProjectManager.singleton.ensureProjectSaved(project.id);
                logger.info("Quality result persisted and saved to disk");
            } catch (Exception e) {
                logger.error("Failed to persist quality result", e);
            }

            // Persist task state to disk
            TaskPersistence.saveTask(task);
            logger.info("Async quality check completed. Task: " + task.getTaskId() + ", Errors: " + totalErrors);

        } catch (Exception e) {
            logger.error("Error in async quality check", e);
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage(e.getMessage());
            task.setCompletedAt(System.currentTimeMillis());
            // Persist failed task state to disk
            TaskPersistence.saveTask(task);
        }
    }

    private ObjectNode buildResponse(int totalRows, CheckResult formatResult, CheckResult resourceResult, CheckResult contentResult) {
        ObjectNode responseNode = mapper.createObjectNode();
        responseNode.put("code", "ok");

        int totalErrors = formatResult.getErrors().size() + resourceResult.getErrors().size() + contentResult.getErrors().size();

        // Summary
        ObjectNode summary = mapper.createObjectNode();
        summary.put("totalRows", totalRows);
        summary.put("totalErrors", totalErrors);
        summary.put("formatErrors", formatResult.getErrors().size());
        summary.put("resourceErrors", resourceResult.getErrors().size());
        summary.put("contentErrors", contentResult.getErrors().size());
        responseNode.set("summary", summary);

        // Combine all errors and set category on each error object
        ArrayNode allErrors = mapper.createArrayNode();
        for (CheckResult.CheckError err : formatResult.getErrors()) {
            err.setCategory("format");
            allErrors.add(mapper.valueToTree(err));
        }
        for (CheckResult.CheckError err : resourceResult.getErrors()) {
            err.setCategory("resource");
            allErrors.add(mapper.valueToTree(err));
        }
        for (CheckResult.CheckError err : contentResult.getErrors()) {
            err.setCategory("content");
            allErrors.add(mapper.valueToTree(err));
        }
        responseNode.set("errors", allErrors);

        // Individual results
        responseNode.set("formatResult", mapper.valueToTree(formatResult));
        responseNode.set("resourceResult", mapper.valueToTree(resourceResult));
        responseNode.set("contentResult", mapper.valueToTree(contentResult));

        return responseNode;
    }

    private ObjectNode createErrorResponse(String errorKey, String defaultMessage) {
        ObjectNode response = mapper.createObjectNode();
        response.put("code", "error");
        response.put("errorKey", errorKey);
        response.put("message", defaultMessage);
        return response;
    }

    private ObjectNode createErrorResponse(String message) {
        return createErrorResponse("error-unknown", message);
    }
}

