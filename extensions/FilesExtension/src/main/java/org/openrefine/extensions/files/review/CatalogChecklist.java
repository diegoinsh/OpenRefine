package org.openrefine.extensions.files.review;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按下游提交目录做数据类校审判定：缺件 / 缺页 / 空件 / 卷内目录 / 件序 / 条件缺件 /
 * 文书必备要素 / 内容组合类。
 *
 * <p>判定口径与平台 {@code ReviewChecklist} 一致（民事一审正卷 32 项基线），
 * 但本类运行在引擎侧：平台只下发目录原文与规则，判定与结果落盘都由引擎完成，
 * 平台按 {@code get-result} 读回（与合规同构）。</p>
 */
public final class CatalogChecklist {

    public static final String CASE_CIVIL_FIRST = "civil-first";
    public static final String VOLUME_MAIN = "main";
    private static final String TYPE_DEPUTY = "deputy";

    public static final class Page {
        public final int pageNo;
        public final String file;
        public final String sha1;

        Page(int pageNo, String file, String sha1) {
            this.pageNo = pageNo;
            this.file = file;
            this.sha1 = sha1;
        }
    }

    public static final class Item {
        public final String pieceNo;
        public final String title;
        public final String level1;
        public final String level2;
        public final String level3Title;
        public final int startPage;
        public final int endPage;
        public final int pageCount;
        public final int declaredPageCount;
        public final List<Page> pages;
        public final String docDate;
        public final String docDateType;
        public final String caseNo;
        public final String partyName;
        public final String amount;

        Item(String pieceNo, String title, String level1, String level2, String level3Title,
             int startPage, int endPage, int pageCount, int declaredPageCount, List<Page> pages,
             String docDate, String docDateType, String caseNo, String partyName, String amount) {
            this.pieceNo = pieceNo;
            this.title = title;
            this.level1 = level1;
            this.level2 = level2;
            this.level3Title = level3Title;
            this.startPage = startPage;
            this.endPage = endPage;
            this.pageCount = pageCount;
            this.declaredPageCount = declaredPageCount;
            this.pages = pages;
            this.docDate = docDate;
            this.docDateType = docDateType;
            this.caseNo = caseNo;
            this.partyName = partyName;
            this.amount = amount;
        }

        public boolean emptyPages() {
            return pageCount <= 0 && pages.isEmpty();
        }
    }

    public static final class TocEntry {
        public final String pieceNo;
        public final String title;
        public final int startPage;
        public final int endPage;

        TocEntry(String pieceNo, String title, int startPage, int endPage) {
            this.pieceNo = pieceNo;
            this.title = title;
            this.startPage = startPage;
            this.endPage = endPage;
        }
    }

    public static final class Volume {
        public final String volumeNo;
        public final String volumeType;
        public final String stage;
        public final Map<String, Boolean> volumeFlags;
        public final List<Item> items;
        public final List<TocEntry> tocItems;

        Volume(String volumeNo, String volumeType, String stage,
               Map<String, Boolean> volumeFlags, List<Item> items, List<TocEntry> tocItems) {
            this.volumeNo = volumeNo;
            this.volumeType = volumeType;
            this.stage = stage;
            this.volumeFlags = volumeFlags;
            this.items = items;
            this.tocItems = tocItems;
        }

        public boolean deputy() {
            return TYPE_DEPUTY.equals(volumeType);
        }
    }

    public static final class Catalog {
        public final String archiveNo;
        public final String caseNo;
        public final String level1;
        public final String stage;
        public final List<Volume> volumes;
        public final Submission submission;

        Catalog(String archiveNo, String caseNo, String level1, String stage, List<Volume> volumes,
                Submission submission) {
            this.archiveNo = archiveNo;
            this.caseNo = caseNo;
            this.level1 = level1;
            this.stage = stage;
            this.volumes = volumes;
            this.submission = submission == null ? Submission.DEFAULT : submission;
        }
    }

    /**
     * 本次提交范围（{@code catalog.submission}）：决定完备性只判"已提交"还是全量判缺件。
     *
     * <p>与平台 {@code ReviewCatalog.Submission} 同口径，下游未给时按 {@code casual}：
     * 未提交的必备件记 {@code out-of-scope}，不产生 issue（设计 §5.2、§七）。</p>
     */
    public static final class Submission {
        public static final String MODE_FULL = "full";
        public static final String MODE_CASUAL = "casual";
        public static final String MODE_SUPPLEMENT = "supplement";
        public static final Submission DEFAULT = new Submission(MODE_CASUAL, null, List.of(), List.of());

        public final String mode;
        public final String stage;
        public final List<String> scopePieceTypes;
        public final List<String> declaredRequired;

        Submission(String mode, String stage, List<String> scopePieceTypes, List<String> declaredRequired) {
            this.mode = mode == null || mode.isBlank() ? MODE_CASUAL : mode;
            this.stage = stage;
            this.scopePieceTypes = scopePieceTypes == null ? List.of() : scopePieceTypes;
            this.declaredRequired = declaredRequired == null ? List.of() : declaredRequired;
        }

        public boolean full() {
            return MODE_FULL.equals(mode);
        }

        public boolean supplement() {
            return MODE_SUPPLEMENT.equals(mode);
        }

        public boolean scoped() {
            return !scopePieceTypes.isEmpty() || !declaredRequired.isEmpty();
        }
    }

    /** 基线条目：{@code scope} = piece（在 items 里找）/ volume（卷级材料）。 */
    public static final class Entry {
        public final int order;
        public final String title;
        public final List<String> titles;
        public final boolean required;
        public final String scope;
        /** 模板条目附加字段（内置基线为缺省值）：类型码/二级目录/卷别/状态/附件并入 */
        public final String typeCode;
        public final String level2;
        public final String severity;
        public final String volumeType;
        public final String status;
        public final String mergeAs;

        Entry(int order, String title, List<String> titles, boolean required, String scope) {
            this(order, title, titles, required, scope, null, title, null, VOLUME_MAIN, "enabled", null);
        }

        Entry(int order, String title, List<String> titles, boolean required, String scope,
              String typeCode, String level2, String severity, String volumeType, String status,
              String mergeAs) {
            this.order = order;
            this.title = title;
            this.titles = titles;
            this.required = required;
            this.scope = scope;
            this.typeCode = typeCode;
            this.level2 = level2 == null || level2.isBlank() ? title : level2;
            this.severity = severity;
            this.volumeType = volumeType == null || volumeType.isBlank() ? VOLUME_MAIN : volumeType;
            this.status = status == null || status.isBlank() ? "enabled" : status;
            this.mergeAs = mergeAs;
        }

        public boolean pending() {
            return "pending".equals(status);
        }

        /** 模板条目的卷别是否覆盖该卷（any 通吃；副卷条目只在副卷生效）。 */
        public boolean appliesTo(String volumeType) {
            if ("any".equals(volumeType)) {
                return true;
            }
            return volumeType == null || volumeType.isBlank() || volumeType.equals(this.volumeType);
        }
    }

    private static Entry entry(int order, String title, boolean required, String scope, String... titles) {
        List<String> list = new ArrayList<>();
        for (String t : titles) {
            list.add(t);
        }
        return new Entry(order, title, list, required, scope);
    }

