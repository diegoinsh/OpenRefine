package org.openrefine.extensions.files.importer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.refine.ProjectManager;
import com.google.refine.model.Cell;
import com.google.refine.model.Column;
import com.google.refine.model.ColumnModel;
import com.google.refine.model.Project;
import com.google.refine.model.Row;
import com.google.refine.model.SheetData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BatchExtractionManager {

    private static final Logger logger = LoggerFactory.getLogger(BatchExtractionManager.class);
    private static final BatchExtractionManager INSTANCE = new BatchExtractionManager();
    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    /** 要素置信度下限：低于该值的取值视为幻觉（如顶部归档章乱码），不写入结果 */
    private static final double MIN_ELEMENT_CONFIDENCE = 0.3;
    /**
     * 成文日期的件内页位置加分：落款、标题下方的成文日期都靠在文书头尾，正文中部的日期
     * 多是叙述性日期。分值取 0.1，只作微调——档位差（0.95 / 0.5 / 0.15）远比它大，
     * 页位置只在同等档位之间起裁决作用。
     */
    private static final double DATE_EDGE_PAGE_BONUS = 0.1;
    private static final int SAVE_EVERY_ROWS = 10;
    /** 异步任务轮询间隔 */
    private static final int ASYNC_POLL_INTERVAL_MS = 1000;
    /** 单份文档异步提取的等待上限；页数极多时远大于同步读取超时，且可随时取消 */
    private static final long ASYNC_TASK_DEADLINE_MS = 60L * 60 * 1000;
    private static final ObjectMapper mapper = new ObjectMapper();

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_CANCELLED = "cancelled";
    public static final String STATUS_FAILED = "failed";

    /** 项目 metadata 中「要素取值所在页码」的键，形如 {行号: {列名: [{v:取值, c:置信度, p:页码}]}} */
    public static final String PAGE_MAP_KEY = "aimpElementPageMap";

    /**
     * 页映射的行级键「件首页」：值为 {v,c,p,f}，p 为件起始页、f 为该页文件名。
     * 前端点击「要素以外的行」时按它定位到该件第一页；与列名区分开，不会与要素候选混淆。
     */
    public static final String PIECE_START_KEY = "__piece_start__";

    /**
     * 「卷级」表在页映射里的键前缀：卷内行用纯行号（如 "3"），卷级行用「表id:行号」（如 "batch#卷级:0"）。
     * 两张表的行号都从 0 起，不加前缀会互相串页；前端按当前活动表决定用哪种键。
     */
    public static final String SUMMARY_PAGE_MAP_PREFIX = BatchExtractionCommand.SUMMARY_SHEET_ID + ":";

    /**
     * 诉讼档案单批并发页数。需与以下两处对齐，否则以最小值生效：
     * Ollama 的 OLLAMA_NUM_PARALLEL、AIMP 的 config/single_gpu_config.yaml 中
     * pipeline.max_workers，当前三者均为 4。
     * 实测（2026-09-26）：本机 GPU 上 OCR 与 LLM 共用带宽，4→8 路时单路 decode
     * 的 ms/token 精确翻倍、页吞吐仅 +3%，而单页耗时从 13.4s 涨到 26-34s，
     * 故维持 4 路。压测中「4→8 有 +16%」是 GPU 上只有 LLM 时的结论，不适用于本机生产。
     */
    private static final int PAGE_CONCURRENCY = 4;

    public static class Task {
        public final long projectId;
        public final String rootPath;
        public final ExtractionTemplate template;
        public final List<CustomElementType> customElements;
        /** 档案门类，决定 AIMP 侧使用的提取规则提示词；null 时由 AIMP 回退文书档案 */
        public final String archiveCategory;
        /** 专业档案的二级细分类别；非空时优先于门类匹配 AIMP 侧规则，未命中则回退门类规则 */
        public final String archiveSubCategory;
        /** 本批次勾选提取的固定要素键；为空表示使用模板的全部要素 */
        public final List<String> selectedKeys;
        public final List<UnitScanner.Volume> units;
        public final boolean disableCache;
        public volatile int totalPages;
        /** 待处理件数（每个子文件夹或单个 PDF 文件各算一件，与页数无关） */
        public volatile int totalFiles;
        public volatile int processedFiles;
        public volatile boolean cancelRequested;
        public volatile String status = STATUS_RUNNING;
        public volatile String message = "";
        public volatile int processedPages;
        public volatile int failedPages;
        public volatile String currentUnit = "";
        public volatile int rowsAppended;
        /**
         * 当前件（案卷/件）内的完成度 0..1。
         * 顶部进度条按「已完成件数 + 件内完成度」推进：PDF 实际页数只能在处理中动态探明，
         * 若直接用 processedPages/totalPages，分母会后移造成百分比回退（进度条来回跳动）。
         */
        public volatile double unitFraction;

        /** 已进入的卷序号（从 1 起）：页号只在卷内唯一，前端据此判断预览队列是否该重置 */
        public volatile int volumeIndex;

        /**
         * 页级预览的环形容量：只保留最近若干页。生产线上 2-300 页一卷很常见，
         * 预览既要够看，又不能随页数无限增长。
         */
        private static final int MAX_PAGE_PREVIEWS = 40;
        /**
         * 最近完成的页级识别结果（页号 → 摘要），按页号有序。
         *
         * 件级行要等整卷抽完、分件矫正之后才写入，长卷期间界面会长时间空白；这里把每页
         * 识别到的内容先存下来供前端展示「AI 认出了什么」。**未经件级合并与分件**，只用于
         * 观察进展，不作为结果。进入新卷时清空，始终保持为当前卷的进展。
         */
        public final NavigableMap<Integer, PagePreview> recentPages =
                Collections.synchronizedNavigableMap(new TreeMap<>());

        /** 要素取值所在页码：{行号: {列名: [{v,c,p}]}}，随行写入并定期落到项目 metadata */
        public final ObjectNode pageMap = mapper.createObjectNode();

        /** 案卷级双表项目的「卷内」数据表；文件级单表项目为 null，直接写入 project */
        public SheetData innerSheet;
        /** 案卷级双表项目的「卷级」汇总数据表；文件级单表项目为 null */
        public SheetData summarySheet;
        /** 各案卷的汇总累加状态，按卷的扫描顺序保序 */
        public final Map<String, VolumeSummary> volumeSummaries = new LinkedHashMap<>();

        public Task(long projectId, String rootPath, ExtractionTemplate template,
                    List<CustomElementType> customElements, String archiveCategory,
                    String archiveSubCategory, List<String> selectedKeys,
                    List<UnitScanner.Volume> units, boolean disableCache) {
            this.projectId = projectId;
            this.rootPath = rootPath;
            this.template = template;
            this.customElements = customElements;
            this.archiveCategory = archiveCategory;
            this.archiveSubCategory = archiveSubCategory;
            this.selectedKeys = selectedKeys;
            this.units = units;
            this.disableCache = disableCache;
            int total = 0;
            for (UnitScanner.Volume u : units) total += u.pages.size();
            this.totalPages = total;
            this.totalFiles = units.size();
        }

        /** 一页的识别摘要：未经件级合并与分件矫正，仅供观察进展，不作为结果 */
        public static final class PagePreview {
            public final int page;
            public final String fileName;
            /** 该页识别到的全部要素（key → 值，已过滤低置信度取值），按要素顺序展示 */
            public final Map<String, String> values;

            PagePreview(int page, String fileName, Map<String, String> values) {
                this.page = page;
                this.fileName = fileName;
                this.values = values;
            }
        }

        /** 记一页的识别结果；超出容量时丢弃最旧的一页，保证长卷不撑内存 */
        void recordPagePreview(int page, String fileName, Map<String, String> values) {
            synchronized (recentPages) {
                recentPages.put(page, new PagePreview(page, fileName, values));
                while (recentPages.size() > MAX_PAGE_PREVIEWS) {
                    recentPages.pollFirstEntry();
                }
            }
        }

        /** 进入新卷时清空预览：页号只在卷内唯一，跨卷累积会把不同卷的页混在一起 */
        void clearPagePreviews() {
            synchronized (recentPages) {
                recentPages.clear();
            }
        }

        /** 页级抽取的并发度：1 表示逐页串行调用，大于 1 表示按批并发（前端据此决定揭示节奏） */
        public volatile int pageConcurrency = 1;
        /**
         * 平均每页耗时（毫秒）：并发时按「批耗时 ÷ 批页数」，只取**最近若干批**的滑动平均。
         *
         * 不用任务级累计平均：单页耗时随输出长度在 7~21s 间波动，累计平均对当前速度无感；
         * 且首批含模型预热/首次推理（首次 prefill 明显偏慢），算进去会让卷首的揭示节奏长期偏慢。
         * 故跳过每卷首批、只留最近 {@link #PAGE_TIMING_WINDOW} 批，换卷时重置。
         */
        private static final int PAGE_TIMING_WINDOW = 8;
        private final Object pageTimingLock = new Object();
        /** 最近若干批的 {批墙钟(ms), 批页数}，容量 PAGE_TIMING_WINDOW */
        private final Deque<long[]> pageTiming = new ArrayDeque<>();
        private int pageTimingBatches;

        void recordPageTiming(long millis, int pages) {
            if (millis <= 0 || pages <= 0) return;
            synchronized (pageTimingLock) {
                pageTimingBatches++;
                if (pageTimingBatches == 1) return;   // 首批含预热/首次推理，不代表稳定速度
                pageTiming.addLast(new long[] { millis, pages });
                while (pageTiming.size() > PAGE_TIMING_WINDOW) {
                    pageTiming.pollFirst();
                }
            }
        }

        int avgPageMillis() {
            synchronized (pageTimingLock) {
                long total = 0;
                int count = 0;
                for (long[] batch : pageTiming) {
                    total += batch[0];
                    count += (int) batch[1];
                }
                return count <= 0 ? 0 : (int) (total / count);
            }
        }

        /** 换卷时重置耗时窗口：新卷首批同样含新一轮启动开销，需重新跳过 */
        void resetPageTiming() {
            synchronized (pageTimingLock) {
                pageTiming.clear();
                pageTimingBatches = 0;
            }
        }
    }

    /**
     * 单个案卷的卷级汇总累加状态：卷内每落一行即累加一次，
     * 责任者去重保序，成文日期只取非空并记录最早/最晚，页数与文件份数按实际累加。
     */
    static class VolumeSummary {
        final String caseNo;
        final String folderPath;
        /** 卷内文件份数：按件级条目累加（件数），而非目录下扫描文件个数（含封面/目录/备考表等） */
        int pieceCount;
        final Set<String> responsibleParties = new LinkedHashSet<>();
        /** 卷内各件题名拆出的事由，去重保序 */
        final Set<String> causes = new LinkedHashSet<>();
        /** 卷内各件题名拆出的文种，去重保序 */
        final Set<String> docTypes = new LinkedHashSet<>();
        /** 卷内出现过的法院名及件数：诉讼档案卷级题名要取本卷立卷法院，而非原审法院 */
        final Map<String, Integer> courtCounts = new LinkedHashMap<>();
        /**
         * 卷级要素汇总结果：key → 取值，写入「卷级」表对应列。
         * 各要素的汇总规则见 add / resolveElements：当事人并集去重、结案方式取最晚一件、
         * 密级与开放状态取最严一档、案由/审级/保管期限取众数。
         */
        final Map<String, String> volumeElements = new LinkedHashMap<>();
        /** 众数类卷级要素的计数：key → 值 → 出现件数 */
        private final Map<String, Map<String, Integer>> elementCounts = new LinkedHashMap<>();
        /** 「取最终结论」类要素（结案方式）：key → 当前最优值所属件的成文日期 */
        private final Map<String, String> elementDates = new LinkedHashMap<>();
        /** 当事人名单：卷内各件当事人拆分、剔除诉讼地位称谓后去重保序 */
        private final Set<String> partyNames = new LinkedHashSet<>();
        /** 当事人出现在多少个件上：低频噪声过滤用 */
        private final Map<String, Integer> partyNameCounts = new LinkedHashMap<>();
        /**
         * 每个当事人名出现在哪些件（件序号集合）：用于区分「错字」与「真实的不同人」。
         * 兄弟姐妹常只差一个字、编辑距离同样是 1（「袁凤仙」「袁凤鸣」），但他们会**在同一件里
         * 并列出现**（判决书首部把当事人同列）；而错字与正字**互斥**，同一件不会两者都有。
         * 故「从未同件共现」是判断二者互为错字的关键判据。
         */
        private final Map<String, Set<Integer>> partyNamePieces = new LinkedHashMap<>();
        /** 卷级要素的来源页：key → 取值 → 候选页，跨件累加，供「卷级」表点击定位 */
        private final Map<String, Map<String, List<AimpLlmClient.ElementCandidate>>> elementPageSources =
                new LinkedHashMap<>();
        /** 件成文日期 → 来源候选页：卷级「起始时间／终止时间」点回取到该日期的那一页 */
        private final Map<String, List<AimpLlmClient.ElementCandidate>> datePageSources = new LinkedHashMap<>();
        /** 本卷首件的首页候选：卷级行点击要素以外的列时定位到这里 */
        ObjectNode firstPieceStart;
        String minDate;
        String maxDate;
        int totalPages;

        /** 密级档位：数值越大越严，整卷取最高档；「非密」等非标准表述视同「公开」 */
        private static final Map<String, Integer> SECURITY_RANKS = Map.of(
                "公开", 1, "非密", 1, "不涉密", 1, "普通", 1,
                "内部", 2, "秘密", 3, "机密", 4, "绝密", 5);

        /** 开放状态档位：整卷从严，取最受限的一档（控制 > 延期开放 > 开放） */
        private static final Map<String, Integer> OPEN_STATUS_RANKS = Map.of(
                "开放", 1, "主动公开", 1, "延期开放", 2, "控制", 3, "不开放", 3, "限制使用", 3);

        VolumeSummary(String caseNo, String folderPath) {
            this.caseNo = caseNo;
            this.folderPath = folderPath;
        }

        /** 不带来源页信息的累加：仅汇总取值，不建页定位 */
        void add(String responsibleParty, String date, int pages, String title,
                 Map<String, String> values) {
            add(responsibleParty, date, pages, title, values, null, null);
        }

        void add(String responsibleParty, String date, int pages, String title,
                 Map<String, String> values,
                 Map<String, List<AimpLlmClient.ElementCandidate>> candidates,
                 ObjectNode pieceStart) {
            // 每落一件就是卷内的一份文件：卷内文件份数按件累加（见 docs 3.4）
            pieceCount++;
            if (firstPieceStart == null && pieceStart != null) {
                firstPieceStart = pieceStart;
            }
            if (responsibleParty != null && !responsibleParty.trim().isEmpty()) {
                String rp = responsibleParty.trim();
                responsibleParties.add(rp);
                // 识别制作机关是否为法院：全称含"法院"，也兼容"××中院""××高院"这类简称
                if (rp.contains("法院") || rp.endsWith("中院") || rp.endsWith("高院")) {
                    courtCounts.merge(rp, 1, Integer::sum);
                }
            }
            if (date != null && !date.trim().isEmpty()) {
                String d = date.trim();
                if (minDate == null || d.compareTo(minDate) < 0) {
                    minDate = d;
                }
                if (maxDate == null || d.compareTo(maxDate) > 0) {
                    maxDate = d;
                }
                // 记下该日期取自哪几页：卷级「起始时间／终止时间」要能点回取值那一页
                collectDatePages(d, candidates);
            }
            if (title != null && !title.trim().isEmpty()) {
                String[] causeAndDocType = TitleSplitter.splitCauseAndDocType(title);
                if (!causeAndDocType[0].isEmpty()) {
                    causes.add(causeAndDocType[0]);
                }
                if (!causeAndDocType[1].isEmpty()) {
                    docTypes.add(causeAndDocType[1]);
                }
            }
            if (values != null) {
                for (String key : ExtractionTemplate.VOLUME_LEVEL_ELEMENT_KEYS) {
                    String v = trimToNull(values.get(key));
                    if (v == null) continue;
                    if ("dangshiren".equals(key)) {
                        // 当事人是多值：逐个人名累加件数、来源页，并记下所在件序号
                        // （件序号供「从未同件共现」判错字用，见 mergePartyNames）
                        for (String name : normalizePartyNames(v).split("，")) {
                            if (name.isEmpty()) continue;
                            partyNames.add(name);
                            partyNameCounts.merge(name, 1, Integer::sum);
                            partyNamePieces.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(pieceCount);
                            collectElementPages(key, name, candidates, name);
                        }
                    } else if ("jiean_fangshi".equals(key)) {
                        keepLatest(key, v, date);
                        collectElementPages(key, v, candidates, null);
                    } else if ("miji".equals(key) || "kaifang_zhuangtai".equals(key)) {
                        keepStrictest(key, v);
                        collectElementPages(key, v, candidates, null);
                    } else {
                        elementCounts.computeIfAbsent(key, k -> new LinkedHashMap<>())
                                .merge(v, 1, Integer::sum);
                        collectElementPages(key, v, candidates, null);
                    }
                }
            }
            totalPages += pages;
        }

        /**
         * 记录某要素取值对应的来源候选页（跨件累加，供「卷级」表点击定位）。
         *
         * nameFilter 非空时按「候选取值包含该姓名」筛选——当事人拆出来的是单个人名，与候选原值
         * （可能带「原告」称谓、含多人）不完全相等；否则按候选取值与该取值相等筛选，
         * 筛不出时回退用该件的全部候选页，至少保证能定位到该件。
         */
        private void collectElementPages(String key, String value,
                                         Map<String, List<AimpLlmClient.ElementCandidate>> candidates,
                                         String nameFilter) {
            if (candidates == null) return;
            List<AimpLlmClient.ElementCandidate> list = candidates.get(key);
            if (list == null || list.isEmpty()) return;
            List<AimpLlmClient.ElementCandidate> matched = new ArrayList<>();
            for (AimpLlmClient.ElementCandidate c : list) {
                if (nameFilter != null) {
                    if (c.value != null && c.value.contains(nameFilter)) matched.add(c);
                } else if (value.equals(c.value)) {
                    matched.add(c);
                }
            }
            if (matched.isEmpty()) {
                if (nameFilter != null) return;
                matched = list;
            }
            elementPageSources.computeIfAbsent(key, k -> new LinkedHashMap<>())
                    .computeIfAbsent(value, k -> new ArrayList<>())
                    .addAll(matched);
        }

        /** 取该要素最终取值对应的来源候选页（按页去重保序），供「卷级」表点击定位到页 */
        List<AimpLlmClient.ElementCandidate> pageSourcesFor(String key) {
            Map<String, List<AimpLlmClient.ElementCandidate>> byValue = elementPageSources.get(key);
            String value = volumeElements.get(key);
            if (byValue == null || value == null) return Collections.emptyList();
            // 当事人是多值并集：按名单顺序把每个人的来源页合并去重
            List<String> keys = "dangshiren".equals(key)
                    ? Arrays.asList(value.split("，")) : Collections.singletonList(value);
            List<AimpLlmClient.ElementCandidate> merged = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (String k : keys) {
                for (AimpLlmClient.ElementCandidate c : byValue.getOrDefault(k, Collections.emptyList())) {
                    String mark = c.page + "|" + (c.fileName == null ? "" : c.fileName);
                    if (seen.add(mark)) merged.add(c);
                }
            }
            return merged;
        }

        /** 记下件成文日期取自哪几页：卷级「起始时间／终止时间」按日期取值匹配候选页 */
        private void collectDatePages(String date,
                                      Map<String, List<AimpLlmClient.ElementCandidate>> candidates) {
            if (candidates == null) return;
            List<AimpLlmClient.ElementCandidate> list = candidates.get("date");
            if (list == null || list.isEmpty()) return;
            for (AimpLlmClient.ElementCandidate c : list) {
                if (date.equals(c.value)) {
                    datePageSources.computeIfAbsent(date, k -> new ArrayList<>()).add(c);
                }
            }
        }

        /** 卷级「起始时间／终止时间」的来源页：按该日期取值取候选页，去重保序 */
        List<AimpLlmClient.ElementCandidate> dateSourcesFor(String date) {
            List<AimpLlmClient.ElementCandidate> list = date == null
                    ? null : datePageSources.get(date);
            if (list == null || list.isEmpty()) return Collections.emptyList();
            List<AimpLlmClient.ElementCandidate> merged = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (AimpLlmClient.ElementCandidate c : list) {
                String mark = c.page + "|" + (c.fileName == null ? "" : c.fileName);
                if (seen.add(mark)) merged.add(c);
            }
            return merged;
        }

        /**
         * 当事人名单去噪：以「件间一致性」为判据，而非固定阈值。
         *
         * 卷内只要有重复出现的当事人，就说明存在可对照的一致性信号——此时只出现在一个件上的
         * 取值没有旁证，多为该页误读（错字、乱抽的"原告/被告"等），剔除；
         * 若整卷所有取值都只出现一次，则本卷根本没有可对照的信号，"只出现一次"不再是噪声特征
         * （小卷、当事人本就单一），此时全部计入，避免把真实当事人删光。
         */
        private List<String> distinctPartyNames() {
            boolean hasRepeat = false;
            for (int count : partyNameCounts.values()) {
                if (count > 1) {
                    hasRepeat = true;
                    break;
                }
            }
            if (!hasRepeat) return new ArrayList<>(partyNames);
            List<String> kept = new ArrayList<>();
            for (String name : partyNames) {
                if (partyNameCounts.getOrDefault(name, 0) > 1) kept.add(name);
            }
            return kept;
        }

        /**
         * 当事人错别字归并：手写材料（答辩状、上诉状、笔录等）把「袁凤莲」写成「袁风莲」，只靠
         * 件间重复次数删不掉——错字同样会重复出现在多个件上。
         *
         * 判据不能只看编辑距离：兄弟姐妹常只差一个字，彼此编辑距离同样是 1（「袁凤仙」「袁凤鸣」），
         * 单凭字面无法区分谁是错字。但二者有两点本质差异：
         *   1．兄弟姐妹会在**同一件里并列出现**（判决书首部把当事人同列），而错字与正字**互斥**，
         *      同一件不会两者都有——故要求「从未同件共现」；
         *   2．错字是少数派，正字在卷内出现的件数更多——故取「件数最多的近邻」（众数）作为正字。
         * 两个条件都满足才归并；出现并列时保持原样（裁决不了就不动）。
         */
        private List<String> mergePartyNames(List<String> names) {
            if (names.size() < 2) return names;
            Map<String, String> redirect = new LinkedHashMap<>();
            for (String name : names) {
                String matched = null;
                int matchedCount = 0;
                boolean ambiguous = false;
                int ownCount = partyNameCounts.getOrDefault(name, 0);
                for (String other : names) {
                    if (other.equals(name)) continue;
                    // 只认等长的「一字之差」（替换），不比插入/删除，避免把名字长短不同的人并掉
                    if (other.length() != name.length()) continue;
                    if (editDistance(name, other) != 1) continue;
                    if (shareAnyPiece(name, other)) continue;
                    int otherCount = partyNameCounts.getOrDefault(other, 0);
                    if (otherCount <= ownCount) continue;
                    if (otherCount > matchedCount) {
                        matched = other;
                        matchedCount = otherCount;
                        ambiguous = false;
                    } else if (otherCount == matchedCount) {
                        ambiguous = true;
                    }
                }
                if (matched != null && !ambiguous) redirect.put(name, matched);
            }
            if (redirect.isEmpty()) return names;
            // 来源页一并迁到纠正后的名字上，卷级行的点击定位不丢页
            Map<String, List<AimpLlmClient.ElementCandidate>> byValue = elementPageSources.get("dangshiren");
            if (byValue != null) {
                Map<String, List<AimpLlmClient.ElementCandidate>> rebuilt = new LinkedHashMap<>();
                for (Map.Entry<String, List<AimpLlmClient.ElementCandidate>> e : byValue.entrySet()) {
                    rebuilt.computeIfAbsent(redirect.getOrDefault(e.getKey(), e.getKey()),
                            k -> new ArrayList<>()).addAll(e.getValue());
                }
                elementPageSources.put("dangshiren", rebuilt);
            }
            Set<String> merged = new LinkedHashSet<>();
            for (String name : names) {
                String target = name;
                Set<String> guard = new LinkedHashSet<>();
                while (redirect.containsKey(target) && guard.add(target)) {
                    target = redirect.get(target);
                }
                merged.add(target);
            }
            return new ArrayList<>(merged);
        }

        /** 两个名字是否在同一件里出现过；共现 ⇒ 是两个不同的人，不可能是彼此的错字 */
        private boolean shareAnyPiece(String a, String b) {
            Set<Integer> piecesA = partyNamePieces.get(a);
            Set<Integer> piecesB = partyNamePieces.get(b);
            if (piecesA == null || piecesB == null) return false;
            Set<Integer> small = piecesA.size() <= piecesB.size() ? piecesA : piecesB;
            Set<Integer> big = small == piecesA ? piecesB : piecesA;
            for (Integer piece : small) {
                if (big.contains(piece)) return true;
            }
            return false;
        }

        /** 逐字符 Levenshtein 距离：人名只有 2~4 字，直接算即可 */
        private static int editDistance(String a, String b) {
            int[] prev = new int[b.length() + 1];
            for (int j = 0; j <= b.length(); j++) {
                prev[j] = j;
            }
            for (int i = 1; i <= a.length(); i++) {
                int[] cur = new int[b.length() + 1];
                cur[0] = i;
                for (int j = 1; j <= b.length(); j++) {
                    int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                    cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                }
                prev = cur;
            }
            return prev[b.length()];
        }

        /**
         * 结案方式取最终结论：按件成文日期比较，取最晚一件的取值。
         * 过程性记载（如调解笔录页抽到的「调解」）会被其后判决书的「判决」覆盖。
         */
        private void keepLatest(String key, String value, String date) {
            String best = elementDates.get(key);
            String d = date == null ? "" : date.trim();
            if (best == null || d.compareTo(best) > 0) {
                elementDates.put(key, d);
                volumeElements.put(key, value);
            }
        }

        /** 密级、开放状态整卷从严：取档位最高的一档 */
        private void keepStrictest(String key, String value) {
            Map<String, Integer> ranks = "miji".equals(key) ? SECURITY_RANKS : OPEN_STATUS_RANKS;
            Integer prev = volumeElements.containsKey(key)
                    ? ranks.getOrDefault(volumeElements.get(key), -1)
                    : null;
            if (prev == null || ranks.getOrDefault(value, -1) > prev) {
                volumeElements.put(key, value);
            }
        }

        /** 汇总落定：众数类要素取出现件数最多的取值，当事人取并集。幂等，可重复调用 */
        void resolveElements() {
            for (Map.Entry<String, Map<String, Integer>> entry : elementCounts.entrySet()) {
                String best = null;
                int bestCount = 0;
                for (Map.Entry<String, Integer> count : entry.getValue().entrySet()) {
                    if (count.getValue() > bestCount) {
                        bestCount = count.getValue();
                        best = count.getKey();
                    }
                }
                if (best != null) {
                    volumeElements.put(entry.getKey(), best);
                }
            }
            List<String> names = mergePartyNames(distinctPartyNames());
            if (!names.isEmpty()) {
                volumeElements.put("dangshiren", String.join("，", names));
            }
        }

        /**
         * 卷级题名。当事人与案由齐全、且能识别出本卷立卷法院时，按诉讼档案著录惯例构成：
         * 「立卷法院」+「关于」+「当事人」+「案由」+「一案」+「的审级」+「诉讼档案」，
         * 例如「河北省张家口市中级人民法院关于袁连顺、袁凤莲与袁凤仙、袁凤鸣继承纠纷一案的二审诉讼档案」；
         * 条件不足时退回通用写法：「关于」+ 事由1、事由2、… +「的」+ 汇总文种
         * （文种唯一时取该文种，缺失或多种文种时兜底为「材料」）。
         */
        String volumeTitle() {
            String litigation = litigationVolumeTitle();
            if (litigation != null) return litigation;
            if (causes.isEmpty()) return "";
            String docType = docTypes.size() == 1
                    ? docTypes.iterator().next()
                    : TitleSplitter.FALLBACK_DOC_TYPE;
            return "关于" + String.join("、", causes) + "的" + docType;
        }

        /** 诉讼档案卷级题名；要素不足时返回 null，由调用方退回通用写法 */
        private String litigationVolumeTitle() {
            String parties = volumeElements.get("dangshiren");
            String cause = volumeElements.get("anyou");
            if (parties == null || cause == null) return null;
            String court = volumeCourt();
            if (court == null) return null;
            StringBuilder sb = new StringBuilder(court)
                    .append("关于").append(abbreviateParties(parties)).append(cause).append("一案");
            String trialLevel = volumeElements.get("shenji");
            if (trialLevel != null) sb.append("的").append(trialLevel);
            return sb.append("诉讼档案").toString();
        }

        /** 本卷立卷法院：卷内制作文书件数最多的法院，避免取到原审法院；识别不出返回 null */
        String volumeCourt() {
            String court = null;
            int best = 0;
            for (Map.Entry<String, Integer> e : courtCounts.entrySet()) {
                if (e.getValue() > best) {
                    best = e.getValue();
                    court = e.getKey();
                }
            }
            return court;
        }

        /** 案卷题名要求简练（GB/T 9705—2008 3.1.3.3）：当事人列前 3 位，第 4 位起以「等」概称 */
        private static String abbreviateParties(String parties) {
            String[] names = parties.split("，");
            if (names.length <= 3) return parties;
            return names[0] + "，" + names[1] + "，" + names[2] + "等";
        }
    }

    /**
     * 证件类件名：证件不是诉讼文书，证面上的签发日期／有效期不是「文书形成日期」，
     * 该件成文日期应留空（见 docs 3.4 决定五）。含「身份证明」以覆盖卷级分件生成的
     * 「原告身份证明」「被告身份证明」件。
     */
    private static final List<String> CERTIFICATE_PIECE_TITLES = Arrays.asList(
            "身份证明", "居民身份证", "常住人口登记表", "常住人口登记卡", "户口簿",
            "营业执照", "组织机构代码证", "企业信用信息公示报告", "法定代表人身份证明");

    /** 件名是否属于证件类件（身份证、户口簿、营业执照等）；件名可能带「原告/被告」前缀，用包含匹配 */
    static boolean isCertificatePiece(String title) {
        if (title == null) return false;
        String text = title.trim();
        if (text.isEmpty()) return false;
        for (String word : CERTIFICATE_PIECE_TITLES) {
            if (text.contains(word)) return true;
        }
        return false;
    }

    /**
     * 无署名材料：这些表格自身没有责任者栏，责任者应留空。
     * 模型容易把表格正文里提到的当事人／机关名当成责任者（实测 SZ05 的「证物处理单」
     * 被抽出正文中的「被告苏州恒盛精密机械有限公司」），故在件级确定性清空。
     */
    private static final List<String> NO_RESPONSIBLE_AUTHOR_TITLES = Arrays.asList(
            "证物处理单", "卷内目录", "备考表");

    /** 件名是否属于无署名材料（证物处理单等表格） */
    static boolean isNoResponsibleAuthorPiece(String title) {
        if (title == null) return false;
        String text = title.trim();
        if (text.isEmpty()) return false;
        for (String word : NO_RESPONSIBLE_AUTHOR_TITLES) {
            if (text.contains(word)) return true;
        }
        return false;
    }

    /** 诉讼地位称谓：当事人取值里出现这些字样时它不是当事人名称，件级规范化时剔除。长称谓在前 */
    private static final List<String> PARTY_ROLE_WORDS = Arrays.asList(
            "被申请执行人", "申请执行人", "被上诉人", "被申请人", "被执行人",
            "反诉原告", "反诉被告", "公诉机关",
            "上诉人", "申请人", "自诉人", "被害人", "被告人", "第三人",
            "原告", "被告");

    /**
     * 当事人取值的分隔符：标点与空白都可作分隔（模型回吐时两种都会出现）。
     * 冒号一并分隔——模型常回吐「上诉人：XXX」这种带冒号的写法。
     */
    private static final Pattern PARTY_NAME_SEPARATOR = Pattern.compile("[\\s\\u3000，,、;；/:：]+");

    /**
     * 件级要素规范化：把要素值整理成规范形式，作为「件级最终值」。
     *
     * 卷内列与卷级汇总都读这一份值——要素自身的规范化（称谓、分隔符、重复项）属件级策略，
     * 放在这里做一次；卷级只做跨件的聚合（并集、取最新、取最高档、众数、低频去噪），
     * 不重复实现件级规则。
     */
    static void normalizePieceValues(Map<String, String> values) {
        if (values == null) return;
        String parties = values.get("dangshiren");
        if (parties != null) {
            values.put("dangshiren", normalizePartyNames(parties));
        }
        // 其余要素的占位式取值同样清空，免得「责任者：XXX」这类内容进入件级与卷级结果
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e.getValue() != null && isPlaceholderValue(e.getValue())) {
                e.setValue("");
            }
        }
        applyTitleConstraints(values);
    }

    /**
     * 按「件题名」对责任者/文号做确定性约束。这类规则与件类型强绑定，放代码里兜底而不是继续堆
     * 提示词——诉讼档案提示词已经很长，继续加会拖慢提取、还会让小模型输出退化。
     *  - 身份证明类：责任者取证件本人／执照单位，模型若回吐签发机关（公安局、市场监督管理局…）按无依据清空；
     *  - 不编文号的材料（笔录、送达回证、送达地址确认书、身份证明、缴费凭证、记录类表格）：
     *    模型若把案号回填进来一律清空。
     */
    private static void applyTitleConstraints(Map<String, String> values) {
        String title = values.get("title");
        if (title == null || title.isEmpty()) return;
        String compact = title.replaceAll("[\\s\\u3000]+", "");
        if (containsAny(compact, IDENTITY_TITLE_HINTS)) {
            String responsible = values.get("responsible_party");
            if (responsible != null && containsAny(responsible, IDENTITY_ISSUER_HINTS)) {
                values.put("responsible_party", "");
            }
        }
        if (containsAny(compact, NO_DOC_NUMBER_TITLE_HINTS)) {
            values.put("document_number", "");
        }
    }

    private static boolean containsAny(String text, List<String> hints) {
        if (text == null) return false;
        for (String hint : hints) {
            if (text.contains(hint)) return true;
        }
        return false;
    }

    /**
     * 当事人值的件级规范化：按分隔符拆成单个当事人，剥掉「原告」「被告」等诉讼地位称谓，
     * 件内去重后以全角逗号重新拼接。整值都是称谓（如「原告，被告」）或占位符时返回空串。
     */
    static String normalizePartyNames(String text) {
        Set<String> names = new LinkedHashSet<>();
        for (String token : PARTY_NAME_SEPARATOR.split(text)) {
            String name = stripPartyRole(token);
            if (!name.isEmpty() && !isPlaceholderValue(name)) {
                names.add(name);
            }
        }
        return String.join("，", names);
    }

    /** 剥掉诉讼地位称谓：「原告沈建明」→「沈建明」；整个值就是称谓时返回空 */
    private static String stripPartyRole(String name) {
        for (String role : PARTY_ROLE_WORDS) {
            if (name.equals(role)) {
                return "";
            }
            if (name.length() > role.length() && name.startsWith(role)) {
                // 去掉称谓后可能还挂着冒号、顿号等残渣（「上诉人：沈建明」→「沈建明」）
                return name.substring(role.length()).replaceFirst("^[\\s\\u3000:：、,，]+", "");
            }
        }
        return name;
    }

    /**
     * 占位式取值：模型遇到「有栏位名、没有实际内容」时会回吐这类内容（例如栏位空白的
     * 「原告XXX，被告XXX」），无论落在哪个要素上都按空值处理——否则这些垃圾会一路进到
     * 件级与卷级结果里。
     */
    private static final Set<String> PLACEHOLDER_VALUES = new HashSet<>(Arrays.asList(
            "某某", "某", "姓名", "名称", "不详", "未知", "待定", "无", "同上", "略", "null", "n/a",
            // 提示词骨架里的示例人名会被模型照抄（实测「张三，李四」进入卷级当事人名单）
            "张三", "李四", "王五", "赵六", "小明", "小红"));

    /** 纯占位符号（X、×、○、※、下划线等）组成的取值 */
    private static final Pattern PLACEHOLDER_SYMBOLS = Pattern.compile("[XxＸ×〇○●※_\\-—.·]+");

    /** 身份证明类材料的题名特征：责任者取证件本人／执照单位，不取签发机关 */
    private static final List<String> IDENTITY_TITLE_HINTS = Arrays.asList(
            "身份证明", "身份证", "户口", "常住人口登记", "营业执照", "工作证", "驾驶证", "护照");

    /** 签发机关特征：出现在身份证明类材料的责任者位置上即为错值（公安局、市场监督管理局…） */
    private static final List<String> IDENTITY_ISSUER_HINTS = Arrays.asList(
            "公安局", "公安分局", "派出所", "市场监督管理", "监督管理局", "政务服务", "公证处");

    /** 按立卷规则不编文号的材料：当事人提交的材料、送达凭证、记录类表格 */
    private static final List<String> NO_DOC_NUMBER_TITLE_HINTS = Arrays.asList(
            "笔录", "送达回证", "送达地址确认书", "身份证明", "身份证", "户口", "常住人口登记",
            "缴费凭证", "缴款书", "证物处理单", "卷内目录", "快递单", "查询单", "存根", "回执");

    static boolean isPlaceholderValue(String value) {
        if (value == null) return true;
        String t = value.trim();
        if (t.isEmpty()) return true;
        if (PLACEHOLDER_VALUES.contains(t.toLowerCase())) return true;
        if (PLACEHOLDER_SYMBOLS.matcher(t).matches()) return true;
        // 纯标点/符号：剥离称谓后残留的冒号、顿号等
        return t.matches("[\\p{P}\\p{S}]+");
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "batch-title-extraction");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, Task> tasks = new ConcurrentHashMap<>();
    private final Object rowLock = new Object();
    private int rowsSinceSave;

    private BatchExtractionManager() {
    }

    public static BatchExtractionManager get() {
        return INSTANCE;
    }

    public Task start(long projectId, String rootPath, ExtractionTemplate template,
                      List<CustomElementType> customElements, String archiveCategory,
                      String archiveSubCategory, List<String> selectedKeys,
                      String aimpUrl, boolean disableCache) {
        List<UnitScanner.Volume> units = template == ExtractionTemplate.BATCH_TITLE_CASE
                ? UnitScanner.scanCases(rootPath)
                : UnitScanner.scanVolumes(rootPath);
        Task task = new Task(projectId, rootPath, template, customElements,
                archiveCategory, archiveSubCategory, selectedKeys, units, disableCache);
        tasks.put(projectId, task);
        executor.submit(() -> run(task, aimpUrl));
        return task;
    }

    public Task getTask(long projectId) {
        return tasks.get(projectId);
    }

    public boolean cancel(long projectId) {
        Task task = tasks.get(projectId);
        if (task == null) return false;
        task.cancelRequested = true;
        return true;
    }

    private void run(Task task, String aimpUrl) {
        try {
            executeExtraction(task, aimpUrl);
        } catch (Throwable t) {
            logger.error("Batch extraction task aborted", t);
            fail(task, t.toString());
        }
    }

    private void executeExtraction(Task task, String aimpUrl) {
        AimpLlmClient client = new AimpLlmClient(aimpUrl);
        client.setDisableCache(task.disableCache);
        client.setArchiveCategory(task.archiveCategory);
        client.setArchiveSubCategory(task.archiveSubCategory);
        AimpLlmClient.CompatibilityResult compat = client.checkCompatibility();
        if (!compat.reachable) {
            fail(task, "AIMP_SERVICE_UNAVAILABLE");
            return;
        }
        if (!compat.compatible) {
            fail(task, "AI服务模块版本不匹配：本扩展要求接口版本 " + compat.expectedApiVersions()
                    + "，当前模块为 " + compat.actualVersion() + "，请更新模块后重试");
            return;
        }
        // 握手成功即把对端版本记进日志：日后排查「某个版本出的问题」时可直接定位到模块提交
        logger.info("AIMP 服务握手成功：{}", compat.actualVersion());
        List<String> keys = task.selectedKeys != null && !task.selectedKeys.isEmpty()
                ? task.selectedKeys
                : Arrays.asList(task.template.getExtractionKeys());
        String keyList = String.join(",", keys);
        String customJson = CustomElementsCodec.toJson(task.customElements);
        // 页级并发度：诉讼档案按页并发（页间无上下文），其余门类逐页串行；
        // 前端据此决定预览的揭示节奏——串行到达即显示，并发按平均每页耗时逐条揭示
        task.pageConcurrency = isLitigationArchive(task) ? PAGE_CONCURRENCY : 1;
        Project project = ProjectManager.singleton.getProject(task.projectId);
        if (project == null) {
            fail(task, "Project not found: " + task.projectId);
            return;
        }
        task.innerSheet = project.getSheetData(BatchExtractionCommand.INNER_SHEET_ID);
        task.summarySheet = project.getSheetData(BatchExtractionCommand.SUMMARY_SHEET_ID);
        if (task.innerSheet != null) {
            project.setActiveSheet(BatchExtractionCommand.INNER_SHEET_ID);
        }
        try {
            int consecutiveFailures = 0;
            for (UnitScanner.Volume unit : task.units) {
                if (task.cancelRequested) break;
                task.currentUnit = unit.name;
                task.volumeIndex++;
                task.clearPagePreviews();
                // 耗时窗口同样按卷重置：新卷首批含新一轮启动开销，旧卷的历史不应影响本卷节奏
                task.resetPageTiming();
                // 件级计数：进入某件即计一件，与件内页数无关
                task.processedFiles++;
                task.unitFraction = 0d;
                // 件号仅在「同一案卷/文件夹」内保证唯一，案卷之间互不影响
                Set<String> usedPieceNos = new HashSet<>();
                String lastPieceNo = null;
                if (unit.pdfMode) {
                    // 卷内全局页号偏移：PDF 件登记的是「卷内第几页到第几页」，
                    // 而非 PDF 文件内部页号，否则同卷多件的页区间会全部重叠、比对时切错页
                    int pageOffset = 0;
                    for (int k = 0; k < unit.pages.size(); k++) {
                        if (task.cancelRequested) break;
                        String pdf = unit.pages.get(k);
                        String label = fileLabel(new File(pdf).getName(), k + 1, unit.pages.size());
                        // 件号：取文件名中“从后往前的 3 位 0 开头数字子串”，取不到或卷内冲突时基于前一件号递推
                        String pieceNo = allocatePieceNumber(new File(pdf).getName(), usedPieceNos, lastPieceNo);
                        lastPieceNo = pieceNo;
                        int pagesBefore = task.processedPages;
                        int totalBefore = task.totalPages;
                        task.message = "正在提交： " + label;

                        AimpLlmClient.ExtractPageResult r = client.submitAsync(pdf, keyList, customJson);
                        if (!r.success || r.taskId == null || r.taskId.isEmpty()) {
                            consecutiveFailures++;
                            task.failedPages++;
                            task.processedPages = pagesBefore + 1;
                            task.unitFraction = (k + 1) / (double) Math.max(1, unit.pages.size());
                            TitleSplitter.Piece p = new TitleSplitter.Piece();
                            // 提交失败也按 1 页占位，保证后续件的卷内页号不重叠
                            p.startPage = pageOffset + 1;
                            p.endPage = pageOffset + 1;
                            pageOffset += 1;
                            appendUnitRow(task, project, unit, pieceNo, new File(pdf).getName(), p, null,
                                    r.error != null ? r.error : "提交提取任务失败", null);
                            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                                fail(task, "AIMP 连续失败 " + consecutiveFailures + " 次，任务中止");
                                return;
                            }
                            continue;
                        }

                        // 异步轮询：同步等待会在大页数文档上触发读取超时，这里按页观察进度
                        int realPages = 0;
                        boolean corrected = false;
                        boolean timedOut = false;
                        long deadline = System.currentTimeMillis() + ASYNC_TASK_DEADLINE_MS;
                        while (true) {
                            if (task.cancelRequested) break;
                            if (System.currentTimeMillis() > deadline) {
                                timedOut = true;
                                break;
                            }
                            AimpLlmClient.TaskStatusResult st = client.getTaskStatus(r.taskId);
                            if (!st.success) {
                                if (st.error != null && st.error.startsWith("HTTP 404")) {
                                    // 任务在 AIMP 侧已不存在（如服务重启），继续轮询无意义
                                    AimpLlmClient.ExtractPageResult lost = new AimpLlmClient.ExtractPageResult();
                                    lost.error = "AIMP 任务已丢失（服务可能已重启）";
                                    r = lost;
                                    break;
                                }
                                // 网络抖动等瞬时错误：保持轮询，不中断整批任务
                                sleepQuietly(ASYNC_POLL_INTERVAL_MS);
                                continue;
                            }
                            if (st.totalPages > 0) realPages = st.totalPages;
                            if (!corrected && realPages > 1) {
                                task.totalPages = totalBefore + realPages - 1;
                                corrected = true;
                            }
                            task.processedPages = pagesBefore + st.processedPages;
                            double pdfRatio = realPages > 0
                                    ? Math.min(st.processedPages, realPages) / (double) realPages : 0d;
                            task.unitFraction = (k + pdfRatio) / Math.max(1, unit.pages.size());
                            task.message = realPages > 0
                                    ? "正在处理第 " + Math.min(st.processedPages + 1, realPages) + "/" + realPages + " 页： " + label
                                    : "正在解析： " + label;
                            if ("completed".equals(st.status)) {
                                AimpLlmClient.ExtractPageResult parsed = client.parseTaskResult(st.result);
                                parsed.pageCount = realPages > 0 ? realPages : 1;
                                r = parsed;
                                break;
                            }
                            if ("failed".equals(st.status) || "cancelled".equals(st.status)) {
                                AimpLlmClient.ExtractPageResult failed = new AimpLlmClient.ExtractPageResult();
                                failed.error = st.error != null && !st.error.isEmpty()
                                        ? st.error : "AIMP 任务" + st.status;
                                r = failed;
                                break;
                            }
                            sleepQuietly(ASYNC_POLL_INTERVAL_MS);
                        }
                        if (task.cancelRequested) break;

                        int donePages = realPages > 1 ? realPages : Math.max(1, r.pageCount);
                        if (!corrected && donePages > 1) task.totalPages = totalBefore + donePages - 1;
                        task.processedPages = pagesBefore + donePages;
                        task.unitFraction = (k + 1) / (double) Math.max(1, unit.pages.size());
                        if (timedOut) {
                            r.success = false;
                            r.error = "等待提取结果超时（" + (ASYNC_TASK_DEADLINE_MS / 60000) + " 分钟）";
                        }

                        if (r.success) {
                            consecutiveFailures = 0;
                            TitleSplitter.Piece p = new TitleSplitter.Piece();
                            int piecePages = Math.max(1, r.pageCount);
                            p.startPage = pageOffset + 1;
                            p.endPage = pageOffset + piecePages;
                            pageOffset += piecePages;
                            p.title = r.values.getOrDefault("title", "");
                            Map<String, String> pdfValues = filterLowConfidence(r.values, r.confidences);
                            // PDF 逐件路径与图片路径口径一致：件级最终值同样先规范化（称谓、占位符）
                            normalizePieceValues(pdfValues);
                            // 页级候选：AIMP 的 page_index 即该 PDF 内部页号，可直接用于浏览器 #page 定位
                            appendUnitRow(task, project, unit, pieceNo, new File(pdf).getName(), p,
                                    pdfValues, null, filterCandidates(r.candidates, pdfValues));
                        } else {
                            consecutiveFailures++;
                            task.failedPages++;
                            TitleSplitter.Piece p = new TitleSplitter.Piece();
                            p.startPage = pageOffset + 1;
                            p.endPage = pageOffset + 1;
                            pageOffset += 1;
                            appendUnitRow(task, project, unit, pieceNo, new File(pdf).getName(), p, null,
                                    r.error, null);
                            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                                fail(task, "AIMP 连续失败 " + consecutiveFailures + " 次，任务中止");
                                return;
                            }
                        }
                    }
                } else {
                    List<String> titles = new ArrayList<>();
                    List<Map<String, String>> pageValues = new ArrayList<>();
                    List<Map<String, Double>> pageConfidences = new ArrayList<>();
                    int unitFailedPages = 0;
                    if (isLitigationArchive(task)) {
                        // 诉讼档案页间彼此独立（不传页间上下文），按 PAGE_CONCURRENCY 路并发提交：
                        // 结果按页序回收后再统计，保证落行顺序与页序一致，不受完成先后影响
                        List<AimpLlmClient.ExtractPageResult> pageResults =
                                new ArrayList<>(Collections.nCopies(unit.pages.size(), null));
                        if (!extractPagesConcurrently(task, client, unit, keyList, customJson, pageResults)) {
                            if (!task.cancelRequested) {
                                fail(task, "AIMP 连续失败 " + MAX_CONSECUTIVE_FAILURES + " 次，任务中止");
                            }
                            return;
                        }
                        for (AimpLlmClient.ExtractPageResult r : pageResults) {
                            if (r == null && task.cancelRequested) break;   // 取消后未处理的页不再计数
                            boolean ok = r != null && r.success;
                            if (!ok) {
                                task.failedPages++;
                                unitFailedPages++;
                            }
                            titles.add(ok ? r.values.getOrDefault("title", "") : "");
                            pageValues.add(ok ? r.values : null);
                            pageConfidences.add(ok ? r.confidences : null);
                            // processedPages 已在并发过程中按批累加，此处不重复计数
                        }
                    } else {
                        // 其余门类页间存在上下文依赖，保持逐页串行的原有行为
                        for (int i = 0; i < unit.pages.size(); i++) {
                            String page = unit.pages.get(i);
                            if (task.cancelRequested) break;
                            task.message = "正在处理第 " + (i + 1) + "/" + unit.pages.size() + " 页： " + new File(page).getName();
                            AimpLlmClient.ExtractPageResult r = client.extractPage(page, keyList, customJson,
                                    i + 1, unit.pages.size(), buildPageContext(task, pageValues, pageConfidences));
                            if (r.success) {
                                consecutiveFailures = 0;
                            } else {
                                consecutiveFailures++;
                                task.failedPages++;
                                unitFailedPages++;
                                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                                    fail(task, "AIMP 连续失败 " + consecutiveFailures + " 次，任务中止");
                                    return;
                                }
                            }
                            if (r.success) {
                                recordPagePreview(task, unit, i, r);
                            }
                            titles.add(r.success ? r.values.getOrDefault("title", "") : "");
                            pageValues.add(r.success ? r.values : null);
                            pageConfidences.add(r.success ? r.confidences : null);
                            task.processedPages++;
                            task.unitFraction = (i + 1) / (double) Math.max(1, unit.pages.size());
                        }
                    }
                    if (task.cancelRequested) break;
                    String remark = unitFailedPages > 0 ? unitFailedPages + " 页提取失败" : null;
                    if (task.template == ExtractionTemplate.BATCH_TITLE_CASE) {
                        TitleSplitter.Piece p = new TitleSplitter.Piece();
                        p.startPage = 1;
                        p.endPage = unit.pages.size();
                        p.title = firstNonEmpty(titles);
                        MergedElements merged = accumulateByConfidence(pageValues, pageConfidences);
                        String casePieceNo = allocatePieceNumber(unit.name, usedPieceNos, lastPieceNo);
                        lastPieceNo = casePieceNo;
                        appendUnitRow(task, project, unit, casePieceNo, null, p,
                                merged.isEmpty() ? null : merged.values, remark,
                                withFileNames(merged.candidates, unit.pages));
                    } else {
                        // 诉讼档案：分件只在「卷内条目页」范围内进行。卷宗封面、卷内目录（卷前材料）
                        // 与备考表、证物袋、卷底（卷尾材料）都不是卷内条目——既不参与分件、也不写条目行，
                        // 但其提取到的卷级要素（案由、当事人、审级、结案方式、保管期限等）
                        // 会回填给各件，供整卷复用。
                        // 其他门类（如文书档案）保持原有的归并行为，不影响已测试通过的效果。
                        boolean litigationArchive = isLitigationArchive(task);
                        List<Integer> itemIndexes = litigationArchive
                                ? itemPageIndexes(unit, titles) : allPageIndexes(unit.pages.size());
                        List<String> itemTitles = new ArrayList<>(itemIndexes.size());
                        for (int idx : itemIndexes) itemTitles.add(titles.get(idx));

                        List<TitleSplitter.Piece> pieces;
                        if (litigationArchive && !itemIndexes.isEmpty()) {
                            // 卷级二次分件：页级题名只回答"这一页是什么"，判断"这几页是否属于同一件"
                            // 是卷级语义任务（如证据清单与其所列证据的实物页、公函与所附证件复印件），
                            // 字符串相似度无能为力。整卷提取完后交给 AIMP 用一次 LLM 定件，
                            // 答案以《人民法院诉讼文书立卷归档办法》的条项为准（一个条项 = 一件）。
                            // 服务端只做结构校验（起点从第 1 页起、严格递增、不超总页数），
                            // 不做件数或件名的语义纠正——分件粒度与人工口径的差异不视为错误。
                            // 逐页当事人 + 卷宗封面抽到的原/被告名单：身份证明类件要按当事人
                            // 分成「原告身份证明」「被告身份证明」（该办法第十四条(8)）；
                            // 名单整卷一份（只有封面页会返回），页当事人逐页对应
                            List<String> itemParties = new ArrayList<>(itemIndexes.size());
                            for (int idx : itemIndexes) {
                                Map<String, String> vals = idx < pageValues.size() ? pageValues.get(idx) : null;
                                itemParties.add(vals == null ? "" : vals.getOrDefault("responsible_party", ""));
                            }
                            AimpLlmClient.SplitResult split = client.splitVolumePieces(
                                    itemTitles, itemParties, firstPartyRoles(pageValues),
                                    task.archiveCategory, task.archiveSubCategory);
                            if (split.success && !split.pieces.isEmpty()) {
                                logger.info("卷 {} 卷级分件成功（{} 件）", unit.name, split.pieces.size());
                                pieces = mapToVolumeIndexes(split.pieces, itemIndexes, unit);
                            } else {
                                logger.warn("卷 {} 卷级分件不可用（{}），回退字符串相似度分件",
                                        unit.name, split.reason);
                                pieces = mapToVolumeIndexes(TitleSplitter.split(itemTitles,
                                        TitleSplitter.DEFAULT_SIMILARITY_THRESHOLD, true), itemIndexes, unit);
                            }
                        } else if (litigationArchive) {
                            // 整卷都被判为非条目页（异常输入）：退回按全部页分件，保证仍有输出
                            logger.warn("卷 {} 未识别出卷内条目页，改按全部页分件", unit.name);
                            pieces = TitleSplitter.split(
                                    titles, TitleSplitter.DEFAULT_SIMILARITY_THRESHOLD, true);
                        } else {
                            pieces = TitleSplitter.split(
                                    titles, TitleSplitter.DEFAULT_SIMILARITY_THRESHOLD, false);
                        }
                        // 审级取卷级多数票：上诉需附一审判决书、上诉案件移送函等前审材料，
                        // 卷内会同时出现两类案号，逐页推断会被前审材料拉成"一审"
                        String volumeTrialLevel = litigationArchive ? majorityTrialLevel(pageValues) : null;
                        if (logger.isInfoEnabled()) {
                            StringBuilder sb = new StringBuilder();
                            for (TitleSplitter.Piece p : pieces) {
                                sb.append(String.format("[%s]%s ", p.pageRangeLabel(),
                                        p.title == null || p.title.isEmpty() ? "(无题名)" : p.title));
                            }
                            logger.info("卷 {} 分件结果（{} 件，页号为卷内页号）: {}",
                                    unit.name, pieces.size(), sb.toString());
                        }
                        Map<String, String> volumeLevelValues = new LinkedHashMap<>();
                        // 卷级要素的候选页：回填取值时必须一并回填，否则单元格有值却找不到
                        // 「取值 → 所在页」的对应关系（前端点击无法定位到封面页）
                        Map<String, List<AimpLlmClient.ElementCandidate>> volumeLevelCandidates =
                                new LinkedHashMap<>();
                        if (litigationArchive) {
                            // 未被任何件覆盖的页即卷宗封面、卷内目录、备考表、证物袋、卷底。
                            // 它们不写条目行，但其提取到的**卷级要素**要回填给各件，供整卷复用。
                            // 只回填卷级要素（案由/当事人/审级/结案方式/保管期限/密级/开放状态，
                            // 见 ExtractionTemplate.VOLUME_LEVEL_ELEMENT_KEYS）：题名、成文日期、
                            // 责任者、文号都是**件级属性**，绝不能回填——题名回填会让单元格定位到
                            // 备考表页（实测 JZ07-2024-M2-0158/0241）；成文日期回填则会把备考表、
                            // 卷底上的日期灌给件内日期缺失的件（实测备考表 0059.jpg 的 20240425
                            // 被灌进 9 个件，卷级起止时间随之被拉成 20231020/20240606）。
                            boolean[] covered = new boolean[unit.pages.size()];
                            for (TitleSplitter.Piece p : pieces) {
                                for (int i = p.startPage - 1; i < p.endPage && i < covered.length; i++) {
                                    covered[i] = true;
                                }
                            }
                            for (int i = 0; i < unit.pages.size(); i++) {
                                if (covered[i] || pageValues.get(i) == null) continue;
                                MergedElements merged = accumulateByConfidence(
                                        Collections.singletonList(pageValues.get(i)),
                                        Collections.singletonList(pageConfidences.get(i)), i + 1);
                                merged.values.forEach((key, value) -> {
                                    if (ExtractionTemplate.isVolumeLevelElement(key)) {
                                        volumeLevelValues.put(key, value);
                                    }
                                });
                                merged.candidates.forEach((key, list) -> {
                                    if (ExtractionTemplate.isVolumeLevelElement(key)) {
                                        volumeLevelCandidates.putIfAbsent(key, list);
                                    }
                                });
                            }
                        }
                        int pieceNo = 1;
                        for (TitleSplitter.Piece p : pieces) {
                            List<Map<String, String>> pieceValues =
                                    pageValues.subList(p.startPage - 1, p.endPage);
                            MergedElements merged = accumulateByConfidence(pieceValues,
                                    pageConfidences.subList(p.startPage - 1, p.endPage), p.startPage);
                            Map<String, String> rowValues = merged.isEmpty()
                                    ? null : new LinkedHashMap<>(merged.values);
                            if (volumeTrialLevel != null || !volumeLevelValues.isEmpty()) {
                                if (rowValues == null) rowValues = new LinkedHashMap<>();
                                for (Map.Entry<String, String> e : volumeLevelValues.entrySet()) {
                                    rowValues.putIfAbsent(e.getKey(), e.getValue());
                                }
                                if (volumeTrialLevel != null) {
                                    rowValues.put("shenji", volumeTrialLevel);
                                }
                            }
                            // 件级最终值：要素先在本级规范化，卷内列与卷级汇总共用同一份值
                            normalizePieceValues(rowValues);
                            // 候选页与单元格取值保持一致：带上本件自己的候选，再补上回填进来的卷级要素候选
                            Map<String, List<AimpLlmClient.ElementCandidate>> rowCandidates =
                                    new LinkedHashMap<>(merged.candidates);
                            volumeLevelCandidates.forEach(rowCandidates::putIfAbsent);
                            appendUnitRow(task, project, unit, String.format("%03d", pieceNo), null, p,
                                    rowValues, remark,
                                    withFileNames(rowCandidates, unit.pages));
                            pieceNo++;
                        }
                    }
                }
            }
            if (task.cancelRequested && !STATUS_FAILED.equals(task.status)) {
                task.status = STATUS_CANCELLED;
                task.message = "已取消";
            } else if (!STATUS_FAILED.equals(task.status)) {
                task.status = STATUS_COMPLETED;
                task.message = "提取完成";
            }
            flushPageMap(task);
            saveProject(task);
        } catch (Exception e) {
            logger.error("Batch extraction failed", e);
            fail(task, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /**
     * 把一页的识别结果记入页级预览，取题名与当事人（已过滤低置信度取值，避免把幻觉显示出来）。
     * 件级行要等整卷抽完并分件后才写，长卷期间这就是用户唯一能看到的内容。
     */
    private static void recordPagePreview(Task task, UnitScanner.Volume unit, int pageIndex,
                                          AimpLlmClient.ExtractPageResult r) {
        // 副本 + 件级同款规范化：预览也不显示「原告XXX」这类占位值
        Map<String, String> values = new LinkedHashMap<>(filterLowConfidence(r.values, r.confidences));
        normalizePieceValues(values);
        task.recordPagePreview(pageIndex + 1, new File(unit.pages.get(pageIndex)).getName(), values);
    }

    /**
     * 诉讼档案按 {@link #PAGE_CONCURRENCY} 路并发调用 AIMP：分批提交（批内并行、批间按页序），
     * 结果按页序写入 results，保证落行顺序与页序一致、不受完成先后影响。
     * 仅在页间无依赖时使用（诉讼档案不传页间上下文）。
     *
     * @return false 表示出现连续失败应中止本任务；用户取消时返回 true
     */
    private boolean extractPagesConcurrently(Task task, AimpLlmClient client, UnitScanner.Volume unit,
                                             String keyList, String customJson,
                                             List<AimpLlmClient.ExtractPageResult> results) {
        int total = unit.pages.size();
        ExecutorService pool = Executors.newFixedThreadPool(PAGE_CONCURRENCY, r -> {
            Thread t = new Thread(r, "aimp-page-extract");
            t.setDaemon(true);
            return t;
        });
        try {
            int consecutiveFailures = 0;
            for (int start = 0; start < total; start += PAGE_CONCURRENCY) {
                if (task.cancelRequested) return true;
                int end = Math.min(total, start + PAGE_CONCURRENCY);
                // 口径与预览对齐：顶部同时给出「已完成页数」与正在提取的范围。只写「正在提取 33-36」
                // 会让人拿它去比「预览到 32」而以为差了一档——其实前者是「正在做」，后者是「已完成」。
                task.message = "已完成 " + start + " / " + total + " 页，正在提取第 "
                        + (start + 1) + "-" + end + " 页";
                long batchStart = System.currentTimeMillis();
                List<Future<AimpLlmClient.ExtractPageResult>> futures = new ArrayList<>(end - start);
                for (int i = start; i < end; i++) {
                    final int pageIndex = i;
                    final String pagePath = unit.pages.get(i);
                    futures.add(pool.submit(() -> {
                        try {
                            // 页间无依赖，不传已提取信息（传 null）
                            return client.extractPage(pagePath, keyList, customJson,
                                    pageIndex + 1, total, null);
                        } catch (Exception e) {
                            logger.warn("第 {} 页提取异常", pageIndex + 1, e);
                            return null;
                        }
                    }));
                }
                int inferredPages = 0;   // 本批真正走 LLM 的页数（跳过页不消耗推理，不进摊分分母）
                for (int i = start; i < end; i++) {
                    AimpLlmClient.ExtractPageResult r;
                    try {
                        r = futures.get(i - start).get();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return true;
                    } catch (ExecutionException ee) {
                        logger.warn("第 {} 页提取失败", i + 1, ee.getCause());
                        r = null;
                    }
                    results.set(i, r);
                    if (r != null && r.success) {
                        // 回收一页就记一条预览，前端轮询即可看到最新识别结果，不必等整卷
                        recordPagePreview(task, unit, i, r);
                        if (!r.skipped) inferredPages++;
                    }
                    consecutiveFailures = (r != null && r.success) ? 0 : consecutiveFailures + 1;
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        return false;
                    }
                }
                // 进度上报：并发时逐页 ++ 要等整卷回收完才生效，改为按批累加，前端页数才能实时前进
                task.processedPages += (end - start);
                task.unitFraction = end / (double) Math.max(1, total);
                // 记批耗时：批内并发，整批耗时摊到「真正推理的页数」才是平均每页耗时。
                // 分类器跳过 / OCR 文本过短的页毫秒级返回，若按批页数摊分，连续跳过的批会把平均值
                // 拉得远小于真实速度，前端揭示节奏忽快忽慢、队列抽干后停在原地等下一批。
                // 整批全跳过（inferredPages=0）时 recordPageTiming 直接忽略，不记账。
                task.recordPageTiming(System.currentTimeMillis() - batchStart, inferredPages);
            }
            return true;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 页间上下文：诉讼类档案不传，其余门类沿用逐页累积。
     *
     * 诉讼档案卷宗页序固定（卷宗封面→卷内目录→各件文书→卷底），卷级要素（案由、当事人、
     * 审级、结案方式、保管期限等）由封面页提取后回填到各件，各件只需按本页内容提取。
     * 实测保留封面页上下文时，模型会把封面页的卷级要素在每一页复述一遍（连 OCR 仅十余字符的
     * 空白页也会输出"维持原判""永久"），既浪费输出 token 又污染数据，因此改为完全不传。
     * 页间也因此彼此独立，为按页并发提交留出空间。文书档案等既有场景保持原行为。
     */
    private String buildPageContext(Task task, List<Map<String, String>> pageValues,
                                    List<Map<String, Double>> pageConfidences) {
        if (isLitigationArchive(task)) {
            return null;
        }
        return buildPreviousExtractions(pageValues, pageConfidences);
    }

    /**
     * 卷尾无题名页的兜底上限。卷尾不计页材料共 3 类（备考表、证物袋、卷底），故取 3：
     * 结尾连续无题名的页数若超过它，说明不是卷尾材料而是某份长文书的正文续页，不做剔除。
     */
    private static final int MAX_TRAILING_BLANK_PAGES = 3;

    /**
     * 卷宗封面、卷内目录、卷内备考表、卷底等：按《人民法院诉讼档案管理办法》（法〔2013〕283号）
     * 第十九条「卷宗封面、卷内目录、备考表、证物袋、封底不编页号」，它们属案卷装订件而非卷内文件，
     * 不列入条目。著录过程照常进行——其提取到的卷级要素会回填给各件——但不写入表格行。
     */

    private static final Set<String> NON_ITEM_TITLES = new HashSet<>(Arrays.asList(
            "卷宗封面", "案卷封面", "卷内目录", "卷内备考表", "备考表",
            "证物袋", "卷底", "封底", "空白封底"));

    private static boolean isNonItemPiece(String title) {
        return title != null && NON_ITEM_TITLES.contains(title.trim());
    }

    /**
     * 「卷内条目页」在 {@code unit.pages} 中的下标（0 起）。
     *
     * 诉讼档案里有两类页不属于卷内条目，必须排除在分件之外——否则会把"卷宗封面""卷内目录"
     * 当成件，并让后续页序整体错位：
     * 1) 卷前材料：卷宗封面、卷内目录，文件名形如 0000-NN（不计卷内页号）；
     * 2) 卷尾材料：备考表、证物袋、卷底，排在卷末、不列入条目。
     * 本卷文件名若都是普通命名（解析不出卷内页号），则只按题名排除，行为与改造前一致。
     */
    private static List<Integer> itemPageIndexes(UnitScanner.Volume unit, List<String> titles) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < unit.pages.size(); i++) {
            if (unit.numberedPages && i < unit.pageNos.size() && unit.pageNos.get(i) == null) continue;
            if (i < titles.size() && isNonItemPiece(titles.get(i))) continue;
            indexes.add(i);
        }
        if (indexes.size() <= 1) return indexes;

        // 卷尾不计页材料（备考表、证物袋、卷底）在扫描件里常是空白页或纯表格页，识别不出题名，
        // 只按题名拦不住，会整段并入最后一件——实测 JZ07-2024-M2-0158 的"退卷函回执"件被撑到
        // 4 页，而它的真身只有"退卷函稿 + 退卷函回执"2 页。故按位置兜底：把结尾处连续的无题名页
        // 判为卷尾材料。
        // 只兜底"连续不超过 MAX_TRAILING_BLANK_PAGES 页"的情形：长文书（判决书、笔录）的正文
        // 续页同样无题名，但它们必然是连续多页，页数超过这个上限，不会被误切。
        int trailing = 0;
        while (trailing < indexes.size() - 1 && !isTitled(titles, indexes.get(indexes.size() - 1 - trailing))) {
            trailing++;
        }
        if (trailing == 0 || trailing > MAX_TRAILING_BLANK_PAGES) return indexes;
        return new ArrayList<>(indexes.subList(0, indexes.size() - trailing));
    }

    private static boolean isTitled(List<String> titles, int index) {
        String title = index >= 0 && index < titles.size() ? titles.get(index) : null;
        return title != null && !title.trim().isEmpty();
    }

    /** 卷宗封面页抽到的原/被告名单（JSON 字符串）：整卷只有封面页带该字段，取第一个非空值。 */
    private static String firstPartyRoles(List<Map<String, String>> pageValues) {
        if (pageValues == null) return null;
        for (Map<String, String> values : pageValues) {
            if (values == null) continue;
            String roles = values.get("party_roles");
            if (roles != null && !roles.trim().isEmpty()) return roles;
        }
        return null;
    }

    private static List<Integer> allPageIndexes(int pageCount) {
        List<Integer> indexes = new ArrayList<>(pageCount);
        for (int i = 0; i < pageCount; i++) indexes.add(i);
        return indexes;
    }

    /**
     * 把「条目页序号」空间的件区间映射回 {@code unit.pages} 的列表序号，并补上卷内页号。
     * 分件（卷级 LLM 或字符串相似度）只看到条目页，其页序与卷内页号都不是列表序号，
     * 落行、切页区间、单元格定位都以列表序号为准，故必须映射一次。
     */
    private static List<TitleSplitter.Piece> mapToVolumeIndexes(List<TitleSplitter.Piece> items,
                                                                List<Integer> itemIndexes,
                                                                UnitScanner.Volume unit) {
        List<TitleSplitter.Piece> pieces = new ArrayList<>(items.size());
        for (TitleSplitter.Piece p : items) {
            int startIdx = itemIndexes.get(clampIndex(p.startPage - 1, itemIndexes.size()));
            int endIdx = itemIndexes.get(clampIndex(p.endPage - 1, itemIndexes.size()));
            TitleSplitter.Piece mapped = new TitleSplitter.Piece();
            mapped.startPage = startIdx + 1;
            mapped.endPage = endIdx + 1;
            mapped.title = p.title;
            mapped.startPageNo = pageNoAt(unit, startIdx);
            mapped.endPageNo = pageNoAt(unit, endIdx);
            pieces.add(mapped);
        }
        return pieces;
    }

    private static int clampIndex(int index, int size) {
        if (index < 0) return 0;
        return Math.min(index, size - 1);
    }

    private static Integer pageNoAt(UnitScanner.Volume unit, int index) {
        if (!unit.numberedPages || index < 0 || index >= unit.pageNos.size()) return null;
        return unit.pageNos.get(index);
    }

    /**
     * 卷级审级：卷内可能同时含前审（一审）与本案（二审）案号——上诉需附一审判决书、
     * 上诉案件移送函等前审材料，按页推断会得到"一审"。按出现次数取多数，可稳定得到本案审级。
     *
     * @return 出现次数最多的审级；无审级要素或无有效值时返回 null
     */
    private static String majorityTrialLevel(List<Map<String, String>> pageValues) {
        Map<String, Integer> counts = new HashMap<>();
        for (Map<String, String> values : pageValues) {
            if (values == null) continue;
            String level = values.get("shenji");
            if (level != null && !level.trim().isEmpty()) {
                counts.merge(level.trim(), 1, Integer::sum);
            }
        }
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    /** 是否诉讼类档案（人民法院诉讼档案／人民检察院诉讼档案等以"诉讼档案"结尾的二级类别） */
    private static boolean isLitigationArchive(Task task) {
        String sub = task.archiveSubCategory;
        return sub != null && sub.trim().endsWith("诉讼档案");
    }

    private String buildPreviousExtractions(List<Map<String, String>> pageValues,
                                           List<Map<String, Double>> pageConfidences) {
        if (pageValues == null || pageValues.isEmpty()) return null;
        ArrayNode arr = mapper.createArrayNode();
        for (int i = 0; i < pageValues.size(); i++) {
            Map<String, String> values = pageValues.get(i);
            if (values == null || values.isEmpty()) continue;
            Map<String, Double> confs = pageConfidences != null && i < pageConfidences.size()
                    ? pageConfidences.get(i) : null;
            ObjectNode elements = mapper.createObjectNode();
            for (Map.Entry<String, String> e : values.entrySet()) {
                String v = e.getValue();
                if (v == null || v.trim().isEmpty()) continue;
                ObjectNode node = elements.putObject(e.getKey()).put("value", v);
                Double conf = confs == null ? null : confs.get(e.getKey());
                if (conf != null) node.put("confidence", conf);
            }
            if (elements.size() == 0) continue;
            ObjectNode pageNode = mapper.createObjectNode();
            pageNode.put("page_index", i + 1);
            pageNode.set("elements", elements);
            arr.add(pageNode);
        }
        return arr.size() == 0 ? null : arr.toString();
    }

    /**
     * @param fileName 该行对应的具体文件名（PDF 模式为单个 PDF 文件名）；整目录成件时为 null，
     *                 前端据此把「文件夹路径 + 文件名」拼成可直达的文件资源
     * @param candidates 各要素在页级的取值候选（AIMP 要素 key 维度），用于建立单元格到页的定位关联
     */
    private void appendUnitRow(Task task, Project project, UnitScanner.Volume unit, String pieceNo,
                               String fileName, TitleSplitter.Piece piece, Map<String, String> values,
                               String remark, Map<String, List<AimpLlmClient.ElementCandidate>> candidates) {
        String status;
        if (values == null) {
            status = "失败";
            if (remark == null || remark.isEmpty()) remark = "提取失败";
        } else if (remark != null && !remark.isEmpty()) {
            status = "部分成功";
        } else {
            status = "成功";
        }
        // 案卷级项目固定写入「卷内」表，避免用户切换 sheet 后把行写进「卷级」表
        ColumnModel columnModel = task.innerSheet != null ? task.innerSheet.columnModel : project.columnModel;
        Row row = new Row(columnModel.getMaxCellIndex() + 1);
        String responsibleParty = get(values, "responsible_party");
        // 无署名材料（证物处理单等表格）：本身没有责任者栏，留空，不取正文提到的当事人／机关名
        if (isNoResponsibleAuthorPiece(piece.title)) {
            responsibleParty = "";
        }
        String documentDate = normalizeDate(get(values, "date"));
        // 证件类件（身份证、户口簿、营业执照等）：证面上的签发日期不是文书形成日期，
        // 著录它会把整卷起始时间拉到证件签发那年，故该件成文日期留空（见 docs 3.4 决定五）
        if (isCertificatePiece(piece.title)) {
            documentDate = "";
        }
        // 件号在调用前已按“文件名解析 + 卷内去重”分配好
        if (task.template == ExtractionTemplate.BATCH_TITLE_VOLUME) {
            put(columnModel, row, "案卷号", unit.name);
            put(columnModel, row, "件号", pieceNo);
            put(columnModel, row, "起止页号", piece.pageRangeLabel());
        } else {
            put(columnModel, row, "文件夹名", unit.name);
            put(columnModel, row, "件号", pieceNo);
        }
        put(columnModel, row, "页数", piece.pageCount());
        put(columnModel, row, "题名", piece.title);
        put(columnModel, row, "责任者", responsibleParty);
        put(columnModel, row, "文号", get(values, "document_number"));
        put(columnModel, row, "成文日期", documentDate);
        put(columnModel, row, "文件名", fileName == null ? "" : fileName);
        put(columnModel, row, ExtractionTemplate.FOLDER_PATH_COLUMN, unit.path);
        put(columnModel, row, "提取状态", status);
        put(columnModel, row, "备注", remark == null ? "" : remark);
        for (CustomElementType ce : task.customElements) {
            if (!ce.isInclude()) continue;
            // 案卷模板下卷级要素只在「卷级」表出列（见 docs 3.4），卷内表没有该列；文件级单表模板不受影响
            if (task.innerSheet != null && ExtractionTemplate.isVolumeLevelElement(ce.getKey())) continue;
            put(columnModel, row, ce.getName(), get(values, ce.getKey()));
        }
        // 案卷模板的件名照录件首页题名（整目录成件模板则取首个非空题名），只有案卷模板需要对齐定位
        if (task.template == ExtractionTemplate.BATCH_TITLE_VOLUME) {
            prioritizePieceTitle(candidates, piece, unit);
        }
        int rowIndex = appendRow(task, project, row);
        ObjectNode pieceStart = pieceStartNode(unit, piece);
        accumulateVolumeSummary(task, unit, piece, responsibleParty, documentDate, values,
                candidates, pieceStart);
        recordPageMap(task, rowIndex, candidates, pieceStart);
        // 每落一行就刷新内存中的页映射：前端在任务进行中轮询时也能定位到正确页。
        // 此前只在每 SAVE_EVERY_ROWS 行落盘时顺带刷新，中间态缺一段映射，前端点击会退化为
        // "第 1 页"（实测：第一卷已出结果、第二卷仍在提取时，第一卷中段各件点击均回到第一页，
        // 两卷都提完、映射补齐后再点又正常）。这里只更新内存元数据，落盘仍按批量进行。
        flushPageMap(task);
    }

    private void accumulateVolumeSummary(Task task, UnitScanner.Volume unit, TitleSplitter.Piece piece,
                                         String responsibleParty, String documentDate,
                                         Map<String, String> values,
                                         Map<String, List<AimpLlmClient.ElementCandidate>> candidates,
                                         ObjectNode pieceStart) {
        if (task.summarySheet == null) return;
        VolumeSummary summary = task.volumeSummaries.get(unit.name);
        if (summary == null) {
            summary = new VolumeSummary(unit.name, unit.path);
            task.volumeSummaries.put(unit.name, summary);
        }
        summary.add(responsibleParty, documentDate, piece.pageCount(), piece.title,
                values, candidates, pieceStart);
    }

    /** 把该行各抽取要素的候选页按列名写入项目 metadata，供前端点击单元格时定位到取值所在页 */
    private void recordPageMap(Task task, int rowIndex,
                               Map<String, List<AimpLlmClient.ElementCandidate>> candidates,
                               ObjectNode pieceStart) {
        ObjectNode rowNode = mapper.createObjectNode();
        if (candidates != null && !candidates.isEmpty()) {
            recordCandidates(rowNode, candidates, "responsible_party", "责任者");
            recordCandidates(rowNode, candidates, "document_number", "文号");
            recordCandidates(rowNode, candidates, "date", "成文日期");
            recordCandidates(rowNode, candidates, "title", "题名");
            for (CustomElementType ce : task.customElements) {
                // 卷级要素在「卷级」表出列，卷内行不再建立它们的页定位（见 docs 3.4）
                if (!ce.isInclude()) continue;
                if (task.innerSheet != null && ExtractionTemplate.isVolumeLevelElement(ce.getKey())) continue;
                recordCandidates(rowNode, candidates, ce.getKey(), ce.getName());
            }
        }
        // 件首页与要素候选分开存：提取失败的件没有要素候选，但仍应能点击定位到件首页
        if (pieceStart != null) {
            rowNode.set(PIECE_START_KEY, pieceStart);
        }
        if (rowNode.size() == 0) return;
        synchronized (task.pageMap) {
            task.pageMap.set(String.valueOf(rowIndex), rowNode);
        }
    }

    /**
     * 该件首页在页映射中的节点（{v,p,f}）。整目录成件的图片批次按件起始页取到对应文件名，
     * 供前端点击「要素以外的行」时定位到件首页。PDF 模式每行本就是一个 PDF 文件、件起始页是
     * 卷内全局页号（与 PDF 内部页号不同），前端已按「文件名」列定位，故不生成。
     */
    private static ObjectNode pieceStartNode(UnitScanner.Volume unit, TitleSplitter.Piece piece) {
        if (unit.pdfMode || piece == null) return null;
        int start = piece.startPage;
        if (start < 1 || start > unit.pages.size()) return null;
        ObjectNode node = mapper.createObjectNode();
        node.put("v", "件首页");
        node.put("p", start);
        node.put("f", new File(unit.pages.get(start - 1)).getName());
        return node;
    }

    private static void recordCandidates(ObjectNode rowNode,
                                         Map<String, List<AimpLlmClient.ElementCandidate>> candidates,
                                         String key, String columnName) {
        List<AimpLlmClient.ElementCandidate> list = candidates.get(key);
        if (list == null || list.isEmpty()) return;
        writeCandidates(rowNode, columnName, list);
    }

    /**
     * 把候选页列表写成前端可读的 `[{v:取值, c:置信度, p:页码, f:文件名}]` 数组。
     *
     * 写入前按页号升序排序：页级抽取是 4 路并发，结果回收顺序随各批完成先后变化，
     * 若直接按回收顺序写入，同一卷两次跑的候选顺序会不同（取值相同、次序不同），
     * 让「取值所在页」与件级择优产生无意义抖动。排序后同一卷的页映射逐字段可复现。
     */
    private static void writeCandidates(ObjectNode rowNode, String columnName,
                                        List<AimpLlmClient.ElementCandidate> list) {
        ArrayNode arr = rowNode.putArray(columnName);
        List<AimpLlmClient.ElementCandidate> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparingInt(c -> c.page));
        for (AimpLlmClient.ElementCandidate c : sorted) {
            ObjectNode node = arr.addObject();
            node.put("v", c.value);
            if (c.confidence != null) node.put("c", c.confidence);
            node.put("p", c.page);
            if (c.fileName != null) node.put("f", c.fileName);
        }
    }

    /**
     * 整目录成件的图片批次：给候选页补上该页对应的文件名。
     * 目录列举接口按字典序返回，与扫描时的自然序不一致，前端按文件名定位才可靠。
     */
    private static Map<String, List<AimpLlmClient.ElementCandidate>> withFileNames(
            Map<String, List<AimpLlmClient.ElementCandidate>> candidates, List<String> pages) {
        Map<String, List<AimpLlmClient.ElementCandidate>> named = new HashMap<>();
        if (candidates == null) return named;
        for (Map.Entry<String, List<AimpLlmClient.ElementCandidate>> e : candidates.entrySet()) {
            List<AimpLlmClient.ElementCandidate> list = new ArrayList<>();
            for (AimpLlmClient.ElementCandidate c : e.getValue()) {
                int index = c.page - 1;
                String fileName = index >= 0 && index < pages.size()
                        ? new File(pages.get(index)).getName() : null;
                list.add(new AimpLlmClient.ElementCandidate(c.value, c.confidence, c.page, fileName));
            }
            named.put(e.getKey(), list);
        }
        return named;
    }

    /**
     * 把「题名」的默认定位页固定为件首页。
     *
     * 件名照录件首页的页级题名（见 expand_volume_pieces），定位也必须回到件首页；但件内第 2 页起
     * 识别出的其它标题（后续材料页、证件页、附件页）置信度可能更高，会把它挤到候选列表后面，
     * 前端点击题名于是跳到了后续页——实测 SZ05-2025-M1-4821 的 0023-0024「被告举证材料」点题名
     * 跳到了 0024（题名候选首位成了 0024 上的"轴承样品提交说明"）、0009-0012「被告身份证明」
     * 跳到了 0012。件首页没有题名候选时（件名由卷级模型兜底，如按当事人切出的「原告身份证明」）
     * 补一条，保证定位仍落在件首页。
     */
    private static void prioritizePieceTitle(
            Map<String, List<AimpLlmClient.ElementCandidate>> candidates,
            TitleSplitter.Piece piece, UnitScanner.Volume unit) {
        if (candidates == null || piece == null || unit.pdfMode) return;
        List<AimpLlmClient.ElementCandidate> titles = candidates.get("title");
        if (titles == null) titles = new ArrayList<>();
        for (int i = 1; i < titles.size(); i++) {
            if (titles.get(i).page == piece.startPage) {
                titles.add(0, titles.remove(i));
                candidates.put("title", titles);
                return;
            }
        }
        if (!titles.isEmpty() && titles.get(0).page == piece.startPage) return;
        String title = piece.title == null ? "" : piece.title.trim();
        if (title.isEmpty()) return;
        int index = piece.startPage - 1;
        String fileName = index >= 0 && index < unit.pages.size()
                ? new File(unit.pages.get(index)).getName() : null;
        titles.add(0, new AimpLlmClient.ElementCandidate(title, null, piece.startPage, fileName));
        candidates.put("title", titles);
    }

    private int appendRow(Task task, Project project, Row row) {
        synchronized (rowLock) {
            List<Row> targetRows = task.innerSheet != null ? task.innerSheet.rows : project.rows;
            int rowIndex = targetRows.size();
            targetRows.add(row);
            task.rowsAppended++;
            if (++rowsSinceSave >= SAVE_EVERY_ROWS) {
                rowsSinceSave = 0;
                flushPageMap(task);
                saveProject(task);
            }
            return rowIndex;
        }
    }

    private void flushPageMap(Task task) {
        synchronized (task.pageMap) {
            if (task.pageMap.size() == 0) return;
            Project project = ProjectManager.singleton.getProject(task.projectId);
            if (project == null) return;
            project.getMetadata().setCustomMetadata(PAGE_MAP_KEY, task.pageMap.toString());
        }
    }

    private void saveProject(Task task) {
        try {
            rebuildVolumeSummary(task);
            ProjectManager.singleton.ensureProjectSaved(task.projectId);
        } catch (Exception e) {
            logger.warn("Failed to save project {}", task.projectId, e);
        }
    }

    /** 用累加器整体重建卷级汇总表，中途取消或失败也保留已抽部分的汇总 */
    void rebuildVolumeSummary(Task task) {
        SheetData summarySheet = task.summarySheet;
        if (summarySheet == null) return;
        ColumnModel columnModel = summarySheet.columnModel;
        List<Row> rows = summarySheet.rows;
        rows.clear();
        int rowIndex = 0;
        for (VolumeSummary summary : task.volumeSummaries.values()) {
            // 先按件级汇总落定卷级要素，卷级题名依赖其中的当事人、案由、审级
            summary.resolveElements();
            Row row = new Row(columnModel.getMaxCellIndex() + 1);
            put(columnModel, row, "案卷号", summary.caseNo);
            put(columnModel, row, "题名", summary.volumeTitle());
            // 案卷级只著录主要责任者（DA/T 18—2022 8.1.1.2）：本卷立卷法院；
            // 识别不出立卷法院时退回卷内各件责任者全集，避免整列留空
            String court = summary.volumeCourt();
            put(columnModel, row, "责任者", court != null ? court
                    : String.join("，", summary.responsibleParties));
            put(columnModel, row, "起始时间", summary.minDate == null ? "" : summary.minDate);
            put(columnModel, row, "终止时间", summary.maxDate == null ? "" : summary.maxDate);
            put(columnModel, row, "总页数", summary.totalPages);
            put(columnModel, row, "卷内文件份数", summary.pieceCount);
            put(columnModel, row, ExtractionTemplate.FOLDER_PATH_COLUMN, summary.folderPath);
            // 卷级要素列：整卷同值，缺值留空（如密级、保管期限页面无字样时）
            for (CustomElementType ce : task.customElements) {
                if (!ce.isInclude() || !ExtractionTemplate.isVolumeLevelElement(ce.getKey())) continue;
                String value = summary.volumeElements.get(ce.getKey());
                put(columnModel, row, ce.getName(), value == null ? "" : value);
            }
            rows.add(row);
            recordSummaryPageMap(task, rowIndex, summary);
            rowIndex++;
        }
    }

    /**
     * 把「卷级」表该行各要素的来源页写入页映射，键为「表id:行号」——两张表行号都从 0 起，
     * 不加前缀会与「卷内」行互相串页。前端按当前活动表选键，点击行为与卷内一致：
     * 要素列跳到该取值第一个来源页，其余来源页在面板底部以页签列出；非要素列跳到本卷首件首页。
     */
    private void recordSummaryPageMap(Task task, int rowIndex, VolumeSummary summary) {
        ObjectNode rowNode = mapper.createObjectNode();
        for (CustomElementType ce : task.customElements) {
            if (!ce.isInclude() || !ExtractionTemplate.isVolumeLevelElement(ce.getKey())) continue;
            List<AimpLlmClient.ElementCandidate> list = summary.pageSourcesFor(ce.getKey());
            if (list.isEmpty()) continue;
            writeCandidates(rowNode, ce.getName(), list);
        }
        // 起始／终止时间：点回取到该成文日期的那几页，而不是笼统跳到本卷首件首页
        List<AimpLlmClient.ElementCandidate> startSources = summary.dateSourcesFor(summary.minDate);
        if (!startSources.isEmpty()) {
            writeCandidates(rowNode, "起始时间", startSources);
        }
        List<AimpLlmClient.ElementCandidate> endSources = summary.dateSourcesFor(summary.maxDate);
        if (!endSources.isEmpty()) {
            writeCandidates(rowNode, "终止时间", endSources);
        }
        if (summary.firstPieceStart != null) {
            rowNode.set(PIECE_START_KEY, summary.firstPieceStart);
        }
        if (rowNode.size() == 0) return;
        synchronized (task.pageMap) {
            task.pageMap.set(SUMMARY_PAGE_MAP_PREFIX + rowIndex, rowNode);
        }
    }

    private void put(ColumnModel columnModel, Row row, String columnName, java.io.Serializable value) {
        Column column = columnModel.getColumnByName(columnName);
        if (column != null) {
            row.setCell(column.getCellIndex(), new Cell(value, null));
        }
    }

    private String get(Map<String, String> values, String key) {
        if (values == null) return "";
        String v = values.get(key);
        return v == null ? "" : v;
    }

    private String firstNonEmpty(List<String> titles) {
        for (String t : titles) {
            if (t != null && !t.trim().isEmpty()) return t.trim();
        }
        return "";
    }

    static Map<String, String> accumulate(List<Map<String, String>> pageValues) {
        return accumulateByConfidence(pageValues, null).values;
    }

    /** 跨页累积结果：values 与 confidences 一一对应，且只保留达到置信度下限的要素 */
    static class MergedElements {
        final Map<String, String> values = new HashMap<>();
        final Map<String, Double> confidences = new HashMap<>();
        /** 各要素在各页的取值候选，首位为最终写入单元格的取值所在页 */
        final Map<String, List<AimpLlmClient.ElementCandidate>> candidates = new HashMap<>();
        /** 成文日期取值的加权得分：日期按「文本位置档位 + 件内页位置」择优，需记住当前胜出分 */
        final Map<String, Double> dateScores = new HashMap<>();

        boolean isEmpty() {
            return values.isEmpty();
        }
    }

    /**
     * 跨页累积：同一要素取置信度最高的页面取值，低于 {@link #MIN_ELEMENT_CONFIDENCE}
     * 的取值视为幻觉（如顶部归档章乱码）直接丢弃，其余页面若置信度更高则覆盖。
     * 页面未返回置信度时按首个非空值保底，避免整列丢空。
     * 例外：成文日期不按模型自评置信度直接择优，而按「文本位置档位（AIMP 重估）+ 件内页位置加分」
     * 加权择优（见 isDateKey 处注释）。
     */
    static MergedElements accumulateByConfidence(List<Map<String, String>> pageValues,
                                                 List<Map<String, Double>> pageConfidences) {
        return accumulateByConfidence(pageValues, pageConfidences, 1);
    }

    /**
     * @param startPage 首个元素在所在件（文件夹 / PDF）内的 1 起页序，
     *                  案卷模板按件切分页区间时用它还原绝对页号
     */
    static MergedElements accumulateByConfidence(List<Map<String, String>> pageValues,
                                                 List<Map<String, Double>> pageConfidences,
                                                 int startPage) {
        MergedElements merged = new MergedElements();
        for (int i = 0; i < pageValues.size(); i++) {
            Map<String, String> pv = pageValues.get(i);
            if (pv == null) continue;
            Map<String, Double> pc = pageConfidences != null && i < pageConfidences.size()
                    ? pageConfidences.get(i) : null;
            for (Map.Entry<String, String> e : pv.entrySet()) {
                String key = e.getKey();
                String v = e.getValue();
                if (v == null || v.trim().isEmpty()) continue;
                Double conf = pc == null ? null : pc.get(key);
                if (conf != null && conf < MIN_ELEMENT_CONFIDENCE) continue;
                merged.candidates.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new AimpLlmClient.ElementCandidate(v.trim(), conf, startPage + i));
                if (isDateKey(key)) {
                    // 成文日期独立择优：置信度已由 AIMP 按「文本一致性 + 是否独立成行」重估为
                    // 高/中/低三档（落款与标题下方的日期独立成行 -> 高；正文中叙述的日期 -> 中；
                    // 原文无据的幻觉 -> 低），这里再叠加**件内页位置**加分：文书头尾的日期才是
                    // 成文日期，中部的多是叙述性日期（见 docs 3.4 决定七）。
                    double base = conf == null ? 0.5 : conf;
                    boolean edgePage = i == 0 || i == pageValues.size() - 1;
                    double score = base + (edgePage ? DATE_EDGE_PAGE_BONUS : 0.0);
                    Double bestScore = merged.dateScores.get(key);
                    if (bestScore == null || score > bestScore) {
                        merged.values.put(key, v.trim());
                        merged.dateScores.put(key, score);
                        if (conf != null) {
                            merged.confidences.put(key, conf);
                        }
                    }
                    continue;
                }
                if (conf == null) {
                    if (!merged.values.containsKey(key)) merged.values.put(key, v.trim());
                    continue;
                }
                Double best = merged.confidences.get(key);
                if (best == null || conf > best) {
                    merged.values.put(key, v.trim());
                    merged.confidences.put(key, conf);
                }
            }
        }
        orderCandidates(merged.candidates, merged.values);
        return merged;
    }

    /** 成文日期的要素键：调用方侧统一为 date，模型偶尔回吐 issue_date */
    private static boolean isDateKey(String key) {
        return "date".equals(key) || "issue_date".equals(key);
    }

    /** 只保留最终写入单元格的要素的候选页 */
    private static Map<String, List<AimpLlmClient.ElementCandidate>> filterCandidates(
            Map<String, List<AimpLlmClient.ElementCandidate>> candidates, Map<String, String> winners) {
        Map<String, List<AimpLlmClient.ElementCandidate>> kept = new HashMap<>();
        if (candidates == null || winners == null) return kept;
        for (Map.Entry<String, List<AimpLlmClient.ElementCandidate>> e : candidates.entrySet()) {
            if (!winners.containsKey(e.getKey())) continue;
            List<AimpLlmClient.ElementCandidate> list = new ArrayList<>();
            for (AimpLlmClient.ElementCandidate c : e.getValue()) {
                if (c.confidence != null && c.confidence < MIN_ELEMENT_CONFIDENCE) continue;
                list.add(c);
            }
            if (!list.isEmpty()) kept.put(e.getKey(), list);
        }
        orderCandidates(kept, winners);
        return kept;
    }

    /** 候选页按置信度降序，并把最终写入单元格的取值提到最前，作为前端默认定位页 */
    private static void orderCandidates(Map<String, List<AimpLlmClient.ElementCandidate>> candidates,
                                        Map<String, String> winners) {
        for (Map.Entry<String, List<AimpLlmClient.ElementCandidate>> e : candidates.entrySet()) {
            List<AimpLlmClient.ElementCandidate> list = e.getValue();
            list.sort((a, b) -> Double.compare(confidenceOf(b), confidenceOf(a)));
            String winner = winners == null ? null : winners.get(e.getKey());
            if (winner == null) continue;
            for (int i = 0; i < list.size(); i++) {
                if (winner.equals(list.get(i).value)) {
                    if (i > 0) list.add(0, list.remove(i));
                    break;
                }
            }
        }
    }

    private static double confidenceOf(AimpLlmClient.ElementCandidate candidate) {
        return candidate.confidence == null ? -1d : candidate.confidence;
    }

    /** 单次多页结果（AIMP 内部已按置信度选优）同样丢弃低置信度要素 */
    static Map<String, String> filterLowConfidence(Map<String, String> values, Map<String, Double> confidences) {
        if (values == null || values.isEmpty() || confidences == null || confidences.isEmpty()) return values;
        Map<String, String> kept = new HashMap<>(values);
        kept.keySet().removeIf(k -> {
            Double c = confidences.get(k);
            return c != null && c < MIN_ELEMENT_CONFIDENCE;
        });
        return kept;
    }

    /** 件内含多个文件时补件内序号，便于区分同一件中的第几个 PDF */
    private String fileLabel(String fileName, int index, int total) {
        return total > 1 ? fileName + "（第 " + index + "/" + total + " 个）" : fileName;
    }

    /**
     * 分配件号：优先取文件名中「从后往前的 3 位、0 开头的数字子串」
     * （如 "立鼎行发（2024-004）号.pdf" → "004"）；
     * 取不到、或与该案卷/文件夹内已用件号冲突时，以「前一件号」为基准追加 -1、-2 … 递增。
     *
     * @param used 当前案卷/文件夹内已占用的件号，调用方维护
     * @param lastPieceNo 当前案卷/文件夹内上一件最终使用的件号，无则为 null
     */
    private String allocatePieceNumber(String fileName, Set<String> used, String lastPieceNo) {
        String candidate = extractTrailingZeroPrefixed3(fileName);
        if (candidate != null && !used.contains(candidate)) {
            used.add(candidate);
            return candidate;
        }
        String base = lastPieceNo != null
                ? stripConflictSuffix(lastPieceNo) : String.format("%03d", used.size() + 1);
        // 首件无前一件可参照时，直接用顺序号，不再叠加冲突后缀
        if (!used.contains(base)) {
            used.add(base);
            return base;
        }
        for (int n = 1; n < 10000; n++) {
            String alt = base + "-" + n;
            if (!used.contains(alt)) {
                used.add(alt);
                return alt;
            }
        }
        String fallback = String.format("%03d", used.size() + 1);
        used.add(fallback);
        return fallback;
    }

    /**
     * 从文件名末尾方向找出第一个「0 开头的数字串」，取其末 3 位，找不到返回 null。
     * 例：{@code 立鼎行发（2024-004）号.pdf → 004}、{@code 001-关于2024年清明节的放假通知.pdf → 001}。
     * 只认「以 0 开头」的连续数字段，是为了避开年份等非件号数字（如 {@code 2024} 中的 {@code 024}）。
     */
    static String extractTrailingZeroPrefixed3(String fileName) {
        if (fileName == null) return null;
        int cursor = fileName.length();
        while (cursor > 0) {
            int end = cursor;
            while (end > 0 && !isAsciiDigit(fileName.charAt(end - 1))) end--;
            if (end == 0) return null;
            int start = end;
            while (start > 0 && isAsciiDigit(fileName.charAt(start - 1))) start--;
            if (end - start >= 3 && fileName.charAt(start) == '0') {
                return fileName.substring(end - 3, end);
            }
            cursor = start;
        }
        return null;
    }

    /** 去掉冲突后缀（"-1"、"-2" …），让连续冲突在同一基数上递增 */
    private static String stripConflictSuffix(String pieceNo) {
        int idx = pieceNo.lastIndexOf('-');
        if (idx <= 0) return pieceNo;
        String tail = pieceNo.substring(idx + 1);
        if (tail.isEmpty()) return pieceNo;
        for (int i = 0; i < tail.length(); i++) {
            if (!isAsciiDigit(tail.charAt(i))) return pieceNo;
        }
        return pieceNo.substring(0, idx);
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private String normalizeDate(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";
        try {
            Matcher m = Pattern.compile("(\\d{4})[年./-](\\d{1,2})[月./-](\\d{1,2})日?").matcher(s);
            if (m.find()) {
                return String.format("%04d%02d%02d", Integer.parseInt(m.group(1)),
                        Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            }
            m = Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})$").matcher(s);
            if (m.matches()) {
                return s;
            }
        } catch (Exception ignored) {
        }
        return s;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void fail(Task task, String message) {
        task.status = STATUS_FAILED;
        task.message = message;
        flushPageMap(task);
        saveProject(task);
        logger.warn("Batch extraction task failed: projectId={}, message={}", task.projectId, message);
    }
}
