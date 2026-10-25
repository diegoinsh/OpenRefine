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
        // 文书档案页级并发：默认打开＝按 extraction.pageConcurrency 路并发、不再传页间上下文
        // （实测同 18 页 33s vs 逐页串行 70s，约 2.1×）。关闭则退回逐页串行 + 传前序页要素，
        // 保上下文但慢约一倍——要素只出现在非件首页的多页件是已知残余风险，待样本回归。
        config.put("extraction.documentPagesIndependent", true);
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

    /** 取布尔配置项；缺失或非法时返回 fallback */
    public static boolean getBoolean(String key, boolean fallback) {
        return parseBoolean(load().get(key), fallback);
    }

    /**
     * 布尔解析：JSON 里既可能是原生布尔（前端复选框写入），也可能是字符串（手改配置文件）。
     * 识别 true/1/yes/on 与 false/0/no/off（忽略大小写与首尾空白），其余一律返回 fallback。
     * 单独抽成包内可见的纯函数，便于不走文件读写的单元测试。
     */
    static boolean parseBoolean(Object value, boolean fallback) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value == null) {
            return fallback;
        }
        String s = String.valueOf(value).trim().toLowerCase();
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s) || "on".equals(s)) {
            return true;
        }
        if ("false".equals(s) || "0".equals(s) || "no".equals(s) || "off".equals(s)) {
            return false;
        }
        return fallback;
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
