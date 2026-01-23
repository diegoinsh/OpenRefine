/*

Copyright 2010, 2022 Google Inc. & OpenRefine contributors
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

    * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
    * Neither the name of Google Inc. nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,           
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY           
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

*/

package com.google.refine.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.fasterxml.jackson.databind.InjectableValues;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.ProjectManager;
import com.google.refine.ProjectMetadata;
import com.google.refine.RefineServlet;
import com.google.refine.history.History;
import com.google.refine.process.ProcessManager;
import com.google.refine.util.ParsingUtilities;
import com.google.refine.util.Pool;

/**
 * Project with all its associated metadata and data
 */
public class Project {

    final static protected Map<String, Class<? extends OverlayModel>> s_overlayModelClasses = new HashMap<String, Class<? extends OverlayModel>>();

    final public long id;
    public List<Row> rows = new ArrayList<>();
    public ColumnModel columnModel = new ColumnModel();
    public RecordModel recordModel = new RecordModel();
    final public Map<String, OverlayModel> overlayModels = new HashMap<String, OverlayModel>();
    final public History history;

    transient public ProcessManager processManager = new ProcessManager();
    transient private Instant _lastSave = Instant.now();

    public String activeSheetId;
    public Map<String, SheetData> sheetDataMap = new HashMap<>();
    public boolean isMultiSheetProject = false;

    final static Logger logger = LoggerFactory.getLogger(Project.class);

    static public long generateID() {
        return System.currentTimeMillis() + Math.round(Math.random() * 1000000000000L);
    }

    /**
     * Create a new project with a generated unique ID
     */
    public Project() {
        this(generateID());
    }

    /**
     * Create a new project with the given ID. For testing ONLY.
     *
     * @param id
     *            long ID to be assigned the new project
     */
    protected Project(long id) {
        this.id = id;
        this.history = new History(this);
        this.sheetDataMap = new HashMap<>();
        this.isMultiSheetProject = false;
    }

    static public void registerOverlayModel(String modelName, Class<? extends OverlayModel> klass) {
        s_overlayModelClasses.put(modelName, klass);
    }

    /**
     * Free/dispose of project data from memory.
     */
    public void dispose() {
        for (OverlayModel overlayModel : overlayModels.values()) {
            try {
                overlayModel.dispose(this);
            } catch (Exception e) {
                logger.warn("Error signaling overlay model before disposing", e);
            }
        }
        for (SheetData sheetData : sheetDataMap.values()) {
            try {
                sheetData.dispose();
            } catch (Exception e) {
                logger.warn("Error disposing sheet data", e);
            }
        }
        ProjectManager.singleton.getLookupCacheManager().flushLookupsInvolvingProject(this.id);
        // The rest of the project should get garbage collected when we return.
    }

    public SheetData getActiveSheetData() {
        if (isMultiSheetProject && activeSheetId != null) {
            return sheetDataMap.get(activeSheetId);
        }
        return null;
    }

    public void setActiveSheet(String sheetId) {
        if (sheetDataMap.containsKey(sheetId)) {
            this.activeSheetId = sheetId;
            SheetData sheetData = sheetDataMap.get(sheetId);
            this.rows = sheetData.rows;
            this.columnModel = sheetData.columnModel;
            this.recordModel = sheetData.recordModel;
            logger.info("setActiveSheet: switched to " + sheetId + ", project.rows.size()=" + this.rows.size());
        } else {
            logger.warn("setActiveSheet: sheetId not found: " + sheetId);
        }
    }

    public void addSheetData(SheetData sheetData) {
        sheetDataMap.put(sheetData.sheetId, sheetData);
        if (sheetDataMap.size() > 1) {
            isMultiSheetProject = true;
        }
    }

    public SheetData getSheetData(String sheetId) {
        return sheetDataMap.get(sheetId);
    }

    public List<SheetData> getAllSheetData() {
        return new ArrayList<>(sheetDataMap.values());
    }
    
    private String tempNextSheetLine = null;
    private boolean hasPendingSheet = false;
    
    public void setTempNextSheetLine(String line) {
        this.tempNextSheetLine = line;
        this.hasPendingSheet = true;
        logger.info("setTempNextSheetLine: set to '" + line + "', hasPendingSheet=true");
    }
    
    public String getTempNextSheetLine() {
        return this.tempNextSheetLine;
    }
    
    public void clearTempNextSheetLine() {
        this.tempNextSheetLine = null;
        this.hasPendingSheet = false;
    }
    
    public boolean hasPendingSheet() {
        return this.hasPendingSheet;
    }