    /** 民事一审正卷 32 项（名称取真值二级目录；required 只放法定必备且歧义小的项）。 */
    private static final List<Entry> CIVIL_FIRST_MAIN = List.of(
            entry(1, "卷宗封面（正卷）", true, "volume", "卷宗封面", "封面"),
            entry(2, "卷内目录（正卷）", true, "volume", "卷内目录"),
            entry(3, "案件审判流程管理信息表、案件登记表", false, "piece",
                    "案件审判流程管理信息表", "案件登记表", "立案过程记录表"),
            entry(4, "诉讼材料收取清单", false, "piece", "诉讼材料收取清单"),
            entry(5, "案件移送函等表明案件来源的材料", false, "piece", "案件移送函", "发回重审函"),
            entry(6, "起诉状及相关材料", true, "piece", "起诉状", "起诉书", "口诉笔录"),
            entry(7, "反诉状及相关材料", false, "piece", "反诉状"),
            entry(8, "答辩状及相关材料", false, "piece", "答辩状", "抗辩状", "诉讼辩驳状"),
            entry(9, "交纳诉讼费用相关材料", false, "piece", "诉讼费", "交纳诉讼费用通知书"),
            entry(10, "受理案件通知书、应诉通知书及相关材料", false, "piece",
                    "受理案件通知书", "应诉通知书"),
            entry(11, "诉讼参与人主体资格材料", true, "piece", "主体资格", "身份证明",
                    "营业执照", "法定代表人身份证明", "授权委托书", "居民身份证", "常住人口"),
            entry(12, "诉讼参与人提交的申请书及相关材料", false, "piece", "申请书"),
            entry(13, "原告举证材料", true, "piece", "原告举证", "原告证据"),
            entry(14, "被告举证材料", false, "piece", "被告举证", "被告证据"),
            entry(15, "第三人举证材料", false, "piece", "第三人举证"),
            entry(16, "音视频材料", false, "piece", "音视频", "录音", "录像"),
            entry(17, "法院调查取证材料", false, "piece", "调查取证", "调查笔录", "询问笔录"),
            entry(18, "多元纠纷化解相关材料", false, "piece", "多元纠纷", "诉前调解"),
            entry(19, "开庭通知书、公告、传票等相关材料", false, "piece",
                    "开庭通知书", "公告", "传票"),
            entry(20, "庭前会议笔录、法庭笔录及相关材料", true, "piece",
                    "法庭笔录", "庭审笔录", "开庭笔录", "庭前会议笔录"),
            entry(21, "公益诉讼起诉人意见、代理词等材料", false, "piece", "代理词"),
            entry(22, "调解协议、公益诉讼调解公告", false, "piece", "调解协议", "调解笔录"),
            entry(23, "延长审理期限、扣除审理期限材料", false, "piece", "延长审理期限", "扣除审理期限"),
            entry(24, "撤诉申请书、撤诉笔录", false, "piece", "撤诉"),
            entry(25, "本院法律文书正本", true, "piece", "法律文书正本", "判决书", "裁定书", "调解书"),
            entry(26, "宣判及委托送达类材料", false, "piece", "宣判", "委托送达"),
            entry(27, "送达地址确认书、送达回证或其他送达凭证", true, "piece",
                    "送达回证", "送达地址确认书", "送达凭证"),
            entry(28, "上诉案件相关材料", false, "piece", "上诉状", "移送函存根", "退卷函"),
            entry(29, "其他与诉讼活动相关的材料", false, "piece"),
            entry(30, "备考表", true, "volume", "备考表"),
            entry(31, "证物袋", false, "volume", "证物袋"),
            entry(32, "卷宗封底（正卷）", true, "volume", "卷宗封底", "封底"));

    private CatalogChecklist() {
    }

    /** 取基线；暂不支持的审级/卷别明确报错（不套用民事一审）。 */
    public static List<Entry> baseline(String caseType, String volume) {
        String ct = blank(caseType) ? CASE_CIVIL_FIRST : caseType.trim();
        String vol = blank(volume) ? VOLUME_MAIN : volume.trim();
        if (CASE_CIVIL_FIRST.equalsIgnoreCase(ct) && VOLUME_MAIN.equalsIgnoreCase(vol)) {
            return CIVIL_FIRST_MAIN;
        }
        throw new IllegalArgumentException("checklist-unsupported：暂只支持民事一审正卷（civil-first/main），"
                + "当前 caseType=" + ct + " volume=" + vol);
    }

    /* ---------------- 模板适配（complete v2，开关 completeness.templateDriven） ---------------- */

    /**
     * 任务快照里的模板完备性段（{@code template_snapshot.complete}）的引擎视图。
     *
     * <p>开关关闭时完全不参与判定，既有卷宗按 {@link #CIVIL_FIRST_MAIN} 基线跑；
     * 打开后完备性只认这份条目集合，引擎不再预置任何案件/法院口径（设计 §四.1）。</p>
     */
    public static final class Template {
        public final double matchThreshold;
        public final boolean mergeAttachments;
        public final boolean pouchElements;
        public final String missingAction;
        public final List<Entry> items;
        public final List<Condition> conditions;

        Template(double matchThreshold, boolean mergeAttachments, boolean pouchElements,
                 String missingAction, List<Entry> items, List<Condition> conditions) {
            this.matchThreshold = matchThreshold;
            this.mergeAttachments = mergeAttachments;
            this.pouchElements = pouchElements;
            this.missingAction = missingAction;
            this.items = items;
            this.conditions = conditions;
        }

        /** 适用于该卷的条目（{@code volumeType=any} 通吃；副卷未单独建模时回落正卷清单）。 */
        public List<Entry> entriesFor(String volumeType) {
            List<Entry> out = new ArrayList<>();
            for (Entry e : items) {
                if (e.appliesTo(volumeType)) {
                    out.add(e);
                }
            }
            if (out.isEmpty() && TYPE_DEPUTY.equals(volumeType)) {
                for (Entry e : items) {
                    if (VOLUME_MAIN.equals(e.volumeType)) {
                        out.add(e);
                    }
                }
            }
            return out;
        }

        public int pendingRequired(String volumeType) {
            int n = 0;
            for (Entry e : entriesFor(volumeType)) {
                if (e.required && e.pending()) {
                    n++;
                }
            }
            return n;
        }
    }

