package org.openrefine.extensions.files.importer;

import java.util.Map;

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
     * 案卷号, 件号, 起止页号, 页数, 题名, 责任者, 文号, 成文日期, 文件夹路径, 提取状态, 备注
     * 提取期项目只读（R-07 方案甲）
     */
    BATCH_TITLE_VOLUME("批量题名提取案卷模板",
            new String[]{"案卷号", "件号", "起止页号", "页数", "题名", "责任者", "文号", "成文日期", "文件夹路径", "提取状态", "备注"},
            "案卷号",
            false),

    /**
     * 批量题名提取案件模板
     * 根目录下子文件夹/PDF=一件；列（无案卷号与起止页号，R-05）：
     * 文件夹名, 件号, 页数, 题名, 责任者, 文号, 成文日期, 文件夹路径, 提取状态, 备注
     */
    BATCH_TITLE_CASE("批量题名提取案件模板",
            new String[]{"文件夹名", "件号", "页数", "题名", "责任者", "文号", "成文日期", "文件夹路径", "提取状态", "备注"},
            "文件夹名",
            false);

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

