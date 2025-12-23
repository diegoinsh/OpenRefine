/*
 * Data Quality Extension - Get Quality Result Command
 */
package com.google.refine.extension.quality.commands;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.commands.Command;
import com.google.refine.extension.quality.model.CheckResult;
import com.google.refine.model.Project;
import com.google.refine.util.ParsingUtilities;

/**
 * Command to retrieve quality check result from the project.
 */
public class GetQualityResultCommand extends Command {

    private static final Logger logger = LoggerFactory.getLogger("GetQualityResultCommand");

    public static final String QUALITY_RESULT_KEY = "qualityCheckResult";

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try {
            Project project = getProject(request);
            logger.info("GetQualityResult for project {}, overlayModels size={}",
                project.id, project.overlayModels.size());
            logger.info("overlayModels keys: {}", project.overlayModels.keySet());

            response.setCharacterEncoding("UTF-8");
            response.setHeader("Content-Type", "application/json");

            Object rawResult = project.overlayModels.get(QUALITY_RESULT_KEY);
            logger.info("Raw result from overlayModels: {}", rawResult != null ? rawResult.getClass().getName() : "null");

            CheckResult result = (CheckResult) rawResult;

            if (result == null) {
                // Return empty response
                logger.info("No quality result found for project {}", project.id);
                response.getWriter().write("{\"code\":\"ok\",\"hasResult\":false}");
            } else {
                String json = ParsingUtilities.mapper.writeValueAsString(result);
                logger.info("Retrieved quality result: {} errors for project {}", result.getErrors().size(), project.id);
                response.getWriter().write("{\"code\":\"ok\",\"hasResult\":true,\"result\":" + json + "}");
            }

        } catch (Exception e) {
            logger.error("Error getting quality result", e);
            respondException(response, e);
        }
    }
}

