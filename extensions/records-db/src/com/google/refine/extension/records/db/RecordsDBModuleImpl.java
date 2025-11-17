/*
 * Records Database Module Implementation
 */

package com.google.refine.extension.records.db;

import javax.servlet.ServletConfig;

import edu.mit.simile.butterfly.ButterflyModuleImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.importing.ImportingManager;

public class RecordsDBModuleImpl extends ButterflyModuleImpl {

    private static final Logger logger = LoggerFactory.getLogger("RecordsDBModuleImpl");

    public static RecordsDBModuleImpl instance;

    @Override
    public void init(ServletConfig config) throws Exception {
        super.init(config);

        // Set the singleton instance
        instance = this;

        // Register the importing controller on the server side to avoid
        // class loading issues when Rhino initializes the module.
        ImportingManager.registerController(
                this,
                "records-db-import-controller",
                new RecordsDatabaseImportController());

        logger.trace("Records Database Extension module initialization completed");
    }
}

