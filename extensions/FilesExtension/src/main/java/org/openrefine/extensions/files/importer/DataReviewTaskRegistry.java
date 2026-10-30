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
 * 数据校审任务登记表（持久化）。
 *
 * <p>校审任务的运行态只在 {@link DataReviewCommand} 的内存表里（进程重启即丢），
 * 控制台要「列出历史任务」就必须落盘。这里把「项目 ↔ 校审任务」的概要写到
 * workspace/data-review-tasks.json，键为项目 id——一次校审任务对应一个项目。</p>
 *
 * <p>与抽取任务登记表分开存放：两个控制台各看各的任务，互不污染。</p>
 */
public final class DataReviewTaskRegistry {

    private static final Logger logger = LoggerFactory.getLogger(DataReviewTaskRegistry.class);
    private static final String FILE_NAME = "data-review-tasks.json";
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Object LOCK = new Object();

    private DataReviewTaskRegistry() {
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

    /** 新增或整体覆盖某个项目的任务概要 */
    public static void upsert(long projectId, Map<String, Object> fields) {
        synchronized (LOCK) {
            Map<String, Object> data = read();
            data.put(String.valueOf(projectId), new LinkedHashMap<>(fields));
            write(data);
        }
    }

    /** 合并式更新（保留原有键） */
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

    /** 删除某个项目的任务登记；登记项不存在则忽略 */
    public static void remove(long projectId) {
        synchronized (LOCK) {
            Map<String, Object> data = read();
            if (data.remove(String.valueOf(projectId)) != null) {
                write(data);
            }
        }
    }

    /** 任务进入终态时更新状态/消息与统计；登记项不存在则忽略 */
    public static void updateStatus(long projectId, String status, String message,
                                    int processedFiles, int passed, int failed, int unknown) {
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
            rec.put("processedFiles", processedFiles);
            rec.put("passed", passed);
            rec.put("failed", failed);
            rec.put("unknown", unknown);
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
            logger.warn("校审任务登记表读取失败，按空表处理", e);
            return new LinkedHashMap<>();
        }
    }

    private static void write(Map<String, Object> data) {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(file(), data);
        } catch (Exception e) {
            logger.warn("校审任务登记表写入失败", e);
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
