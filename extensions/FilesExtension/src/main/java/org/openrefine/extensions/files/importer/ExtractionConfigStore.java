package org.openrefine.extensions.files.importer;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.refine.io.FileProjectManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 数据抽取能力的系统级配置。
 *
 * 与「合规审查」域分开存储：落在 workspace/extraction-config.json，不与
 * data-quality-config.json 互相覆盖——两类任务的配置彼此独立（抽取任务与
 * 合规检查任务的输入/产物完全不同）。
 */
public final class ExtractionConfigStore {

    private static final Logger logger = LoggerFactory.getLogger(ExtractionConfigStore.class);
    private static final String CONFIG_FILE_NAME = "extraction-config.json";
    private static final ObjectMapper mapper = new ObjectMapper();

    private ExtractionConfigStore() {
    }

    public static Map<String, Object> defaults() {
        Map<String, Object> config = new HashMap<>();
        config.put("aimp.server", "http://127.0.0.1:7998");
        config.put("aimp.timeout", 30);
        config.put("extraction.pageConcurrency", 4);
        config.put("extraction.defaultCategory", "");
        config.put("extraction.defaultSubCategory", "");
        config.put("extraction.disableCache", false);
        config.put("ui.refreshInterval", 5);
        config.put("ui.pageSize", 50);
        return config;
    }

    /** 读取配置并与默认值合并：旧配置文件缺项时用默认值补齐 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> load() {
        Map<String, Object> merged = defaults();
        try {
            File configFile = configFile();
            if (configFile.exists()) {
                Map<String, Object> stored = mapper.readValue(configFile, Map.class);
                if (stored != null) {
                    merged.putAll(stored);
                }
            }
        } catch (Exception e) {
            logger.warn("读取抽取配置失败，使用默认值", e);
        }
        return merged;
    }

    /** 取字符串配置项；缺失或空串返回 null */
    public static String getString(String key) {
        Object v = load().get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    /** 取整数配置项；缺失或非法时返回 fallback */
    public static int getInt(String key, int fallback) {
        Object v = load().get(key);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    public static void save(Map<String, Object> config) throws java.io.IOException {
        mapper.writerWithDefaultPrettyPrinter().writeValue(configFile(), config);
        logger.debug("抽取配置已保存到 {}", configFile().getAbsolutePath());
    }

    private static File configFile() {
        File workspaceDir = ((FileProjectManager) FileProjectManager.singleton).getWorkspaceDir();
        return new File(workspaceDir, CONFIG_FILE_NAME);
    }
}
