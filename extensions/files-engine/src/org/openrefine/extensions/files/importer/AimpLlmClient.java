package org.openrefine.extensions.files.importer;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jiongche.sdk.aimp.AimpEndpoints;
import com.jiongche.sdk.aimp.AimpFields;
import com.jiongche.sdk.aimp.AimpTransport;
import com.jiongche.sdk.aimp.HttpAimpTransport;
import com.jiongche.sdk.aimp.Multipart;
import com.jiongche.sdk.authz.LicenseGate;
import com.jiongche.sdk.module.ModuleNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private final AimpTransport transport;

    public AimpLlmClient(String serviceUrl) {
        this(new HttpAimpTransport(serviceUrl != null && !serviceUrl.trim().isEmpty()
                ? serviceUrl : "http://127.0.0.1:7998"));
    }

    /** 注入传输实现（HTTP / 进程内）；调用方代码不感知传输形态。 */
    public AimpLlmClient(AimpTransport transport) {
        this.transport = transport;
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
            AimpTransport.Response response = transport.execute(
                    AimpTransport.Request.get(AimpEndpoints.HEALTH, HEALTH_CONNECT_TIMEOUT, HEALTH_READ_TIMEOUT));
            if (response.status() != 200) {
                result.error = "HTTP " + response.status();
                return result;
            }
            JsonNode json = mapper.readTree(response.text());
            result.reachable = true;
            if (json.hasNonNull(AimpFields.API_VERSION)) result.apiVersion = json.get(AimpFields.API_VERSION).asInt();
            if (json.hasNonNull(AimpFields.VERSION)) result.appVersion = json.get(AimpFields.VERSION).asText();
            if (json.hasNonNull(AimpFields.GIT_SHA)) result.gitSha = json.get(AimpFields.GIT_SHA).asText();
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
        // 进程内授权门：所有同步/异步抽取入口都汇聚到这里（直调引擎亦不可绕过）
        LicenseGate.fromSystemProperties().require(ModuleNames.INFO_EXTRACTION);

        ExtractPageResult result = new ExtractPageResult();
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                result.error = "File not found: " + filePath;
                return result;
            }
            byte[] fileBytes = Files.readAllBytes(file.toPath());
            String fileName = file.getName();

            Multipart multipart = Multipart.webKitBoundary()
                    .file(AimpFields.FILE, fileName, fileBytes)
                    .field(AimpFields.KEY_LIST, keyList)
                    .field(AimpFields.SYNC, sync ? "true" : "false");

            if (customElementsJson != null && !customElementsJson.isEmpty()) {
                multipart.field(AimpFields.CUSTOM_ELEMENTS, customElementsJson);
            }

            boolean hasPrev = previousExtractionsJson != null && !previousExtractionsJson.isEmpty();
            boolean hasArchiveCategory = archiveCategory != null && !archiveCategory.isEmpty();
            boolean hasArchiveSubCategory = archiveSubCategory != null && !archiveSubCategory.isEmpty();
            if (currentPage != null || totalPages != null || disableCache || hasPrev
                    || hasArchiveCategory || hasArchiveSubCategory || forceExtract) {
                ObjectNode opts = mapper.createObjectNode();
                if (currentPage != null) opts.put(AimpFields.CURRENT_PAGE, currentPage);
                if (totalPages != null) opts.put(AimpFields.TOTAL_PAGES, totalPages);
                if (disableCache) opts.put(AimpFields.DISABLE_CACHE, true);
                if (forceExtract) opts.put(AimpFields.FORCE_EXTRACT, true);
                if (hasArchiveCategory) opts.put(AimpFields.ARCHIVE_CATEGORY, archiveCategory);
                if (hasArchiveSubCategory) opts.put(AimpFields.ARCHIVE_SUB_CATEGORY, archiveSubCategory);
                if (hasPrev) {
                    try {
                        opts.set(AimpFields.PREVIOUS_EXTRACTIONS, mapper.readTree(previousExtractionsJson));
                    } catch (Exception e) {
                        logger.warn("previous_extractions 不是合法JSON，已忽略: " + e.getMessage());
                    }
                }
                multipart.field(AimpFields.OPTIONS, opts.toString());
            }

            AimpTransport.Response response = transport.execute(AimpTransport.Request.post(
                    AimpEndpoints.EXTRACT_UPLOAD, multipart.contentType(), multipart.bytes(),
                    CONNECT_TIMEOUT, READ_TIMEOUT));
            if (response.status() == 200) {
                JsonNode json = mapper.readTree(response.text());
                JsonNode taskIdNode = json.get(AimpFields.TASK_ID);
                if (taskIdNode != null && !taskIdNode.isNull()) result.taskId = taskIdNode.asText();
                if (json.has(AimpFields.RESULTS) && json.get(AimpFields.RESULTS).isObject()) {
                    json.get(AimpFields.RESULTS).fields().forEachRemaining(e ->
                            result.values.put(e.getKey(), e.getValue().asText()));
                }
                JsonNode dataNode = json.get(AimpFields.DATA);
                if (dataNode != null && dataNode.has(AimpFields.RESULTS)
                        && dataNode.get(AimpFields.RESULTS).isObject()) {
                    dataNode.get(AimpFields.RESULTS).fields().forEachRemaining(e -> {
                        JsonNode info = e.getValue();
                        if (info != null && info.isObject() && info.has(AimpFields.CONFIDENCE)) {
                            result.confidences.put(e.getKey(),
                                    info.get(AimpFields.CONFIDENCE).asDouble(0.0));
                        }
                    });
                }
                if (result.values.isEmpty() && json.has(AimpFields.EXTRACTED_FIELDS)
                        && json.get(AimpFields.EXTRACTED_FIELDS).isObject()) {
                    json.get(AimpFields.EXTRACTED_FIELDS).fields().forEachRemaining(e ->
                            result.values.put(e.getKey(), e.getValue().asText()));
                }
                JsonNode pc = json.get(AimpFields.PAGE_COUNT);
                if (pc != null && pc.isNumber()) result.pageCount = pc.asInt(1);
                // 同步逐页路径（图片模式）同样要认「本页是否走了 LLM」与页型：
                // 前者供调用方从「平均每页耗时」的摊分里剔除短路页，后者供 pageMap 标注页型。
                // 注意取值位置：同步响应把流水线结果整份放在 data 下（data.processing_mode /
                // data.document_status），只读顶层会恒为空——跳页与页型都拿不到，标记也就画不出来
                JsonNode payload = json.path(AimpFields.DATA);
                if (!payload.isObject() || !payload.has(AimpFields.PROCESSING_MODE)) {
                    payload = json;
                }
                result.skipped = payload.path(AimpFields.PROCESSING_MODE).asText("")
                        .startsWith("single_page_skipped");
                result.pageType = payload.path(AimpFields.DOCUMENT_STATUS)
                        .path(AimpFields.PAGE_TYPE).asText("");
                // 层3 候选页信号：跳过的页会回带 page_status.full_evidence（全文痕迹）与
                // page_status.text_length（OCR 文本长度）。两者随页序收集，分件后交给
                // /extract/piece-integrity 做优先级排序与正则预筛
                JsonNode pageStatus = payload.path("page_status");
                if (pageStatus.isObject()) {
                    JsonNode fullEvidence = pageStatus.path("full_evidence");
                    if (fullEvidence.isArray()) {
                        for (JsonNode n : fullEvidence) result.evidenceHits.add(n.asText(""));
                    }
                    JsonNode textLength = pageStatus.path(AimpFields.TEXT_LENGTH);
                    if (textLength.isNumber()) result.textLength = textLength.asInt();
                }
                // 版面标题块证据（l0.pt 同一次推理）：分件层判件首用，不额外增加推理开销
                JsonNode layoutTitles = payload.path(AimpFields.LAYOUT_TITLES);
                if (layoutTitles.isObject()) {
                    JsonNode trc = layoutTitles.path(AimpFields.TITLE_REGION_COUNT);
                    if (trc.isNumber()) result.titleRegionCount = trc.asInt(0);
                }
                result.success = true;
            } else {
                result.error = "HTTP " + response.status();
                logger.warn("AIMP extract failed: HTTP " + response.status());
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
            AimpTransport.Response response = transport.execute(AimpTransport.Request.get(
                    AimpEndpoints.taskStatus(taskId), CONNECT_TIMEOUT, STATUS_READ_TIMEOUT));
            int code = response.status();
            if (code == 200) {
                JsonNode json = mapper.readTree(response.text());
                r.status = json.path(AimpFields.STATUS).asText("");
                r.progress = json.path(AimpFields.PROGRESS).asDouble(0.0);
                r.processedPages = json.path(AimpFields.PROCESSED_PAGES).asInt(0);
                r.totalPages = json.path(AimpFields.TOTAL_PAGES).asInt(0);
                JsonNode errNode = json.get(AimpFields.ERROR);
                if (errNode != null && !errNode.isNull()) r.error = errNode.asText();
                JsonNode resultNode = json.get(AimpFields.RESULT);
                if (resultNode != null && !resultNode.isNull()) r.result = resultNode;
                r.success = true;
            } else {
                r.error = "HTTP " + code;
            }
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
        LicenseGate.fromSystemProperties().require(ModuleNames.AIMP_CHAT);

        LlmAnalyzeResult r = new LlmAnalyzeResult();
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put(AimpFields.PROMPT, prompt);
            body.put(AimpFields.RESPONSE_FORMAT, responseFormat != null ? responseFormat : "json");
            AimpTransport.Response response = transport.execute(AimpTransport.Request.postJson(
                    AimpEndpoints.LLM_ANALYZE, body.toString(), "application/json; charset=UTF-8",
                    CONNECT_TIMEOUT, READ_TIMEOUT));
            if (response.status() == 200) {
                JsonNode json = mapper.readTree(response.text());
                r.success = json.path(AimpFields.SUCCESS).asBoolean(false);
                if (json.has(AimpFields.RESULT)) r.result = json.get(AimpFields.RESULT);
                if (json.has(AimpFields.ERROR)) r.error = json.get(AimpFields.ERROR).asText();
            } else {
                r.success = false;
                r.error = "HTTP " + response.status();
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
        LicenseGate.fromSystemProperties().require(ModuleNames.INFO_EXTRACTION);

        SplitResult r = new SplitResult();
        try {
            ObjectNode body = mapper.createObjectNode();
            ArrayNode pages = body.putArray(AimpFields.PAGES);
            for (int i = 0; i < pageTitles.size(); i++) {
                ObjectNode p = pages.addObject();
                p.put(AimpFields.PAGE, i + 1);
                p.put(AimpFields.TITLE, pageTitles.get(i) == null ? "" : pageTitles.get(i));
                if (pageParties != null && i < pageParties.size() && pageParties.get(i) != null) {
                    p.put(AimpFields.PARTY, pageParties.get(i));
                }
            }
            body.put(AimpFields.TOTAL_PAGES, pageTitles.size());
            if (partyRoles != null && !partyRoles.isEmpty()) body.put(AimpFields.PARTY_ROLES, partyRoles);
            if (archiveCategory != null) body.put(AimpFields.ARCHIVE_CATEGORY, archiveCategory);
            if (archiveSubCategory != null) body.put(AimpFields.ARCHIVE_SUB_CATEGORY, archiveSubCategory);

            AimpTransport.Response response = transport.execute(AimpTransport.Request.postJson(
                    AimpEndpoints.EXTRACT_SPLIT_PIECES, body.toString(), "application/json; charset=UTF-8",
                    CONNECT_TIMEOUT, READ_TIMEOUT));
            if (response.status() == 200) {
                JsonNode json = mapper.readTree(response.text());
                r.success = json.path(AimpFields.SUCCESS).asBoolean(false);
                r.reason = json.path(AimpFields.REASON).asText("");
                JsonNode arr = json.path(AimpFields.PIECES);
                if (arr.isArray()) {
                    for (JsonNode n : arr) {
                        TitleSplitter.Piece p = new TitleSplitter.Piece();
                        p.startPage = n.path(AimpFields.START).asInt(0);
                        p.endPage = n.path(AimpFields.END).asInt(0);
                        p.title = n.path(AimpFields.TITLE).asText("");
                        r.pieces.add(p);
                    }
                }
                if (!r.success) r.pieces.clear();
            } else {
                r.success = false;
                r.reason = "HTTP " + response.status();
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
        return pieceIntegrity(pieces, pageSkipped, pageElements, null, null,
                requiredElements, maxRetryPages);
    }

    /**
     * 层3 完整版重载：额外回传逐页的全文要素痕迹与 OCR 文本长度，服务端据此给回抽候选页
     * 做优先级排序并正则预筛（无这两个信号时服务端回落「件尾优先」简化版）。
     *
     * @param pageEvidence    逐页全文要素痕迹命中，下标对齐 pageSkipped；可为 null
     * @param pageTextLengths 逐页 OCR 文本长度，下标对齐 pageSkipped；可为 null
     */
    public PieceIntegrityResult pieceIntegrity(List<TitleSplitter.Piece> pieces,
                                              List<Boolean> pageSkipped,
                                              List<Map<String, String>> pageElements,
                                              List<List<String>> pageEvidence,
                                              List<Integer> pageTextLengths,
                                              List<String> requiredElements,
                                              Integer maxRetryPages) {
        LicenseGate.fromSystemProperties().require(ModuleNames.INFO_EXTRACTION);

        PieceIntegrityResult r = new PieceIntegrityResult();
        try {
            ObjectNode body = mapper.createObjectNode();
            ArrayNode pieceArr = body.putArray(AimpFields.PIECES);
            for (TitleSplitter.Piece p : pieces) {
                ObjectNode n = pieceArr.addObject();
                n.put(AimpFields.START, p.startPage);
                n.put(AimpFields.END, p.endPage);
                n.put(AimpFields.TITLE, p.title == null ? "" : p.title);
            }
            ArrayNode pageArr = body.putArray(AimpFields.PAGES);
            int pageTotal = pageSkipped == null ? 0 : pageSkipped.size();
            for (int i = 0; i < pageTotal; i++) {
                ObjectNode n = pageArr.addObject();
                n.put(AimpFields.PAGE, i + 1);
                n.put(AimpFields.SKIPPED, Boolean.TRUE.equals(pageSkipped.get(i)));
                ObjectNode els = n.putObject(AimpFields.ELEMENTS);
                Map<String, String> vals = pageElements != null && i < pageElements.size()
                        ? pageElements.get(i) : null;
                if (vals != null) {
                    for (Map.Entry<String, String> e : vals.entrySet()) {
                        if (e.getValue() != null && !e.getValue().trim().isEmpty()) {
                            els.put(e.getKey(), e.getValue());
                        }
                    }
                }
                // 层3 完整版候选页信号：有痕迹才带 evidence（无痕迹页不带，服务端视作无信号页），
                // text_length 有回传就带——服务端靠它做正则预筛（剔除文本过短的页）
                List<String> evidence = pageEvidence != null && i < pageEvidence.size()
                        ? pageEvidence.get(i) : null;
                if (evidence != null && !evidence.isEmpty()) {
                    ArrayNode evArr = n.putArray(AimpFields.EVIDENCE);
                    for (String h : evidence) {
                        if (h != null && !h.trim().isEmpty()) evArr.add(h);
                    }
                }
                if (pageTextLengths != null && i < pageTextLengths.size()
                        && pageTextLengths.get(i) != null) {
                    n.put(AimpFields.TEXT_LENGTH, pageTextLengths.get(i));
                }
            }
            if (requiredElements != null && !requiredElements.isEmpty()) {
                ArrayNode req = body.putArray(AimpFields.REQUIRED_ELEMENTS);
                for (String k : requiredElements) req.add(k);
            }
            if (maxRetryPages != null) body.put(AimpFields.MAX_RETRY_PAGES, maxRetryPages);

            AimpTransport.Response response = transport.execute(AimpTransport.Request.postJson(
                    AimpEndpoints.EXTRACT_PIECE_INTEGRITY, body.toString(), "application/json; charset=UTF-8",
                    CONNECT_TIMEOUT, READ_TIMEOUT));
            if (response.status() == 200) {
                JsonNode json = mapper.readTree(response.text());
                r.success = json.path(AimpFields.SUCCESS).asBoolean(false);
                r.reason = json.path(AimpFields.REASON).asText("");
                JsonNode summary = json.path(AimpFields.SUMMARY);
                r.suspectPieces = summary.path(AimpFields.SUSPECT_PIECES).asInt(0);
                r.retryPageCount = summary.path(AimpFields.RETRY_PAGES).asInt(0);
                for (JsonNode n : json.path(AimpFields.DASHBOARD)) {
                    PieceIntegrityItem item = new PieceIntegrityItem();
                    item.index = n.path(AimpFields.INDEX).asInt(0);
                    item.start = n.path(AimpFields.START).asInt(0);
                    item.end = n.path(AimpFields.END).asInt(0);
                    item.title = n.path(AimpFields.TITLE).asText("");
                    item.suspect = n.path(AimpFields.SUSPECT).asBoolean(false);
                    for (JsonNode m : n.path(AimpFields.MISSING)) item.missing.add(m.asText(""));
                    for (JsonNode pg : n.path(AimpFields.RETRY_PAGES)) item.retryPages.add(pg.asInt(0));
                    r.dashboard.add(item);
                }
                if (!r.success) r.dashboard.clear();
            } else {
                r.success = false;
                r.reason = "HTTP " + response.status();
            }
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
        /**
         * 层3 回抽的候选页信号（AIMP 逐页响应的 page_status.full_evidence）：本页**全文口径**
         * 的要素痕迹命中类别。层1 的位置化判据对本页无命中才会跳过，但全文里往往仍有痕迹，
         * 层3 据此给回抽候选页排序。非跳过页或分类器未启用时为空列表。
         */
        public List<String> evidenceHits = new ArrayList<>();
        /**
         * 层3 正则预筛用：本页 OCR 文本长度（AIMP page_status.text_length）。AIMP 未回传时为
         * null，此时服务端不做预筛，保持与简化版一致的候选池。
         */
        public Integer textLength;
        /**
         * 版面分析模型（l0.pt，与印章检测共用同一次推理）在本页检出的「标题」类块数量。
         * 分件层判定「件首」的强证据：题名非空且置信度达标、同时本页存在版面标题块，
         * 才认作新件起点。印章处理未启用、模型不可用或本页未检出标题块时为 0。
         */
        public int titleRegionCount;
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

