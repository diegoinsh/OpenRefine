package org.openrefine.extensions.files.importer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TitleSplitter {

    public static final double DEFAULT_SIMILARITY_THRESHOLD = 0.9;

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
                    newPiece = similarity(norm, currentNorm) < threshold;
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
