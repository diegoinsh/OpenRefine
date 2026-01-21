/*
 * Data Quality Extension - List Global Rules Command
 * Lists quality rules from all projects for global management
 */
package com.google.refine.extension.quality.commands;

import java.io.IOException;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.ProjectManager;
import com.google.refine.ProjectMetadata;
import com.google.refine.commands.Command;
import com.google.refine.extension.quality.model.ContentComparisonRule;
import com.google.refine.extension.quality.model.FormatRule;
import com.google.refine.extension.quality.model.QualityRulesConfig;
import com.google.refine.model.Project;

/**
 * Command to list all quality rules from all projects.
 * Used for global rules management view.
 */
public class ListGlobalRulesCommand extends Command {

    private static final Logger logger = LoggerFactory.getLogger("ListGlobalRulesCommand");
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String QUALITY_RULES_KEY = "qualityRulesConfig";

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        ObjectNode responseNode = mapper.createObjectNode();

        try {
            ArrayNode rulesArray = mapper.createArrayNode();
            int ruleCount = 0;

            // Get all project IDs
            Map<Long, ProjectMetadata> allProjects = ProjectManager.singleton.getAllProjectMetadata();

            for (Map.Entry<Long, ProjectMetadata> entry : allProjects.entrySet()) {
                long projectId = entry.getKey();
                ProjectMetadata metadata = entry.getValue();

                try {
                    Project project = ProjectManager.singleton.getProject(projectId);
                    if (project == null) continue;

                    QualityRulesConfig rulesConfig = (QualityRulesConfig) project.overlayModels.get(QUALITY_RULES_KEY);
                    if (rulesConfig == null) continue;

                    // Add format rules
                    if (rulesConfig.getFormatRules() != null) {
                        for (Map.Entry<String, FormatRule> ruleEntry : rulesConfig.getFormatRules().entrySet()) {
                            String columnName = ruleEntry.getKey();
                            FormatRule rule = ruleEntry.getValue();

                            ObjectNode ruleNode = mapper.createObjectNode();
                            ruleNode.put("id", "format-" + projectId + "-" + columnName);
                            ruleNode.put("name", columnName + " 格式规则");
                            ruleNode.put("type", "格式检查");
                            ruleNode.put("column", columnName);
                            ruleNode.put("projectId", projectId);
                            ruleNode.put("projectName", metadata.getName());
                            ruleNode.put("createdAt", System.currentTimeMillis());

                            rulesArray.add(ruleNode);
                            ruleCount++;
                        }
                    }

                    // Add content comparison rules
                    if (rulesConfig.getContentRules() != null) {
                        int idx = 0;
                        for (ContentComparisonRule rule : rulesConfig.getContentRules()) {
                            ObjectNode ruleNode = mapper.createObjectNode();
                            ruleNode.put("id", "content-" + projectId + "-" + idx);
                            ruleNode.put("name", rule.getColumn() + " 内容比对");
                            ruleNode.put("type", "内容比对");
                            ruleNode.put("column", rule.getColumn());
                            ruleNode.put("extractLabel", rule.getExtractLabel());
                            ruleNode.put("projectId", projectId);
                            ruleNode.put("projectName", metadata.getName());
                            ruleNode.put("createdAt", System.currentTimeMillis());

                            rulesArray.add(ruleNode);
                            ruleCount++;
                            idx++;
                        }
                    }

                    // Add resource check config as a single rule entry
                    if (rulesConfig.getResourceConfig() != null &&
                        rulesConfig.getResourceConfig().getPathFields() != null &&
                        !rulesConfig.getResourceConfig().getPathFields().isEmpty()) {
                        ObjectNode ruleNode = mapper.createObjectNode();
                        ruleNode.put("id", "resource-" + projectId);
                        ruleNode.put("name", "资源关联检查");
                        ruleNode.put("type", "资源检查");
                        ruleNode.put("projectId", projectId);
                        ruleNode.put("projectName", metadata.getName());
                        ruleNode.put("createdAt", System.currentTimeMillis());

                        rulesArray.add(ruleNode);
                        ruleCount++;
                    }
                } catch (Exception e) {
                    logger.warn("Failed to load rules from project " + projectId, e);
                }
            }

            responseNode.put("code", "ok");
            responseNode.set("rules", rulesArray);
            responseNode.put("count", ruleCount);

            logger.info("Listed " + ruleCount + " rules from " + allProjects.size() + " projects");

        } catch (Exception e) {
            logger.error("Error listing global rules", e);
            responseNode.put("code", "error");
            responseNode.put("message", e.getMessage());
        }

        respondJSON(response, responseNode);
    }
}

