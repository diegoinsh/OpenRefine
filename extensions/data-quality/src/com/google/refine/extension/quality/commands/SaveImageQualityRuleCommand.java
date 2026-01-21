/*
 * Data Quality Extension - Save Image Quality Rule Command
 * Handles requests to save image quality rules for a project
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
import com.google.refine.commands.HttpUtilities;
import com.google.refine.extension.quality.model.ImageQualityRule;
import com.google.refine.extension.quality.model.QualityRulesConfig;
import com.google.refine.model.Project;

public class SaveImageQualityRuleCommand extends Command {

    private static final Logger logger = LoggerFactory.getLogger(SaveImageQualityRuleCommand.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        if (!hasValidCSRFToken(request)) {
            respondCSRFError(response);
            return;
        }

        try {
            Project project = getProject(request);
            if (project == null) {
                HttpUtilities.respond(response, "error", "Project not found");
                return;
            }

            String jsonStr = request.getParameter("rule");
            if (jsonStr == null || jsonStr.isEmpty()) {
                // Try reading from request body for JSON content
                jsonStr = request.getReader().readLine();
            }
            if (jsonStr == null || jsonStr.isEmpty()) {
                HttpUtilities.respond(response, "error", "Missing rule parameter");
                return;
            }

            // Parse the JSON body to extract rule if it contains projectId wrapper
            ObjectMapper mapper = new ObjectMapper();
            try {
                ObjectMapper bodyMapper = new ObjectMapper();
                ObjectNode body = bodyMapper.readValue(jsonStr, ObjectNode.class);
                if (body.has("rule")) {
                    jsonStr = body.get("rule").toString();
                }
            } catch (Exception e) {
                // Not a wrapped JSON, use the original string
            }

            ImageQualityRule rule = mapper.readValue(jsonStr, ImageQualityRule.class);

            QualityRulesConfig config = (QualityRulesConfig) project.overlayModels.get("qualityRulesConfig");
            if (config == null) {
                config = new QualityRulesConfig();
                project.overlayModels.put("qualityRulesConfig", config);
            }

            config.setImageQualityRule(rule);

            ObjectNode result = mapper.createObjectNode();
            result.put("code", "ok");
            result.put("message", "规则保存成功");

            respondJSON(response, result);
        } catch (Exception e) {
            logger.error("Error saving image quality rule", e);
            HttpUtilities.respondException(response, e);
        }
    }
}
