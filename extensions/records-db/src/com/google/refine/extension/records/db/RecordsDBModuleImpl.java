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

        // Set the singleton instance
        instance = this;

        // 注册逻辑已收敛到框架无关的 Java SPI：
        // com.jiongche.extension.records.db.RecordsDBExtensionSpi
        // （super.init() 会触发 controller.js 的 init() → SpiBootstrap.register）。

        logger.info("Records Database Extension module initialization completed");
    }
}

