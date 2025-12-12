/*
 * Data Quality Extension - Get Quality Rules Command
 */
package com.google.refine.extension.quality.commands;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.commands.Command;
import com.google.refine.extension.quality.model.QualityRulesConfig;
import com.google.refine.model.Project;
import com.google.refine.util.ParsingUtilities;

/**
 * Command to retrieve quality rules configuration from the project.
 */
public class GetQualityRulesCommand extends Command {

    private static final Logger logger = LoggerFactory.getLogger("GetQualityRulesCommand");

    public static final String QUALITY_RULES_KEY = "qualityRulesConfig";

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try {
            Project project = getProject(request);
            
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Content-Type", "application/json");

            QualityRulesConfig rulesConfig = (QualityRulesConfig) project.overlayModels.get(QUALITY_RULES_KEY);

            if (rulesConfig == null) {
                // Return empty config
                rulesConfig = new QualityRulesConfig();
            }

            String json = ParsingUtilities.mapper.writeValueAsString(rulesConfig);
            logger.debug("Retrieved quality rules: {}", json);

            response.getWriter().write("{\"code\":\"ok\",\"rules\":" + json + "}");

        } catch (Exception e) {
            logger.error("Error getting quality rules", e);
            respondException(response, e);
        }
    }
}

