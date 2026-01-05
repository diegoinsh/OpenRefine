/*
 * Data Quality Extension - Get Image Quality Rule Command
 * Handles requests to retrieve image quality rules for a project
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

public class GetImageQualityRuleCommand extends Command {

    private static final Logger logger = LoggerFactory.getLogger(GetImageQualityRuleCommand.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
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

            QualityRulesConfig config = (QualityRulesConfig) project.overlayModels.get("qualityRulesConfig");

            ObjectNode result = mapper.createObjectNode();

            if (config != null && config.getImageQualityRule() != null) {
                ImageQualityRule rule = config.getImageQualityRule();
                result.put("code", "ok");
                result.set("rule", mapper.valueToTree(rule));
            } else {
                result.put("code", "ok");
                result.putNull("rule");
            }

            respondJSON(response, result);
        } catch (Exception e) {
            logger.error("Error getting image quality rule", e);
            HttpUtilities.respondException(response, e);
        }
    }
}
