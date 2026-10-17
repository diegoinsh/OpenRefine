/*
 * JinRefine 容器改造 · 模块注册表
 *
 * 统一维护 name → RefineModule、挂载前缀索引与依赖图顺序；
 * wirings / 静态资源 / 模块请求分发都以此表为单一事实来源。
 */

package com.jiongche.refine.kernel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ModuleRegistry {

    private final Map<String, RefineModule> modules = new LinkedHashMap<>();
    private final List<RefineModule> byMountLength = new ArrayList<>();
    private volatile List<String> order = Collections.emptyList();

    public void register(RefineModule module) {
        modules.put(module.getName(), module);
        byMountLength.clear();
        byMountLength.addAll(modules.values());
        byMountLength.sort(Comparator.comparingInt((RefineModule m) -> length(m.getMountPoint())).reversed());
    }

    public void computeOrder() {
        List<ModuleDescriptor> descriptors = new ArrayList<>();
        for (RefineModule module : modules.values()) {
            descriptors.add(module.descriptor());
        }
        order = Collections.unmodifiableList(ModuleGraph.order(descriptors));
    }

    public List<String> getOrder() {
        return order;
    }

    public RefineModule get(String name) {
        return modules.get(name);
    }

    public Collection<RefineModule> all() {
        return Collections.unmodifiableCollection(modules.values());
    }

    public boolean isEmpty() {
        return modules.isEmpty();
    }

    /** 按最长挂载前缀匹配；core 的 "/" 作为最终兜底。 */
    public RefineModule findByPath(String path) {
        for (RefineModule module : byMountLength) {
            String mount = module.getMountPoint();
            if (mount != null && path.startsWith(mount)) {
                return module;
            }
        }
        return null;
    }

    public String relativePath(String path, RefineModule module) {
        String mount = module.getMountPoint();
        if (mount == null || mount.isEmpty()) {
            return path;
        }
        String relative = path.startsWith(mount) ? path.substring(mount.length()) : path;
        if ("/".equals(mount) && relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        return relative;
    }

    /** 按依赖图顺序输出 name → mount；供 wirings.js 使用。 */
    public Map<String, String> mounts() {
        Map<String, String> result = new LinkedHashMap<>();
        List<String> names = order.isEmpty() ? new ArrayList<>(modules.keySet()) : order;
        for (String name : names) {
            RefineModule module = modules.get(name);
            if (module != null && module.getMountPoint() != null) {
                result.put(name, module.getMountPoint());
            }
        }
        return result;
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }
}
