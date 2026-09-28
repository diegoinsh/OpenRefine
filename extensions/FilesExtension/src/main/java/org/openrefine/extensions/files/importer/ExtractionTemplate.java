package org.openrefine.extensions.files.importer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 结构化要素提取模板枚举。
 * 定义案件模板和案卷模板的列结构、LLM提取键、是否生成案卷汇总行等。
 */
public enum ExtractionTemplate {

    /**
     * 档案要素案件模板（默认）
     * 列：档号, 题名, 责任者, 文号, 成文日期, 源路径, 提取状态
     */
    ARCHIVE_CASE("档案要素案件模板",
            new String[]{"档号", "题名", "责任者", "文号", "成文日期", "源路径", "提取状态"},
            "档号",
            false),

    /**
     * 档案要素案卷模板
     * 列：条目类型, 档号, 题名, 责任者, 成文日期, 源路径
     * 案卷模板不含文号和提取状态列
     */
    ARCHIVE_VOLUME("档案要素案卷模板",
            new String[]{"条目类型", "档号", "题名", "责任者", "成文日期", "源路径"},
            "档号",
            true),

    /**
     * 批量题名提取案卷模板（首要场景：法院卷宗材料整理）
     * 根目录下子文件夹=案卷，卷内图像按标题分件；列：
     * 案卷号, 件号, 起止页号, 页数, 题名, 责任者, 文号, 成文日期, 文件名, 提取状态, 备注, 文件夹路径
     * 「文件夹路径」是定位用的长路径，固定排在最后一列
     * 提取期项目只读（R-07 方案甲）
     */
    BATCH_TITLE_VOLUME("批量题名提取案卷模板",
            new String[]{"案卷号", "件号", "起止页号", "页数", "题名", "责任者", "文号", "成文日期", "文件名", "提取状态", "备注", "文件夹路径"},
            "案卷号",
            true),

    /**
     * 批量题名提取案件模板
     * 根目录下子文件夹/PDF=一件；列（无案卷号与起止页号，R-05）：
     * 文件夹名, 件号, 页数, 题名, 责任者, 文号, 成文日期, 文件名, 文件夹路径, 提取状态, 备注
     */
    BATCH_TITLE_CASE("批量题名提取案件模板",
            new String[]{"文件夹名", "件号", "页数", "题名", "责任者", "文号", "成文日期", "文件名", "文件夹路径", "提取状态", "备注"},
            "文件夹名",
            false);

    /** 批量模板中用于定位「具体文件」的列，仅在有 PDF 等多页文件时生成 */
    public static final String FILE_NAME_COLUMN = "文件名";

    /** 卷级汇总表（「卷级」Sheet）的列：每卷一行，由该卷卷内各件汇总生成 */
    public static final String[] VOLUME_SUMMARY_COLUMNS = {
            "案卷号", "题名", "责任者", "起始时间", "终止时间", "总页数", "卷内文件份数"
    };

    /** 文件所在文件夹路径列：卷内、卷级两张表都固定排在最后一列 */
    public static final String FOLDER_PATH_COLUMN = "文件夹路径";

    /**
     * 卷级要素 key：整卷同值的案卷级元数据。
     *
     * 《人民法院电子诉讼档案管理暂行办法》（法〔2013〕283 号）把 案由、当事人、审判程序（审级）、
     * 审理结果（结案方式）、密级、保管期限 列为**案卷级**著录项；它们整卷同值，若逐件重复写进卷内行，
     * 属系统冗余展示而不是著录位置。故案卷模板下这些要素只在「卷级」表出列，卷内表不再生成。
     * 口径与依据见 docs/design/archive-category-and-fields-standard.md 3.4。
     */
    public static final Set<String> VOLUME_LEVEL_ELEMENT_KEYS = new LinkedHashSet<>(Arrays.asList(
            "anyou", "dangshiren", "shenji", "jiean_fangshi", "baoguan_qixian",
            "miji", "kaifang_zhuangtai"));

    public static boolean isVolumeLevelElement(String key) {
        return key != null && VOLUME_LEVEL_ELEMENT_KEYS.contains(key);
    }

    public static List<String> volumeSummaryColumns() {
        List<String> columns = new ArrayList<>(Arrays.asList(VOLUME_SUMMARY_COLUMNS));
        columns.add(FOLDER_PATH_COLUMN);
        return columns;
    }

    private final String displayName;
    private final String[] columns;
    private final String keyColumn;
    private final boolean generateVolumeSummary;

    ExtractionTemplate(String displayName, String[] columns,
                       String keyColumn, boolean generateVolumeSummary) {
        this.displayName = displayName;
        this.columns = columns;
        this.keyColumn = keyColumn;
        this.generateVolumeSummary = generateVolumeSummary;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String[] getColumns() {
        return columns.clone();
    }

    /**
     * 按「本批次是否需要文件名列」返回列清单。
     *
     * 提取对象全是单页图片时，一行对应一个文件夹（整目录成件），没有可以定位的具体文件，
     * 文件名列会整列为空，因此在建项目时直接不生成该列；存在 PDF 等多页文件时保留。
     * 非批量模板不含文件名列，不受影响。
     */
    public String[] getColumns(boolean includeFileNameColumn) {
        if (includeFileNameColumn || !isBatchTitle()) {
            return getColumns();
        }
        List<String> kept = new ArrayList<>();
        for (String column : columns) {
            if (!FILE_NAME_COLUMN.equals(column)) {
                kept.add(column);
            }
        }
        return kept.toArray(new String[0]);
    }

    public String getKeyColumn() {
        return keyColumn;
    }

    public boolean isGenerateVolumeSummary() {
        return generateVolumeSummary;
    }

    public boolean isBatchTitle() {
        return this == BATCH_TITLE_VOLUME || this == BATCH_TITLE_CASE;
    }

    /**
     * LLM提取的核心要素键列表（不含系统自动填充列，档号由路径规则生成）。
     * 案件模板和案卷模板的LLM提取键相同。
     */
    public String[] getExtractionKeys() {
        return new String[]{"title", "responsible_party", "document_number", "date"};
    }

    public Map<String, String> getExtractionKeyMapping() {
        Map<String, String> mapping = new java.util.HashMap<>();
        mapping.put("title", "题名");
        mapping.put("responsible_party", "责任者");
        mapping.put("document_number", "文号");
        mapping.put("date", "成文日期");
        return mapping;
    }

    /**
     * 根据显示名称查找模板，未匹配则返回默认的案件模板。
     */
    public static ExtractionTemplate fromName(String name) {
        if (name != null) {
            for (ExtractionTemplate t : values()) {
                if (t.displayName.equals(name)) {
                    return t;
                }
            }
        }
        return ARCHIVE_CASE;
    }

    /**
     * 获取列名在列数组中的索引，未找到返回 -1。
     */
    public int getColumnIndex(String columnName) {
        for (int i = 0; i < columns.length; i++) {
            if (columns[i].equals(columnName)) {
                return i;
            }
        }
        return -1;
    }
}

