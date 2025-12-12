/*
 * Data Quality Extension - Save Quality Rules Command
 *
 * This command saves quality rules directly to project overlay without adding to undo/redo history.
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
import com.google.refine.extension.quality.model.QualityRulesConfig;
import com.google.refine.extension.quality.operations.SaveQualityRulesOperation;
import com.google.refine.model.Project;
import com.google.refine.util.ParsingUtilities;

/**
 * Command to save quality rules configuration to the project.
 * Saves directly to overlay without adding to undo/redo history.
 */
public class SaveQualityRulesCommand extends Command {

    private static final Logger logger = LoggerFactory.getLogger("SaveQualityRulesCommand");

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        if (!hasValidCSRFToken(request)) {
            respondCSRFError(response);
            return;
        }

        try {
            Project project = getProject(request);
            String rulesJson = request.getParameter("rules");

            if (rulesJson == null || "null".equals(rulesJson)) {
                respondError(response, "No quality rules provided.");
                return;
            }

            logger.debug("Saving quality rules: {}", rulesJson);

            QualityRulesConfig rulesConfig = ParsingUtilities.mapper.readValue(
                rulesJson, QualityRulesConfig.class);

            // Save directly to overlay without adding to history
            synchronized (project) {
                project.overlayModels.put(SaveQualityRulesOperation.OVERLAY_MODEL_KEY, rulesConfig);
            }

            // Mark project as modified so it gets saved
            project.getMetadata().updateModified();

            // Respond with success
            ObjectNode result = ParsingUtilities.mapper.createObjectNode();
            result.put("code", "ok");
            respondJSON(response, result);

        } catch (Exception e) {
            logger.error("Error saving quality rules", e);
            respondException(response, e);
        }
    }

    private void respondError(HttpServletResponse response, String message) throws IOException {
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Type", "application/json");
        response.getWriter().write("{\"code\":\"error\",\"message\":\"" + message + "\"}");
    }
}

