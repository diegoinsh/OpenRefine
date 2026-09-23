/*
 * Data Quality Extension - Column Semantics
 *
 * 列头归一化：把「规则里配置的列名」解析到项目实际的列。
 * 人工录入的条目表与批量提取生成的条目表列头并不一致（例如「起止页号」/「起讫页号」/「首尾页号」、
 * 「卷号」/「案卷号」、「文件夹路径」/「源路径」），若按列名精确匹配就会静默跳过、造成比对错位。
 * 这里按语义槽位（Slot）归类，同槽位内的列名互为别名，从而让同一份规则同时适配两种来源。
 */
package com.google.refine.extension.quality.util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ColumnSemantics {

    /**
     * 语义槽位。同一槽位下的列名互为别名，可互相替代。
     */
    public enum Slot {
        /** 案卷号 / 卷号 */
        VOLUME_NO,
        /** 件号 / 序号 / 流水号 */
        PIECE_NO,
        /** 起始页号 / 起始页码 / 首页号 */
        START_PAGE,
        /** 终止页号 / 终止页码 / 尾页号 */
        END_PAGE,
        /** 起止页号 / 起讫页号 / 首尾页号（形如 0001-0005） */
        PAGE_RANGE,
        /** 页数 / 张数 */
        PAGE_COUNT,
        /** 文件夹路径 / 源路径 / 资源路径 */
        RESOURCE_PATH,
        /** 文件名（指向具体文件，PDF 分件时定位到单个 PDF） */
        RESOURCE_FILE,
        TITLE,
        RESPONSIBLE_PARTY,
        DOCUMENT_NUMBER,
        ISSUE_DATE,
        UNKNOWN
    }

    private static final Map<String, Slot> ALIASES = new HashMap<>();
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private static void alias(Slot slot, String... names) {
        for (String name : names) {
            ALIASES.put(normalize(name), slot);
        }
    }

    static {
        // 卷标识：案卷号（批量提取）/ 卷号（人工录入）
        alias(Slot.VOLUME_NO, "案卷号", "卷号", "案卷", "案卷编号", "卷编号", "案卷号数", "目录号", "全宗内案卷号",
                "volumeno", "volume_no", "volumenumber", "volume_number", "juanhao", "juanha", "ajh", "jh");

        // 件标识
        alias(Slot.PIECE_NO, "件号", "件次", "件序号", "顺序号", "序号", "流水号", "文件序号",
                "itemno", "item_no", "itemnumber", "item_number", "pieceno", "piece_no", "jianhao", "jianha", "jjh");

        // 起始页：首页号 + 页数 / 起始页码 + 终止页码 / 起始页号 + 卷尾号
        alias(Slot.START_PAGE, "起始页号", "起始页码", "起始页", "起页号", "起页码", "起页",
                "开始页号", "开始页码", "开始页", "首页号", "首页页码", "首页", "自页号", "自页码", "自页",
                "起始卷内页号", "卷内起始页号",
                "startpage", "start_page", "frompage", "from_page", "beginpage", "begin_page",
                "qsph", "qsym", "syph");

        // 终止页
        alias(Slot.END_PAGE, "终止页号", "终止页码", "终止页", "止页号", "止页码", "止页",
                "结束页号", "结束页码", "结束页", "尾页号", "尾页码", "尾页", "至页号", "至页码", "至页",
                "终止卷内页号", "卷内终止页号", "卷尾号",
                "endpage", "end_page", "topage", "to_page", "zzph", "zzym", "wyph");

        // 区间串：一个单元格内表达起止（0001-0005 / 0001～0005 / 1至5）
        alias(Slot.PAGE_RANGE, "起止页号", "起止页码", "起止页", "起讫页号", "起讫页码", "起讫页",
                "首尾页号", "首尾页码", "首尾页", "页号区间", "页码区间", "页码范围", "页号范围",
                "页号", "页码", "卷内页号", "卷内页码",
                "pagerange", "page_range", "pageinterval", "pagescope", "qzph", "qzym");

        // 页数
        alias(Slot.PAGE_COUNT, "页数", "张数", "总页数", "页码数", "页数合计", "总计页数", "件内页数",
                "pagecount", "page_count", "pagecounts", "pagesnum", "ys", "yshu", "zs");

        // 资源路径（定位到文件夹）
        alias(Slot.RESOURCE_PATH, "文件夹路径", "文件路径", "源路径", "资源路径", "目录路径", "存储路径",
                "归档路径", "电子文件路径", "文件存放路径", "路径",
                "folderpath", "folder_path", "filepath", "file_path", "sourcepath", "source_path", "path", "lj");

        // 资源文件（定位到具体文件）。注意「文件夹名」是案卷标识而非具体文件，不在此槽位
        alias(Slot.RESOURCE_FILE, "文件名", "文档名", "电子文件名", "文件名称",
                "filename", "file_name", "docname", "doc_name", "wjm");

        // 要素
        alias(Slot.TITLE, "题名", "案卷题名", "卷内题名", "文件题名", "档案题名", "标题",
                "title", "tm", "timing");
        alias(Slot.RESPONSIBLE_PARTY, "责任者", "作者", "发文单位", "拟稿人", "责任人",
                "responsible_party", "responsible", "zrz", "author");
        alias(Slot.DOCUMENT_NUMBER, "文号", "文件号", "发文号", "文档号", "文件编号",
                "document_number", "docno", "doc_no", "wh");
        alias(Slot.ISSUE_DATE, "成文日期", "成文时间", "发文日期", "发文时间", "形成日期", "日期", "时间",
                "issue_date", "documentdate", "document_date", "cwrq", "cwsj", "rq");
    }

    private ColumnSemantics() {
    }

    /**
     * 归一化列名：全角转半角、去掉空白、统一小写。
     */
    public static String normalize(String name) {
        if (name == null) return "";
        String s = Normalizer.normalize(name, Normalizer.Form.NFKC);
        s = s.replaceAll("\\s+", "");
        return s.toLowerCase();
    }

    /**
     * 取列名所属的语义槽位，未识别返回 {@link Slot#UNKNOWN}。
     */
    public static Slot slotOf(String columnName) {
        if (columnName == null) return Slot.UNKNOWN;
        Slot slot = ALIASES.get(normalize(columnName));
        return slot == null ? Slot.UNKNOWN : slot;
    }

    /**
     * 在给定列名清单中找出属于指定槽位的第一列。
     */
    public static String findColumnNameBySlot(Slot slot, List<String> columnNames) {
        if (slot == null || slot == Slot.UNKNOWN || columnNames == null) return null;
        for (String name : columnNames) {
            if (slotOf(name) == slot) return name;
        }
        return null;
    }

    /**
     * 把配置的列名解析为项目中的实际列名。
     * 匹配顺序：精确 → 归一化精确 → 同槽位别名 → 前后缀包含（兜底）。
     *
     * @return 实际列名；解析不到返回 null
     */
    public static String resolveColumnName(String configuredName, List<String> columnNames) {
        if (configuredName == null || configuredName.trim().isEmpty() || columnNames == null) return null;

        // 1. 精确
        for (String name : columnNames) {
            if (configuredName.equals(name)) return name;
        }

        // 2. 归一化精确（全角/空白差异）
        String target = normalize(configuredName);
        for (String name : columnNames) {
            if (target.equals(normalize(name))) return name;
        }

        // 3. 同槽位别名
        Slot slot = slotOf(configuredName);
        if (slot != Slot.UNKNOWN) {
            String byAlias = findColumnNameBySlot(slot, columnNames);
            if (byAlias != null) return byAlias;
        }

        // 4. 前后缀包含兜底（取最短的候选，避免误配到更长的复合列名）
        String best = null;
        for (String name : columnNames) {
            String normalizedName = normalize(name);
            if (normalizedName.isEmpty() || target.isEmpty()) continue;
            boolean hit = normalizedName.endsWith(target) || target.endsWith(normalizedName);
            if (hit && (best == null || normalizedName.length() < normalize(best).length())) {
                best = name;
            }
        }
        return best;
    }

    /**
     * 解析形如 0001-0005 / 0001～0005 / 1至5 的区间串。
     * 全角数字与全角连字符会先归一化；数字顺序颠倒时自动交换。
     *
     * @return {start, end}（1-based，含端点）；无法解析出两个数字时返回 null
     */
    public static int[] parsePageRange(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        Matcher matcher = NUMBER.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC));
        List<Integer> numbers = new ArrayList<>();
        while (matcher.find() && numbers.size() < 2) {
            try {
                numbers.add(Integer.parseInt(matcher.group()));
            } catch (NumberFormatException ignored) {
                // 数字过大，忽略
            }
        }
        if (numbers.size() < 2) return null;
        int a = numbers.get(0);
        int b = numbers.get(1);
        return new int[]{Math.min(a, b), Math.max(a, b)};
    }
}