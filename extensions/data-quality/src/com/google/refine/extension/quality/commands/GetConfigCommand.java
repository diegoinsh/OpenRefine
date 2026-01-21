/*
 * Data Quality Extension - Get Config Command
 */
package com.google.refine.extension.quality.commands;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
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
 * Command to retrieve data quality configuration.
 * Config is stored in workspace/data-quality-config.json
 */
public class GetConfigCommand extends Command {

    private static final Logger logger = LoggerFactory.getLogger("GetConfigCommand");
    private static final String CONFIG_FILE_NAME = "data-quality-config.json";
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try {
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Content-Type", "application/json");

            Map<String, Object> config = loadConfig();
            
            String json = mapper.writeValueAsString(config);
            response.getWriter().write("{\"code\":\"ok\",\"config\":" + json + "}");

        } catch (Exception e) {
            logger.error("Error getting config", e);
            respondException(response, e);
        }
    }

    /**
     * Load config from workspace directory
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> loadConfig() {
        try {
            File workspaceDir = ((FileProjectManager) FileProjectManager.singleton).getWorkspaceDir();
            File configFile = new File(workspaceDir, CONFIG_FILE_NAME);
            
            if (configFile.exists()) {
                return mapper.readValue(configFile, Map.class);
            }
        } catch (Exception e) {
            logger.warn("Failed to load config file, using defaults", e);
        }
        
        // Return default config
        return getDefaultConfig();
    }

    /**
     * Get default configuration
     */
    public static Map<String, Object> getDefaultConfig() {
        Map<String, Object> config = new HashMap<>();
        
        // AIMP服务配置
        config.put("aimp.server", "http://127.0.0.1:7998");
        config.put("aimp.timeout", 30);
        
        // 任务执行配置
        config.put("task.threadPoolSize", 4);
        config.put("task.batchSize", 100);
        config.put("task.autosaveInterval", 60);
        
        // 内容比对配置
        config.put("content.similarityPass", 100);
        config.put("content.similarityWarning", 100);
        config.put("content.batchSize", 5);
        config.put("content.ocrConfidence", 0.8);
        
        // 界面配置
        config.put("ui.refreshInterval", 5);
        config.put("ui.pageSize", 50);
        
        return config;
    }
}

