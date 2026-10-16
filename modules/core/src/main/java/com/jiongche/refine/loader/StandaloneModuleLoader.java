/*
 * JinRefine 容器改造 · 自研装载器（开关值 solon）
 *
 * 职责：判定归属 + 为每个接管模块提供独立 URLClassLoader + 创建模块对象。
 * 不负责装配（MountPoint/implements/extends 展开由 Butterfly 的
 * wireModules/configureModules 照常完成），也不实例化 SPI
 * （统一收口在 SpiBootstrap，避免双重注册导致 bundle 膨胀）。
 */

package com.jiongche.refine.loader;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.jiongche.refine.spi.SpiBootstrap;
import edu.mit.simile.butterfly.ButterflyModule;
import edu.mit.simile.butterfly.ButterflyModuleImpl;
import org.apache.commons.collections.ExtendedProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.RefineServlet;

/**
 * 自研装载器。归属判定 = 「描述符优先 + ServiceLoader 回退」：
 * <ul>
 * <li>模块 {@code MOD-INF/module.properties} 声明了 {@code refine.spi} → 接管；</li>
 * <li>否则其 {@code MOD-INF/classes} 或 {@code MOD-INF/lib/*.jar} 内含
 * {@code META-INF/services/com.jiongche.refine.spi.RefineModuleSpi} → 接管；</li>
 * <li>否则不接管，留给 Butterfly（混合模式）。</li>
 * </ul>
 * 归属判定复用 Butterfly 已完成的发现结果（{@code servlet.moduleProperties(name)}
 * 在 {@code createModule} 之前已由 {@code findModulesIn} 填充，且已应用 ignore 过滤），
 * 因此无需自行复刻发现逻辑。
 */
public final class StandaloneModuleLoader implements RefineModuleLoader {

    public static final StandaloneModuleLoader INSTANCE = new StandaloneModuleLoader();

    private static final Logger logger = LoggerFactory.getLogger("refine");

    private static final String SERVICE_FILE =
            "META-INF/services/com.jiongche.refine.spi.RefineModuleSpi";

    private RefineServlet _servlet;
    private final Map<String, Entry> _entries = new LinkedHashMap<>();

    private StandaloneModuleLoader() {
    }

    private static final class Entry {
        final String name;
        final ButterflyModuleImpl module;
        final URLClassLoader classLoader;

        Entry(String name, ButterflyModuleImpl module, URLClassLoader classLoader) {
            this.name = name;
            this.module = module;
            this.classLoader = classLoader;
        }
    }

    @Override
    public String id() {
        return "solon";
    }

    @Override
    public void preConfigure(RefineServlet servlet) {
        this._servlet = servlet;
        logRoots(servlet);
    }

    @Override
    public void afterConfigure(RefineServlet servlet) {
        List<String> claimed = new ArrayList<>();
        for (String name : servlet.moduleMap().keySet()) {
            if (owns(name)) {
                claimed.add(name);
            }
        }
        logger.info("StandaloneModuleLoader: claimed={}", claimed);
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
        File modulePath = new File(path);
        String spi = SpiBootstrap.readModuleProperty(modulePath, SpiBootstrap.SPI_PROPERTY);
        if (spi != null && !spi.isBlank()) {
            return true;
        }
        return hasServiceDescriptor(modulePath);
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
        URLClassLoader cl = buildClassLoader(path);

        ButterflyModuleImpl module = new ButterflyModuleImpl();

        String implClass = props.getString("module-impl");
        if (implClass != null && !implClass.equals(module.getClass().getName())) {
            try {
                module = (ButterflyModuleImpl) cl.loadClass(implClass).getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                logger.error("StandaloneModuleLoader: error loading special module manager for {}", moduleName, e);
            }
        }

        // 复刻 Butterfly.createModule 的字段装配顺序（不含共享 CL 的 addRepository，
        // 用独立 cl 取代），随后仍放回 _modulesByName，供 wireModules/configureModules 装配。
        module.setName(moduleName);
        module.setPath(path);
        module.setModules(servlet.moduleMap());
        module.setMounter(servlet.mounter());
        module.setClassLoader(cl);
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

        _entries.put(moduleName, new Entry(moduleName, module, cl));
        logger.info("StandaloneModuleLoader: module={} cl={}", moduleName, cl);
        logger.debug("StandaloneModuleLoader: module={} urls={}", moduleName, Arrays.toString(cl.getURLs()));
        return module;
    }

