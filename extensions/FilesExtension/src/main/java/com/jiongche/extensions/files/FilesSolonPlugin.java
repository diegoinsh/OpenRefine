/*
 * JinRefine 插件形态 · FilesExtension 的 Solon 插件入口
 *
 * 本类是 Solon 插件契约（org.noear.solon.core.Plugin）的实现，由描述符
 * META-INF/solon/files.properties 声明（solon.plugin=本类 FQCN）。
 *
 * 与 controller.js 的关系：controller.js 里那行 SpiBootstrap.register(module, null)
 * 在「插件形态」装载器接管本模块时会让位（见 SolonPluginBridge.isDelegated），
 * 注册改由本类的 start() 驱动 —— 单一注册路径，且 butterfly / solon 模式行为不变。
 */

package com.jiongche.extensions.files;

import com.jiongche.refine.spi.SolonPluginBridge;

import org.noear.solon.core.AppContext;
import org.noear.solon.core.Plugin;

public class FilesSolonPlugin implements Plugin {

    @Override
    public void start(AppContext context) {
        // 插件加载器已在当前线程上下文绑定本模块（TCCL = 本插件的 PluginClassLoader），
        // 故此处注册与 controller.js 的那行完全等价。
        SolonPluginBridge.registerCurrentModule();
    }
}
