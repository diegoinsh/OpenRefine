/*
 * File Command - Serves raw file content for preview (PDF, images, etc.)
 */

package com.google.refine.extension.records.assets;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.commands.Command;

/**
 * Command to serve raw file content
 * GET /command/records-assets/file?path=...
 */
public class FileCommand extends Command {

    private static final Logger logger = LoggerFactory.getLogger("FileCommand");
    private static final int BUFFER_SIZE = 8192;

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        if (logger.isDebugEnabled()) {
            logger.debug("FileCommand::doGet");
        }

        try {
            String root = request.getParameter("root");
            String path = request.getParameter("path");

            // Handle empty root - use path as full path
            if (root == null || root.isEmpty()) {
                if (path != null && !path.isEmpty()) {
                    root = path;
                    path = "";
                } else {
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST, "path parameter is required");
                    return;
                }
            }

            // Validate security
            if (!SecurityValidator.isPathSafe(root, path)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid or unsafe path");
                return;
            }

            // Get canonical path
            String fullPath = PathValidator.getCanonicalPath(root, path);
            if (fullPath == null) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid path");
                return;
            }

            File file = new File(fullPath);
            if (!file.exists()) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "File not found");
                return;
            }

            if (!file.isFile()) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Path is not a file");
                return;
            }

            // Determine content type
            String contentType = getMimeType(file.getName());
            response.setContentType(contentType);
            response.setContentLengthLong(file.length());

            // Set headers for inline display (not download)
            response.setHeader("Content-Disposition", "inline; filename=\"" + file.getName() + "\"");

            // Allow cross-origin for PDF viewer
            response.setHeader("Access-Control-Allow-Origin", "*");

            // Stream file content
            try (FileInputStream fis = new FileInputStream(file);
                 OutputStream os = response.getOutputStream()) {
                
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
                os.flush();
            }

            if (logger.isDebugEnabled()) {
                logger.debug("Served file: {} ({})", fullPath, contentType);
            }

        } catch (Exception e) {
            logger.error("Error in FileCommand", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /**
     * Get MIME type from filename
     */
    private String getMimeType(String filename) {
        String lower = filename.toLowerCase();

        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        } else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (lower.endsWith(".png")) {
            return "image/png";
        } else if (lower.endsWith(".gif")) {
            return "image/gif";
        } else if (lower.endsWith(".bmp")) {
            return "image/bmp";
        } else if (lower.endsWith(".webp")) {
            return "image/webp";
        } else if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        } else if (lower.endsWith(".txt")) {
            return "text/plain; charset=utf-8";
        } else if (lower.endsWith(".json")) {
            return "application/json; charset=utf-8";
        } else if (lower.endsWith(".xml")) {
            return "application/xml; charset=utf-8";
        } else {
            return "application/octet-stream";
        }
    }
}