    @Override
    public boolean handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        // 保留的请求路径接缝：本 POC 恒 false（资源仍由 super.service() 经 _mounter 分发）
        return false;
    }

    @Override
    public void unload(RefineServlet servlet) {
        int closed = 0;
        for (Entry entry : _entries.values()) {
            try {
                entry.classLoader.close();
                closed++;
            } catch (IOException e) {
                logger.warn("StandaloneModuleLoader: error closing classloader for {}", entry.name, e);
            }
        }
        _entries.clear();
        // 模块对象自身的销毁由 Butterfly.destroy() 统一完成（它遍历 _modulesByName 逐个调 module.destroy()），
        // 此处只关闭本装载器持有的 per-module URLClassLoader，避免与 Butterfly 重复销毁同一模块。
        logger.info("StandaloneModuleLoader: unloaded {} modules (classloaders closed)", closed);
    }

    // ---- helpers ----

    private URLClassLoader buildClassLoader(File modulePath) {
        List<URL> urls = new ArrayList<>();
        addUrl(urls, new File(modulePath, "MOD-INF/classes"));
        File libDir = new File(modulePath, "MOD-INF/lib");
        if (libDir.isDirectory()) {
            File[] jars = libDir.listFiles((d, n) -> n.endsWith(".jar"));
            if (jars != null) {
                Arrays.sort(jars);
                for (File jar : jars) {
                    addUrl(urls, jar);
                }
            }
        }
        // parent = core 类加载器（parent-first，保证 core 的 SPI 接口对扩展可见）
        return new URLClassLoader(urls.toArray(new URL[0]), RefineServlet.class.getClassLoader());
    }

    private static void addUrl(List<URL> urls, File file) {
        if (file.exists()) {
            try {
                urls.add(file.toURI().toURL());
            } catch (Exception e) {
                logger.warn("StandaloneModuleLoader: bad url for {}", file, e);
            }
        }
    }

    private boolean hasServiceDescriptor(File modulePath) {
        File classesService = new File(modulePath, "MOD-INF/classes/" + SERVICE_FILE);
        if (classesService.exists()) {
            return true;
        }
        File libDir = new File(modulePath, "MOD-INF/lib");
        File[] jars = libDir.isDirectory() ? libDir.listFiles((d, n) -> n.endsWith(".jar")) : null;
        if (jars != null) {
            for (File jar : jars) {
                try (JarFile jarFile = new JarFile(jar)) {
                    JarEntry entry = jarFile.getJarEntry(SERVICE_FILE);
                    if (entry != null) {
                        return true;
                    }
                } catch (IOException e) {
                    logger.warn("StandaloneModuleLoader: cannot inspect {}", jar, e);
                }
            }
        }
        return false;
    }

    private void logRoots(RefineServlet servlet) {
        List<String> roots = new ArrayList<>();
        List<?> fromProps = servlet.butterflyPropertyList("butterfly.modules.path");
        if (fromProps != null) {
            for (Object o : fromProps) {
                roots.add(String.valueOf(o));
            }
        }
        String fromInit = servlet.getInitParameter("butterfly.modules.path");
        if (fromInit != null) {
            for (String part : fromInit.split(",")) {
                if (!part.isBlank()) {
                    roots.add(part.trim());
                }
            }
        }
        logger.debug("StandaloneModuleLoader: roots={}", roots);
    }
}
