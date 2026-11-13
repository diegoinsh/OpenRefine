/*
 * Data Quality Extension - Document Element Checker
 * 文书级检测（案件质量评查）：裁判文书必备要素是否齐全
 */
package com.google.refine.extension.quality.checker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.jiongche.sdk.data.TableView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.quality.model.CheckResult;
import com.google.refine.extension.quality.model.DocumentCheckError;
import com.google.refine.extension.quality.model.QualityRulesConfig;
import com.google.refine.extension.quality.task.QualityCheckTask;
import com.google.refine.extension.quality.util.TableViews;

/**
 * 文书级（案件质量评查）检测：对命中 {@code documentQualityRule.documentTypes} 的件，
 * 逐项核对 {@code requiredFields}（如案号 / 日期 / 责任者）是否为空。
 *
 * <p>印章 / 签名 / 指纹类判定统一归数据校审，本检查器不再涉及图像与 AI 服务，
 * 只做纯表格要素核对，结果归四性中的<b>完整性</b>。</p>
 */
public class DocumentElementChecker {

    /** 结果类别名（与前端/下游按 category 区分；沿用历史值，避免下游改造）。 */
    public static final String CATEGORY = "document_quality";

    private static final Logger logger = LoggerFactory.getLogger(DocumentElementChecker.class);

    private final TableView view;
    private final QualityRulesConfig rules;
    private QualityCheckTask task;

    public DocumentElementChecker(TableView view, QualityRulesConfig rules) {
        this.view = view;
        this.rules = rules;
    }

    public void setTask(QualityCheckTask task) {
        this.task = task;
    }

    public CheckResult runCheck() {
        CheckResult result = new CheckResult(CATEGORY);
        int totalRows = view.rowCount();
        result.setTotalRows(totalRows);

        Rule rule = Rule.from(rules == null ? null : rules.getDocumentQualityRule());
        if (rule == null || !rule.enabled) {
            return skip(result, "rule_disabled");
        }

        Map<String, Integer> columnIndexMap = TableViews.columnIndexMap(view);
        String titleColumn = findColumn(columnIndexMap, "题名");
        if (titleColumn == null) {
            return skip(result, "no_title_column");
        }

        Integer archiveIndex = columnIndexMap.get("档号");
        Integer pieceIndex = columnIndexMap.get("件号");
        Integer titleIndex = columnIndexMap.get(titleColumn);

        int checkedDocuments = 0;
        int passedDocuments = 0;
        int failedDocuments = 0;

        result.setExecuted(true);
        result.setDocuments(new ArrayList<>());

        for (int rowIndex = 0; rowIndex < totalRows; rowIndex++) {
            if (task != null && task.shouldStop()) {
                logger.warn("Document element check cancelled at row {}", rowIndex);
                break;
            }
            String title = cell(view, rowIndex, titleIndex);
            if (title.isEmpty()) {
                continue;
            }
            DocType docType = rule.match(title);
            if (docType == null) {
                continue;  // 非裁判文书类件，不检测
            }
            checkedDocuments++;
            if (task != null) {
                task.setCurrentPhase("案件质量评查");
                task.setCheckedRows(checkedDocuments);
            }

            String archiveNo = cell(view, rowIndex, archiveIndex);
            String pieceNo = cell(view, rowIndex, pieceIndex);
            boolean rowFailed = false;

            for (String field : docType.requiredFields) {
                if (field == null || field.isEmpty()) {
                    continue;
                }
                Integer idx = columnIndexMap.get(field);
                if (idx == null && "日期".equals(field)) {
                    // 卷宗目录/著录表的列名为「成文日期」，必备要素口径写作「日期」
                    idx = columnIndexMap.get("成文日期");
                }
                if (idx == null || cell(view, rowIndex, idx).isEmpty()) {
                    rowFailed = true;
                    DocumentCheckError err = baseDocument(rowIndex, title, docType, archiveNo, pieceNo);
                    err.setErrorType("field_missing");
                    err.setMissingField(field);
                    err.setMessage(docType.typeName + "缺少必备要素：" + field);
                    result.getDocuments().add(err);
                    result.addError(plainError(rowIndex, field, title, "field_missing", err.getMessage()));
                }
            }

            if (rowFailed) {
                failedDocuments++;
            } else {
                passedDocuments++;
                // 通过的件也要落一条明细：报告/前端的「文书级明细」是逐件清单，
                // 只记失败件会让全部合格时明细为空（看起来像没执行）
                result.getDocuments().add(baseDocument(rowIndex, title, docType, archiveNo, pieceNo));
            }
        }

        result.setCheckedRows(checkedDocuments);
        result.setPassedRows(passedDocuments);
        result.setFailedRows(failedDocuments);
        result.complete();
        logger.info("Document element check completed - documents: {}, passed: {}, failed: {}, errors: {}",
                checkedDocuments, passedDocuments, failedDocuments, result.getErrors().size());
        return result;
    }

