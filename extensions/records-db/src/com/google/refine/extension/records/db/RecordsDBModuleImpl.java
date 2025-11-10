/*
 * Records Database Module Implementation
 */

package com.google.refine.extension.records.db;

import javax.servlet.ServletConfig;

import edu.mit.simile.butterfly.ButterflyModuleImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RecordsDBModuleImpl extends ButterflyModuleImpl {

    private static final Logger logger = LoggerFactory.getLogger("RecordsDBModuleImpl");

    public static RecordsDBModuleImpl instance;

    @Override
    public void init(ServletConfig config) throws Exception {
        super.init(config);
        instance = this;
        logger.trace("Records Database Extension module initialization completed");
    }
}