    public Instant getLastSave() {
        return this._lastSave;
    }

    /**
     * Sets the lastSave time to now
     */
    public void setLastSave() {
        this._lastSave = Instant.now();
    }

    public ProjectMetadata getMetadata() {
        return ProjectManager.singleton.getProjectMetadata(id);
    }

    public void saveToOutputStream(OutputStream out, Pool pool) throws IOException {
        for (OverlayModel overlayModel : overlayModels.values()) {
            try {
                overlayModel.onBeforeSave(this);
            } catch (Exception e) {
                logger.warn("Error signaling overlay model before saving", e);
            }
        }

        Writer writer = new OutputStreamWriter(out, "UTF-8");
        try {
            Properties options = new Properties();
            options.setProperty("mode", "save");
            options.put("pool", pool);

            saveToWriter(writer, options);
        } finally {
            writer.flush();
        }

        for (OverlayModel overlayModel : overlayModels.values()) {
            try {
                overlayModel.onAfterSave(this);
            } catch (Exception e) {
                logger.warn("Error signaling overlay model after saving", e);
            }
        }
    }

    protected void saveToWriter(Writer writer, Properties options) throws IOException {
        writer.write(RefineServlet.VERSION);
        writer.write('\n');

        if (isMultiSheetProject) {
            writer.write("isMultiSheetProject=true\n");
            logger.info("saveToWriter: isMultiSheetProject=true, activeSheetId=" + activeSheetId);
            if (activeSheetId != null) {
                writer.write("activeSheetId=");
                writer.write(activeSheetId);
                writer.write('\n');
            }
            writer.write("sheetCount=");
            writer.write(Integer.toString(sheetDataMap.size()));
            writer.write('\n');
            
            for (SheetData sheetData : sheetDataMap.values()) {
                writer.write("sheet:");
                writer.write(sheetData.sheetId);
                writer.write("=\n");
                
                writer.write("columnModel=\n");
                sheetData.columnModel.save(writer, options);
                
                writer.write("rowCount=");
                writer.write(Integer.toString(sheetData.rows.size()));
                writer.write('\n');
                for (Row row : sheetData.rows) {
                    row.save(writer, options);
                    writer.write('\n');
                }
            }
            
            writer.write("history=\n");
            history.save(writer, options);
        } else {
            writer.write("columnModel=\n");
            columnModel.save(writer, options);
            writer.write("history=\n");
            history.save(writer, options);

            for (String modelName : overlayModels.keySet()) {
                writer.write("overlayModel:");
                writer.write(modelName);
                writer.write("=");

                ParsingUtilities.saveWriter.writeValue(writer, overlayModels.get(modelName));
                writer.write('\n');
            }

            writer.write("rowCount=");
            writer.write(Integer.toString(rows.size()));
            writer.write('\n');
            for (Row row : rows) {
                row.save(writer, options);
                writer.write('\n');
            }
        }
    }

    static public Project loadFromInputStream(InputStream is, long id, Pool pool) throws IOException {
        return loadFromReader(new LineNumberReader(new InputStreamReader(is, StandardCharsets.UTF_8)), id, pool);
    }

