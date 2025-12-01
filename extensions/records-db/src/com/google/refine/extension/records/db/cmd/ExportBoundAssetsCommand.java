/*
 * Export Bound Assets Command
 * Handles file copy/move operations based on project data
 */

package com.google.refine.extension.records.db.cmd;

import java.io.IOException;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.commands.Command;
import com.google.refine.extension.records.db.BoundAssetsExporter;
import com.google.refine.extension.records.db.model.RecordsDBOverlayModel;
import com.google.refine.model.Project;
import com.google.refine.util.ParsingUtilities;

/**
 * Command to export bound assets (files) from a project.
 * 
 * GET: Returns export configuration (columns, file mapping info)
 * POST: Executes the export operation
 */
public class ExportBoundAssetsCommand extends Command {

    private static final Logger logger = LoggerFactory.getLogger("ExportBoundAssetsCommand");

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            Project project = getProject(request);
            if (project == null) {
                respondCodeError(response, "Missing or invalid project parameter", 
                        HttpServletResponse.SC_BAD_REQUEST);
                return;
            }

            ObjectNode result = ParsingUtilities.mapper.createObjectNode();
            result.put("status", "ok");

            // Get columns from project
            ArrayNode columns = result.putArray("columns");
            for (int i = 0; i < project.columnModel.columns.size(); i++) {
                columns.add(project.columnModel.columns.get(i).getName());
            }

            // Get file mapping from overlay model if available
            RecordsDBOverlayModel overlayModel = (RecordsDBOverlayModel) 
                    project.overlayModels.get(RecordsDBOverlayModel.OVERLAY_MODEL_KEY);
            
            if (overlayModel != null && overlayModel.getFileMapping() != null) {
                Map<String, Object> fileMapping = overlayModel.getFileMapping();
                ObjectNode fileMappingNode = result.putObject("fileMapping");
                
                if (fileMapping.get("rootPath") != null) {
                    fileMappingNode.put("rootPath", fileMapping.get("rootPath").toString());
                }
                if (fileMapping.get("columnLabel") != null) {
                    fileMappingNode.put("columnLabel", fileMapping.get("columnLabel").toString());
                }
                if (fileMapping.get("source") != null) {
                    fileMappingNode.put("source", fileMapping.get("source").toString());
                }
            }

            respondJSON(response, result);

        } catch (Exception e) {
            logger.error("Error getting export config", e);
            respondCodeError(response, "Error: " + e.getMessage(), 
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        if (!hasValidCSRFToken(request)) {
            respondCSRFError(response);
            return;
        }

        try {
            Project project = getProject(request);
            if (project == null) {
                respondCodeError(response, "Missing or invalid project parameter", 
                        HttpServletResponse.SC_BAD_REQUEST);
                return;
            }

            // Parse options from request
            String optionsJson = request.getParameter("options");
            if (optionsJson == null || optionsJson.isEmpty()) {
                respondCodeError(response, "Missing options parameter", 
                        HttpServletResponse.SC_BAD_REQUEST);
                return;
            }

            JsonNode options = ParsingUtilities.mapper.readTree(optionsJson);

            // Check if this is a preview request
            boolean isPreview = options.has("preview") && options.get("preview").asBoolean(false);

            // Extract parameters
            String sourceRootPath = options.has("sourceRootPath") ?
                    options.get("sourceRootPath").asText() : null;
            String separator = options.has("separator") ?
                    options.get("separator").asText("/") : "/";

            // Path fields (columns to use for building file paths)
            ArrayNode pathFieldsNode = options.has("pathFields") ?
                    (ArrayNode) options.get("pathFields") : null;

            BoundAssetsExporter exporter = new BoundAssetsExporter(project);
            ObjectNode result;

            if (isPreview) {
                // Preview mode - just check files exist
                int limit = options.has("limit") ? options.get("limit").asInt(10) : 10;
                result = exporter.previewExport(pathFieldsNode, sourceRootPath, separator, limit);
            } else {
                // Execute export
                String targetPath = options.has("targetPath") ?
                        options.get("targetPath").asText() : null;
                String mode = options.has("mode") ?
                        options.get("mode").asText() : "copy";

                if (targetPath == null || targetPath.isEmpty()) {
                    respondCodeError(response, "Missing targetPath parameter",
                            HttpServletResponse.SC_BAD_REQUEST);
                    return;
                }

                result = exporter.exportAssets(
                        pathFieldsNode, sourceRootPath, targetPath, mode, options);
            }

            respondJSON(response, result);

        } catch (Exception e) {
            logger.error("Error executing export", e);
            respondCodeError(response, "Export error: " + e.getMessage(), 
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }
}

