/*
 * JinRefine 容器改造 · 自研模块内核
 *
 * 完整替代 Butterfly.configure/wireModules/configureModules：
 *   发现（MOD-INF/module.properties）→ 描述符 → 稳定拓扑排序 → 每模块 PluginClassLoader
 *   → module-impl 实例化 → extends 装配 → init（controller.js）→ Solon 插件生命周期
 *   → 构建请求分发器。
 */

package com.jiongche.refine.kernel;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;

import com.jiongche.refine.spi.SolonPluginBridge;
import com.jiongche.refine.web.ModuleRequestDispatcher;
import org.noear.solon.core.PluginEntity;
import org.noear.solon.core.util.PluginUtil;
import org.noear.solon.hotplug.PluginClassLoader;
import org.noear.solon.hotplug.PluginPackage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.RefineServlet;

public final class ModuleKernel {

    private static final Logger logger = LoggerFactory.getLogger("refine-kernel");

    private static final String DESCRIPTOR_DIR = "META-INF/solon";
    private static final String MODULE_PROPERTIES = "MOD-INF/module.properties";

    private final RefineServlet servlet;
    private final ModuleRegistry registry = new ModuleRegistry();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<String> order = new ArrayList<>();
    private ModuleRequestDispatcher dispatcher;

    public ModuleKernel(RefineServlet servlet) {
        this.servlet = servlet;
    }

    public void configure(ServletConfig config) throws ServletException {
        File webapp = new File(config.getServletContext().getRealPath("/"));
        Map<String, List<String>> global = ModuleDescriptorReader.readProperties(
                new File(webapp, "WEB-INF/butterfly.properties"));

        List<ModuleDescriptor> descriptors = discover(webapp, global, config);
        List<String> ordered = ModuleGraph.order(descriptors);
        order.clear();
        order.addAll(ordered);

        for (String name : ordered) {
            ModuleDescriptor descriptor = find(descriptors, name);
            createModule(descriptor);
        }

        for (String name : ordered) {
            Entry entry = entries.get(name);
            String extendedName = entry.module.descriptor().getExtendedName();
            if (extendedName != null) {
                Entry extended = entries.get(extendedName);
                if (extended == null) {
                    throw new ServletException("Cannot wire module '" + name
                            + "' because the extended module '" + extendedName + "' is not defined.");
                }
                entry.module.setExtended(extended.module);
            }
        }

        for (String name : ordered) {
            initializeModule(name, config);
        }

        dispatcher = new ModuleRequestDispatcher(registry);
        logger.info("Module loader: solon-kernel");
        logger.info("Module kernel: registry={} order={}",
                registry.all().stream().map(RefineModule::getName).collect(java.util.stream.Collectors.toList()), ordered);
    }

    public RefineModule getModule(String name) {
        return registry.get(name);
    }

    public String[] getModuleNames() {
        return registry.all().stream().map(RefineModule::getName).sorted().toArray(String[]::new);
    }

    public boolean handle(javax.servlet.http.HttpServletRequest request,
            javax.servlet.http.HttpServletResponse response) throws java.io.IOException {
        return dispatcher != null && dispatcher.handle(request, response);
    }

    public ModuleRegistry getRegistry() {
        return registry;
    }

    public void destroy() {
        List<String> reverse = new ArrayList<>(order);
        Collections.reverse(reverse);
        for (String name : reverse) {
            Entry entry = entries.get(name);
            if (entry == null) {
                continue;
            }
            try {
                if (entry.pluginPackage != null && entry.pluginPackage.getStarted()) {
                    entry.pluginPackage.preStop();
                    entry.pluginPackage.stop();
                }
            } catch (Throwable t) {
                logger.warn("Plugin stop failed for {}", name, t);
            }
            try {
                entry.module.destroy();
            } catch (Throwable t) {
                logger.warn("Module destroy failed for {}", name, t);
            }
            try {
                entry.classLoader.close();
            } catch (Throwable t) {
                logger.warn("Classloader close failed for {}", name, t);
            }
        }
        entries.clear();
        order.clear();
        SolonPluginBridge.clearOwned();
        logger.info("Module kernel destroyed");
    }

