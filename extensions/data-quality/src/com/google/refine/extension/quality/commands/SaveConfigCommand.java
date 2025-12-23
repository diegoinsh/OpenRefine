/*
 * Data Quality Extension - Save Config Command
 */
package com.google.refine.extension.quality.commands;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.commands.Command;
import com.google.refine.io.FileProjectManager;

/**
 * Command to save data quality configuration.
 * Config is stored in workspace/data-quality-config.json
 */
public class SaveConfigCommand extends Command {

    private static final Logger logger = LoggerFactory.getLogger("SaveConfigCommand");
    private static final String CONFIG_FILE_NAME = "data-quality-config.json";
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try {
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Content-Type", "application/json");

            String configJson = request.getParameter("config");
            
            if (configJson == null || configJson.trim().isEmpty()) {
                response.getWriter().write("{\"code\":\"error\",\"message\":\"Config is required\"}");
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> config = mapper.readValue(configJson, Map.class);
            
            saveConfig(config);
            
            logger.info("Config saved successfully");
            response.getWriter().write("{\"code\":\"ok\"}");

        } catch (Exception e) {
            logger.error("Error saving config", e);
            respondException(response, e);
        }
    }

    /**
     * Save config to workspace directory
     */
    public static void saveConfig(Map<String, Object> config) throws IOException {
        File workspaceDir = ((FileProjectManager) FileProjectManager.singleton).getWorkspaceDir();
        File configFile = new File(workspaceDir, CONFIG_FILE_NAME);
        
        mapper.writerWithDefaultPrettyPrinter().writeValue(configFile, config);
        logger.debug("Config saved to: {}", configFile.getAbsolutePath());
    }
}

