/*
 * Records Assets Module Implementation
 */

package com.google.refine.extension.records.assets;

import javax.servlet.ServletConfig;

import edu.mit.simile.butterfly.ButterflyModuleImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Module implementation for Records Assets extension
 */
public class RecordsAssetsModuleImpl extends ButterflyModuleImpl {

    private static final Logger logger = LoggerFactory.getLogger("RecordsAssetsModuleImpl");

    public static RecordsAssetsModuleImpl instance;

    @Override
    public void init(ServletConfig config) throws Exception {
        super.init(config);
        instance = this;
        logger.trace("Records Assets Extension module initialization completed");
    }
}