    /** 未执行：如实标记原因，不产出任何「通过」假象。 */
    private static CheckResult skip(CheckResult result, String reason) {
        result.setExecuted(false);
        result.setSkipReason(reason);
        result.complete();
        logger.info("Skipping document element check - {}", reason);
        return result;
    }

    /** 取单元格文本（去空白）；列不存在返回空串。 */
    private static String cell(TableView view, int rowIndex, Integer columnIndex) {
        if (columnIndex == null) {
            return "";
        }
        Object v = view.value(rowIndex, columnIndex);
        return v == null ? "" : String.valueOf(v).trim();
    }

    /** 列名命中（精确，兼容「件题名」别名）。 */
    private static String findColumn(Map<String, Integer> columnIndexMap, String name) {
        if (columnIndexMap.get(name) != null) {
            return name;
        }
        if ("题名".equals(name) && columnIndexMap.get("件题名") != null) {
            return "件题名";
        }
        return null;
    }

    private static DocumentCheckError baseDocument(int rowIndex, String title, DocType docType,
                                                   String archiveNo, String pieceNo) {
        DocumentCheckError err = new DocumentCheckError();
        err.setRowIndex(rowIndex);
        err.setArchiveNo(archiveNo);
        err.setPieceNo(pieceNo);
        err.setDocumentTitle(title);
        err.setDocumentType(docType.typeCode);
        err.setDocumentTypeName(docType.typeName);
        return err;
    }

    /** 统一 errors 数组用的精简错误（下游按 category=document_quality 归类）。 */
    private static CheckResult.CheckError plainError(int rowIndex, String column, String value,
                                                     String errorType, String message) {
        return new CheckResult.CheckError(rowIndex, column, value, errorType, message);
    }

    /**
     * {@code documentQualityRule} 的解析结果（payload 里是 JSON 对象，模型侧是 Map，
     * 故按 Map 逐字段读取，避免为规则结构再造一层强类型模型）。
     */
    static final class Rule {
        boolean enabled;
        final List<DocType> types = new ArrayList<>();

        static Rule from(Map<String, Object> raw) {
            if (raw == null || raw.isEmpty()) {
                return null;
            }
            Rule rule = new Rule();
            rule.enabled = boolValue(raw.get("enabled"), false);
            Object list = raw.get("documentTypes");
            if (list instanceof List) {
                for (Object item : (List<?>) list) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        DocType type = DocType.from((Map<String, Object>) item);
                        if (type.titlePattern != null && !type.titlePattern.isEmpty()) {
                            rule.types.add(type);
                        }
                    }
                }
            }
            // 长模式优先：「刑事附带民事调解书」不应被「民事调解书」先截走
            rule.types.sort((a, b) -> b.titlePattern.length() - a.titlePattern.length());
            return rule;
        }

        /** 题名命中哪个文书类型（未命中返回 null）。 */
        DocType match(String title) {
            for (DocType type : types) {
                if (title.contains(type.titlePattern)) {
                    return type;
                }
            }
            return null;
        }
    }

    /** 单个文书类型（民事判决书等）的必备要素。 */
    static final class DocType {
        String typeCode;
        String typeName;
        String titlePattern;
        final List<String> requiredFields = new ArrayList<>();

        static DocType from(Map<String, Object> raw) {
            DocType type = new DocType();
            type.typeCode = strValue(raw.get("typeCode"), "");
            type.typeName = strValue(raw.get("typeName"), "");
            type.titlePattern = strValue(raw.get("titlePattern"), type.typeName);
            Object fields = raw.get("requiredFields");
            if (fields instanceof List) {
                for (Object f : (List<?>) fields) {
                    if (f != null) {
                        type.requiredFields.add(String.valueOf(f));
                    }
                }
            }
            return type;
        }
    }

    private static String strValue(Object v, String def) {
        return v == null ? def : String.valueOf(v);
    }

    private static boolean boolValue(Object v, boolean def) {
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v != null) {
            String s = String.valueOf(v).trim();
            if ("true".equalsIgnoreCase(s)) {
                return true;
            }
            if ("false".equalsIgnoreCase(s)) {
                return false;
            }
        }
        return def;
    }
}