    static private Project loadFromReader(
            LineNumberReader reader,
            long id,
            Pool pool) throws IOException {
        long start = System.currentTimeMillis();

        String version = reader.readLine();

        Project project = new Project(id);
        int maxCellCount = 0;

        ObjectMapper mapper = ParsingUtilities.mapper.copy();
        InjectableValues injections = new InjectableValues.Std().addValue("project", project);
        mapper.setInjectableValues(injections);

        String line;
        
        while (true) {
            logger.info("loadFromReader: Outer loop iteration start, hasPendingSheet=" + project.hasPendingSheet() + ", tempNextSheetLine=" + (project.getTempNextSheetLine() != null ? "set" : "null"));
            
            if (project.hasPendingSheet()) {
                line = project.getTempNextSheetLine();
                logger.info("loadFromReader: >>>> USING PENDING SHEET LINE: " + line);
                project.clearTempNextSheetLine();
                logger.info("loadFromReader: After clearTempNextSheetLine, hasPendingSheet=" + project.hasPendingSheet());
            } else {
                line = reader.readLine();
                logger.info("loadFromReader: Read line from reader: " + (line == null ? "null" : line.substring(0, Math.min(50, line.length()))));
            }
            if (line == null) {
                logger.info("loadFromReader: End of file reached");
                break;
            }
            
            if ("/e/".equals(line)) {
                logger.warn("/e/ found!!!!!!!!!!!!!!!!!!!!!!", line);
                continue;
            }
            
            if (line.startsWith("sheet:")) {
                int equal = line.indexOf('=');
                if (equal == -1) {
                    logger.warn("Invalid sheet format: " + line);
                    continue;
                }
                String sheetId = line.substring("sheet:".length(), equal);
                
                String sheetName = sheetId;
                String[] parts = sheetId.split("#");
                if (parts.length > 1) {
                    sheetName = parts[1];
                }
                
                logger.info("loadFromReader: Processing sheet: " + sheetId);
                SheetData sheetData = new SheetData(sheetId, sheetName, "");
                
                String sheetField;
                boolean inColumnModel = false;
                boolean foundRowCount = false;
                int sheetFieldCount = 0;
                while ((sheetField = reader.readLine()) != null) {
                    sheetFieldCount++;
                    logger.info("loadFromReader: sheetField #" + sheetFieldCount + ": " + (sheetField.length() > 50 ? sheetField.substring(0, 50) + "..." : sheetField));
                    
                    if ("/e/".equals(sheetField)) {
                        inColumnModel = false;
                        if (foundRowCount) {
                            logger.info("loadFromReader: /e/ after rowCount, ending sheet processing");
                            break;
                        }
                        logger.info("loadFromReader: /e/ found but rowCount not found yet, continuing");
                        continue;
                    }
                    
                    if (sheetField.startsWith("sheet:")) {
                        logger.info("loadFromReader: >>>>> NEXT SHEET DETECTED: " + sheetField + " <<<<<");
                        logger.info("loadFromReader: Before setTempNextSheetLine, hasPendingSheet=" + project.hasPendingSheet());
                        project.setTempNextSheetLine(sheetField);
                        logger.info("loadFromReader: After setTempNextSheetLine, hasPendingSheet=" + project.hasPendingSheet());
                        logger.debug("loadFromReader: Set tempNextSheetLine to: " + sheetField);
                        break;
                    }
                    
                    if (inColumnModel) {
                        logger.debug("loadFromReader: inColumnModel=true, skipping line");
                        continue;
                    }
                    
                    int sheetFieldEqual = sheetField.indexOf('=');
                    if (sheetFieldEqual == -1) {
                        logger.warn("loadFromReader: Invalid line format (missing '='): " + sheetField.substring(0, Math.min(50, sheetField.length())));
                        continue;
                    }
                    String sheetFieldName = sheetField.substring(0, sheetFieldEqual);
                    String sheetFieldValue = sheetField.substring(sheetFieldEqual + 1);
                    
                    if ("columnModel".equals(sheetFieldName)) {
                        logger.info("Loading columnModel for sheet: " + sheetData.sheetId);
                        inColumnModel = true;
                        sheetData.columnModel.load(reader);
                        logger.info("ColumnModel loaded, columns.size=" + sheetData.columnModel.columns.size());
                        inColumnModel = false;
                        
                        String endMarker = reader.readLine();
                        logger.info("loadFromReader: After ColumnModel.load(), endMarker=" + endMarker);
                        if (endMarker == null) {
                            logger.error("loadFromReader: Unexpected end of file after columnModel");
                        } else if ("/e/".equals(endMarker)) {
                            logger.info("loadFromReader: Consumed /e/ after columnModel");
                        } else {
                            logger.warn("loadFromReader: Expected /e/ after columnModel, got: " + endMarker);
                            // If it's rowCount or sheet:, put it back for processing
                            if (endMarker != null && (endMarker.startsWith("rowCount=") || endMarker.startsWith("sheet:"))) {
                                project.setTempNextSheetLine(endMarker);
                                logger.info("loadFromReader: Set tempNextSheetLine to: " + endMarker);
                            } else {
                                reader.reset();
                            }
                        }
                    } else if ("rowCount".equals(sheetFieldName)) {
                        int count = Integer.parseInt(sheetFieldValue);
                        logger.info("Found rowCount=" + count + " for sheet: " + sheetData.sheetId);
                        foundRowCount = true;
                        for (int j = 0; j < count; j++) {
                            String rowLine = reader.readLine();
                            if (rowLine != null) {
                                Row row = Row.load(rowLine, pool);
                                sheetData.rows.add(row);
                                maxCellCount = Math.max(maxCellCount, row.cells.size());
                            }
                        }
                        logger.info("Loaded " + sheetData.rows.size() + " rows for sheet: " + sheetData.sheetId);
                    } else if ("history".equals(sheetFieldName)) {
                        logger.info("loadFromReader: Found history field, ending sheet processing");
                        project.setTempNextSheetLine(sheetField);
                        break;
                    } else {
                        logger.info("loadFromReader: Unknown field in sheet: " + sheetFieldName + ", ending sheet processing");
                        project.setTempNextSheetLine(sheetField);
                        break;
                    }
                }
                
                logger.info("loadFromReader: Sheet processing loop ended, sheetFieldCount=" + sheetFieldCount + ", foundRowCount=" + foundRowCount);
                
                logger.info("Adding sheet to project: " + sheetData.sheetId + ", rows=" + sheetData.rows.size());
                project.addSheetData(sheetData);
                continue;
            }
            
            int equal = line.indexOf('=');
            if (equal == -1) {
                logger.warn("Invalid line format (missing '='): {}", line);
                continue;
            }
            String field = line.substring(0, equal);
            String value = line.substring(equal + 1);

            if ("isMultiSheetProject".equals(field)) {
                project.isMultiSheetProject = Boolean.parseBoolean(value);
                logger.info("loadFromReader: isMultiSheetProject=" + project.isMultiSheetProject);
            } else if ("activeSheetId".equals(field)) {
                project.activeSheetId = value;
                logger.info("loadFromReader: activeSheetId=" + project.activeSheetId);
            } else if ("sheetCount".equals(field)) {
                int sheetCount = Integer.parseInt(value);
                logger.info("loadFromReader: sheetCount=" + sheetCount + ", sheets will be processed when 'sheet:' lines are encountered");
            } else if ("columnModel".equals(field)) {
                if (!project.isMultiSheetProject) {
                    project.columnModel.load(reader);
                }
            } else if ("history".equals(field)) {
                if (!project.isMultiSheetProject) {
                    project.history.load(project, reader);
                } else {
                    logger.info("loadFromReader: Loading history for multi-sheet project");
                    project.history.load(project, reader);
                }
            } else if ("rowCount".equals(field)) {
                if (!project.isMultiSheetProject) {
                    int count = Integer.parseInt(value);
                    for (int i = 0; i < count; i++) {
                        line = reader.readLine();
                        if (line != null) {
                            Row row = Row.load(line, pool);
                            project.rows.add(row);
                            maxCellCount = Math.max(maxCellCount, row.cells.size());
                        }
                    }
                }
            } else if (field.startsWith("overlayModel:")) {
                String modelName = field.substring("overlayModel:".length());
                if (s_overlayModelClasses.containsKey(modelName)) {
                    Class<? extends OverlayModel> klass = s_overlayModelClasses.get(modelName);

                    try {
                        OverlayModel overlayModel = ParsingUtilities.mapper.readValue(value, klass);

                        project.overlayModels.put(modelName, overlayModel);
                    } catch (IOException e) {
                        logger.error("Failed to load overlay model " + modelName);
                    }
                }
            }
        }

        if (project.isMultiSheetProject && project.activeSheetId != null) {
            logger.info("loadFromReader: Calling setActiveSheet with activeSheetId=" + project.activeSheetId);
            project.setActiveSheet(project.activeSheetId);
            
            SheetData activeData = project.getActiveSheetData();
            if (activeData == null || activeData.rows.isEmpty()) {
                logger.warn("loadFromReader: activeSheet has no data, switching to first sheet with data");
                for (SheetData sheetData : project.getAllSheetData()) {
                    if (!sheetData.rows.isEmpty()) {
                        logger.info("loadFromReader: Switching to first sheet with data: " + sheetData.sheetId);
                        project.setActiveSheet(sheetData.sheetId);
                        break;
                    }
                }
            }
        } else {
            logger.info("loadFromReader: NOT calling setActiveSheet. isMultiSheetProject=" + project.isMultiSheetProject + ", activeSheetId=" + project.activeSheetId);
        }

        if (!project.isMultiSheetProject) {
            project.columnModel.setMaxCellIndex(maxCellCount - 1);
        }

        logger.info(
                "Loaded project {} from disk in {} sec(s)", id, Long.toString((System.currentTimeMillis() - start) / 1000));

        project.update();

        return project;
    }

    public void update() {
        columnModel.update();
        recordModel.update(this);
        // Old projects may have a row count of 0, but we don't want the act of filling this in to change modified time.
        if (getMetadata() != null) {
            getMetadata().setRowCountInternal(rows.size());
        }
    }

    // wrapper of processManager variable to allow unit testing
    // TODO make the processManager variable private, and force all calls through this method
    public ProcessManager getProcessManager() {
        return this.processManager;
    }
}
