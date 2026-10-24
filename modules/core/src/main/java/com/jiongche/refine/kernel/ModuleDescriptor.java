/*
 * JinRefine 容器改造 · 模块描述符
 *
 * 解析 MOD-INF/module.properties 的框架无关视图；保留 OR 既有字段语义：
 * name / implements / extends / requires / before / after / module-impl / refine.spi /
 * templating.macros。Butterfly 删除后由自研内核消费。
 */

package com.jiongche.refine.kernel;

import java.io.File;
import java.util.Collections;
import java.util.List;

public final class ModuleDescriptor {

    private final String name;
    private final File path;
    private final String extendedName;
    private final List<String> implementations;
    private final List<String> requires;
    private final List<String> before;
    private final List<String> after;
    private final String moduleImpl;
    private final String spiClass;
    private final String macros;

    ModuleDescriptor(String name, File path, String extendedName,
            List<String> implementations, List<String> requires,
            List<String> before, List<String> after,
            String moduleImpl, String spiClass, String macros) {
        this.name = name;
        this.path = path;
        this.extendedName = extendedName;
        this.implementations = immutable(implementations);
        this.requires = immutable(requires);
        this.before = immutable(before);
        this.after = immutable(after);
        this.moduleImpl = moduleImpl;
        this.spiClass = spiClass;
        this.macros = macros;
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? Collections.emptyList() : Collections.unmodifiableList(values);
    }

    public String getName() {
        return name;
    }

    public File getPath() {
        return path;
    }

    public String getExtendedName() {
        return extendedName;
    }

    public List<String> getImplementations() {
        return implementations;
    }

    public List<String> getRequires() {
        return requires;
    }

    public List<String> getBefore() {
        return before;
    }

    public List<String> getAfter() {
        return after;
    }

    public String getModuleImpl() {
        return moduleImpl;
    }

    public String getSpiClass() {
        return spiClass;
    }

    public String getMacros() {
        return macros;
    }

    public boolean isCore() {
        return implementations.contains("core") || "core".equals(name);
    }

    public String getMountPoint() {
        return isCore() ? "/" : "/extension/" + name + "/";
    }
}
