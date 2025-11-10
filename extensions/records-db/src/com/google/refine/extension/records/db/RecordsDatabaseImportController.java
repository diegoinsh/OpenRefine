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

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.RefineServlet;
import com.google.refine.commands.HttpUtilities;
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

        try {
            ObjectNode result = ParsingUtilities.mapper.createObjectNode();
            JSONUtilities.safePut(result, "status", "ok");

            // Return empty data for now - will be implemented in Task 1.2
            JSONUtilities.safePut(result, "rows", ParsingUtilities.mapper.createArrayNode());
            JSONUtilities.safePut(result, "columns", ParsingUtilities.mapper.createArrayNode());
            JSONUtilities.safePut(result, "rowCount", 0);

            if (logger.isDebugEnabled()) {
                logger.debug("doParsePreview:::{}", result.toString());
            }

            HttpUtilities.respond(response, result.toString());
        } catch (Exception e) {
            logger.error("Error in doParsePreview", e);
            HttpUtilities.respond(response, "error", e.getMessage());
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

        try {
            ObjectNode result = ParsingUtilities.mapper.createObjectNode();
            JSONUtilities.safePut(result, "status", "error");
            JSONUtilities.safePut(result, "message", "Create project not yet implemented");

            HttpUtilities.respond(response, result.toString());
        } catch (Exception e) {
            logger.error("Error in doCreateProject", e);
            HttpUtilities.respond(response, "error", e.getMessage());
        }
    }
}

