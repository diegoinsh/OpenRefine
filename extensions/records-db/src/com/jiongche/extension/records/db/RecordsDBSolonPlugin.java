/*
 * JinRefine 插件形态 · Records-DB 扩展的 Solon 插件入口
 */

package com.jiongche.extension.records.db;

import com.jiongche.refine.spi.SolonPluginBridge;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.Plugin;

public class RecordsDBSolonPlugin implements Plugin {

    @Override
    public void start(AppContext context) {
        SolonPluginBridge.registerCurrentModule();
    }
}
