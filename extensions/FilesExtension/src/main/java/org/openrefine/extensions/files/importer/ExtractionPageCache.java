package org.openrefine.extensions.files.importer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.refine.io.FileProjectManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 页级识别结果的本地缓存 —— 抽取任务「断点续跑」的实现。
 *
 * 单页识别（AIMP /extract/upload）是整条抽取链路里最贵的一步。把「一页 → 页级识别结果」
 * 落到 workspace/extraction-cache/pages/ 下，再次运行时命中即跳过 AIMP 调用。于是
 * 「暂停后恢复」「停止后重启」「崩溃后重启」「控制台就地重跑」都天然变成「续跑」，
 * 而不需要维护一套单独的任务状态机。
 *
 * 一页的识别结果只取决于「本页内容 + 提取参数 + 页间上下文」，三者都纳入缓存键：
 * 无上下文的并发路径（诉讼档案）自然命中；带上下文的串行路径（文书档案等）把上下文一并
 * 入键——上下文本身由前序页的识别结果派生，所以重启后逐页命中即可自洽重建，等效断点续跑。
 * 上下文变化（前序页结果与上次不同）则 miss，不会复用在别的上下文里算出的结果。
 *
 * 缓存键 = 文件绝对路径 + 大小 + 修改时间 + 提取参数（要素键 / 自定义要素 / 门类 / 子类）
 *          + 页间上下文的 SHA 输入。
 * 文件被替换、参数被改动都会 miss，不会把旧结果张冠李戴。
 * 用户在「高级设置」里勾了默认禁用缓存（或单次任务传 disableCache=true）则读写都跳过，
 * 用于模型升级后强制全量重新识别。
 */
public final class ExtractionPageCache {

    private static final Logger logger = LoggerFactory.getLogger(ExtractionPageCache.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String CACHE_DIR = "extraction-cache";
    private static final String PAGES_DIR = "pages";

    private ExtractionPageCache() {
    }

    /**
     * 计算缓存键：把页间上下文一并纳入。上下文相同的页才会命中（重启后由前序页的缓存结果
     * 派生出与上次相同的上下文，因此串行路径也能逐页续跑）；上下文不同则 miss。
     * 环境不可用（定位不到 workspace）时返回 null。
     *
     * @param pageContext 页间上下文；空表示本页不依赖前序页的识别结果
     */
    public static String keyFor(String pagePath, String keyList, String customJson,
                                String archiveCategory, String archiveSubCategory, String pageContext) {
        File file = new File(pagePath);
        StringBuilder raw = new StringBuilder()
                .append(file.getAbsolutePath()).append('\u0001')
                .append(file.length()).append('\u0001')
                .append(file.lastModified()).append('\u0001')
                .append(nullToEmpty(keyList)).append('\u0001')
                .append(nullToEmpty(customJson)).append('\u0001')
                .append(nullToEmpty(archiveCategory)).append('\u0001')
                .append(nullToEmpty(archiveSubCategory)).append('\u0001')
                .append(nullToEmpty(pageContext));
        return sha256(raw.toString());
    }

    /** 读取缓存；未命中、文件损坏或环境不可用时返回 null（按未命中处理，绝不抛错打断抽取） */
    public static AimpLlmClient.ExtractPageResult load(String key) {
        File file = cacheFile(key);
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(file);
            AimpLlmClient.ExtractPageResult result = new AimpLlmClient.ExtractPageResult();
            result.success = node.path("success").asBoolean(true);
            result.skipped = node.path("skipped").asBoolean(false);
            result.pageCount = node.path("pageCount").asInt(1);
            result.pageType = node.path("pageType").asText("");
            if (node.hasNonNull("textLength")) {
                result.textLength = node.get("textLength").asInt();
            }
            result.evidenceHits = new ArrayList<>();
            for (JsonNode hit : node.withArray("evidenceHits")) {
                result.evidenceHits.add(hit.asText());
            }
            result.values = new LinkedHashMap<>();
            node.path("values").fields().forEachRemaining(e ->
                    result.values.put(e.getKey(), e.getValue().asText("")));
            result.confidences = new LinkedHashMap<>();
            node.path("confidences").fields().forEachRemaining(e ->
                    result.confidences.put(e.getKey(), e.getValue().asDouble()));
            result.candidates = new LinkedHashMap<>();
            node.path("candidates").fields().forEachRemaining(e -> {
                List<AimpLlmClient.ElementCandidate> list = new ArrayList<>();
                for (JsonNode c : e.getValue()) {
                    list.add(new AimpLlmClient.ElementCandidate(
                            c.path("value").asText(null),
                            c.hasNonNull("confidence") ? c.get("confidence").asDouble() : null,
                            c.path("page").asInt(),
                            c.hasNonNull("fileName") ? c.get("fileName").asText() : null));
                }
                result.candidates.put(e.getKey(), list);
            });
            return result;
        } catch (Exception e) {
            logger.warn("页级缓存读取失败，按未命中处理: {}", key, e);
            return null;
        }
    }

