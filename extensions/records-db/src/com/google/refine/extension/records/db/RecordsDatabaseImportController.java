/*
 * Records Database Import Controller
 * 
 * Implements the ImportingController interface for Catalog Mode database import
 */

package com.google.refine.extension.records.db;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.RefineServlet;
import com.google.refine.commands.HttpUtilities;
import com.google.refine.extension.records.db.model.SchemaProfile;
import com.google.refine.importing.ImportingController;
import com.google.refine.util.JSONUtilities;
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
     * Initialize UI - returns available modes, presets, and dialects
     */
    private void doInitializeUI(HttpServletRequest request, HttpServletResponse response,
            Map<String, String> parameters) throws ServletException, IOException {
        if (logger.isDebugEnabled()) {
            logger.debug("::doInitializeUI::");
        }

        try {
            ObjectNode result = ParsingUtilities.mapper.createObjectNode();
            JSONUtilities.safePut(result, "status", "ok");

            // Add available modes
            JSONUtilities.safePut(result, "modes", PresetManager.getAvailableModes());

            // Add available presets
            JSONUtilities.safePut(result, "presets", PresetManager.getAvailablePresets());

            // Add available database dialects
            JSONUtilities.safePut(result, "dialects", PresetManager.getAvailableDialects());

            // Add default options
            ObjectNode options = ParsingUtilities.mapper.createObjectNode();
            JSONUtilities.safePut(options, "mode", "catalog");
            JSONUtilities.safePut(options, "preset", "kubao");
            JSONUtilities.safePut(options, "dialect", "mysql");
            JSONUtilities.safePut(options, "pageSize", DEFAULT_PREVIEW_LIMIT);
            JSONUtilities.safePut(result, "options", options);

            if (logger.isDebugEnabled()) {
                logger.debug("doInitializeUI:::{}", result.toString());
            }

            HttpUtilities.respond(response, result.toString());
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
        if (logger.isDebugEnabled()) {
            logger.debug("::doParsePreview::");
        }

        java.sql.Connection conn = null;
        try {
            // Get Schema Profile from request
            String schemaProfileJson = parameters.get("schemaProfile");
            if (schemaProfileJson == null || schemaProfileJson.isEmpty()) {
                HttpUtilities.respond(response, "error", "schemaProfile parameter is required");
                return;
            }

            // Parse Schema Profile
            SchemaProfile profile = SchemaProfileParser.parse(schemaProfileJson);

            // Validate Schema Profile
            List<String> errors = SchemaProfileValidator.validate(profile);
            if (!errors.isEmpty()) {
                ObjectNode result = ParsingUtilities.mapper.createObjectNode();
                JSONUtilities.safePut(result, "status", "error");
                JSONUtilities.safePut(result, "message", "Schema Profile validation failed");
                ArrayNode errorArray = ParsingUtilities.mapper.createArrayNode();
                for (String error : errors) {
                    errorArray.add(error);
                }
                result.set("errors", errorArray);
                HttpUtilities.respond(response, result.toString());
                return;
            }

            // Get database connection
            conn = DatabaseConnectionManager.getConnection(profile);

            // Execute query
            ObjectNode result = QueryExecutor.executeQuery(conn, profile, null, 0, DEFAULT_PREVIEW_LIMIT);

            if (logger.isDebugEnabled()) {
                logger.debug("doParsePreview:::{}", result.toString());
            }

            HttpUtilities.respond(response, result.toString());
        } catch (Exception e) {
            logger.error("Error in doParsePreview", e);
            ObjectNode result = ParsingUtilities.mapper.createObjectNode();
            JSONUtilities.safePut(result, "status", "error");
            JSONUtilities.safePut(result, "message", e.getMessage());
            HttpUtilities.respond(response, result.toString());
        } finally {
            DatabaseConnectionManager.closeConnection(conn);
        }
    }

    /**
     * Create project - creates an OpenRefine project from the imported data
     */
    private void doCreateProject(HttpServletRequest request, HttpServletResponse response,
            Map<String, String> parameters) throws ServletException, IOException {
        if (logger.isDebugEnabled()) {
            logger.debug("::doCreateProject::");
        }

        java.sql.Connection conn = null;
        try {
            // Get parameters
            String schemaProfileJson = parameters.get("schemaProfile");
            String projectName = parameters.get("projectName");
            String maxRowsStr = parameters.get("maxRows");

            if (schemaProfileJson == null || schemaProfileJson.isEmpty()) {
                HttpUtilities.respond(response, "error", "schemaProfile parameter is required");
                return;
            }

            if (projectName == null || projectName.isEmpty()) {
                HttpUtilities.respond(response, "error", "projectName parameter is required");
                return;
            }

            int maxRows = DEFAULT_PREVIEW_LIMIT * 100; // Default to 10000 rows
            if (maxRowsStr != null && !maxRowsStr.isEmpty()) {
                try {
                    maxRows = Integer.parseInt(maxRowsStr);
                } catch (NumberFormatException e) {
                    logger.warn("Invalid maxRows value: {}", maxRowsStr);
                }
            }

            // Parse and validate Schema Profile
            SchemaProfile profile = SchemaProfileParser.parse(schemaProfileJson);
            List<String> errors = SchemaProfileValidator.validate(profile);
            if (!errors.isEmpty()) {
                ObjectNode result = ParsingUtilities.mapper.createObjectNode();
                JSONUtilities.safePut(result, "status", "error");
                JSONUtilities.safePut(result, "message", "Schema Profile validation failed");
                ArrayNode errorArray = ParsingUtilities.mapper.createArrayNode();
                for (String error : errors) {
                    errorArray.add(error);
                }
                result.set("errors", errorArray);
                HttpUtilities.respond(response, result.toString());
                return;
            }

            // Get database connection
            conn = DatabaseConnectionManager.getConnection(profile);

            // Prepare project creation
            ObjectNode result = ProjectCreator.prepareProjectCreation(projectName, conn, profile, maxRows);

            if (logger.isDebugEnabled()) {
                logger.debug("doCreateProject:::{}", result.toString());
            }

            HttpUtilities.respond(response, result.toString());
        } catch (Exception e) {
            logger.error("Error in doCreateProject", e);
            ObjectNode result = ParsingUtilities.mapper.createObjectNode();
            JSONUtilities.safePut(result, "status", "error");
            JSONUtilities.safePut(result, "message", e.getMessage());
            HttpUtilities.respond(response, result.toString());
        } finally {
            DatabaseConnectionManager.closeConnection(conn);
        }
    }
}

