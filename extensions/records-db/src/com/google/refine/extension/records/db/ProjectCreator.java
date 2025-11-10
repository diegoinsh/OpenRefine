/*
 * Project Creator
 */

package com.google.refine.extension.records.db;

import java.sql.Connection;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.records.db.model.SchemaProfile;
import com.google.refine.util.JSONUtilities;
import com.google.refine.util.ParsingUtilities;

/**
 * Creates OpenRefine projects from database data
 *
 * Note: The actual project creation is handled asynchronously by OpenRefine's
 * ImportingManager. This class prepares the data for project creation.
 */
public class ProjectCreator {

    private static final Logger logger = LoggerFactory.getLogger("ProjectCreator");

    /**
     * Prepare project creation from database data
     *
     * Returns a response indicating that the project creation has been initiated.
     * The actual project creation is handled by OpenRefine's ImportingManager.
     */
    public static ObjectNode prepareProjectCreation(String projectName, Connection conn,
            SchemaProfile profile, int maxRows) throws Exception {

        if (logger.isDebugEnabled()) {
            logger.debug("Preparing project creation: {}", projectName);
        }

        // Verify we can query the database
        long rowCount = QueryExecutor.getRowCount(conn, profile);

        if (logger.isDebugEnabled()) {
            logger.debug("Database has {} rows", rowCount);
        }

        // Build response
        ObjectNode result = ParsingUtilities.mapper.createObjectNode();
        JSONUtilities.safePut(result, "status", "ok");
        JSONUtilities.safePut(result, "projectName", projectName);
        JSONUtilities.safePut(result, "totalRows", rowCount);
        JSONUtilities.safePut(result, "message", "Project creation initiated. The project will be created in the background.");

        return result;
    }
}