    // ---- discovery ----

    private List<ModuleDescriptor> discover(File webapp, Map<String, List<String>> global, ServletConfig config) {
        Set<File> roots = new LinkedHashSet<>();
        addRoots(roots, webapp, global.get("butterfly.modules.path"));
        String fromInit = config.getInitParameter("butterfly.modules.path");
        if (fromInit != null) {
            for (String part : fromInit.split(",")) {
                if (!part.isBlank()) {
                    roots.add(absolutize(webapp, part.trim()));
                }
            }
        }

        String ignore = firstValue(global, "butterfly.modules.ignore");
        String ignorePattern = (ignore == null || ignore.isBlank()) ? null : ignore.split(",")[0].trim();

        Map<String, ModuleDescriptor> byName = new LinkedHashMap<>();
        for (File root : roots) {
            if (root.isDirectory()) {
                discoverIn(root, byName, ignorePattern);
            }
        }
        return new ArrayList<>(byName.values());
    }

    private void addRoots(Set<File> roots, File webapp, List<String> paths) {
        if (paths == null) {
            return;
        }
        for (String path : paths) {
            for (String part : path.split(",")) {
                if (!part.isBlank()) {
                    roots.add(absolutize(webapp, part.trim()));
                }
            }
        }
    }

    private void discoverIn(File dir, Map<String, ModuleDescriptor> byName, String ignorePattern) {
        File modInf = new File(dir, "MOD-INF");
        if (modInf.isDirectory()) {
            ModuleDescriptor descriptor = ModuleDescriptorReader.read(dir);
            if (ignorePattern != null && descriptor.getName().matches(ignorePattern)) {
                logger.info("Module kernel: ignore {} at {}", descriptor.getName(), dir);
                return;
            }
            byName.put(descriptor.getName(), descriptor);
            return;
        }
        File[] children = dir.listFiles(File::isDirectory);
        if (children == null) {
            return;
        }
        Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File child : children) {
            discoverIn(child, byName, ignorePattern);
        }
    }

    private static File absolutize(File webapp, String path) {
        File file = new File(path);
        if (file.isAbsolute() || path.contains(":")) {
            return file;
        }
        return new File(webapp, path);
    }

    private static ModuleDescriptor find(List<ModuleDescriptor> descriptors, String name) {
        for (ModuleDescriptor descriptor : descriptors) {
            if (descriptor.getName().equals(name)) {
                return descriptor;
            }
        }
        throw new IllegalStateException("Missing descriptor for module " + name);
    }

    private static String firstValue(Map<String, List<String>> props, String key) {
        List<String> values = props.get(key);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    // ---- assembly ----

    private void createModule(ModuleDescriptor descriptor) throws ServletException {
        PluginClassLoader classLoader = buildClassLoader(descriptor.getPath());
        List<PluginEntity> plugins = scanPlugins(classLoader);

        AbstractRefineModule module = instantiate(descriptor, classLoader);
        module.setName(descriptor.getName());
        module.setPath(descriptor.getPath());
        module.setDescriptor(descriptor);
        module.setClassLoader(classLoader);
        module.setRegistry(registry);
        module.setMountPoint(descriptor.getMountPoint());

        PluginPackage pluginPackage = new PluginPackage(descriptor.getPath(), classLoader, plugins);
        if (!plugins.isEmpty()) {
            SolonPluginBridge.markOwned(descriptor.getName());
        }
        entries.put(descriptor.getName(), new Entry(module, classLoader, pluginPackage, !plugins.isEmpty()));
        registry.register(module);
        logger.info("Module kernel: module={} path={} plugins={}", descriptor.getName(),
                descriptor.getPath(), plugins.size());
    }

    private AbstractRefineModule instantiate(ModuleDescriptor descriptor, ClassLoader classLoader)
            throws ServletException {
        String impl = descriptor.getModuleImpl();
        if (impl != null && !impl.isBlank()) {
            try {
                Class<?> klass = Class.forName(impl, true, classLoader);
                Object instance = klass.getDeclaredConstructor().newInstance();
                if (!(instance instanceof AbstractRefineModule)) {
                    throw new ServletException("module-impl " + impl + " does not extend AbstractRefineModule");
                }
                return (AbstractRefineModule) instance;
            } catch (ClassNotFoundException e) {
                logger.warn("module-impl {} not found for {}; using default module", impl, descriptor.getName());
            } catch (Exception e) {
                throw new ServletException("Cannot instantiate module-impl " + impl, e);
            }
        }
        return new AbstractRefineModule();
    }

    private void initializeModule(String name, ServletConfig config) throws ServletException {
        Entry entry = entries.get(name);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(entry.classLoader);
        try {
            entry.module.init(config);
            if (entry.hasPlugins) {
                SolonPluginBridge.bindCurrent(entry.module);
                try {
                    entry.pluginPackage.start();
                } finally {
                    SolonPluginBridge.clearCurrent();
                }
            }
        } catch (Throwable t) {
            throw new ServletException("Cannot initialize module " + name, t);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private PluginClassLoader buildClassLoader(File modulePath) {
        PluginClassLoader classLoader = new PluginClassLoader(RefineServlet.class.getClassLoader());
        File classes = new File(modulePath, "MOD-INF/classes");
        if (classes.exists()) {
            classLoader.addJar(classes);
        }
        File libDir = new File(modulePath, "MOD-INF/lib");
        File[] jars = libDir.isDirectory() ? libDir.listFiles((d, n) -> n.endsWith(".jar")) : null;
        if (jars != null) {
            Arrays.sort(jars);
            for (File jar : jars) {
                classLoader.addJar(jar);
            }
        }
        return classLoader;
    }

    private List<PluginEntity> scanPlugins(PluginClassLoader classLoader) {
        List<PluginEntity> plugins = new ArrayList<>();
        try {
            PluginUtil.scanPlugins(classLoader, Collections.emptyList(), plugins::add);
        } catch (Throwable t) {
            logger.error("Module kernel: scanPlugins failed", t);
        }
        return plugins;
    }

    private boolean hasSolonDescriptor(File modulePath) {
        File classesDescriptor = new File(modulePath, "MOD-INF/classes/" + DESCRIPTOR_DIR);
        if (classesDescriptor.isDirectory()) {
            File[] files = classesDescriptor.listFiles((d, n) -> n.endsWith(".properties"));
            if (files != null && files.length > 0) {
                return true;
            }
        }
        File libDir = new File(modulePath, "MOD-INF/lib");
        File[] jars = libDir.isDirectory() ? libDir.listFiles((d, n) -> n.endsWith(".jar")) : null;
        if (jars != null) {
            for (File jar : jars) {
                try (JarFile jarFile = new JarFile(jar)) {
                    Enumeration<JarEntry> entries = jarFile.entries();
                    while (entries.hasMoreElements()) {
                        String entry = entries.nextElement().getName();
                        if (entry.startsWith(DESCRIPTOR_DIR + "/") && entry.endsWith(".properties")) {
                            return true;
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Module kernel: cannot inspect {}", jar, e);
                }
            }
        }
        return false;
    }

    private static final class Entry {
        final AbstractRefineModule module;
        final PluginClassLoader classLoader;
        final PluginPackage pluginPackage;
        final boolean hasPlugins;

        Entry(AbstractRefineModule module, PluginClassLoader classLoader, PluginPackage pluginPackage,
                boolean hasPlugins) {
            this.module = module;
            this.classLoader = classLoader;
            this.pluginPackage = pluginPackage;
            this.hasPlugins = hasPlugins;
        }
    }
}
