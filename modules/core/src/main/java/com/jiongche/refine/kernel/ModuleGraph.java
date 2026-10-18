/*
 * JinRefine 容器改造 · 模块依赖图
 *
 * 支持 extends / requires / before / after 四类边，Kahn 拓扑排序；
 * 同层保持发现顺序（确定性），有环时 fail-fast 并打印剩余节点。
 */

package com.jiongche.refine.kernel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
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

        // 同层优先级沿用 Butterfly 的模块初始化顺序：Butterfly 以 HashMap<String,...>
        // 存放模块并按 keySet() 迭代初始化，故这里用同样的 HashMap 复刻该顺序，
        // 再以它为 Kahn 的稳定 tie-breaker（仍保证 requires/extends 先于依赖方）。
        Map<String, Boolean> hashOrder = new HashMap<>();
        for (String name : byName.keySet()) {
            hashOrder.put(name, Boolean.TRUE);
        }
        Map<String, Integer> discoveryIndex = new LinkedHashMap<>();
        int index = 0;
        for (String name : hashOrder.keySet()) {
            discoveryIndex.put(name, index++);
        }

        PriorityQueue<String> ready = new PriorityQueue<>((a, b) -> Integer.compare(discoveryIndex.get(a), discoveryIndex.get(b)));
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }

        List<String> ordered = new ArrayList<>(byName.size());
        while (!ready.isEmpty()) {
            String name = ready.poll();
            ordered.add(name);
            for (String dependent : outgoing.get(name)) {
                int remaining = indegree.get(dependent) - 1;
                indegree.put(dependent, remaining);
                if (remaining == 0) {
                    ready.add(dependent);
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
