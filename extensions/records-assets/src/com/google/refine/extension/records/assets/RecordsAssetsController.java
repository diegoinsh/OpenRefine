/*
 * Records Assets Controller
 */

package com.google.refine.extension.records.assets;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.refine.RefineServlet;
import com.google.refine.commands.HttpUtilities;
import com.google.refine.importing.ImportingController;
import com.google.refine.util.JSONUtilities;
import com.google.refine.util.ParsingUtilities;

/**
 * Controller for Records Assets endpoints
 * Handles file/directory listing and preview
 */
public class RecordsAssetsController implements ImportingController {

    private static final Logger logger = LoggerFactory.getLogger("RecordsAssetsController");
    protected RefineServlet servlet;

    @Override
    public void init(RefineServlet servlet) {
        this.servlet = servlet;
        logger.info("RecordsAssetsController initialized");
    }

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        response.setCharacterEncoding("UTF-8");
        String command = request.getPathInfo();

        if (logger.isDebugEnabled()) {
            logger.debug("doGet command: {}", command);
        }

        if ("/list".equals(command)) {
            doList(request, response);
        } else if ("/preview".equals(command)) {
            doPreview(request, response);
        } else {
            HttpUtilities.respond(response, "error", "Unknown command: " + command);
        }
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        doGet(request, response);
    }

    /**
     * List files and directories
     * GET /command/records-assets/list?root=<root>&path=<path>&depth=<depth>&offset=<offset>&limit=<limit>
     */
    private void doList(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        if (logger.isDebugEnabled()) {
            logger.debug("::doList::");
        }

        try {
            String root = request.getParameter("root");
            String path = request.getParameter("path");
            String depthStr = request.getParameter("depth");
            String offsetStr = request.getParameter("offset");
            String limitStr = request.getParameter("limit");

            int depth = depthStr != null ? Integer.parseInt(depthStr) : 1;
            int offset = offsetStr != null ? Integer.parseInt(offsetStr) : 0;
            int limit = limitStr != null ? Integer.parseInt(limitStr) : 100;

            if (root == null || root.isEmpty()) {
                HttpUtilities.respond(response, "error", "root parameter is required");
                return;
            }

            // List directory
            ObjectNode result = DirectoryLister.listDirectory(root, path, depth, offset, limit);

            if (logger.isDebugEnabled()) {
                logger.debug("doList:::{}", result.toString());
            }

            HttpUtilities.respond(response, result.toString());
        } catch (Exception e) {
            logger.error("Error in doList", e);
            ObjectNode result = ParsingUtilities.mapper.createObjectNode();
            JSONUtilities.safePut(result, "status", "error");
            JSONUtilities.safePut(result, "message", e.getMessage());
            HttpUtilities.respond(response, result.toString());
        }
    }

    /**
     * Preview file content
     * GET /command/records-assets/preview?root=<root>&path=<path>&type=<type>
     */
    private void doPreview(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        if (logger.isDebugEnabled()) {
            logger.debug("::doPreview::");
        }

        try {
            String root = request.getParameter("root");
            String path = request.getParameter("path");
            String type = request.getParameter("type");

            if (root == null || root.isEmpty() || path == null || path.isEmpty()) {
                HttpUtilities.respond(response, "error", "root and path parameters are required");
                return;
            }

            ObjectNode result = ParsingUtilities.mapper.createObjectNode();
            JSONUtilities.safePut(result, "status", "error");
            JSONUtilities.safePut(result, "message", "Preview not yet implemented");

            if (logger.isDebugEnabled()) {
                logger.debug("doPreview:::{}", result.toString());
            }

            HttpUtilities.respond(response, result.toString());
        } catch (Exception e) {
            logger.error("Error in doPreview", e);
            HttpUtilities.respond(response, "error", e.getMessage());
        }
    }
}

