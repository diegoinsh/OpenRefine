/*
 * JinRefine 容器改造 · 模块装配共享工具（Phase 3 插件装载形态）
 *
 * 把「ButterflyModule 对象装配」段抽出，供 solon（自研）与 solon-plugin（插件形态）
 * 两个装载器共用：复刻 Butterfly.createModule 的字段装配顺序（以独立 CL 取代共享 CL 的
 * addRepository），并处理 extends 链，最后把模块对象放回 _modulesByName，
 * 供随后 Butterfly 的 wireModules / configureModules 装配。
 */

package com.jiongche.refine.loader;

import java.io.File;

import edu.mit.simile.butterfly.ButterflyModule;
import edu.mit.simile.butterfly.ButterflyModuleImpl;
import org.apache.commons.collections.ExtendedProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.RefineServlet;

final class ModuleAssembly {

    private static final Logger logger = LoggerFactory.getLogger("refine");

    private ModuleAssembly() {
    }

    static ButterflyModuleImpl assemble(RefineServlet servlet, String moduleName, File path,
            ClassLoader classLoader, ExtendedProperties props) {
        ButterflyModuleImpl module = new ButterflyModuleImpl();

        String implClass = props.getString("module-impl");
        if (implClass != null && !implClass.equals(module.getClass().getName())) {
            try {
                module = (ButterflyModuleImpl) classLoader.loadClass(implClass).getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                logger.error("ModuleAssembly: error loading special module manager for {}", moduleName, e);
            }
        }

        module.setName(moduleName);
        module.setPath(path);
        module.setModules(servlet.moduleMap());
        module.setMounter(servlet.mounter());
        module.setClassLoader(classLoader);
        module.setTimer(servlet.timer());
        servlet.registerModule(moduleName, module);

        String extendedName = props.getString("extends");
        if (extendedName != null) {
            ButterflyModule extended;
            if (servlet.moduleProperties(extendedName) == null) {
                throw new RuntimeException("Cannot wire module '" + moduleName + "' because the extended module '"
                        + extendedName + "' is not defined.");
            }
            extended = servlet.getModule(extendedName);
            if (extended == null) {
                extended = servlet.createModuleByName(extendedName);
            }
            if (extended != null) {
                module.setExtended(extended);
                extended.addExtendedBy(module);
            }
        }
        return module;
    }
}
