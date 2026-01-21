/*
 * Records Database Module Implementation
 */

package com.google.refine.extension.records.db;

import javax.servlet.ServletConfig;

import edu.mit.simile.butterfly.ButterflyModuleImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.records.db.model.RecordsDBOverlayModel;
import com.google.refine.importing.ImportingManager;
import com.google.refine.model.Project;

public class RecordsDBModuleImpl extends ButterflyModuleImpl {

    private static final Logger logger = LoggerFactory.getLogger("RecordsDBModuleImpl");

    public static RecordsDBModuleImpl instance;

    @Override
    public void init(ServletConfig config) throws Exception {
        super.init(config);

        // Set the singleton instance
        instance = this;

        // Register the overlay model for storing schema profile in projects
        Project.registerOverlayModel(
                RecordsDBOverlayModel.OVERLAY_MODEL_KEY,
                RecordsDBOverlayModel.class);

        // Register the importing controller on the server side to avoid
        // class loading issues when Rhino initializes the module.
        ImportingManager.registerController(
                this,
                "records-db-import-controller",
                new RecordsDatabaseImportController());

        logger.info("Records Database Extension module initialization completed");
    }
}

