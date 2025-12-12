/*
 * Data Quality Extension - Save Quality Result Command
 *
 * This command saves quality check result directly to project overlay without adding to undo/redo history.
 */
package com.google.refine.extension.quality.commands;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.commands.Command;
import com.google.refine.extension.quality.model.CheckResult;
import com.google.refine.extension.quality.operations.SaveQualityResultOperation;
import com.google.refine.model.Project;
import com.google.refine.util.ParsingUtilities;

/**
 * Command to save quality check result to the project.
 * Saves directly to overlay without adding to undo/redo history.
 */
public class SaveQualityResultCommand extends Command {

    private static final Logger logger = LoggerFactory.getLogger("SaveQualityResultCommand");

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        if (!hasValidCSRFToken(request)) {
            respondCSRFError(response);
            return;
        }

        try {
            Project project = getProject(request);
            String resultJson = request.getParameter("result");

            if (resultJson == null || "null".equals(resultJson)) {
                respondError(response, "No quality result provided.");
                return;
            }

            logger.debug("Saving quality result: {} chars", resultJson.length());

            CheckResult result = ParsingUtilities.mapper.readValue(resultJson, CheckResult.class);

            // Save directly to overlay without adding to history
            synchronized (project) {
                project.overlayModels.put(SaveQualityResultOperation.OVERLAY_MODEL_KEY, result);
            }

            // Mark project as modified so it gets saved
            project.getMetadata().updateModified();

            // Respond with success
            ObjectNode responseNode = ParsingUtilities.mapper.createObjectNode();
            responseNode.put("code", "ok");
            respondJSON(response, responseNode);

        } catch (Exception e) {
            logger.error("Error saving quality result", e);
            respondException(response, e);
        }
    }

    private void respondError(HttpServletResponse response, String message) throws IOException {
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Type", "application/json");
        response.getWriter().write("{\"code\":\"error\",\"message\":\"" + message + "\"}");
    }
}

