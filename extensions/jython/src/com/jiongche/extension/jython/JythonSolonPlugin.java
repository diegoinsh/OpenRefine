/*
 * JinRefine 插件形态 · Jython 扩展的 Solon 插件入口
 */

package com.jiongche.extension.jython;

import com.jiongche.refine.spi.SolonPluginBridge;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.Plugin;

public class JythonSolonPlugin implements Plugin {

    @Override
    public void start(AppContext context) {
        SolonPluginBridge.registerCurrentModule();
    }
}
