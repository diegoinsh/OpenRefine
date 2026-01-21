/*
 * Data Quality Extension - Test AIMP Connection Command
 */
package com.google.refine.extension.quality.commands;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.commands.Command;

/**
 * Command to test AIMP service connection.
 */
public class TestAimpConnectionCommand extends Command {

    private static final Logger logger = LoggerFactory.getLogger("TestAimpConnectionCommand");
    private static final int CONNECTION_TIMEOUT = 5000; // 5 seconds

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        doTestConnection(request, response);
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        if (!hasValidCSRFToken(request)) {
            respondCSRFError(response);
            return;
        }
        doTestConnection(request, response);
    }

    private void doTestConnection(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        try {
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Content-Type", "application/json");

            // Support both "server" and "serviceUrl" parameter names
            String serverUrl = request.getParameter("serviceUrl");
            if (serverUrl == null || serverUrl.trim().isEmpty()) {
                serverUrl = request.getParameter("server");
            }

            if (serverUrl == null || serverUrl.trim().isEmpty()) {
                serverUrl = "http://127.0.0.1:7998";
            }

            // Test connection to AIMP health endpoint
            boolean connected = testConnection(serverUrl);

            if (connected) {
                response.getWriter().write("{\"code\":\"ok\",\"connected\":true,\"message\":\"连接成功\"}");
            } else {
                response.getWriter().write("{\"code\":\"ok\",\"connected\":false,\"message\":\"无法连接到服务\"}");
            }

        } catch (Exception e) {
            logger.error("Error testing AIMP connection", e);
            response.getWriter().write("{\"code\":\"ok\",\"connected\":false,\"message\":\"" +
                escapeJson(e.getMessage()) + "\"}");
        }
    }

    /**
     * Test connection to AIMP server
     * Supports both 7998 (/health) and 7999 (/docs) endpoints
     * Returns true if either endpoint is accessible
     */
    private boolean testConnection(String serverUrl) {
        HttpURLConnection connection = null;
        try {
            if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
                logger.warn("Invalid URL format: {}", serverUrl);
                return false;
            }

            String baseUrl = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;

            String[] endpoints = {"/health", "/docs"};
            for (String endpoint : endpoints) {
                String testUrl = baseUrl + endpoint;
                try {
                    URL url = new URL(testUrl);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(CONNECTION_TIMEOUT);
                    connection.setReadTimeout(CONNECTION_TIMEOUT);

                    int responseCode = connection.getResponseCode();
                    logger.debug("AIMP health check response ({}): {}", testUrl, responseCode);

                    if (responseCode == 200) {
                        logger.info("AIMP connection successful via endpoint: {}", endpoint);
                        return true;
                    }
                } catch (Exception e) {
                    logger.debug("Endpoint {} failed: {}", endpoint, e.getMessage());
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                        connection = null;
                    }
                }
            }

            logger.warn("AIMP connection test failed for all endpoints");
            return false;

        } catch (Exception e) {
            logger.warn("AIMP connection test failed: {}", e.getMessage());
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Escape special characters for JSON string
     */
    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
}

