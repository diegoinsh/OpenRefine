/*
 * JinRefine 容器改造 · 插件形态装载器（开关值 solon-plugin）· Phase 3
 *
 * 以 Solon 插件契约（org.noear.solon.core.Plugin）作为业务插件的「发现 + 生命周期」契约，
 * 用 Solon hotplug 组件（PluginClassLoader / PluginPackage / PluginUtil）提供 per-plugin
 * 类加载器隔离；OR 的模块体系与前端契约（/command、wirings.js、-bundle.js、.vt、i18n）
 * 不动 —— 仍由 Butterfly 装配，本装载器只在 createModule / initialize 两处接管。
 *
 * 归属判定 = 模块目录内存在 Solon 插件描述符 META-INF/solon/*.properties
 * （位于 MOD-INF/classes 或 MOD-INF/lib/*.jar 内）。缺描述符者不接管，留给上层装载器，
 * 从而在混合场景下与 butterfly / solon 并存。
 *
 * 生命周期对齐：
 * - createModule：建 per-plugin PluginClassLoader（parent = core CL）→ 复用 ModuleAssembly
 *   装配 OR 模块对象 → 扫描 PluginEntity → 组 PluginPackage（此时不 start）。
 * - afterModuleInitialize：TCCL 已置为该插件 CL 时绑定 OR 模块 → PluginPackage.start()
 *   → 各 Plugin.start(ctx)（插件侧调用 SolonPluginBridge.registerCurrentModule() 注册 SPI）。
 * - unload：preStop + stop + 关 CL。
 */

package com.jiongche.refine.loader;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.jiongche.refine.spi.SolonPluginBridge;
import edu.mit.simile.butterfly.ButterflyModule;
import edu.mit.simile.butterfly.ButterflyModuleImpl;
import org.apache.commons.collections.ExtendedProperties;
import org.noear.solon.core.PluginEntity;
import org.noear.solon.core.util.PluginUtil;
import org.noear.solon.hotplug.PluginClassLoader;
import org.noear.solon.hotplug.PluginPackage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.RefineServlet;

public final class SolonPluginModuleLoader implements RefineModuleLoader {

    public static final SolonPluginModuleLoader INSTANCE = new SolonPluginModuleLoader();

    private static final Logger logger = LoggerFactory.getLogger("refine");

    /** Solon 插件描述符所在目录/前缀。 */
    private static final String DESCRIPTOR_DIR = "META-INF/solon";

    private RefineServlet _servlet;
    private final Map<String, Entry> _entries = new LinkedHashMap<>();

    private SolonPluginModuleLoader() {
    }

    private static final class Entry {
        final String name;
        final ButterflyModuleImpl module;
        final PluginClassLoader classLoader;
        final PluginPackage pluginPackage;

        Entry(String name, ButterflyModuleImpl module, PluginClassLoader classLoader, PluginPackage pluginPackage) {
            this.name = name;
            this.module = module;
            this.classLoader = classLoader;
            this.pluginPackage = pluginPackage;
        }
    }

    @Override
    public String id() {
        return "solon-plugin";
    }

    @Override
    public void preConfigure(RefineServlet servlet) {
        this._servlet = servlet;
    }

    @Override
    public void afterConfigure(RefineServlet servlet) {
        List<String> claimed = new ArrayList<>();
        for (String name : servlet.moduleMap().keySet()) {
            if (owns(name)) {
                claimed.add(name);
            }
        }
        logger.info("SolonPluginModuleLoader: claimed={}", claimed);
    }

    @Override
    public boolean owns(String moduleName) {
        if (_servlet == null) {
            return false;
        }
        ExtendedProperties props = _servlet.moduleProperties(moduleName);
        if (props == null) {
            return false;
        }
        String path = props.getString("__path__");
        if (path == null) {
            return false;
        }
        return hasSolonDescriptor(new File(path));
    }

    @Override
    public ClassLoader classLoaderFor(String moduleName) {
        Entry entry = _entries.get(moduleName);
        return entry == null ? null : entry.classLoader;
    }

