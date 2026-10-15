package org.openrefine.extensions.files.importer;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.refine.io.FileProjectManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 抽取任务登记表（持久化）。
 *
 * 抽取任务的运行态只存在 {@link BatchExtractionManager} 的内存表里（进程重启即丢），
 * 控制台要「列出历史任务」就必须落盘。这里把「项目 ↔ 抽取任务」的概要写到
 * workspace/extraction-tasks.json，键为项目 id——一次抽取任务对应一个项目
 * （决策 D4：一任务一项目）。
 */
public final class ExtractionTaskRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ExtractionTaskRegistry.class);
    private static final String FILE_NAME = "extraction-tasks.json";
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Object LOCK = new Object();

    private ExtractionTaskRegistry() {
    }

    /** 读取某个项目的任务概要；不存在或表损坏时返回 null */
    public static Map<String, Object> get(long projectId) {
        synchronized (LOCK) {
            Object existing = read().get(String.valueOf(projectId));
            if (!(existing instanceof Map)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> rec = (Map<String, Object>) existing;
            return new LinkedHashMap<>(rec);
        }
    }

    /**
     * 记下「已完整跑完并落盘」的卷名（整卷卷内行 + 卷级行都已写入项目）。
     *
     * <p>进程被强杀（断电/杀进程）后内存任务丢失，重启再「启动」时无从判断哪些卷已经算完；
     * 这里逐卷落盘，续跑时据此跳过这些卷、直接沿用项目里已有的结果，不再重算。
     */
    public static void updateCompletedUnits(long projectId, List<String> completedUnits) {
        synchronized (LOCK) {
            Map<String, Object> data = read();
            Object existing = data.get(String.valueOf(projectId));
            if (!(existing instanceof Map)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> rec = new LinkedHashMap<>((Map<String, Object>) existing);
            rec.put("completedUnits", new ArrayList<>(completedUnits));
            data.put(String.valueOf(projectId), rec);
            write(data);
        }
    }

    /** 新增或整体覆盖某个项目的任务概要 */
    public static void upsert(long projectId, Map<String, Object> fields) {
        synchronized (LOCK) {
            Map<String, Object> data = read();
            data.put(String.valueOf(projectId), new LinkedHashMap<>(fields));
            write(data);
        }
    }

    /**
     * 合并式更新（保留原有键）：控制台「编辑」只改抽取参数，
     * 不应抹掉该任务上一次运行留下的状态/页数等概要。
     */
    public static void merge(long projectId, Map<String, Object> fields) {
        synchronized (LOCK) {
            Map<String, Object> data = read();
            Object existing = data.get(String.valueOf(projectId));
            Map<String, Object> rec = new LinkedHashMap<>();
            if (existing instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> old = (Map<String, Object>) existing;
                rec.putAll(old);
            }
            rec.putAll(fields);
            data.put(String.valueOf(projectId), rec);
            write(data);
        }
    }

    /**
     * 只更新运行态（状态 + 消息），不写 finishedAt：用于暂停/恢复这类「任务仍在进行」的转换。
     */
    public static void updateRunState(long projectId, String status, String message) {
        synchronized (LOCK) {
            Map<String, Object> data = read();
            Object existing = data.get(String.valueOf(projectId));
            if (!(existing instanceof Map)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> rec = new LinkedHashMap<>((Map<String, Object>) existing);
            rec.put("status", status);
            rec.put("message", message);
            data.put(String.valueOf(projectId), rec);
            write(data);
        }
    }

    /**
     * 只更新运行进度（已处理页数 / 总页数），不触碰其它键、也不写 finishedAt。
     * processedPages 只存在内存任务里，进程被强杀后即丢失；定期落盘后，中断态列表
     * 才能显示「前面已经跑完的页数」，而不是回落到 0。登记项不存在则忽略。
     */
    public static void updateProgress(long projectId, int processedPages, int totalPages) {
        synchronized (LOCK) {
            Map<String, Object> data = read();
            Object existing = data.get(String.valueOf(projectId));
            if (!(existing instanceof Map)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> rec = new LinkedHashMap<>((Map<String, Object>) existing);
            rec.put("processedPages", processedPages);
            if (totalPages > 0) {
                rec.put("totalPages", totalPages);
            }
            data.put(String.valueOf(projectId), rec);
            write(data);
        }
    }

    /** 任务进入终态时只更新状态/消息；登记项不存在则忽略 */
    public static void updateStatus(long projectId, String status, String message) {
        synchronized (LOCK) {
            Map<String, Object> data = read();
            Object existing = data.get(String.valueOf(projectId));
            if (!(existing instanceof Map)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> rec = new LinkedHashMap<>((Map<String, Object>) existing);
            rec.put("status", status);
            rec.put("message", message);
            rec.put("finishedAt", System.currentTimeMillis());
            data.put(String.valueOf(projectId), rec);
            write(data);
        }
    }

    /** 全部任务概要，按开始时间倒序（最近的在最前） */
    public static List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (LOCK) {
            for (Object v : read().values()) {
                if (v instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> rec = (Map<String, Object>) v;
                    out.add(rec);
                }
            }
        }
        out.sort((a, b) -> Long.compare(asLong(b.get("startedAt")), asLong(a.get("startedAt"))));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read() {
        File f = file();
        if (!f.exists()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> m = mapper.readValue(f, Map.class);
            return m == null ? new LinkedHashMap<>() : m;
        } catch (Exception e) {
            logger.warn("抽取任务登记表读取失败，按空表处理", e);
            return new LinkedHashMap<>();
        }
    }

    private static void write(Map<String, Object> data) {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(file(), data);
        } catch (Exception e) {
            logger.warn("抽取任务登记表写入失败", e);
        }
    }

    private static File file() {
        File workspaceDir = ((FileProjectManager) FileProjectManager.singleton).getWorkspaceDir();
        return new File(workspaceDir, FILE_NAME);
    }

    private static long asLong(Object o) {
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return 0L;
        }
    }
}
