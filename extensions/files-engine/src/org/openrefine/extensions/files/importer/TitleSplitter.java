package org.openrefine.extensions.files.importer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TitleSplitter {

    public static final double DEFAULT_SIMILARITY_THRESHOLD = 0.9;

    /** 题名要素在 AIMP 逐页结果里的 key（与 {@code pageValues}/{@code confidences} 一致） */
    public static final String TITLE_KEY = "title";

    public static class Piece {
        public int startPage;
        public int endPage;
        /**
         * 卷内页号（按文件名解析，见 {@link UnitScanner#parseInternalPageNo}）。
         * 文件名为普通命名（无 4 位以上编号）时为 null，此时以 startPage/endPage 的列表序号为准。
         */
        public Integer startPageNo;
        public Integer endPageNo;
        public String title = "";
        /**
         * 规则渲染后的四级件名（「原告赵建明借条」）。仅在非生成式引擎（题名里没有方别/当事人）
         * 降级分件时由 {@link PartyTitleComposer} 填充；空串表示沿用 {@link #title}。
         */
        public String level4Title = "";
        /** 题名来源：model（模型题名）/ mixed（模型文种 + 规则方别当事人）/ rule（纯规则命名）。 */
        public String titleSource = "";
        /** 规则判定出的方别（原告/被告…），认不出为 null。 */
        public String partySide;
        /** 规则判定出的当事人规范名（取封面名单写法），未知为空串。 */
        public String partyName = "";
        /** 纯文种（剥掉方别与当事人后的题名片段）。 */
        public String docType = "";
        /**
         * 三级「材料类别」（AIMP 归桶结果，客户词表口径）。空串表示 AIMP 未给出，
         * 由下游（RCP）回退本地词表分类。
         */
        public String level3Title = "";
        /** 材料类别来源：table/retrieval/neighbor-inherit/model/missing（见 AIMP 归桶）。 */
        public String level3Source = "";

        public int pageCount() {
            return endPage - startPage + 1;
        }

        /** 「起止页号」列取值：优先卷内页号，缺失时回退列表序号 */
        public String pageRangeLabel() {
            int s = startPageNo != null ? startPageNo : startPage;
            int e = endPageNo != null ? endPageNo : endPage;
            return String.format("%04d-%04d", s, e);
        }
    }

    public static String normalize(String title) {
        if (title == null) return "";
        String s = Normalizer.normalize(title, Normalizer.Form.NFKC);
        s = s.replaceAll("\\s+", "");
        s = s.replaceAll("[\\p{Punct}，。、；：？！“”‘’（）《》〈〉【】〔〕…—·～￥]", "");
        return s.toLowerCase();
    }

    public static double similarity(String a, String b) {
        String x = normalize(a);
        String y = normalize(b);
        if (x.isEmpty() && y.isEmpty()) return 1.0;
        if (x.isEmpty() || y.isEmpty()) return 0.0;
        int dist = levenshtein(x, y);
        return 1.0 - dist / (double) Math.max(x.length(), y.length());
    }

    /** 卷级汇总题名的文种兜底词：卷内文种缺失或出现多种文种时统一使用 */
    public static final String FALLBACK_DOC_TYPE = "材料";

    /**
     * 公文文种词表：法定 15 种（《党政机关公文处理工作条例》第八条）
     * + 档案归档实务中常见的事务类文书文种（国家档案局令第 8 号口径）。
     */
    private static final Set<String> DOC_TYPES = new HashSet<>(Arrays.asList(
            // 法定公文 15 种
            "决议", "决定", "命令", "令", "公报", "公告", "通告", "意见", "通知",
            "通报", "报告", "请示", "批复", "议案", "函", "纪要",
            // 事务类文书常见文种
            "计划", "规划", "方案", "安排", "总结", "调查报告", "调研报告", "章程",
            "条例", "办法", "规则", "规程", "制度", "规定", "守则", "公约", "简报",
            "会议记录", "讲话", "开幕词", "闭幕词", "介绍信", "花名册", "招标书",
            "投标书", "中标通知书", "合同", "协议", "协定", "议定书", "备忘录",
            "意向书", "报表", "邀请信", "感谢信", "贺信", "请柬", "证明", "名单",
            "议程", "大事记"));

    /** 题名的「关于 + 事由 + 的 + 文种」结构，贪婪匹配使「的」落在最后一个，事由自带「的」时不误切 */
    private static final Pattern CAUSE_DOC_TYPE = Pattern.compile("^关于(.+)的(.+)$");

    /**
     * 拆解题名：符合「关于…的…」且尾段为已知文种时返回 {@code {事由, 文种}}；
     * 不符合该结构或尾段无法识别为文种时，整条题名作为事由、文种返回空串。
     */
    public static String[] splitCauseAndDocType(String title) {
        if (title == null) return new String[] { "", "" };
        String t = title.trim();
        if (t.isEmpty()) return new String[] { "", "" };
        Matcher m = CAUSE_DOC_TYPE.matcher(t);
        if (m.matches()) {
            String cause = m.group(1).trim();
            String docType = m.group(2).trim();
            if (!cause.isEmpty() && DOC_TYPES.contains(docType)) {
                return new String[] { cause, docType };
            }
        }
        return new String[] { t, "" };
    }

    /**
     * 文本末尾是否已是某个文种词。卷级题名拼接时用它避免「…的通知的通知」这类叠字：
     * 事由条整条保留（尾段文种不在词表）时会自带文种后缀（如「…的紧急通知」）。
     */
    public static boolean endsWithDocType(String text) {
        if (text == null || text.isEmpty()) return false;
        for (String docType : DOC_TYPES) {
            if (text.endsWith(docType)) return true;
        }
        return false;
    }

    public static List<Piece> split(List<String> pageTitles) {
        return split(pageTitles, DEFAULT_SIMILARITY_THRESHOLD, true);
    }

    public static List<Piece> split(List<String> pageTitles, double threshold) {
        return split(pageTitles, threshold, true);
    }

    /**
     * @param splitWhenCurrentUntitled 当前件尚未取得题名时，遇到有题名的页是否另起一件。
     *        卷宗封面、卷内目录这类无题名页在先时，取 true 可避免它们把紧随其后的
     *        第一件并进来（诉讼档案使用）；取 false 则保持原有的归并行为。
     */
    public static List<Piece> split(List<String> pageTitles, double threshold,
                                    boolean splitWhenCurrentUntitled) {
        return split(pageTitles, threshold, splitWhenCurrentUntitled, null, null,
                DEFAULT_TITLE_CONFIDENCE_THRESHOLD);
    }

    /** 「件首」门限：题名置信度须达到该值，且本页须有版面标题块，才认作新件起点 */
    public static final double DEFAULT_TITLE_CONFIDENCE_THRESHOLD = 0.9;

    /**
     * 四级口径分件（诉讼档案，卷内每份材料一件）。
     *
     * <p>切分金标准：{@code (文种, 当事人, 对象)} 任一变化即切，三者都不变才合并。
     * 与相似度分件的区别在于「不相似就切」——相似度分件会给无题名/弱相似页留合并余地，
     * 四级口径下每份独立材料各自成件（对齐甲方《民事一审案件编目规则》的「材料编目名」行）。
     *
     * <p><b>对象</b>（{@code pageObjects}）是法院文书切件的第三元：传票、送达回证、执行通知书、
     * 询问/讯问笔录等文种的责任者都是法院，只有相对人（受送达人/被传唤人/被询问人/被执行人）
     * 能区分同文种的多份材料。缺对象时退化为 (文种, 当事人) 两元——刑事卷会因此大幅并件
     * （实测 70 件并成 39 件），故调用方应尽量把页级对象传进来。
     *
     * <p>无题名的页一律视为续页并入当前件（Grounding 只回原文标题，续页通常无标题）。
     *
     * @param pageTitles  逐页题名，下标 0 为卷内第 1 页
     * @param pageParties 逐页当事人，可为 null（此时只按文种切）
     * @param pageObjects 逐页对象（文书相对人），可为 null
     */
    public static List<Piece> splitByMaterial(List<String> pageTitles, List<String> pageParties,
                                              List<String> pageObjects) {
        return splitByMaterial(pageTitles, pageParties, pageObjects, null, null,
                MATERIAL_TITLE_CONFIDENCE_THRESHOLD);
    }

    public static List<Piece> splitByMaterial(List<String> pageTitles, List<String> pageParties) {
        return splitByMaterial(pageTitles, pageParties, null);
    }

    /**
     * 四级口径分件的「件首」门限：题名置信度低于它、或本页没有版面标题块时，该页视作续页。
     *
     * <p>与文书档案的 0.9 不同，这里取 0.6：诉讼档案的页级题名常带表格栏位/版记噪声，
     * 门限过高会把真件首当续页并掉（漏件比多切一件更难纠正）。
     */
    public static final double MATERIAL_TITLE_CONFIDENCE_THRESHOLD = 0.6;

    /** 题名**尾部**的括号补注：「（截图）」「(续)」「（1）」等。只剥离尾部，中间括号不动。 */
    private static final Pattern TRAILING_BRACKET = Pattern.compile("[（(][^（）()]*[）)]\\s*$");

    /**
     * 带「件首双重证据」门控的四级口径分件。
     *
     * <p>证据（题名置信度／版面标题块）任一缺失时退化为不门控——保持与旧调用方一致：
     * Grounding 引擎不回置信度，不能因此把整卷并成一件。
     */
    public static List<Piece> splitByMaterial(List<String> pageTitles, List<String> pageParties,
                                              List<String> pageObjects,
                                              List<Map<String, Double>> pageConfidences,
                                              List<Integer> titleRegionCounts,
                                              double titleConfidenceThreshold) {
        List<Piece> pieces = new ArrayList<>();
        if (pageTitles == null || pageTitles.isEmpty()) return pieces;
        boolean gated = pageConfidences != null && titleRegionCounts != null;
        Piece current = null;
        String currentParty = "";
        String currentObject = "";
        for (int i = 0; i < pageTitles.size(); i++) {
            String raw = pageTitles.get(i);
            String title = raw == null ? "" : raw.trim();
            // 页码/角标类纯数字题名不是文种，按无题名处理（续页）
            if (isNoiseTitle(title)) title = "";
            String party = elementAt(pageParties, i);
            String object = elementAt(pageObjects, i);
            if (current == null) {
                current = newPiece(i + 1, title);
                pieces.add(current);
                currentParty = party;
                currentObject = object;
                continue;
            }
            // 无题名 / 证据不足：续页，并回当前件
            boolean continuation = title.isEmpty()
                    || (gated && !hasMaterialTitleEvidence(i, pageConfidences,
                            titleRegionCounts, titleConfidenceThreshold));
            if (continuation
                    || sameMaterial(current.title, title, currentParty, party, currentObject, object)
                    || continuationPiece(current.title, title)
                    || mergeAcrossBreed(current.title, title)) {
                current.endPage = i + 1;
                if (current.title.isEmpty()) current.title = title;
                if (currentParty.isEmpty()) currentParty = party;
                if (currentObject.isEmpty()) currentObject = object;
                continue;
            }
            current = newPiece(i + 1, title);
            pieces.add(current);
            currentParty = party;
            currentObject = object;
        }
        return pieces;
    }

    private static Piece newPiece(int page, String title) {
        Piece p = new Piece();
        p.startPage = page;
        p.endPage = page;
        p.title = title;
        return p;
    }

    private static String elementAt(List<String> values, int i) {
        if (values == null || i >= values.size() || values.get(i) == null) return "";
        return values.get(i).trim();
    }

    /**
     * 同一份材料：题名相同（或互相包含），且当事人、对象都不冲突。
     *
     * <p>当事人/对象任一为空视为不冲突（页级未抽到时不作为切分依据）；
     * 都有值时必须一致或互为包含（「赵建明、王秀兰」与「王秀兰」）。
     *
     * <p>对象还要「有区分力」才参与切分：页级把标题抄成对象的（借条页 object=借条、
     * 收条页 object=收条）在语义上没有相对人，不能因此切件——一侧对象无区分力时按
     * 「不确定不切」处理。该规则与文种无关：连续多张发票、聊天记录截图的同题名页，
     * 只要对象抽成标题或抽空，都并成一件；只有两侧都是真实相对人且不同才切
     * （传票按受送达人、笔录按被询问人）。
     */
    private static boolean sameMaterial(String prevTitle, String title,
                                        String prevParty, String party,
                                        String prevObject, String object) {
        String a = normalize(prevTitle);
        String b = normalize(title);
        if (a.isEmpty() || b.isEmpty()) return true;
        if (!(a.equals(b) || a.contains(b) || b.contains(a))) return false;
        return compatible(prevParty, party)
                && objectsCompatible(prevTitle, title, prevObject, object);
    }

    /**
     * 对象兼容性：任一侧无对象、或对象只是把题名/文种抄了一遍时视为不冲突；
     * 两侧都是真实相对人时，须一致或互为包含。
     */
    private static boolean objectsCompatible(String prevTitle, String title,
                                             String prevObject, String object) {
        String a = normalize(prevObject);
        String b = normalize(object);
        if (a.isEmpty() || b.isEmpty()) return true;
        if (!hasDistinctObject(prevObject, prevTitle) || !hasDistinctObject(object, title)) {
            return true;
        }
        return a.equals(b) || a.contains(b) || b.contains(a);
    }

    /**
     * 页级对象是否有区分力：与题名/文种同化（如借条页的 object=借条）说明抽取把
     * 标题当成了相对人，不能作为切件边界。
     */
    static boolean hasDistinctObject(String object, String title) {
        String o = normalize(object);
        if (o.isEmpty()) return false;
        String t = normalize(title);
        if (!t.isEmpty() && (o.equals(t) || t.contains(o) || o.contains(t))) return false;
        return true;
    }

    private static boolean compatible(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        if (a.isEmpty() || b.isEmpty()) return true;
        return a.equals(b) || a.contains(b) || b.contains(a);
    }

    /**
     * 跨文种但属同一份材料的合并（编目规则把多份材料编为一个条项）。
     *
     * <p>只收录「材料级」也属同一件的两类，避免与材料级真值冲突：
     * 诉状及其附件（第十四条(7)「××状及附件」）、卷末送达回证与其后的邮件/EMS 底单。
     * 条项级归并（公函与执业证、执行手续材料全并、退卷函与回执）不在此处——
     * 甲方四级编目按「一份材料一件」计，这类合并会凭空少件。
     */
    static boolean mergeAcrossBreed(String prevTitle, String title) {
        String prev = normalize(prevTitle);
        String next = normalize(title);
        if (prev.isEmpty() || next.isEmpty()) return false;
        if (isPleading(prev) && next.contains("附件")) return true;
        boolean prevReceipt = prev.contains("送达回证");
        boolean nextReceipt = next.contains("送达回证");
        if (prevReceipt && isMailSlip(next)) return true;
        if (nextReceipt && isMailSlip(prev)) return true;
        return false;
    }

    private static boolean isPleading(String norm) {
        return norm.contains("起诉状") || norm.contains("上诉状") || norm.contains("答辩状");
    }

    /** 续页标记：题名尾部的「（续）」「(续)」「-续」「—续」「－续」。 */
    static boolean hasContinuationMark(String title) {
        if (title == null) return false;
        String t = title.trim();
        return t.contains("（续）") || t.contains("(续)")
                || t.endsWith("-续") || t.endsWith("—续") || t.endsWith("－续");
    }

    /**
     * 纯数字题名（页码、页眉/页脚角标）不是文种，不能作为件题名。
     *
     * <p>页级模型偶尔把版面右上角的编号当成标题（实测 GD06-2026-M1-0215 的起诉书第 2 页
     * 返回题名「3」），会凭空切出一个名为「3」的件。此类值按「无题名」处理，即并入前件。</p>
     */
    public static boolean isNoiseTitle(String title) {
        String n = normalize(title);
        if (n.isEmpty()) return false;
        return n.matches("\\d{1,4}");
    }

    /**
     * 件题名清洗（与「（续）」归一化同口径），**只动题名尾部**：
     * <ol>
     *   <li>纯数字角标（页码/页眉编号）→ 空串（视为续页）；</li>
     *   <li>去掉尾部的括号补注（全/半角都算），如「催收短信记录（截图）」「请假单（补办）」；
     *       中间的括号是文种的一部分，原样保留（如「证据核对（质证）记录」
     *       「证据材料（一）银行流水」）。</li>
     * </ol>
     * 不做责任者判断（法院/当事人都按同一口径）：真实卷宗里法院文书的尾部括号补注
     * 属于 mock 自造形态，先不为其增加分支，避免引入误判。
     */
    public static String cleanTitle(String title) {
        if (title == null) return "";
        String t = title.trim();
        if (t.isEmpty()) return "";
        if (isNoiseTitle(t)) return "";
        return stripTrailingBracket(t);
    }

    /** 循环剥掉题名尾部的括号补注（「xx（1）（副本）」这类多重后缀一并去掉）。 */
    static String stripTrailingBracket(String title) {
        if (title == null) return "";
        String t = title.trim();
        while (true) {
            Matcher m = TRAILING_BRACKET.matcher(t);
            if (!m.find()) break;
            String next = t.substring(0, m.start()).trim();
            if (next.isEmpty()) break;   // 整条题名就是括号时不动
            t = next;
        }
        return t;
    }


    /**
     * 「××（续）」并入前一件：去掉括号内容与续页后缀后基名相同即视为同一份。
     *
     * <p>例：「催收短信记录（截图）」与「催收短信记录（续）」、「微信聊天记录（截图）」与
     * 「微信聊天记录（续）」。这类续页在利用层没有独立成件的意义，且前件可能带「（截图）」
     * 等修饰，故不能只靠题名包含判断。
     */
    static boolean continuationPiece(String prevTitle, String title) {
        if (!hasContinuationMark(title)) return false;
        String a = baseTitle(prevTitle);
        String b = baseTitle(title);
        return !b.isEmpty() && b.equals(a);
    }

    /** 去掉尾部括号补注与续页后缀后的题名基名（中间括号保留）。 */
    static String baseTitle(String title) {
        if (title == null) return "";
        String t = stripTrailingBracket(title);
        t = t.replaceAll("[-—－]\\s*续\\s*$", "").replaceAll("\\s*续\\s*$", "");
        // 「xx（续）」去掉「续」后可能又露出尾部括号，再剥一次
        return normalize(stripTrailingBracket(t));
    }

    /**
     * 对卷级模型给出的件序列做相邻件合并。
     *
     * <p>模型常把连续多页的同名材料逐页切成独立件，并给每页起同一个题名（甚至编出
     * 「（续）」后缀）：笔录/裁判文书会这样切，连续多张借条、聊天记录截图也会。
     * 判据与规则层完全一致——(题名, 当事人, 对象) 三元组不冲突即同一件，不按文种区分；
     * 对象与题名同化（借条页 object=借条）时不算冲突。
     */
    public static List<Piece> mergeBodyPieces(List<Piece> pieces, List<String> pageParties,
                                              List<String> pageObjects) {
        if (pieces == null || pieces.size() < 2) return pieces;
        List<Piece> out = new ArrayList<>(pieces.size());
        for (Piece p : pieces) {
            Piece prev = out.isEmpty() ? null : out.get(out.size() - 1);
            if (prev == null) {
                out.add(p);
                continue;
            }
            String party = elementAt(pageParties, p.startPage - 1);
            String prevParty = elementAt(pageParties, prev.startPage - 1);
            String object = elementAt(pageObjects, p.startPage - 1);
            String prevObject = elementAt(pageObjects, prev.startPage - 1);
            if (sameMaterial(prev.title, p.title, prevParty, party, prevObject, object)
                    || continuationPiece(prev.title, p.title)) {
                prev.endPage = Math.max(prev.endPage, p.endPage);
                prev.title = preferFullTitle(prev.title, p.title);
                continue;
            }
            out.add(p);
        }
        return out;
    }

    /** 合并后取更完整的题名：去掉「（续）」后缀者优先，其次取较长者。 */
    private static String preferFullTitle(String a, String b) {
        String na = stripContinued(a);
        String nb = stripContinued(b);
        boolean aStripped = !na.equals(a);
        boolean bStripped = !nb.equals(b);
        if (aStripped && !bStripped) return nb;
        if (bStripped && !aStripped) return na;
        return na.length() >= nb.length() ? na : nb;
    }

    private static String stripContinued(String title) {
        if (title == null) return "";
        return title.replace("（续）", "").replace("(续)", "").trim();
    }

    private static boolean isMailSlip(String norm) {
        return norm.contains("ems") || norm.contains("邮件") || norm.contains("邮寄")
                || norm.contains("底单") || norm.contains("查询单");
    }

    /**
     * 双重证据分件：在「题名与当前件不同（相似度 &lt; threshold）」这一条之外，再加两条必要条件
     * ——<b>题名置信度达标</b>（{@code pageConfidences[i]["title"] ≥ titleConfidenceThreshold}）
     * 且 <b>本页存在版面标题块</b>（{@code titleRegionCounts[i] ≥ 1}，来自版面分析模型 l0.pt
     * 的 label=10，与印章检测共用同一次推理）。
     *
     * <p>为什么要加这两条：模型在正文续页上会「不回 null」而编造题名，或把正文句、签字栏标签
     * 当作题名回吐。这类取值在页面原文逐字存在、形态也合法，规则层拦不住；但它们的题名置信度
     * 偏低（实测幻觉题名 c≈0.85 &lt; 真题名 c≈0.95），且页面并无版面标题块。反过来，真实件首
     * 一律检出标题块、且题名置信度高。故以「高置信度题名 + 版面标题块」作为件首的强证据。
     *
     * <p>证据不满足时<b>不是</b>另起一件，而是<b>归入当前件</b>（视作续页）——这正是不想发生的
     * 「幻觉题名切出新件」的纠正方向。
     *
     * @param pageConfidences 逐页的要素置信度（与 pageTitles 同序）；为 null 或某页缺 "title"
     *                       置信度时该页按「证据不足」处理（归入当前件）。
     * @param titleRegionCounts 逐页的版面标题块数量（与 pageTitles 同序）；为 null 或某页缺失时
     *                        该页按「无标题块」处理。
     * @param titleConfidenceThreshold 题名置信度门限（通常取 {@link #DEFAULT_TITLE_CONFIDENCE_THRESHOLD}）。
     *                        两证据中任一传入 null 时，本重载退化为 {@link #split(List, double, boolean)}。
     */
    public static List<Piece> split(List<String> pageTitles, double threshold,
                                    boolean splitWhenCurrentUntitled,
                                    List<Map<String, Double>> pageConfidences,
                                    List<Integer> titleRegionCounts,
                                    double titleConfidenceThreshold) {
        boolean gated = pageConfidences != null || titleRegionCounts != null;
        List<Piece> pieces = new ArrayList<>();
        if (pageTitles == null || pageTitles.isEmpty()) return pieces;
        Piece current = null;
        for (int i = 0; i < pageTitles.size(); i++) {
            String raw = pageTitles.get(i);
            String rawTitle = raw == null ? "" : raw.trim();
            String norm = normalize(rawTitle);
            boolean newPiece;
            if (current == null) {
                newPiece = true;
            } else {
                String currentNorm = normalize(current.title);
                if (norm.isEmpty()) {
                    // 本页无题名（卷宗封面、卷内目录、正文续页等）→ 归入当前件
                    newPiece = false;
                } else if (currentNorm.isEmpty()) {
                    // 当前件尚未取得题名，而本页有题名 → 按调用方策略决定是否另起一件
                    newPiece = splitWhenCurrentUntitled;
                } else {
                    // 题名与当前件不同：是否另起一件，由「高置信度题名 + 版面标题块」双重证据裁定
                    boolean titleDiffers = similarity(norm, currentNorm) < threshold;
                    newPiece = titleDiffers
                            && (!gated || hasTitleEvidence(i, pageConfidences, titleRegionCounts,
                                    titleConfidenceThreshold));
                }
            }
            if (newPiece) {
                current = new Piece();
                current.startPage = i + 1;
                current.endPage = i + 1;
                current.title = rawTitle;
                pieces.add(current);
            } else {
                current.endPage = i + 1;
                if (current.title.isEmpty()) {
                    current.title = rawTitle;
                }
            }
        }
        return pieces;
    }

    /**
     * 第 i 页是否具备「件首」双重证据：题名置信度达门限 且 版面标题块数 ≥ 1。
     * 任一证据缺失（列表越界、该页无置信度、该页版面块未回传）一律判为不满足——宁可归并，
     * 也不因证据缺失而误切新件。
     */
    private static boolean hasTitleEvidence(int i, List<Map<String, Double>> pageConfidences,
                                            List<Integer> titleRegionCounts,
                                            double titleConfidenceThreshold) {
        double conf = -1.0;
        if (pageConfidences != null && i < pageConfidences.size()) {
            Map<String, Double> m = pageConfidences.get(i);
            if (m != null) {
                Double c = m.get(TITLE_KEY);
                if (c != null) conf = c;
            }
        }
        if (conf < titleConfidenceThreshold) return false;
        if (titleRegionCounts == null || i >= titleRegionCounts.size()) return false;
        Integer regions = titleRegionCounts.get(i);
        return regions != null && regions >= 1;
    }

    /**
     * 材料级「件首页」证据：以题名置信度为准；置信度缺失时才退回看版面标题块。
     *
     * <p>与文书档案的 {@link #hasTitleEvidence} 不同：法院卷里的传票、送达回证、执行通知书、
     * 居民身份证、收据等是表格/证件类页面，标题印在表格内，版面分析检不出「标题块」。
     * 若仍把 title_regions≥1 作为必要条件，这些真件首会被当成续页并入前一件
     * （实测 WH11 的换押证吞并 3 页开庭传票、HB07 的公告费收据吞并 2 页身份证）。
     * 故这里只要求置信度达标；置信度缺失（如 Grounding 引擎）时才看版面块。
     */
    private static boolean hasMaterialTitleEvidence(int i, List<Map<String, Double>> pageConfidences,
                                                    List<Integer> titleRegionCounts,
                                                    double titleConfidenceThreshold) {
        if (pageConfidences != null && i < pageConfidences.size()) {
            Map<String, Double> m = pageConfidences.get(i);
            Double c = m == null ? null : m.get(TITLE_KEY);
            if (c != null) {
                return c >= titleConfidenceThreshold;
            }
        }
        if (titleRegionCounts == null || i >= titleRegionCounts.size()) return false;
        Integer regions = titleRegionCounts.get(i);
        return regions != null && regions >= 1;
    }

    public static int levenshtein(String a, String b) {
        int la = a.length();
        int lb = b.length();
        if (la == 0) return lb;
        if (lb == 0) return la;
        int[] prev = new int[lb + 1];
        int[] curr = new int[lb + 1];
        for (int j = 0; j <= lb; j++) prev[j] = j;
        for (int i = 1; i <= la; i++) {
            curr[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= lb; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[lb];
    }
}