    /**
     * 解析模板快照：入参可以是整份模板 payload（取 {@code complete} 段），也可以直接是 complete 段。
     *
     * @return 无 complete/items 时返回 null（调用方按"无模板"处理，走内置基线）
     */
    public static Template parseTemplate(JsonNode snapshot) {
        if (snapshot == null || snapshot.isNull()) {
            return null;
        }
        JsonNode complete = snapshot.get("complete");
        if (complete == null || !complete.isObject()) {
            complete = snapshot;
        }
        JsonNode itemsNode = complete.get("items");
        if (itemsNode == null || !itemsNode.isArray() || itemsNode.isEmpty()) {
            return null;
        }
        List<Entry> items = new ArrayList<>();
        int index = 0;
        for (JsonNode n : itemsNode) {
            index++;
            if (n == null || !n.isObject()) {
                continue;
            }
            String title = firstNonBlank(text(n, "title"), text(n, "name"), text(n, "level2"));
            if (blank(title)) {
                continue;
            }
            List<String> aliases = new ArrayList<>();
            JsonNode aliasNode = n.get("aliases");
            if (aliasNode != null && aliasNode.isArray()) {
                for (JsonNode a : aliasNode) {
                    String v = a == null || a.isNull() ? "" : a.asText("");
                    if (!v.isBlank() && !aliases.contains(v.trim())) {
                        aliases.add(v.trim());
                    }
                }
            }
            if (!aliases.contains(title)) {
                aliases.add(title);
            }
            boolean required = !n.has("required") || n.path("required").asBoolean(true);
            int order = n.path("order").asInt(index);
            items.add(new Entry(order, title, aliases, required, "piece",
                    firstNonBlank(text(n, "typeCode"), "item-" + order),
                    text(n, "level2"),
                    firstNonBlank(text(n, "severity"), required ? "error" : "warning"),
                    firstNonBlank(text(n, "volumeType"), VOLUME_MAIN),
                    firstNonBlank(text(n, "status"), "enabled"),
                    text(n, "mergeAs")));
        }
        if (items.isEmpty()) {
            return null;
        }
        List<Condition> conditions = new ArrayList<>();
        JsonNode condNode = complete.get("conditions");
        if (condNode != null && condNode.isArray()) {
            for (JsonNode c : condNode) {
                if (c == null || !c.isObject()) {
                    continue;
                }
                List<String> when = stringListOf(c.get("whenAny"));
                List<String> requires = stringListOf(c.get("requires"));
                if (when.isEmpty() || requires.isEmpty()) {
                    continue;
                }
                conditions.add(new Condition(firstNonBlank(text(c, "id"), "cond-" + conditions.size()),
                        firstNonBlank(text(c, "rule"), "missing_attachment"),
                        firstNonBlank(text(c, "severity"), "warning"),
                        firstNonBlank(text(c, "hint"), ""), when, requires));
            }
        }
        double threshold = complete.path("matchThreshold").asDouble(0.82);
        boolean merge = !complete.has("mergeAttachments") || complete.path("mergeAttachments").asBoolean(true);
        boolean pouch = !complete.has("pouchElements") || complete.path("pouchElements").asBoolean(true);
        String missingAction = firstNonBlank(text(complete, "missingAction"), "warning");
        return new Template(threshold, merge, pouch, missingAction, items, conditions);
    }

    /** 模板驱动开关：默认关闭（旧基线行为逐字不变）；沙箱/验收可按需打开。 */
    public static boolean templateDrivenEnabled() {
        String v = System.getProperty("completeness.templateDriven");
        if (v == null || v.isBlank()) {
            v = System.getenv("COMPLETENESS_TEMPLATE_DRIVEN");
        }
        if (v == null) {
            return false;
        }
        String s = v.trim().toLowerCase();
        return "true".equals(s) || "1".equals(s) || "yes".equals(s);
    }

    /* ---------------- 解析 ---------------- */

    public static Catalog parse(JsonNode raw) {
        String archiveNo = text(raw, "archiveNo");
        String caseNo = text(raw, "caseNo");
        String level1 = text(raw, "level1");
        String stage = firstNonBlank(text(raw, "stage"), text(raw, "caseStage"),
                text(raw, "archiveStatus"));

        List<Volume> volumes = new ArrayList<>();
        JsonNode arr = raw == null ? null : raw.get("volumes");
        if (arr != null && arr.isArray() && arr.size() > 0) {
            int index = 0;
            for (JsonNode n : arr) {
                index++;
                volumes.add(parseVolume(n, level1, stage, index == 1 ? VOLUME_MAIN : TYPE_DEPUTY,
                        String.valueOf(index)));
            }
        } else {
            volumes.add(parseVolume(raw, level1, stage, VOLUME_MAIN, "1"));
        }
        return new Catalog(archiveNo, caseNo, level1, stage, volumes, submissionOf(raw, stage));
    }

    /** 本次提交范围：只认 {@code submission} 子对象；缺省 casual（只判已提交材料）。 */
    private static Submission submissionOf(JsonNode raw, String catalogStage) {
        JsonNode s = raw == null ? null : raw.get("submission");
        if (s == null || s.isNull() || !s.isObject()) {
            return Submission.DEFAULT;
        }
        String mode = normalizeMode(text(s, "mode"));
        String stage = firstNonBlank(text(s, "stage"), catalogStage);
        List<String> scope = stringListOf(s.path("scope").get("pieceTypes"));
        List<String> declared = stringListOf(s.get("declaredRequired"));
        return new Submission(mode, stage, scope, declared);
    }

    private static String normalizeMode(String raw) {
        String m = raw == null ? "" : raw.trim().toLowerCase();
        if ("all".equals(m) || "archive".equals(m) || "archived".equals(m)) {
            return Submission.MODE_FULL;
        }
        if ("single".equals(m) || "partial".equals(m)) {
            return Submission.MODE_SUPPLEMENT;
        }
        if (Submission.MODE_FULL.equals(m) || Submission.MODE_SUPPLEMENT.equals(m)) {
            return m;
        }
        return Submission.MODE_CASUAL;
    }

