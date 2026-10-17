/*
 * JinRefine 容器改造 · 模块依赖图
 *
 * 支持 extends / requires / before / after 四类边，Kahn 拓扑排序；
 * 同层保持发现顺序（确定性），有环时 fail-fast 并打印剩余节点。
 */

package com.jiongche.refine.kernel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModuleGraph {

    private static final Logger logger = LoggerFactory.getLogger("refine-kernel");

    private ModuleGraph() {
    }

    public static List<String> order(List<ModuleDescriptor> descriptors) {
        Map<String, ModuleDescriptor> byName = new LinkedHashMap<>();
        for (ModuleDescriptor descriptor : descriptors) {
            byName.put(descriptor.getName(), descriptor);
        }

        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, Set<String>> outgoing = new LinkedHashMap<>();
        for (String name : byName.keySet()) {
            indegree.put(name, 0);
            outgoing.put(name, new LinkedHashSet<>());
        }

        for (ModuleDescriptor descriptor : descriptors) {
            String name = descriptor.getName();
            if (descriptor.getExtendedName() != null) {
                addEdge(descriptor.getExtendedName(), name, byName, indegree, outgoing);
            }
            for (String required : descriptor.getRequires()) {
                addEdge(required, name, byName, indegree, outgoing);
            }
            for (String before : descriptor.getBefore()) {
                addEdge(name, before, byName, indegree, outgoing);
            }
            for (String after : descriptor.getAfter()) {
                addEdge(after, name, byName, indegree, outgoing);
            }
        }

        Deque<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }

        List<String> ordered = new ArrayList<>(byName.size());
        while (!ready.isEmpty()) {
            String name = ready.removeFirst();
            ordered.add(name);
            for (String dependent : outgoing.get(name)) {
                int remaining = indegree.get(dependent) - 1;
                indegree.put(dependent, remaining);
                if (remaining == 0) {
                    ready.addLast(dependent);
                }
            }
        }

        if (ordered.size() != byName.size()) {
            List<String> cycle = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
                if (entry.getValue() > 0) {
                    cycle.add(entry.getKey());
                }
            }
            throw new IllegalStateException("Module dependency cycle detected among " + cycle);
        }
        return ordered;
    }

    private static void addEdge(String from, String to,
            Map<String, ModuleDescriptor> byName,
            Map<String, Integer> indegree,
            Map<String, Set<String>> outgoing) {
        if (from == null || to == null || from.equals(to)) {
            return;
        }
        if (!byName.containsKey(from)) {
            logger.warn("Module {} depends on unknown module {}; edge ignored", to, from);
            return;
        }
        if (outgoing.get(from).add(to)) {
            indegree.put(to, indegree.get(to) + 1);
        }
    }
}
