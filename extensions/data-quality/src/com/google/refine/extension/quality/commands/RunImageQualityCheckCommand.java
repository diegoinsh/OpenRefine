/*
 * Data Quality Extension - Run Image Quality Check Command
 * Handles requests to run image quality checks on a project
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
import com.google.refine.extension.quality.checker.ImageQualityChecker;
import com.google.refine.extension.quality.model.CheckResult;
import com.google.refine.extension.quality.model.ImageQualityRule;
import com.google.refine.extension.quality.model.QualityRulesConfig;
import com.google.refine.extension.quality.operations.SaveQualityRulesOperation;
import com.google.refine.model.Project;

public class RunImageQualityCheckCommand extends Command {

    private static final Logger logger = LoggerFactory.getLogger(RunImageQualityCheckCommand.class);
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
            ImageQualityRule rule;

            if (jsonStr != null && !jsonStr.isEmpty()) {
                rule = mapper.readValue(jsonStr, ImageQualityRule.class);
            } else {
                QualityRulesConfig config = (QualityRulesConfig) project.overlayModels.get(
                        SaveQualityRulesOperation.OVERLAY_MODEL_KEY);
                if (config != null && config.getImageQualityRule() != null) {
                    rule = config.getImageQualityRule();
                } else {
                    rule = new ImageQualityRule();
                }
            }

            QualityRulesConfig config = new QualityRulesConfig();
            config.setImageQualityRule(rule);

            String aimpServiceUrl = CheckAimpConnectionCommand.getConfiguredServiceUrl();
            if (aimpServiceUrl == null || aimpServiceUrl.isEmpty()) {
                if (config.getAimpConfig() != null) {
                    aimpServiceUrl = config.getAimpConfig().getServiceUrl();
                }
            }

            ImageQualityChecker checker = new ImageQualityChecker(project, config, aimpServiceUrl);
            CheckResult result = checker.runCheck();

            ObjectNode jsonResult = mapper.createObjectNode();
            jsonResult.put("code", "ok");
            jsonResult.set("result", mapper.valueToTree(result));
            jsonResult.put("errorCount", result.getErrors().size());

            respondJSON(response, jsonResult);
        } catch (Exception e) {
            logger.error("Error running image quality check", e);
            HttpUtilities.respondException(response, e);
        }
    }
}