    private static List<String> stringListOf(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode n : arr) {
            String v = n == null || n.isNull() ? "" : n.asText("");
            if (!v.isBlank()) {
                out.add(v.trim());
            }
        }
        return out;
    }

    private static Volume parseVolume(JsonNode node, String catalogLevel1, String catalogStage,
                                      String defaultType, String defaultNo) {
        JsonNode vol = node == null ? null : node.get("volume");
        String volumeNo = firstNonBlank(text(vol, "volumeNo"), text(node, "volumeNo"), defaultNo);
        String volumeType = volumeTypeOf(firstNonBlank(text(vol, "volumeType"),
                text(vol, "volumeTypeName"), text(node, "volumeType"), text(node, "卷别"),
                text(node, "volumeCategory")), defaultType);
        String stage = firstNonBlank(text(vol, "stage"), text(node, "stage"),
                text(node, "caseStage"), text(node, "archiveStatus"), catalogStage);
        Map<String, Boolean> flags = volumeFlagsOf(vol != null ? vol : node);
        List<Item> items = itemsOf(node, catalogLevel1);
        List<TocEntry> toc = tocOf(vol != null ? vol : node);
        return new Volume(volumeNo, volumeType, stage, flags, items, toc);
    }

    /** 卷别归一：正卷/main/正 → main；副卷/deputy/副 → deputy；其余取小写原值。 */
    private static String volumeTypeOf(String raw, String fallback) {
        String s = raw == null ? "" : raw.trim().toLowerCase();
        if (s.isEmpty()) {
            return fallback;
        }
        if (s.contains("副") || s.contains("deputy")) {
            return TYPE_DEPUTY;
        }
        if (s.contains("正") || s.contains("main") || s.contains("primary")) {
            return VOLUME_MAIN;
        }
        return s;
    }

    private static List<Item> itemsOf(JsonNode node, String catalogLevel1) {
        List<Item> items = new ArrayList<>();
        JsonNode arr = node == null ? null : node.get("items");
        if (arr == null || !arr.isArray()) {
            return items;
        }
        int index = 0;
        for (JsonNode n : arr) {
            index++;
            String pieceNo = firstNonBlank(text(n, "pieceNo"), text(n, "序号"), String.valueOf(index));
            String title = firstNonBlank(text(n, "title"), text(n, "name"), text(n, "level3Title"));
            String itemLevel1 = firstNonBlank(text(n, "level1"), catalogLevel1);
            String level2 = text(n, "level2");
            String level3 = text(n, "level3Title");
            int startPage = intOf(n, "startPage");
            int endPage = intOf(n, "endPage");
            List<Page> pages = pagesOf(n);
            int declaredPageCount = intOf(n, "pageCount");
            int pageCount = declaredPageCount;
            if (pageCount <= 0) {
                pageCount = !pages.isEmpty() ? pages.size()
                        : (startPage > 0 && endPage >= startPage ? endPage - startPage + 1 : 0);
            }
            String docDate = firstNonBlank(text(n, "docDate"), text(n, "成文日期"),
                    text(n, "documentDate"));
            String docDateType = firstNonBlank(text(n, "docDateType"), text(n, "日期类型"),
                    text(n, "dateType"));
            String itemCaseNo = firstNonBlank(text(n, "caseNo"), text(n, "案号"));
            String partyName = firstNonBlank(text(n, "partyName"), text(n, "party"),
                    text(n, "当事人"));
            String amount = firstNonBlank(text(n, "amount"), text(n, "金额"));
            items.add(new Item(pieceNo, title, itemLevel1 == null ? "" : itemLevel1,
                    level2, level3, startPage, endPage, pageCount, declaredPageCount, pages,
                    docDate, docDateType, itemCaseNo, partyName, amount));
        }
        return items;
    }

    private static List<Page> pagesOf(JsonNode item) {
        List<Page> pages = new ArrayList<>();
        JsonNode arr = item == null ? null : item.get("pages");
        if (arr == null || !arr.isArray()) {
            return pages;
        }
        for (JsonNode p : arr) {
            pages.add(new Page(intOf(p, "pageNo"), text(p, "file"), text(p, "sha1")));
        }
        return pages;
    }

    private static List<TocEntry> tocOf(JsonNode volume) {
        List<TocEntry> toc = new ArrayList<>();
        JsonNode arr = volume == null ? null : volume.get("tocItems");
        if (arr == null || !arr.isArray()) {
            return toc;
        }
        int index = 0;
        for (JsonNode n : arr) {
            index++;
            toc.add(new TocEntry(firstNonBlank(text(n, "pieceNo"), text(n, "序号"),
                    String.valueOf(index)), text(n, "title"), intOf(n, "startPage"), intOf(n, "endPage")));
        }
        return toc;
    }

    /** 卷级材料声明的键位（与 volume 基线项一一对应）。 */
    private static Map<String, Boolean> volumeFlagsOf(JsonNode volume) {
        Map<String, Boolean> flags = new LinkedHashMap<>();
        putFlag(flags, "cover", volume, "cover", "coverPage", "hasCover", "封面");
        putFlag(flags, "toc", volume, "toc", "tocPresent", "hasToc", "卷内目录");
        putFlag(flags, "memo", volume, "memo", "memoSheet", "hasMemo", "备考表");
        putFlag(flags, "back", volume, "back", "backCover", "hasBack", "封底");
        putFlag(flags, "pouch", volume, "pouch", "evidenceBag", "hasPouch", "证物袋");
        return flags;
    }

    private static void putFlag(Map<String, Boolean> flags, String key, JsonNode node, String... names) {
        for (String name : names) {
            JsonNode v = node == null ? null : node.get(name);
            if (v == null || v.isNull()) {
                continue;
            }
            flags.put(key, boolOf(v));
            return;
        }
    }

    private static boolean boolOf(JsonNode v) {
        if (v == null) {
            return false;
        }
        if (v.isObject() || v.isArray()) {
            return true;
        }
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        String s = v.asText("");
        s = s.trim().toLowerCase();
        if (s.isEmpty()) {
            return false;
        }
        return "true".equals(s) || "1".equals(s) || "yes".equals(s) || "y".equals(s)
                || "有".equals(s) || "是".equals(s);
    }

    /* ---------------- 判定 ---------------- */

    /**
     * 纯数据判定（按卷分别判定后汇总）：缺件、空件、缺页、卷内目录、件序、条件缺件、
     * 文书必备要素、内容组合类。
     *
     * <p>无模板（或 {@code completeness.templateDriven=false}）时走内置民事一审基线，
     * 行为与模板机制上线前逐字一致。</p>
     *
     * @return {@code {caseType,volume,volumeChecks,items,issues,documents,combo,summary}}
     */
    public static Map<String, Object> evaluate(Catalog catalog) {
        return evaluate(catalog, null);
    }

    /** 模板双读：{@code template} 非空且开关打开时按模板条目判定，否则回落内置基线。 */
    public static Map<String, Object> evaluate(Catalog catalog, Template template) {
        boolean templateDriven = template != null && !template.items.isEmpty() && templateDrivenEnabled();
        List<Entry> mainEntries = templateDriven
                ? template.entriesFor(VOLUME_MAIN) : baseline(CASE_CIVIL_FIRST, VOLUME_MAIN);
        Submission submission = catalog.submission == null ? Submission.DEFAULT : catalog.submission;
        List<Map<String, Object>> volumeChecks = new ArrayList<>();
        List<Map<String, Object>> allItems = new ArrayList<>();
        List<Map<String, Object>> allIssues = new ArrayList<>();
        List<Map<String, Object>> allDocuments = new ArrayList<>();
        List<Map<String, Object>> allCombos = new ArrayList<>();
        int required = 0;
        int present = 0;
        int missing = 0;
        int unknown = 0;
        int volumeDeclared = 0;
        int requiredChecked = 0;
        int requiredSkipped = 0;
        int outOfScope = 0;
        boolean archived = false;

        for (Volume volume : catalog.volumes) {
            Map<String, Object> check = evaluateVolume(volume, catalog.caseNo, template,
                    templateDriven, submission);
            volumeChecks.add(check);
            required += intOf(check.get("required"));
            present += intOf(check.get("present"));
            missing += intOf(check.get("missing"));
            unknown += intOf(check.get("unknown"));
            volumeDeclared += intOf(check.get("volumeDeclared"));
            requiredChecked += intOf(check.get("requiredChecked"));
            requiredSkipped += intOf(check.get("requiredSkipped"));
            outOfScope += intOf(check.get("outOfScope"));
            archived |= Boolean.TRUE.equals(check.get("archived"));
            allItems.addAll(listOf(check.get("items")));
            allIssues.addAll(listOf(check.get("issues")));
            allDocuments.addAll(listOf(check.get("documents")));
            List<Map<String, Object>> combos =
                    comboRows(volume, volume.deputy() ? List.of() : mainEntries);
            allCombos.addAll(combos);
            allIssues.addAll(comboIssues(combos));
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("stage", catalog.stage);
        summary.put("archived", archived);
        // 完备性判定口径（设计 §5.3）：范围、基线来源、匹配阈值与三数统计
        summary.put("completenessScope", submission.mode);
        summary.put("baselineSource", templateDriven ? "template" : "builtin");
        summary.put("matchThreshold", templateDriven ? template.matchThreshold : null);
        summary.put("requiredChecked", requiredChecked);
        summary.put("requiredSkipped", requiredSkipped);
        summary.put("outOfScope", outOfScope);
        summary.put("volumeCount", volumeChecks.size());
        summary.put("required", required);
        summary.put("present", present);
        summary.put("missing", missing);
        summary.put("unknown", unknown);
        summary.put("volumeDeclared", volumeDeclared);
        summary.put("issueCount", allIssues.size());
        summary.put("errorCount", countSeverity(allIssues, "error"));
        summary.put("warningCount", countSeverity(allIssues, "warning"));
        int docPassed = 0;
        for (Map<String, Object> doc : allDocuments) {
            if ("passed".equals(doc.get("status"))) {
                docPassed++;
            }
        }
        summary.put("docChecked", allDocuments.size());
        summary.put("docPassed", docPassed);
        summary.put("docFailed", allDocuments.size() - docPassed);
        int unsupported = 0;
        for (Map<String, Object> c : volumeChecks) {
            if (Boolean.FALSE.equals(c.get("supported"))) {
                unsupported++;
            }
        }
        summary.put("unsupportedVolumes", unsupported);
        int comboComplete = 0;
        int comboPending = 0;
        for (Map<String, Object> c : allCombos) {
            if ("complete".equals(c.get("status"))) {
                comboComplete++;
            } else if ("pending".equals(c.get("status"))) {
                comboPending++;
            }
        }
        summary.put("comboGroups", allCombos.size());
        summary.put("comboComplete", comboComplete);
        summary.put("comboPending", comboPending);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("caseType", CASE_CIVIL_FIRST);
        out.put("volume", VOLUME_MAIN);
        out.put("volumeChecks", volumeChecks);
        out.put("items", allItems);
        out.put("issues", allIssues);
        out.put("documents", allDocuments);
        out.put("combo", allCombos);
        out.put("summary", summary);
        return out;
    }

    /** 内容组合类：按本次提交目录的二级目录归组，逐组给出件数/页数与组合判定。 */
    private static List<Map<String, Object>> comboRows(Volume volume, List<Entry> entries) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, List<Item>> groups = new LinkedHashMap<>();
        Map<String, String> rawNames = new LinkedHashMap<>();
        String volumeNo = volume.volumeNo == null ? "" : volume.volumeNo;
        for (Item item : volume.items) {
            String raw = item.level2 == null ? "" : item.level2.trim();
            String key = normalizeLevel2(raw);
            if (key.isEmpty()) {
                key = "未标注二级目录";
            }
            rawNames.putIfAbsent(key, raw.isEmpty() ? key : raw);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }
        for (Map.Entry<String, List<Item>> group : groups.entrySet()) {
            String key = group.getKey();
            List<Item> items = group.getValue();
            Entry baselineEntry = matchEntry(entries, key);
            String status;
            if (key.contains("待归类")) {
                status = "pending";
            } else if (baselineEntry != null) {
                status = "complete";
            } else {
                status = "unmatched";
            }
            int pages = 0;
            List<String> titles = new ArrayList<>();
            for (Item item : items) {
                pages += Math.max(0, item.pageCount > 0
                        ? item.pageCount : item.endPage - item.startPage + 1);
                titles.add(item.title == null ? "" : item.title);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("volumeNo", volumeNo);
            row.put("level2", rawNames.get(key));
            row.put("groupKey", key);
            row.put("baseline", baselineEntry == null ? null : baselineEntry.title);
            row.put("pieceCount", items.size());
            row.put("pageCount", pages);
            row.put("titles", titles);
            row.put("status", status);
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> comboIssues(List<Map<String, Object>> combos) {
        List<Map<String, Object>> issues = new ArrayList<>();
        for (Map<String, Object> row : combos) {
            Object status = row.get("status");
            if ("pending".equals(status)) {
                issues.add(issue("piece_combo", "warning", "level2",
                        String.valueOf(row.get("volumeNo")), null, String.valueOf(row.get("level2")),
                        "二级目录「" + row.get("level2") + "」为待归类，需人工归类后再校审"));
            } else if ("unmatched".equals(status)) {
                issues.add(issue("piece_combo", "warning", "level2",
                        String.valueOf(row.get("volumeNo")), null, String.valueOf(row.get("level2")),
                        "二级目录「" + row.get("level2") + "」不在基线清单内，待人工确认"));
            }
        }
        return issues;
    }

    private static String normalizeLevel2(String level2) {
        if (level2 == null) {
            return "";
        }
        return level2.replaceFirst("^\\s*\\d+\\s*[、.．\\-—_]?\\s*", "").trim();
    }

    private static Entry matchEntry(List<Entry> entries, String key) {
        for (Entry e : entries) {
            if (e.title.equals(key)) {
                return e;
            }
            for (String title : e.titles) {
                if (title.equals(key)) {
                    return e;
                }
            }
        }
        return null;
    }

    /** 单卷判定：正卷走完整基线；暂不支持的卷别只给出"暂不支持"结论，不静默套用。 */
    private static Map<String, Object> evaluateVolume(Volume volume, String catalogCaseNo,
                                                      Template template, boolean templateDriven,
                                                      Submission submission) {
        String volumeNo = volume.volumeNo == null ? "" : volume.volumeNo;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("volumeNo", volumeNo);
        out.put("volumeType", volume.volumeType);
        out.put("stage", volume.stage);
        List<Entry> entries;
        if (templateDriven) {
            // 模板驱动：只认快照条目（副卷无独立条目时回落正卷清单，由 UI 标注「沿用正卷」）
            entries = template.entriesFor(volume.volumeType);
        } else {
            try {
                entries = baseline(CASE_CIVIL_FIRST, volume.volumeType);
            } catch (IllegalArgumentException e) {
                out.put("supported", false);
                out.put("message", e.getMessage());
                out.put("itemCount", volume.items.size());
                out.put("required", 0);
                out.put("present", 0);
                out.put("missing", 0);
                out.put("unknown", 0);
                out.put("requiredChecked", 0);
                out.put("requiredSkipped", 0);
                out.put("outOfScope", 0);
                out.put("volumeDeclared", volume.volumeFlags == null ? 0 : volume.volumeFlags.size());
                out.put("archived", false);
                out.put("items", List.of());
                out.put("issues", List.of());
                out.put("documents", List.of());
                return out;
            }
        }

        boolean archived = "archived".equalsIgnoreCase(
                volume.stage == null ? "" : volume.stage.trim());
        int present = 0;
        int missing = 0;
        int unknown = 0;
        int requiredChecked = 0;
        int requiredSkipped = 0;
        int outOfScope = 0;
        List<Map<String, Object>> itemResults = new ArrayList<>();
        List<Map<String, Object>> issues = new ArrayList<>();

        for (Entry e : entries) {
            if (e.pending()) {
                // UI「待开放」：只展示不下判（不计入 present/missing）
                if (e.required) {
                    requiredSkipped++;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("volumeNo", volumeNo);
                row.put("order", e.order);
                row.put("title", e.title);
                row.put("required", e.required);
                row.put("scope", e.scope);
                row.put("status", "pending");
                row.put("declared", null);
                row.put("matched", List.of());
                putTemplateFields(row, e);
                itemResults.add(row);
                continue;
            }
            List<String> matched = new ArrayList<>();
            for (Item item : volume.items) {
                if (matches(e, item)) {
                    matched.add(item.pieceNo);
                }
            }
            Boolean declared = "volume".equals(e.scope) ? declared(volume, e) : null;
            String status;
            if (!matched.isEmpty()) {
                status = "present";
                present++;
            } else if (Boolean.TRUE.equals(declared)) {
                status = "present";
                present++;
            } else if (Boolean.FALSE.equals(declared)) {
                if (!e.required) {
                    status = "absent";
                } else if (archived) {
                    status = "missing";
                    missing++;
                    issues.add(missingIssue(e, volumeNo, "volume", null,
                            "缺少卷级材料：" + e.title, true));
                } else {
                    status = "unknown";
                    unknown++;
                    issues.add(missingIssue(e, volumeNo, "volume", null,
                            "未归档（stage=" + stageText(volume) + "），卷级缺件待结案后复核："
                                    + e.title, false));
                }
            } else if (e.required && "volume".equals(e.scope)) {
                if (outOfSubmission(e, submission, templateDriven)) {
                    status = "out-of-scope";
                    outOfScope++;
                } else {
                    status = "unknown";
                    unknown++;
                }
            } else if (e.required) {
                if (outOfSubmission(e, submission, templateDriven)) {
                    // 随案/单件补交：未纳入本次范围的必备件只统计、不出 issue（设计 §七）
                    status = "out-of-scope";
                    outOfScope++;
                } else if (archived) {
                    status = "missing";
                    missing++;
                    issues.add(missingIssue(e, volumeNo, "piece", e.title,
                            "缺少必备件：" + e.title, true));
                } else {
                    status = "unknown";
                    unknown++;
                    issues.add(missingIssue(e, volumeNo, "piece", e.title,
                            "未归档（stage=" + stageText(volume) + "），缺件待结案后复核："
                                    + e.title, false));
                }
            } else {
                status = "absent";
            }
            if (e.required && !"out-of-scope".equals(status)) {
                requiredChecked++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("volumeNo", volumeNo);
            row.put("order", e.order);
            row.put("title", e.title);
            row.put("required", e.required);
            row.put("scope", e.scope);
            row.put("status", status);
            row.put("declared", declared);
            row.put("matched", matched);
            putTemplateFields(row, e);
            itemResults.add(row);
        }

        issues.addAll(pageIssues(volume));
        issues.addAll(tocIssues(volume));
        issues.addAll(tocCompareIssues(volume));
        issues.addAll(orderIssues(volume, entries));
        // 条件缺件：模板驱动时读模板 conditions，否则沿用内置 3 条
        List<Condition> conditions = templateDriven && template != null && !template.conditions.isEmpty()
                ? template.conditions : CONDITIONS;
        issues.addAll(attachmentIssues(volume, conditions));
        List<Map<String, Object>> documents = documentElements(volume, catalogCaseNo);
        issues.addAll(documentIssues(volume, documents));

        out.put("supported", true);
        out.put("archived", archived);
        out.put("itemCount", volume.items.size());
        out.put("required", count(entries, true));
        out.put("present", present);
        out.put("missing", missing);
        out.put("unknown", unknown);
        out.put("requiredChecked", requiredChecked);
        out.put("requiredSkipped", requiredSkipped);
        out.put("outOfScope", outOfScope);
        out.put("volumeDeclared", volume.volumeFlags == null ? 0 : volume.volumeFlags.size());
        out.put("issueCount", issues.size());
        out.put("errorCount", countSeverity(issues, "error"));
        out.put("warningCount", countSeverity(issues, "warning"));
        out.put("items", itemResults);
        out.put("issues", issues);
        out.put("documents", documents);
        return out;
    }

    /** 文书必备要素的文书类型与必备要素（长模式优先匹配，案号可用卷级案号兜底）。 */
    private static final String[][] DOC_TYPES = {
            {"判决书", "caseNo", "partyName", "docDate"},
            {"裁定书", "caseNo", "partyName", "docDate"},
            {"调解书", "caseNo", "partyName", "docDate"},
            {"送达回证", "caseNo", "partyName", "docDate"},
            {"传票", "caseNo", "partyName"},
            {"笔录", "docDate"}
    };

    private static List<Map<String, Object>> documentElements(Volume volume, String catalogCaseNo) {
        List<Map<String, Object>> docs = new ArrayList<>();
        String volumeNo = volume.volumeNo == null ? "" : volume.volumeNo;
        for (Item item : volume.items) {
            String title = item.title == null ? "" : item.title;
            String typeName = null;
            String[] fields = null;
            for (String[] d : DOC_TYPES) {
                if (title.contains(d[0])) {
                    typeName = d[0];
                    fields = d;
                    break;
                }
            }
            if (typeName == null) {
                continue;
            }
            List<String> missing = new ArrayList<>();
            for (int i = 1; i < fields.length; i++) {
                if (blank(elementValue(item, fields[i], catalogCaseNo))) {
                    missing.add(fieldName(fields[i]));
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("volumeNo", volumeNo);
            row.put("pieceNo", item.pieceNo);
            row.put("title", title);
            row.put("typeName", typeName);
            row.put("status", missing.isEmpty() ? "passed" : "failed");
            row.put("missing", missing);
            docs.add(row);
        }
        return docs;
    }

    private static List<Map<String, Object>> documentIssues(Volume volume, List<Map<String, Object>> docs) {
        List<Map<String, Object>> issues = new ArrayList<>();
        String volumeNo = volume.volumeNo == null ? "" : volume.volumeNo;
        for (Map<String, Object> doc : docs) {
            if (!"failed".equals(doc.get("status"))) {
                continue;
            }
            List<String> missing = listOfStrings(doc.get("missing"));
            issues.add(issue("document_element", "warning", "piece", volumeNo,
                    String.valueOf(doc.get("pieceNo")), String.valueOf(doc.get("title")),
                    doc.get("typeName") + "缺少必备要素：" + String.join("、", missing)
                            + "（本次提交目录未提供，按「尚未形成」处理）"));
        }
        return issues;
    }

    private static String elementValue(Item item, String field, String catalogCaseNo) {
        if ("caseNo".equals(field)) {
            return blank(item.caseNo) ? catalogCaseNo : item.caseNo;
        }
        if ("partyName".equals(field)) {
            return item.partyName;
        }
        if ("docDate".equals(field)) {
            return item.docDate;
        }
        return null;
    }

    private static String fieldName(String field) {
        if ("caseNo".equals(field)) {
            return "案号";
        }
        if ("partyName".equals(field)) {
            return "当事人";
        }
        if ("docDate".equals(field)) {
            return "成文日期";
        }
        return field;
    }

    /** 缺页 / 空件：件内页号覆盖 + 卷内页号连续。 */
    private static List<Map<String, Object>> pageIssues(Volume volume) {
        List<Map<String, Object>> issues = new ArrayList<>();
        String volumeNo = volume.volumeNo == null ? "" : volume.volumeNo;
        for (Item item : volume.items) {
            if (item.emptyPages()) {
                issues.add(issue("empty_piece", "error", "piece", volumeNo, item.pieceNo, item.title,
                        "件 " + item.pieceNo + "（" + item.title + "）有件无页"));
                continue;
            }
            if (!item.pages.isEmpty() && item.startPage > 0 && item.endPage >= item.startPage) {
                Set<Integer> declared = new LinkedHashSet<>();
                for (Page page : item.pages) {
                    if (page.pageNo > 0) {
                        declared.add(page.pageNo);
                    }
                }
                List<String> gaps = new ArrayList<>();
                for (int p = item.startPage; p <= item.endPage; p++) {
                    if (!declared.contains(p)) {
                        gaps.add(String.valueOf(p));
                    }
                }
                if (!gaps.isEmpty()) {
                    issues.add(issue("missing_page", "error", "piece", volumeNo, item.pieceNo, item.title,
                            "件 " + item.pieceNo + "（" + item.title + "）缺页："
                                    + String.join(",", gaps)));
                }
            }
        }

        List<Item> ranged = new ArrayList<>();
        for (Item item : volume.items) {
            if (item.startPage > 0 && item.endPage >= item.startPage) {
                ranged.add(item);
            }
        }
        if (!ranged.isEmpty() && ranged.size() == volume.items.size()) {
            ranged.sort((a, b) -> Integer.compare(a.startPage, b.startPage));
            int expect = ranged.get(0).startPage;
            for (Item item : ranged) {
                if (item.startPage > expect) {
                    issues.add(issue("missing_page", "error", "volume", volumeNo, item.pieceNo, item.title,
                            "卷内页号不连续：件 " + item.pieceNo + " 前缺第 "
                                    + expect + "~" + (item.startPage - 1) + " 页"));
                }
                expect = Math.max(expect, item.endPage + 1);
            }
        }
        return issues;
    }

    /** 卷内目录：件号必须连续（纯数字件号时校验），不连续只出 warning。 */
    private static List<Map<String, Object>> tocIssues(Volume volume) {
        List<Map<String, Object>> issues = new ArrayList<>();
        List<Integer> numbers = new ArrayList<>();
        for (Item item : volume.items) {
            if (item.pieceNo != null && item.pieceNo.matches("\\d+")) {
                try {
                    numbers.add(Integer.parseInt(item.pieceNo));
                } catch (NumberFormatException ignore) {
                    // 位数过大按非数字件号处理
                }
            }
        }
        if (!numbers.isEmpty() && numbers.size() == volume.items.size()) {
            numbers.sort(Integer::compareTo);
            for (int i = 0; i < numbers.size(); i++) {
                if (numbers.get(i) != i + 1) {
                    issues.add(issue("volume_toc", "warning", "volume",
                            volume.volumeNo == null ? "" : volume.volumeNo, null, null,
                            "卷内目录件号不连续：期望 " + (i + 1) + "，实际 " + numbers.get(i)));
                    break;
                }
            }
        }
        return issues;
    }

    /** 卷内目录逐件核对（A2）：把下游从目录页著录的 tocItems 与实物件逐条比对。 */
    private static List<Map<String, Object>> tocCompareIssues(Volume volume) {
        List<Map<String, Object>> issues = new ArrayList<>();
        List<TocEntry> toc = volume.tocItems;
        if (toc == null || toc.isEmpty()) {
            return issues;
        }
        String volumeNo = volume.volumeNo == null ? "" : volume.volumeNo;
        Map<String, Item> byNo = new LinkedHashMap<>();
        for (Item item : volume.items) {
            byNo.put(item.pieceNo == null ? "" : item.pieceNo, item);
        }
        Set<String> tocNos = new LinkedHashSet<>();
        for (TocEntry entry : toc) {
            if (issues.size() >= 6) {
                break;
            }
            String pieceNo = entry.pieceNo == null ? "" : entry.pieceNo;
            tocNos.add(pieceNo);
            Item item = byNo.get(pieceNo);
            if (item == null) {
                issues.add(issue("volume_toc", "warning", "volume", volumeNo, pieceNo, entry.title,
                        "卷内目录列有「" + entry.title + "」（件 " + pieceNo + "），但卷内无此件"));
                continue;
            }
            String tocTitle = entry.title == null ? "" : entry.title.trim();
            String itemTitle = item.title == null ? "" : item.title.trim();
            if (!tocTitle.isEmpty() && !itemTitle.isEmpty()
                    && !tocTitle.equals(itemTitle)
                    && !tocTitle.contains(itemTitle) && !itemTitle.contains(tocTitle)) {
                issues.add(issue("volume_toc", "warning", "volume", volumeNo, pieceNo, itemTitle,
                        "件 " + pieceNo + " 题名与卷内目录不一致：目录「" + tocTitle
                                + "」/ 卷内「" + itemTitle + "」"));
            }
            if (entry.startPage > 0 && item.startPage > 0 && entry.startPage != item.startPage) {
                issues.add(issue("volume_toc", "warning", "volume", volumeNo, pieceNo, itemTitle,
                        "件 " + pieceNo + " 起始页与卷内目录不一致：目录 " + entry.startPage
                                + " / 卷内 " + item.startPage));
            }
            if (entry.endPage > 0 && item.endPage > 0 && entry.endPage != item.endPage) {
                issues.add(issue("volume_toc", "warning", "volume", volumeNo, pieceNo, itemTitle,
                        "件 " + pieceNo + " 止页与卷内目录不一致：目录 " + entry.endPage
                                + " / 卷内 " + item.endPage));
            }
        }
        for (Item item : volume.items) {
            if (issues.size() >= 6) {
                break;
            }
            String pieceNo = item.pieceNo == null ? "" : item.pieceNo;
            if (!tocNos.contains(pieceNo)) {
                issues.add(issue("volume_toc", "warning", "volume", volumeNo, pieceNo, item.title,
                        "卷内件 " + pieceNo + "（" + item.title + "）未列入卷内目录"));
            }
        }
        return issues;
    }

    /** 件序：卷内件清单顺序应与法定排列顺序一致，只出 warning。 */
    private static List<Map<String, Object>> orderIssues(Volume volume, List<Entry> entries) {
        List<Map<String, Object>> issues = new ArrayList<>();
        String volumeNo = volume.volumeNo == null ? "" : volume.volumeNo;
        List<int[]> sequence = new ArrayList<>();
        for (int i = 0; i < volume.items.size(); i++) {
            Item item = volume.items.get(i);
            Entry hit = null;
            int hits = 0;
            for (Entry e : entries) {
                if (matches(e, item)) {
                    hits++;
                    if (hit == null) {
                        hit = e;
                    }
                }
            }
            if (hit != null && hits == 1) {
                sequence.add(new int[]{hit.order, i});
            }
        }
        for (int k = 1; k < sequence.size() && issues.size() < 3; k++) {
            if (sequence.get(k)[0] >= sequence.get(k - 1)[0]) {
                continue;
            }
            Item prev = volume.items.get(sequence.get(k - 1)[1]);
            Item cur = volume.items.get(sequence.get(k)[1]);
            issues.add(issue("piece_order", "warning", "piece", volumeNo, cur.pieceNo, cur.title,
                    "件序与法定顺序不符：件 " + cur.pieceNo + "（" + cur.title
                            + "）应排在件 " + prev.pieceNo + "（" + prev.title + "）之前"));
        }
        return issues;
    }

    /** 条件缺件：{@code whenAny} 命中即要求 {@code requires} 至少存在一件。 */
    public static final class Condition {
        final String id;
        final String rule;
        final String severity;
        final String hint;
        final List<String> triggers;
        final List<String> required;

        Condition(String id, String rule, String severity, String hint,
                  List<String> triggers, List<String> required) {
            this.id = id;
            this.rule = rule == null || rule.isBlank() ? "missing_attachment" : rule;
            this.severity = severity == null || severity.isBlank() ? "warning" : severity;
            this.hint = hint == null ? "" : hint;
            this.triggers = triggers;
            this.required = required;
        }
    }

    private static final List<Condition> CONDITIONS = List.of(
            new Condition("public-service", "missing_attachment", "warning",
                    "下落不明（无法直接送达）的证明材料",
                    List.of("公告送达"),
                    List.of("下落不明", "查无此人", "无法送达", "公告送达情况说明")),
            new Condition("appeal-withdrawal", "missing_attachment", "warning", "上诉状",
                    List.of("退卷函", "移送函存根"),
                    List.of("上诉状", "上诉书")),
            new Condition("appeal-transfer", "missing_attachment", "warning", "移送函（存根）/退卷函",
                    List.of("上诉状", "上诉书"),
                    List.of("移送函", "退卷函")));

    private static List<Map<String, Object>> attachmentIssues(Volume volume, List<Condition> conditions) {
        List<Map<String, Object>> issues = new ArrayList<>();
        String volumeNo = volume.volumeNo == null ? "" : volume.volumeNo;
        for (Condition c : conditions) {
            if (issues.size() >= 3) {
                break;
            }
            if (findItem(volume, c.required) != null) {
                continue;
            }
            Item trigger = findItem(volume, c.triggers);
            if (trigger == null) {
                continue;
            }
            issues.add(issue(c.rule, c.severity, "piece", volumeNo, trigger.pieceNo,
                    trigger.title,
                    "条件缺件：件 " + trigger.pieceNo + "（" + trigger.title + "）为"
                            + triggerText(c, trigger) + "，应附" + c.hint));
        }
        return issues;
    }

    private static String triggerText(Condition c, Item item) {
        String text = text(item);
        for (String key : c.triggers) {
            if (text.contains(key)) {
                return "「" + key + "」";
            }
        }
        return "「" + c.triggers.get(0) + "」";
    }

    private static Item findItem(Volume volume, List<String> keys) {
        for (Item item : volume.items) {
            String text = text(item);
            for (String key : keys) {
                if (text.contains(key)) {
                    return item;
                }
            }
        }
        return null;
    }

    private static String text(Item item) {
        return (item.level2 == null ? "" : item.level2)
                + " " + (item.title == null ? "" : item.title);
    }

    private static Boolean declared(Volume volume, Entry e) {
        String key = volumeKey(e);
        if (key == null || volume.volumeFlags == null) {
            return null;
        }
        return volume.volumeFlags.get(key);
    }

    private static String volumeKey(Entry e) {
        String title = e.title == null ? "" : e.title;
        if (title.contains("封面")) {
            return "cover";
        }
        if (title.contains("卷内目录")) {
            return "toc";
        }
        if (title.contains("备考表")) {
            return "memo";
        }
        if (title.contains("证物袋")) {
            return "pouch";
        }
        if (title.contains("封底")) {
            return "back";
        }
        return null;
    }

    private static String stageText(Volume volume) {
        return volume.stage == null ? "未提供" : volume.stage;
    }

    private static boolean matches(Entry e, Item item) {
        String level2 = item.level2 == null ? "" : item.level2;
        if (!level2.isBlank()) {
            for (String t : e.titles) {
                if (level2.contains(t)) {
                    return true;
                }
            }
            if (level2.contains(stripVolumeSuffix(e.title))) {
                return true;
            }
        }
        String title = item.title == null ? "" : item.title;
        for (String t : e.titles) {
            if (title.contains(t)) {
                return true;
            }
        }
        return false;
    }

    private static String stripVolumeSuffix(String s) {
        return s == null ? "" : s.replace("（正卷）", "").replace("(正卷)", "").trim();
    }

    /**
     * 缺件 issue：模板条目带 severity 时按模板（内置基线为 null，沿用「归档 error / 在建 warning」）。
     * 附加 {@code expectedTitle}/{@code pieceTypeCode}，供结果页标出"应为哪一类件"（附加式字段）。
     */
    private static Map<String, Object> missingIssue(Entry e, String volumeNo, String scope,
                                                    String pieceTitle, String message, boolean archived) {
        String severity = e.severity == null || e.severity.isBlank()
                ? (archived ? "error" : "warning") : e.severity;
        Map<String, Object> m = issue("missing_piece", severity, scope, volumeNo, null, pieceTitle, message);
        m.put("expectedTitle", e.title);
        if (e.typeCode != null && !e.typeCode.isBlank()) {
            m.put("pieceTypeCode", e.typeCode);
        }
        return m;
    }

    /**
     * 该必备件是否不在本次提交范围内（只统计、不出 issue）。
     *
     * <ul>
     *   <li>内置基线（开关关闭）：不启用范围语义，行为不变；</li>
     *   <li>{@code full}：全量判定，不排除；</li>
     *   <li>{@code supplement}：单件补交，未命中一律不判缺件；</li>
     *   <li>{@code casual}：声明了范围时只判范围内；未声明时只判已提交材料。</li>
     * </ul>
     */
    private static boolean outOfSubmission(Entry e, Submission submission, boolean templateDriven) {
        if (!templateDriven || !e.required || submission == null) {
            return false;
        }
        if (submission.full()) {
            return false;
        }
        if (submission.supplement()) {
            return true;
        }
        if (!submission.scoped()) {
            return true;
        }
        for (String word : submission.scopePieceTypes) {
            if (titleHits(e, word)) {
                return false;
            }
        }
        for (String word : submission.declaredRequired) {
            if (titleHits(e, word)) {
                return false;
            }
        }
        return true;
    }

    private static boolean titleHits(Entry e, String word) {
        if (word == null || word.isBlank()) {
            return false;
        }
        String w = word.trim();
        if (w.equals(e.title)) {
            return true;
        }
        for (String t : e.titles) {
            if (t.equals(w) || t.contains(w) || w.contains(t)) {
                return true;
            }
        }
        return false;
    }

    /** 模板条目的附加列（内置基线不写，保持旧结果结构不变）。 */
    private static void putTemplateFields(Map<String, Object> row, Entry e) {
        if (e.typeCode == null || e.typeCode.isBlank()) {
            return;
        }
        row.put("typeCode", e.typeCode);
        row.put("level2", e.level2);
        if (e.mergeAs != null && !e.mergeAs.isBlank()) {
            row.put("mergeAs", e.mergeAs);
        }
    }

    private static Map<String, Object> issue(String rule, String severity, String scope,
                                             String volumeNo, String pieceNo, String pieceTitle,
                                             String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule", rule);
        m.put("severity", severity);
        m.put("scope", scope);
        m.put("volumeNo", volumeNo);
        m.put("pieceNo", pieceNo);
        m.put("pieceTitle", pieceTitle);
        m.put("message", message);
        return m;
    }

    private static int count(List<Entry> entries, boolean required) {
        int n = 0;
        for (Entry e : entries) {
            if (e.required == required) {
                n++;
            }
        }
        return n;
    }

    private static int countSeverity(List<Map<String, Object>> issues, String severity) {
        int n = 0;
        for (Map<String, Object> issue : issues) {
            if (severity.equals(issue.get("severity"))) {
                n++;
            }
        }
        return n;
    }

    private static List<Map<String, Object>> listOf(Object value) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (value instanceof List) {
            for (Object o : (List<?>) value) {
                if (o instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) o;
                    out.add(m);
                }
            }
        }
        return out;
    }

    private static List<String> listOfStrings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List) {
            for (Object o : (List<?>) value) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    private static int intOf(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static int intOf(JsonNode node, String key) {
        JsonNode v = node == null ? null : node.get(key);
        return v == null || v.isNull() ? 0 : v.asInt(0);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String text(JsonNode node, String key) {
        JsonNode v = node == null ? null : node.get(key);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText("");
        return s.isEmpty() ? null : s;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
