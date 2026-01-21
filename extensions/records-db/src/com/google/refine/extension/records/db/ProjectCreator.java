package com.google.refine.extension.records.db;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.ProjectManager;
import com.google.refine.ProjectMetadata;
import com.google.refine.extension.records.db.model.RecordsDBOverlayModel;
import com.google.refine.extension.records.db.model.SchemaProfile;
import com.google.refine.importers.TabularImportingParserBase;
import com.google.refine.importing.ImportingJob;
import com.google.refine.importing.ImportingManager;
import com.google.refine.model.Project;
import com.google.refine.util.JSONUtilities;
import com.google.refine.util.ParsingUtilities;

/**
 * Creates OpenRefine projects from database data for the records-db extension.
 */
public class ProjectCreator {

    private static final Logger logger = LoggerFactory.getLogger("ProjectCreator");
    private static final int DEFAULT_BATCH_SIZE = 1000;

    /**
     * Prepare project creation from database data.
     *
     * This implementation creates the project synchronously and returns
     * a result object containing the created project id and basic stats.
     */
    public static ObjectNode prepareProjectCreation(final String projectName,
            final SchemaProfile profile, final int maxRows) throws Exception {

        if (logger.isDebugEnabled()) {
            logger.debug("Preparing project creation: {}", projectName);
        }

        long rowCount = 0L;
        Connection countConn = null;
        try {
            countConn = DatabaseConnectionManager.getConnection(profile);
            rowCount = QueryExecutor.getRowCount(countConn, profile);
        } catch (Exception e) {
            logger.warn("Error counting rows for {}: {}", projectName, e.getMessage());
        } finally {
            DatabaseConnectionManager.closeConnection(countConn);
        }

        final long totalRows = rowCount;
        final int effectiveMaxRows = maxRows > 0 ? maxRows : profile.getMaxRows();

        Connection conn = null;
        ImportingJob job = null;
        Project project = new Project();
        ProjectMetadata metadata = new ProjectMetadata();
        metadata.setName(projectName);
        metadata.setEncoding("UTF-8");

        List<Exception> exceptions = new ArrayList<Exception>();
        long projectId = -1L;

        try {
            conn = DatabaseConnectionManager.getConnection(profile);
            job = ImportingManager.createJob();
            job.setState("creating-project");
            job.updating = true;

            ObjectNode options = ParsingUtilities.mapper.createObjectNode();
            JSONUtilities.safePut(options, "ignoreLines", -1);
            JSONUtilities.safePut(options, "headerLines", 1);
            JSONUtilities.safePut(options, "storeBlankRows", true);
            JSONUtilities.safePut(options, "storeBlankCellsAsNulls", true);

            RecordsDBImportReader reader = new RecordsDBImportReader(
                    conn, profile, job, DEFAULT_BATCH_SIZE, effectiveMaxRows);

            TabularImportingParserBase.readTable(
                    project,
                    metadata,
                    job,
                    reader,
                    profile.getMainTable(),
                    effectiveMaxRows,
                    options,
                    exceptions);

            if (!job.canceled && exceptions.isEmpty()) {
                project.update();

                // Store the schema profile in the project's overlay models
                // This allows the export feature to access the original file mapping configuration
                RecordsDBOverlayModel overlayModel = RecordsDBOverlayModel.fromSchemaProfile(profile);
                project.overlayModels.put(RecordsDBOverlayModel.OVERLAY_MODEL_KEY, overlayModel);
                if (logger.isDebugEnabled()) {
                    logger.debug("Stored RecordsDBOverlayModel in project with fileMapping: {}",
                            profile.getFileMapping());
                }

                ProjectManager.singleton.registerProject(project, metadata);
                job.setState("created-project");
                job.setProjectID(project.id);
                projectId = project.id;
                if (logger.isInfoEnabled()) {
                    logger.info("Created project {} (id={}) with {} rows (totalRows={})",
                            projectName, project.id, project.rows.size(), totalRows);
                }
            } else {
                job.setState("error");
                for (Exception ex : exceptions) {
                    logger.error("Error while creating project " + projectName, ex);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to create project " + projectName, e);
        } finally {
            if (job != null) {
                job.touch();
                job.updating = false;
            }
            DatabaseConnectionManager.closeConnection(conn);
        }

        ObjectNode result = ParsingUtilities.mapper.createObjectNode();
        if (projectId > 0 && exceptions.isEmpty()) {
            JSONUtilities.safePut(result, "status", "ok");
            JSONUtilities.safePut(result, "projectId", projectId);
            JSONUtilities.safePut(result, "projectName", projectName);
            JSONUtilities.safePut(result, "totalRows", totalRows);
            JSONUtilities.safePut(result, "importedRows", project.rows.size());
        } else {
            JSONUtilities.safePut(result, "status", "error");
            JSONUtilities.safePut(result, "message", "Failed to create project");
        }
        return result;
    }
}

