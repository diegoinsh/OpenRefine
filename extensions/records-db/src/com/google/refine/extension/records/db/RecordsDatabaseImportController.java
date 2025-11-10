/*
 * Records Database Import Controller
 * 
 * Implements the ImportingController interface for Catalog Mode database import
 */

package com.google.refine.extension.records.db;

import java.io.IOException;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.RefineServlet;
import com.google.refine.commands.HttpUtilities;
import com.google.refine.importing.ImportingController;
import com.google.refine.util.ParsingUtilities;

public class RecordsDatabaseImportController implements ImportingController {

    private static final Logger logger = LoggerFactory.getLogger("RecordsDatabaseImportController");
    protected RefineServlet servlet;
    public static int DEFAULT_PREVIEW_LIMIT = 100;

    @Override
    public void init(RefineServlet servlet) {
        this.servlet = servlet;
        logger.info("RecordsDatabaseImportController initialized");
    }

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        HttpUtilities.respond(response, "error", "GET not implemented");
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        if (logger.isDebugEnabled()) {
            logger.debug("doPost Query String::{}", request.getQueryString());
        }
        response.setCharacterEncoding("UTF-8");
        Map<String, String> parameters = ParsingUtilities.parseParameters(request);

        String subCommand = parameters.get("subCommand");

        if (logger.isDebugEnabled()) {
            logger.info("doPost::subCommand::{}", subCommand);
        }

        if ("initialize-ui".equals(subCommand)) {
            doInitializeUI(request, response, parameters);
        } else if ("parse-preview".equals(subCommand)) {
            doParsePreview(request, response, parameters);
        } else if ("create-project".equals(subCommand)) {
            doCreateProject(request, response, parameters);
        } else {
            HttpUtilities.respond(response, "error", "Unknown subCommand: " + subCommand);
        }
    }

    /**
     * Initialize UI - returns available modes and presets
     */
    private void doInitializeUI(HttpServletRequest request, HttpServletResponse response,
            Map<String, String> parameters) throws ServletException, IOException {
        try {
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");

            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"mode\": \"catalog\",");
            sb.append("\"presets\": [");
            sb.append("\"kubao\",");
            sb.append("\"flat_table\",");
            sb.append("\"generic_json\"");
            sb.append("],");
            sb.append("\"dialects\": [");
            sb.append("\"mysql\",");
            sb.append("\"postgresql\",");
            sb.append("\"mariadb\",");
            sb.append("\"sqlite\"");
            sb.append("]");
            sb.append("}");

            response.getWriter().write(sb.toString());
        } catch (Exception e) {
            logger.error("Error in doInitializeUI", e);
            HttpUtilities.respond(response, "error", e.getMessage());
        }
    }

    /**
     * Parse preview - returns sample data for preview
     */
    private void doParsePreview(HttpServletRequest request, HttpServletResponse response,
            Map<String, String> parameters) throws ServletException, IOException {
        try {
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");

            // For now, return empty preview
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"rows\": [],");
            sb.append("\"columns\": [],");
            sb.append("\"rowCount\": 0");
            sb.append("}");

            response.getWriter().write(sb.toString());
        } catch (Exception e) {
            logger.error("Error in doParsePreview", e);
            HttpUtilities.respond(response, "error", e.getMessage());
        }
    }

    /**
     * Create project - creates an OpenRefine project from the data
     */
    private void doCreateProject(HttpServletRequest request, HttpServletResponse response,
            Map<String, String> parameters) throws ServletException, IOException {
        try {
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");

            // For now, return error
            HttpUtilities.respond(response, "error", "Create project not yet implemented");
        } catch (Exception e) {
            logger.error("Error in doCreateProject", e);
            HttpUtilities.respond(response, "error", e.getMessage());
        }
    }
}