    /** 写入缓存（尽力而为，失败不影响抽取） */
    public static void save(String key, AimpLlmClient.ExtractPageResult result) {
        if (key == null || result == null || !result.success) {
            return;
        }
        File file = cacheFile(key);
        if (file == null) {
            return;
        }
        try {
            File dir = file.getParentFile();
            if (!dir.isDirectory() && !dir.mkdirs()) {
                logger.warn("页级缓存目录创建失败: {}", dir.getAbsolutePath());
                return;
            }
            File tmp = new File(dir, key + ".tmp");
            mapper.writeValue(tmp, toJson(result));
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            logger.warn("页级缓存写入失败: {}", key, e);
        }
    }

    private static ObjectNode toJson(AimpLlmClient.ExtractPageResult result) {
        ObjectNode node = mapper.createObjectNode();
        node.put("success", result.success);
        node.put("skipped", result.skipped);
        node.put("pageCount", result.pageCount);
        node.put("pageType", result.pageType == null ? "" : result.pageType);
        if (result.textLength != null) {
            node.put("textLength", result.textLength);
        }
        ArrayNode hits = node.putArray("evidenceHits");
        if (result.evidenceHits != null) {
            for (String hit : result.evidenceHits) {
                hits.add(hit);
            }
        }
        ObjectNode values = node.putObject("values");
        if (result.values != null) {
            result.values.forEach((k, v) -> values.put(k, v == null ? "" : v));
        }
        ObjectNode confidences = node.putObject("confidences");
        if (result.confidences != null) {
            result.confidences.forEach((k, v) -> confidences.put(k, v == null ? 0.0 : v));
        }
        ObjectNode candidates = node.putObject("candidates");
        if (result.candidates != null) {
            result.candidates.forEach((k, list) -> {
                ArrayNode arr = candidates.putArray(k);
                if (list == null) {
                    return;
                }
                for (AimpLlmClient.ElementCandidate c : list) {
                    if (c == null) {
                        continue;
                    }
                    ObjectNode item = arr.addObject();
                    item.put("value", c.value);
                    if (c.confidence != null) {
                        item.put("confidence", c.confidence);
                    }
                    item.put("page", c.page);
                    if (c.fileName != null) {
                        item.put("fileName", c.fileName);
                    }
                }
            });
        }
        return node;
    }

    private static File cacheFile(String key) {
        if (key == null) {
            return null;
        }
        try {
            File workspaceDir = ((FileProjectManager) FileProjectManager.singleton).getWorkspaceDir();
            if (workspaceDir == null) {
                return null;
            }
            return new File(new File(new File(workspaceDir, CACHE_DIR), PAGES_DIR), key + ".json");
        } catch (Exception e) {
            logger.warn("无法定位页级缓存目录，本次不缓存", e);
            return null;
        }
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
