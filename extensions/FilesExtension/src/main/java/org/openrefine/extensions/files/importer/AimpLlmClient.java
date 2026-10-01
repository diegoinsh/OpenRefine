package org.openrefine.extensions.files.importer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AimpLlmClient {

    private static final Logger logger = LoggerFactory.getLogger(AimpLlmClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int CONNECT_TIMEOUT = 30000;
    private static final int READ_TIMEOUT = 120000;
    /** 异步任务状态查询响应较短，单独设置较小的读取超时，避免网络抖动时长时间阻塞 */
    private static final int STATUS_READ_TIMEOUT = 15000;
    /** 健康检查握手请求很小，用较短超时，避免模块未启动时界面长时间干等 */
    private static final int HEALTH_CONNECT_TIMEOUT = 5000;
    private static final int HEALTH_READ_TIMEOUT = 5000;
    /**
     * 与 AIMP 约定的接口契约版本集合：AIMP `/health` 报告的 api_version 不在此集合内即视为
     * 版本不配套，直接给出明确提示而不是让后续调用出各种怪错误。
     * 接口的请求／响应结构发生不兼容变化时两端同步递增（AIMP 侧 src/__init__.py 的
     * __api_version__），并更新 docs/release-compat.md。
     */
    private static final Set<Integer> SUPPORTED_API_VERSIONS = Set.of(2);
    private final String serviceUrl;

    public AimpLlmClient(String serviceUrl) {
        this.serviceUrl = serviceUrl != null ? serviceUrl.replaceAll("/+$", "") : "http://127.0.0.1:7998";
    }

    /** 服务可达性与版本配套性的探测结果 */
    public static class CompatibilityResult {
        /** /health 是否返回 200 */
        public boolean reachable;
        /** AIMP 报告的接口版本是否在本扩展支持范围内 */
        public boolean compatible;
        /** AIMP 报告的接口版本；字段缺失（旧版模块）时为 null */
        public Integer apiVersion;
        /** AIMP 的应用版本与提交，用于提示里指明对端究竟是哪个构建 */
        public String appVersion;
        public String gitSha;
        /** 不可达时的原因（HTTP 状态码或异常信息） */
        public String error;

        /** 本扩展要求的接口版本，形如 "2" */
        public String expectedApiVersions() {
            StringBuilder sb = new StringBuilder();
            for (Integer version : SUPPORTED_API_VERSIONS) {
                if (sb.length() > 0) sb.append('/');
                sb.append(version);
            }
            return sb.toString();
        }

        /** 对端版本的展示串，形如「接口 v2，模块 2.0.0+b4946c2」 */
        public String actualVersion() {
            if (apiVersion == null) return "未知（可能为旧版模块）";
            StringBuilder sb = new StringBuilder("接口 v").append(apiVersion);
            if (appVersion != null && !appVersion.isEmpty()) sb.append("，模块 ").append(appVersion);
            if (gitSha != null && !gitSha.isEmpty() && !"unknown".equals(gitSha)) {
                sb.append('+').append(gitSha);
            }
            return sb.toString();
        }
    }

    /**
     * 握手：GET /health，既判断模块是否可用，也判断**接口版本是否配套**。
     *
     * 版本不配套时的表现往往是一连串莫名其妙的运行时错误（少字段、行为不一致），
     * 故在调用业务接口前先把这件事问清楚，由调用方给出明确提示。
     */
    public CompatibilityResult checkCompatibility() {
        CompatibilityResult result = new CompatibilityResult();
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(serviceUrl + "/health").openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(HEALTH_CONNECT_TIMEOUT);
            c.setReadTimeout(HEALTH_READ_TIMEOUT);
            int code = c.getResponseCode();
            if (code != 200) {
                result.error = "HTTP " + code;
                c.disconnect();
                return result;
            }
            JsonNode json = mapper.readTree(readStream(c.getInputStream()));
            c.disconnect();
            result.reachable = true;
            if (json.hasNonNull("api_version")) result.apiVersion = json.get("api_version").asInt();
            if (json.hasNonNull("version")) result.appVersion = json.get("version").asText();
            if (json.hasNonNull("git_sha")) result.gitSha = json.get("git_sha").asText();
            result.compatible = result.apiVersion != null && SUPPORTED_API_VERSIONS.contains(result.apiVersion);
            return result;
        } catch (Exception e) {
            result.error = e.getMessage() == null ? e.toString() : e.getMessage();
            return result;
        }
    }

    public boolean testConnection() {
        return checkCompatibility().reachable;
    }

    private boolean disableCache;

    public void setDisableCache(boolean disableCache) {
        this.disableCache = disableCache;
    }

    /** 档案门类，随 options.archive_category 传给 AIMP，用于选择该门类的提取规则提示词 */
    private String archiveCategory;

    public void setArchiveCategory(String archiveCategory) {
        this.archiveCategory = archiveCategory;
    }

    /** 专业档案的二级细分类别，随 options.archive_sub_category 传给 AIMP，优先于门类规则 */
    private String archiveSubCategory;

    public void setArchiveSubCategory(String archiveSubCategory) {
        this.archiveSubCategory = archiveSubCategory;
    }

    public Map<String, String> extractContent(String filePath, String keyList) {
        Map<String, String> result = new HashMap<>();
        ExtractPageResult r = extractPage(filePath, keyList, null);
        if (r.success) result.putAll(r.values);
        return result;
    }

    public ExtractPageResult extractPage(String filePath, String keyList, String customElementsJson) {
        return extractPage(filePath, keyList, customElementsJson, null, null);
    }

    public ExtractPageResult extractPage(String filePath, String keyList, String customElementsJson,
                                         Integer currentPage, Integer totalPages) {
        return extractPage(filePath, keyList, customElementsJson, currentPage, totalPages, null);
    }

    /**
     * 提取单页信息。previousExtractionsJson 为卷/件内前面页面的已提取要素
     * （JSON 数组，元素形如 {"page_index":1,"elements":{"title":{"value":"..."}}}），
     * 作为 options.previous_extractions 回传给 AIMP 供提示词参考。
     */
    public ExtractPageResult extractPage(String filePath, String keyList, String customElementsJson,
                                         Integer currentPage, Integer totalPages, String previousExtractionsJson) {
        return extractPage(filePath, keyList, customElementsJson, currentPage, totalPages,
                previousExtractionsJson, false);
    }

    /**
     * 提取单页信息，并可用 {@code forceExtract} 覆盖服务端的判页结论。
     *
     * <p>层3 定向回溯补抽走的就是这条路：件级完整性校验发现某件缺要素时，对它当初被判跳过的页
     * 回抽一次；带上 {@code force_extract=true}，服务端才不会再按分类器把它判成可跳页
     * （否则规划阶段判「必抽」、抽取阶段又被判「可跳」，要素照样丢）。
     */
    public ExtractPageResult extractPage(String filePath, String keyList, String customElementsJson,
                                         Integer currentPage, Integer totalPages,
                                         String previousExtractionsJson, boolean forceExtract) {
        return extractUpload(filePath, keyList, customElementsJson, currentPage, totalPages,
                previousExtractionsJson, true, forceExtract);
    }

    /**
     * 异步提交整份文档（PDF / 多页 TIFF / OFD）：AIMP 立即返回 task_id，
     * 调用方通过 {@link #getTaskStatus(String)} 轮询页级进度，完成后取回结果，
     * 避免同步等待在大页数文档上触发读取超时。
     */
    public ExtractPageResult submitAsync(String filePath, String keyList, String customElementsJson) {
        return extractUpload(filePath, keyList, customElementsJson, null, null, null, false, false);
    }

    private ExtractPageResult extractUpload(String filePath, String keyList, String customElementsJson,
                                            Integer currentPage, Integer totalPages, String previousExtractionsJson,
                                            boolean sync, boolean forceExtract) {
        ExtractPageResult result = new ExtractPageResult();
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                result.error = "File not found: " + filePath;
                return result;
            }
            String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
            byte[] fileBytes = Files.readAllBytes(file.toPath());
            String fileName = file.getName();

            StringBuilder bodyBuilder = new StringBuilder();
            bodyBuilder.append("--").append(boundary).append("\r\n");
            bodyBuilder.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(fileName).append("\"\r\n");
            bodyBuilder.append("Content-Type: application/octet-stream\r\n\r\n");

            byte[] bodyStart = bodyBuilder.toString().getBytes(StandardCharsets.UTF_8);

            StringBuilder parts = new StringBuilder();
            parts.append("\r\n--").append(boundary).append("\r\n");
            parts.append("Content-Disposition: form-data; name=\"key_list\"\r\n\r\n");
            parts.append(keyList);

            parts.append("\r\n--").append(boundary).append("\r\n");
            parts.append("Content-Disposition: form-data; name=\"sync\"\r\n\r\n");
            parts.append(sync ? "true" : "false");

            if (customElementsJson != null && !customElementsJson.isEmpty()) {
                parts.append("\r\n--").append(boundary).append("\r\n");
                parts.append("Content-Disposition: form-data; name=\"custom_elements\"\r\n\r\n");
                parts.append(customElementsJson);
            }

            boolean hasPrev = previousExtractionsJson != null && !previousExtractionsJson.isEmpty();
            boolean hasArchiveCategory = archiveCategory != null && !archiveCategory.isEmpty();
            boolean hasArchiveSubCategory = archiveSubCategory != null && !archiveSubCategory.isEmpty();
            if (currentPage != null || totalPages != null || disableCache || hasPrev
                    || hasArchiveCategory || hasArchiveSubCategory || forceExtract) {
                ObjectNode opts = mapper.createObjectNode();
                if (currentPage != null) opts.put("current_page", currentPage);
                if (totalPages != null) opts.put("total_pages", totalPages);
                if (disableCache) opts.put("disable_cache", true);
                if (forceExtract) opts.put("force_extract", true);
                if (hasArchiveCategory) opts.put("archive_category", archiveCategory);
                if (hasArchiveSubCategory) opts.put("archive_sub_category", archiveSubCategory);
                if (hasPrev) {
                    try {
                        opts.set("previous_extractions", mapper.readTree(previousExtractionsJson));
                    } catch (Exception e) {
                        logger.warn("previous_extractions 不是合法JSON，已忽略: " + e.getMessage());
                    }
                }
                parts.append("\r\n--").append(boundary).append("\r\n");
                parts.append("Content-Disposition: form-data; name=\"options\"\r\n\r\n");
                parts.append(opts.toString());
            }

            parts.append("\r\n--").append(boundary).append("--\r\n");
            byte[] bodyEnd = parts.toString().getBytes(StandardCharsets.UTF_8);

            HttpURLConnection c = (HttpURLConnection) new URL(serviceUrl + "/extract/upload").openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            c.setConnectTimeout(CONNECT_TIMEOUT);
            c.setReadTimeout(READ_TIMEOUT);
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) {
                os.write(bodyStart);
                os.write(fileBytes);
                os.write(bodyEnd);
            }
            if (c.getResponseCode() == 200) {
                JsonNode json = mapper.readTree(readStream(c.getInputStream()));
                JsonNode taskIdNode = json.get("task_id");
                if (taskIdNode != null && !taskIdNode.isNull()) result.taskId = taskIdNode.asText();
                if (json.has("results") && json.get("results").isObject()) {
                    json.get("results").fields().forEachRemaining(e ->
                            result.values.put(e.getKey(), e.getValue().asText()));
                }
                JsonNode dataNode = json.get("data");
                if (dataNode != null && dataNode.has("results") && dataNode.get("results").isObject()) {
                    dataNode.get("results").fields().forEachRemaining(e -> {
                        JsonNode info = e.getValue();
                        if (info != null && info.isObject() && info.has("confidence")) {
                            result.confidences.put(e.getKey(), info.get("confidence").asDouble(0.0));
                        }
                    });
                }
                if (result.values.isEmpty() && json.has("extracted_fields") && json.get("extracted_fields").isObject()) {
                    json.get("extracted_fields").fields().forEachRemaining(e ->
                            result.values.put(e.getKey(), e.getValue().asText()));
                }
                JsonNode pc = json.get("page_count");
                if (pc != null && pc.isNumber()) result.pageCount = pc.asInt(1);
                // 同步逐页路径（图片模式）同样要认「本页是否走了 LLM」与页型：
                // 前者供调用方从「平均每页耗时」的摊分里剔除短路页，后者供 pageMap 标注页型
                result.skipped = json.path("processing_mode").asText("").startsWith("single_page_skipped");
                result.pageType = json.path("document_status").path("page_type").asText("");
                result.success = true;
            } else {
                result.error = "HTTP " + c.getResponseCode();
                logger.warn("AIMP extract failed: HTTP " + c.getResponseCode());
            }
        } catch (Exception e) {
            logger.error("Error extracting: " + filePath, e);
            result.error = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        return result;
    }

    /** 查询异步任务状态（含页级进度）；网络抖动等瞬时错误通过 success=false 返回，由调用方决定是否重试 */
    public TaskStatusResult getTaskStatus(String taskId) {
        TaskStatusResult r = new TaskStatusResult();
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(serviceUrl + "/task/" + taskId).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(CONNECT_TIMEOUT);
            c.setReadTimeout(STATUS_READ_TIMEOUT);
            int code = c.getResponseCode();
            if (code == 200) {
                JsonNode json = mapper.readTree(readStream(c.getInputStream()));
                r.status = json.path("status").asText("");
                r.progress = json.path("progress").asDouble(0.0);
                r.processedPages = json.path("processed_pages").asInt(0);
                r.totalPages = json.path("total_pages").asInt(0);
                JsonNode errNode = json.get("error");
                if (errNode != null && !errNode.isNull()) r.error = errNode.asText();
                JsonNode resultNode = json.get("result");
                if (resultNode != null && !resultNode.isNull()) r.result = resultNode;
                r.success = true;
            } else {
                r.error = "HTTP " + code;
            }
            c.disconnect();
        } catch (Exception e) {
            logger.warn("Error querying AIMP task status: " + taskId, e);
            r.error = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        return r;
    }

    /** 把 /task/{taskId} 返回的 result 解析为提取结果（结构与同步响应中的 results 一致） */
    public ExtractPageResult parseTaskResult(JsonNode result) {
        ExtractPageResult r = new ExtractPageResult();
        if (result == null || !result.has("results") || !result.get("results").isObject()) {
            r.error = "任务结果为空";
            return r;
        }
        result.get("results").fields().forEachRemaining(e -> {
            JsonNode info = e.getValue();
            if (info != null && info.isObject()) {
                r.values.put(e.getKey(), info.path("value").asText(""));
                if (info.has("confidence") && !info.get("confidence").isNull()) {
                    r.confidences.put(e.getKey(), info.get("confidence").asDouble(0.0));
                }
            } else if (info != null) {
                r.values.put(e.getKey(), info.asText(""));
            }
        });
        parsePageExtractions(result, r);
        // 分类器判定跳过、OCR 文本过短的页：AIMP 在 processing_mode 里标出（single_page_skipped*），
        // 本页并未消耗推理。计入 skipped 供调用方从「平均每页耗时」的摊分里排除
        String mode = result.path("processing_mode").asText("");
        r.skipped = mode.startsWith("single_page_skipped");
        r.success = true;
        return r;
    }

    /**
     * 解析多页任务结果中的页级明细（page_extractions），
     * 每页形如 {"page_index":3,"elements":{"responsible_party":{"value":"...","confidence":0.9}}}，
     * 转成「要素 → 候选页列表」，供前端点击单元格时定位到取值所在页。
     */
    private void parsePageExtractions(JsonNode result, ExtractPageResult r) {
        JsonNode pages = result.get("page_extractions");
        if (pages == null || !pages.isArray()) return;
        for (JsonNode pageNode : pages) {
            int page = pageNode.path("page_index").asInt(0);
            JsonNode elements = pageNode.get("elements");
            if (page <= 0 || elements == null || !elements.isObject()) continue;
            elements.fields().forEachRemaining(e -> {
                JsonNode info = e.getValue();
                if (info == null || !info.isObject()) return;
                String value = info.path("value").asText("");
                if (value.trim().isEmpty()) return;
                Double confidence = info.has("confidence") && !info.get("confidence").isNull()
                        ? info.get("confidence").asDouble(0.0) : null;
                r.candidates.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                        .add(new ElementCandidate(value.trim(), confidence, page));
            });
        }
    }

    public LlmAnalyzeResult llmAnalyze(String prompt, String responseFormat) {
        LlmAnalyzeResult r = new LlmAnalyzeResult();
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(serviceUrl + "/api/llm/analyze").openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            c.setConnectTimeout(CONNECT_TIMEOUT);
            c.setReadTimeout(READ_TIMEOUT);
            c.setDoOutput(true);
            ObjectNode body = mapper.createObjectNode();
            body.put("prompt", prompt);
            body.put("response_format", responseFormat != null ? responseFormat : "json");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (c.getResponseCode() == 200) {
                JsonNode json = mapper.readTree(readStream(c.getInputStream()));
                r.success = json.path("success").asBoolean(false);
                if (json.has("result")) r.result = json.get("result");
                if (json.has("error")) r.error = json.get("error").asText();
            } else {
                r.success = false;
                r.error = "HTTP " + c.getResponseCode();
            }
        } catch (Exception e) {
            logger.error("Error calling LLM analyze", e);
            r.success = false;
            r.error = e.getMessage();
        }
        return r;
    }

    /**
     * 卷级二次分件：整卷页级提取完成后调用一次，由 AIMP 用一次 LLM 判断「每一件的起始页」，
     * 区间由服务端按"下一件的起始页 - 1"聚合。
     *
     * <p>页级提取只回答"这一页是什么"，回答不了"这几页是否属于同一件"——后者是卷级语义任务，
     * 字符串相似度无能为力。调用失败或服务端结构校验不过时，调用方回退 {@link TitleSplitter}。
     *
     * @param pageTitles 逐页题名，下标 0 为卷内第 1 页
     */
    public SplitResult splitVolumePieces(List<String> pageTitles, String archiveCategory,
                                         String archiveSubCategory) {
        return splitVolumePieces(pageTitles, null, null, archiveCategory, archiveSubCategory);
    }

    /**
     * 卷级二次分件（带当事人信息）。
     *
     * @param pageParties 逐页当事人（责任者），下标 0 为卷内第 1 页；身份证明类件靠它区分原/被告
     * @param partyRoles  卷宗封面页抽到的原、被告名单（JSON 字符串），整卷一份，与页序无关
     */
    public SplitResult splitVolumePieces(List<String> pageTitles, List<String> pageParties,
                                         String partyRoles, String archiveCategory,
                                         String archiveSubCategory) {
        SplitResult r = new SplitResult();
        try {
            ObjectNode body = mapper.createObjectNode();
            ArrayNode pages = body.putArray("pages");
            for (int i = 0; i < pageTitles.size(); i++) {
                ObjectNode p = pages.addObject();
                p.put("page", i + 1);
                p.put("title", pageTitles.get(i) == null ? "" : pageTitles.get(i));
                if (pageParties != null && i < pageParties.size() && pageParties.get(i) != null) {
                    p.put("party", pageParties.get(i));
                }
            }
            body.put("total_pages", pageTitles.size());
            if (partyRoles != null && !partyRoles.isEmpty()) body.put("party_roles", partyRoles);
            if (archiveCategory != null) body.put("archive_category", archiveCategory);
            if (archiveSubCategory != null) body.put("archive_sub_category", archiveSubCategory);

            HttpURLConnection c = (HttpURLConnection) new URL(serviceUrl + "/extract/split-pieces").openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            c.setConnectTimeout(CONNECT_TIMEOUT);
            c.setReadTimeout(READ_TIMEOUT);
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (c.getResponseCode() == 200) {
                JsonNode json = mapper.readTree(readStream(c.getInputStream()));
                r.success = json.path("success").asBoolean(false);
                r.reason = json.path("reason").asText("");
                JsonNode arr = json.path("pieces");
                if (arr.isArray()) {
                    for (JsonNode n : arr) {
                        TitleSplitter.Piece p = new TitleSplitter.Piece();
                        p.startPage = n.path("start").asInt(0);
                        p.endPage = n.path("end").asInt(0);
                        p.title = n.path("title").asText("");
                        r.pieces.add(p);
                    }
                }
                if (!r.success) r.pieces.clear();
            } else {
                r.success = false;
                r.reason = "HTTP " + c.getResponseCode();
            }
        } catch (Exception e) {
            logger.error("Error calling volume piece split", e);
            r.success = false;
            r.reason = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        return r;
    }

    /** 卷级分件结果：pieces 为按页序的件区间（服务端已做结构校验） */
    public static class SplitResult {
        public boolean success;
        public String reason = "";
        public List<TitleSplitter.Piece> pieces = new ArrayList<>();
    }

    /**
     * 层2 件级完整性校验：把分件边界与逐页状态交给服务端体检，取回可疑件与建议回抽的页。
     *
     * <p>服务端只做纯计算（不调模型、不读图片），失败或不可用时 success=false——调用方直接
     * 跳过层3 即可，不影响正常出结果。
     *
     * @param pieces         分件结果（件区间 + 件名）
     * @param pageSkipped    逐页「是否被判跳过 LLM」，下标 0 为卷内第 1 页
     * @param pageElements   逐页抽到的要素，下标对齐 pageSkipped；元素可为 null
     * @param requiredElements 必需要素 key 清单；null 或空则取服务端配置（默认仅题名必需）
     * @param maxRetryPages  每件最多回抽页数；null 取服务端配置
     */
    public PieceIntegrityResult pieceIntegrity(List<TitleSplitter.Piece> pieces,
                                              List<Boolean> pageSkipped,
                                              List<Map<String, String>> pageElements,
                                              List<String> requiredElements,
                                              Integer maxRetryPages) {
        PieceIntegrityResult r = new PieceIntegrityResult();
        try {
            ObjectNode body = mapper.createObjectNode();
            ArrayNode pieceArr = body.putArray("pieces");
            for (TitleSplitter.Piece p : pieces) {
                ObjectNode n = pieceArr.addObject();
                n.put("start", p.startPage);
                n.put("end", p.endPage);
                n.put("title", p.title == null ? "" : p.title);
            }
            ArrayNode pageArr = body.putArray("pages");
            int pageTotal = pageSkipped == null ? 0 : pageSkipped.size();
            for (int i = 0; i < pageTotal; i++) {
                ObjectNode n = pageArr.addObject();
                n.put("page", i + 1);
                n.put("skipped", Boolean.TRUE.equals(pageSkipped.get(i)));
                ObjectNode els = n.putObject("elements");
                Map<String, String> vals = pageElements != null && i < pageElements.size()
                        ? pageElements.get(i) : null;
                if (vals != null) {
                    for (Map.Entry<String, String> e : vals.entrySet()) {
                        if (e.getValue() != null && !e.getValue().trim().isEmpty()) {
                            els.put(e.getKey(), e.getValue());
                        }
                    }
                }
            }
            if (requiredElements != null && !requiredElements.isEmpty()) {
                ArrayNode req = body.putArray("required_elements");
                for (String k : requiredElements) req.add(k);
            }
            if (maxRetryPages != null) body.put("max_retry_pages", maxRetryPages);

            HttpURLConnection c = (HttpURLConnection) new URL(serviceUrl + "/extract/piece-integrity").openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            c.setConnectTimeout(CONNECT_TIMEOUT);
            c.setReadTimeout(READ_TIMEOUT);
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (c.getResponseCode() == 200) {
                JsonNode json = mapper.readTree(readStream(c.getInputStream()));
                r.success = json.path("success").asBoolean(false);
                r.reason = json.path("reason").asText("");
                JsonNode summary = json.path("summary");
                r.suspectPieces = summary.path("suspect_pieces").asInt(0);
                r.retryPageCount = summary.path("retry_pages").asInt(0);
                for (JsonNode n : json.path("dashboard")) {
                    PieceIntegrityItem item = new PieceIntegrityItem();
                    item.index = n.path("index").asInt(0);
                    item.start = n.path("start").asInt(0);
                    item.end = n.path("end").asInt(0);
                    item.title = n.path("title").asText("");
                    item.suspect = n.path("suspect").asBoolean(false);
                    for (JsonNode m : n.path("missing")) item.missing.add(m.asText(""));
                    for (JsonNode pg : n.path("retry_pages")) item.retryPages.add(pg.asInt(0));
                    r.dashboard.add(item);
                }
                if (!r.success) r.dashboard.clear();
            } else {
                r.success = false;
                r.reason = "HTTP " + c.getResponseCode();
            }
            c.disconnect();
        } catch (Exception e) {
            logger.warn("Error calling piece integrity", e);
            r.success = false;
            r.reason = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        return r;
    }

    /** 单件体检结论：缺哪些要素、建议回抽哪些页 */
    public static class PieceIntegrityItem {
        public int index;
        public int start;
        public int end;
        public String title = "";
        public List<String> missing = new ArrayList<>();
        public List<Integer> retryPages = new ArrayList<>();
        public boolean suspect;
    }

    /** 件级完整性校验结果：dashboard 逐件一条，统计值来自服务端 summary */
    public static class PieceIntegrityResult {
        public boolean success;
        public String reason = "";
        public List<PieceIntegrityItem> dashboard = new ArrayList<>();
        public int suspectPieces;
        public int retryPageCount;
    }

    private String readStream(InputStream s) throws IOException {
        if (s == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(s, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    public static class LlmAnalyzeResult {
        public boolean success;
        public JsonNode result;
        public String error;
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean s) { this.success = s; }
        public JsonNode getResult() { return result; }
        public void setResult(JsonNode r) { this.result = r; }
        public String getError() { return error; }
        public void setError(String e) { this.error = e; }
    }

    public static class ExtractPageResult {
        public boolean success;
        public int pageCount = 1;
        /**
         * 本页未走 LLM：分类器判定跳过，或 OCR 文本过短直接短路。
         * 这类页毫秒级返回，统计「平均每页耗时」时不能按它计数，否则连续跳过的批会把平均值
         * 拉得远小于真实推理速度，前端揭示节奏随之忽快忽慢。
         */
        public boolean skipped;
        /**
         * 本页页型（AIMP 六分类之一：header_page/content_page/signature_page/tail_page/
         * table_content_page/attachment_page）。分类器未启用或模型没给出时为空串。
         */
        public String pageType = "";
        /** 异步提交时 AIMP 返回的任务号 */
        public String taskId;
        public Map<String, String> values = new HashMap<>();
        public Map<String, Double> confidences = new LinkedHashMap<>();
        /** 页级候选：要素 key → 该要素在各页出现的取值与置信度（多页任务才有） */
        public Map<String, List<ElementCandidate>> candidates = new LinkedHashMap<>();
        public String error;
    }

    /** 某要素在某一页上的取值（page 为该文件/文件夹内的 1 起页序） */
    public static class ElementCandidate {
        public final String value;
        public final Double confidence;
        public final int page;
        /** 整目录成件的图片批次：该页对应的文件名，前端据此定位而不依赖目录列举顺序 */
        public final String fileName;

        public ElementCandidate(String value, Double confidence, int page) {
            this(value, confidence, page, null);
        }

        public ElementCandidate(String value, Double confidence, int page, String fileName) {
            this.value = value;
            this.confidence = confidence;
            this.page = page;
            this.fileName = fileName;
        }
    }

    public static class TaskStatusResult {
        public boolean success;
        public String status = "";
        public double progress;
        public int processedPages;
        public int totalPages;
        public String error;
        /** 任务完成时携带的结果（JSON） */
        public JsonNode result;
    }
}

