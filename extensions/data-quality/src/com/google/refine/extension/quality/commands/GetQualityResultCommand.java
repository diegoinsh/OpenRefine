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
            
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Content-Type", "application/json");

            CheckResult result = (CheckResult) project.overlayModels.get(QUALITY_RESULT_KEY);

            if (result == null) {
                // Return empty response
                response.getWriter().write("{\"code\":\"ok\",\"hasResult\":false}");
            } else {
                String json = ParsingUtilities.mapper.writeValueAsString(result);
                logger.debug("Retrieved quality result: {} errors", result.getErrors().size());
                response.getWriter().write("{\"code\":\"ok\",\"hasResult\":true,\"result\":" + json + "}");
            }

        } catch (Exception e) {
            logger.error("Error getting quality result", e);
            respondException(response, e);
        }
    }
}

