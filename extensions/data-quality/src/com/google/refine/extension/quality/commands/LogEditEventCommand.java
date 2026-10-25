/*
 * Data Quality Extension - Log Edit Event Command
 */
package com.google.refine.extension.quality.commands;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.Collections;
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
 * Records human cell-edit events, appended to
 * {@code workspace/edit-events/<projectId>.jsonl}.
 *
 * <p>Purpose: collect the calibration labels that the rule-based quality score lacks —
 * the value the user changed a cell from/to, plus the page and quality score the
 * candidate came from. Nothing is written into project data; removing the client hook
 * (or setting {@code ExtractionEditLog.ENABLED = false}) restores the previous behavior.</p>
 */
public class LogEditEventCommand extends Command {

    private static final Logger logger = LoggerFactory.getLogger("LogEditEventCommand");
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String DIR_NAME = "edit-events";
    private static final int MAX_EVENT_CHARS = 8 * 1024;
    private static final Object lock = new Object();

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try {
            response.setCharacterEncoding("UTF-8");

            String eventJson = request.getParameter("event");
            String projectId = request.getParameter("project");

            if (eventJson == null || eventJson.trim().isEmpty()
                    || eventJson.length() > MAX_EVENT_CHARS) {
                respondCodeError(response, "event is required and must be small");
                return;
            }
            if (projectId == null || !projectId.matches("[A-Za-z0-9_.-]{1,64}")) {
                respondCodeError(response, "invalid project id");
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> event = mapper.readValue(eventJson, Map.class);
            event.putIfAbsent("ts", OffsetDateTime.now().toString());
            event.putIfAbsent("projectId", projectId);

            File workspaceDir = ((FileProjectManager) FileProjectManager.singleton).getWorkspaceDir();
            Path dir = new File(workspaceDir, DIR_NAME).toPath();
            Files.createDirectories(dir);
            Path file = dir.resolve(projectId + ".jsonl");

            String line = mapper.writeValueAsString(event) + "\n";
            synchronized (lock) {
                Files.write(file, line.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }

            respondJSON(response, Collections.singletonMap("code", "ok"));

        } catch (Exception e) {
            logger.error("Error logging edit event", e);
            respondException(response, e);
        }
    }
}
