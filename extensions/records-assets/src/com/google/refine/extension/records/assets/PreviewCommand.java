/*
 * Preview Command - Handles file preview
 */

package com.google.refine.extension.records.assets;

import java.io.File;
import java.io.IOException;
import java.nio.file.NoSuchFileException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.refine.commands.Command;
import com.google.refine.commands.HttpUtilities;
import com.google.refine.util.JSONUtilities;
import com.google.refine.util.ParsingUtilities;

/**
 * Command to preview file content
 * GET /command/records-assets/preview
 */
public class PreviewCommand extends Command {

    private static final Logger logger = LoggerFactory.getLogger("PreviewCommand");

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");

        if (logger.isDebugEnabled()) {
            logger.debug("PreviewCommand::doGet");
        }

        String root = request.getParameter("root");
        String path = request.getParameter("path");

        try {
            String thumbnailStr = request.getParameter("thumbnail");
            boolean thumbnail = "true".equalsIgnoreCase(thumbnailStr);

            // Handle empty root - use path as full path
            if (root == null || root.isEmpty()) {
                if (path != null && !path.isEmpty()) {
                    root = path;
                    path = "";
                } else {
                    ObjectNode result = ParsingUtilities.mapper.createObjectNode();
                    JSONUtilities.safePut(result, "status", "error");
                    JSONUtilities.safePut(result, "message", "path parameter is required");
                    HttpUtilities.respond(response, result.toString());
                    return;
                }
            }

            // Validate security
            if (!SecurityValidator.isPathSafe(root, path)) {
                ObjectNode result = ParsingUtilities.mapper.createObjectNode();
                JSONUtilities.safePut(result, "status", "error");
                JSONUtilities.safePut(result, "message", "Invalid or unsafe path");
                HttpUtilities.respond(response, result.toString());
                return;
            }

            // Generate preview
            ObjectNode result;
            if (thumbnail) {
                result = FilePreviewHandler.generateThumbnail(root, path);
            } else {
                result = FilePreviewHandler.generatePreview(root, path);
            }

            if (logger.isDebugEnabled()) {
                logger.debug("PreviewCommand result for: {}/{}", root, path);
            }

            HttpUtilities.respond(response, result.toString());

        } catch (NoSuchFileException e) {
            String errorPath = e.getFile() != null ? e.getFile() : (root + File.separator + path);
            logger.warn("File not found: {}", errorPath);
            ObjectNode result = ParsingUtilities.mapper.createObjectNode();
            JSONUtilities.safePut(result, "status", "error");
            JSONUtilities.safePut(result, "message", "文件路径不存在: " + errorPath);
            JSONUtilities.safePut(result, "errorPath", errorPath);
            HttpUtilities.respond(response, result.toString());
        } catch (IOException e) {
            String errorPath = root + (path != null && !path.isEmpty() ? File.separator + path : "");
            logger.warn("IO error for path: {} - {}", errorPath, e.getMessage());
            ObjectNode result = ParsingUtilities.mapper.createObjectNode();
            JSONUtilities.safePut(result, "status", "error");
            JSONUtilities.safePut(result, "message", "文件路径错误: " + errorPath);
            JSONUtilities.safePut(result, "errorPath", errorPath);
            HttpUtilities.respond(response, result.toString());
        } catch (Exception e) {
            logger.error("Error in PreviewCommand", e);
            ObjectNode result = ParsingUtilities.mapper.createObjectNode();
            JSONUtilities.safePut(result, "status", "error");
            JSONUtilities.safePut(result, "message", e.getMessage());
            HttpUtilities.respond(response, result.toString());
        }
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        doGet(request, response);
    }
}