    @Override
    public ButterflyModule createModule(RefineServlet servlet, String moduleName) {
        ExtendedProperties props = servlet.moduleProperties(moduleName);
        File path = new File(props.getString("__path__"));

        PluginClassLoader cl = buildPluginClassLoader(path);
        List<PluginEntity> plugins = scanPlugins(cl);

        ButterflyModuleImpl module = ModuleAssembly.assemble(servlet, moduleName, path, cl, props);

        PluginPackage pkg = new PluginPackage(path, cl, plugins);
        _entries.put(moduleName, new Entry(moduleName, module, cl, pkg));
        // 登记：该模块的 SPI 注册改由插件驱动（JS 侧那次调用让位）
        SolonPluginBridge.markOwned(moduleName);
        logger.info("SolonPluginModuleLoader: module={} cl={} plugins={}", moduleName, cl, plugins.size());
        logger.debug("SolonPluginModuleLoader: module={} urls={}", moduleName, Arrays.toString(cl.getURLs()));
        return module;
    }

    @Override
    public void afterModuleInitialize(RefineServlet servlet, String moduleName) {
        Entry entry = _entries.get(moduleName);
        if (entry == null || entry.pluginPackage == null) {
            return;
        }
        SolonPluginBridge.bindCurrent(entry.module);
        try {
            entry.pluginPackage.start();
        } catch (Throwable t) {
            logger.error("SolonPluginModuleLoader: plugin start failed for {}", moduleName, t);
            throw t;
        } finally {
            SolonPluginBridge.clearCurrent();
        }
    }

    @Override
    public boolean handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        // 保留的请求路径接缝：本装载器恒 false（资源仍由 super.service() 经 _mounter 分发）
        return false;
    }

    @Override
    public void unload(RefineServlet servlet) {
        int closed = 0;
        for (Entry entry : _entries.values()) {
            try {
                if (entry.pluginPackage.getStarted()) {
                    entry.pluginPackage.preStop();
                    entry.pluginPackage.stop();
                }
                entry.classLoader.close();
                closed++;
            } catch (Throwable t) {
                logger.warn("SolonPluginModuleLoader: error unloading {}", entry.name, t);
            }
        }
        _entries.clear();
        SolonPluginBridge.clearOwned();
        // 模块对象自身的销毁由 Butterfly.destroy() 统一完成，此处只驱动插件生命周期并关 CL。
        logger.info("SolonPluginModuleLoader: unloaded {} plugins (classloaders closed)", closed);
    }

    // ---- helpers ----

    /** per-plugin 类加载器：parent = core CL（parent-first，保证 core 的 SPI/桥对插件可见）。 */
    private PluginClassLoader buildPluginClassLoader(File modulePath) {
        PluginClassLoader cl = new PluginClassLoader(RefineServlet.class.getClassLoader());
        File classes = new File(modulePath, "MOD-INF/classes");
        if (classes.exists()) {
            cl.addJar(classes);
        }
        File libDir = new File(modulePath, "MOD-INF/lib");
        File[] jars = libDir.isDirectory() ? libDir.listFiles((d, n) -> n.endsWith(".jar")) : null;
        if (jars != null) {
            Arrays.sort(jars);
            for (File jar : jars) {
                cl.addJar(jar);
            }
        }
        return cl;
    }

    /** 扫描 Solon 插件描述符（META-INF/solon/*.properties）得到插件实体。 */
    private List<PluginEntity> scanPlugins(PluginClassLoader cl) {
        List<PluginEntity> plugins = new ArrayList<>();
        try {
            PluginUtil.scanPlugins(cl, Collections.emptyList(), plugins::add);
        } catch (Throwable t) {
            logger.error("SolonPluginModuleLoader: scanPlugins failed", t);
        }
        return plugins;
    }

    private boolean hasSolonDescriptor(File modulePath) {
        File classesDescriptor = new File(modulePath, "MOD-INF/classes/" + DESCRIPTOR_DIR);
        if (classesDescriptor.isDirectory() && hasProperties(classesDescriptor)) {
            return true;
        }
        File libDir = new File(modulePath, "MOD-INF/lib");
        File[] jars = libDir.isDirectory() ? libDir.listFiles((d, n) -> n.endsWith(".jar")) : null;
        if (jars != null) {
            for (File jar : jars) {
                if (jarContainsDescriptor(jar)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasProperties(File dir) {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".properties"));
        return files != null && files.length > 0;
    }

    private boolean jarContainsDescriptor(File jar) {
        try (JarFile jarFile = new JarFile(jar)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith(DESCRIPTOR_DIR + "/") && name.endsWith(".properties")) {
                    return true;
                }
            }
        } catch (IOException e) {
            logger.warn("SolonPluginModuleLoader: cannot inspect {}", jar, e);
        }
        return false;
    }
}
