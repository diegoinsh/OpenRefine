/*
 * Data Quality Extension - Check AIMP Connection Command
 */
package com.google.refine.extension.quality.commands;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.commands.Command;
import com.google.refine.extension.quality.aimp.AimpClient;

/**
 * Command to check AIMP service connection status.
 */
public class CheckAimpConnectionCommand extends Command {

    private static final Logger logger = LoggerFactory.getLogger(CheckAimpConnectionCommand.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    // Default AIMP service URL - can be configured
    private static String configuredServiceUrl = "";

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try {
            ObjectNode responseNode = mapper.createObjectNode();

            if (configuredServiceUrl == null || configuredServiceUrl.isEmpty()) {
                responseNode.put("connected", false);
                responseNode.put("message", "Service URL not configured");
            } else {
                AimpClient client = new AimpClient(configuredServiceUrl);
                boolean connected = client.testConnection();
                responseNode.put("connected", connected);
                responseNode.put("serviceUrl", configuredServiceUrl);
                if (!connected) {
                    responseNode.put("message", "Cannot connect to AIMP service");
                }
            }

            respondJSON(response, responseNode);

        } catch (Exception e) {
            logger.error("Error checking AIMP connection", e);
            ObjectNode errorResponse = mapper.createObjectNode();
            errorResponse.put("connected", false);
            errorResponse.put("message", e.getMessage());
            respondJSON(response, errorResponse);
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
            String serviceUrl = request.getParameter("serviceUrl");
            ObjectNode responseNode = mapper.createObjectNode();

            if (serviceUrl == null || serviceUrl.isEmpty()) {
                responseNode.put("connected", false);
                responseNode.put("message", "Service URL is required");
            } else {
                AimpClient client = new AimpClient(serviceUrl);
                boolean connected = client.testConnection();
                responseNode.put("connected", connected);

                if (connected) {
                    // Save the configured URL
                    configuredServiceUrl = serviceUrl;
                    responseNode.put("message", "Connection successful");
                } else {
                    responseNode.put("message", "Cannot connect to AIMP service at " + serviceUrl);
                }
            }

            respondJSON(response, responseNode);

        } catch (Exception e) {
            logger.error("Error testing AIMP connection", e);
            ObjectNode errorResponse = mapper.createObjectNode();
            errorResponse.put("connected", false);
            errorResponse.put("message", e.getMessage());
            respondJSON(response, errorResponse);
        }
    }

    public static String getConfiguredServiceUrl() {
        return configuredServiceUrl;
    }

    public static void setConfiguredServiceUrl(String url) {
        configuredServiceUrl = url;
    }
}

