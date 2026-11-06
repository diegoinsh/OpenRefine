/*
 * Data Quality Extension - Document Seal Checker
 * 文书级检测（案件质量评查）：裁判文书是否按规范盖章
 */
package com.google.refine.extension.quality.checker;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jiongche.sdk.data.TableView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.quality.aimp.AimpClient;
import com.google.refine.extension.quality.model.CheckResult;
import com.google.refine.extension.quality.model.DocumentCheckError;
import com.google.refine.extension.quality.model.QualityRulesConfig;
import com.google.refine.extension.quality.model.ResourceCheckConfig;
import com.google.refine.extension.quality.model.ReviewElementResult;
import com.google.refine.extension.quality.task.QualityCheckTask;
import com.google.refine.extension.quality.util.ResourcePathBuilder;
import com.google.refine.extension.quality.util.TableViews;

/**
 * 文书级（案件质量评查）检测：对命中 {@code documentQualityRule.documentTypes} 的件，
 * 取该件**末页**图像调用 AIMP {@code /review/element}，按落款区域统计印章数并判定是否达标。
 *
 * <p>为什么只测末页：法院裁判文书的落款（院名 + 印章 + 日期）固定在**最后一页下部**，
 * 逐页全测会把每件多页都送 AI、耗时按页数线性增长，而结论只取决于落款页。</p>
 *
 * <p>与条目级检测的关系：条目级（{@link FormatChecker} 等）看「著录对不对」，
 * 本类看「这份裁判文书盖没盖章」，两者互不影响；本类不读取也不改写条目规则。</p>
 */
public class DocumentSealChecker {

    /** 结果类别名（与前端/下游按 category 区分）。 */
    public static final String CATEGORY = "document_quality";

    private static final Logger logger = LoggerFactory.getLogger(DocumentSealChecker.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** 可作为页图像的扩展名（与图像质量检查同口径）。 */
    private static final List<String> IMAGE_EXTENSIONS =
            Arrays.asList("jpg", "jpeg", "png", "tif", "tiff", "bmp", "gif", "webp");

    private final TableView view;
    private final QualityRulesConfig rules;
    private final String aimpServiceUrl;
    private QualityCheckTask task;

    public DocumentSealChecker(TableView view, QualityRulesConfig rules, String aimpServiceUrl) {
        this.view = view;
        this.rules = rules;
        this.aimpServiceUrl = aimpServiceUrl;
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
            logger.info("Skipping document seal check - documentQualityRule 未启用");
            result.complete();
            return result;
        }
        result.setDocuments(new ArrayList<>());
        if (aimpServiceUrl == null || aimpServiceUrl.isEmpty()) {
            logger.info("Skipping document seal check - 未配置 AIMP 服务地址");
            result.complete();
            return result;
        }

        Map<String, Integer> columnIndexMap = TableViews.columnIndexMap(view);
        String titleColumn = findColumn(columnIndexMap, "题名");
        if (titleColumn == null) {
            logger.info("Skipping document seal check - 项目中没有「题名」列，无法匹配文书类型");
            result.complete();
            return result;
        }

        ResourceCheckConfig resourceConfig = rules.getResourceConfig();
        String separator = "/";
        if (resourceConfig != null && resourceConfig.getSeparator() != null
                && !resourceConfig.getSeparator().isEmpty()) {
            separator = resourceConfig.getSeparator();
        } else {
            separator = File.separator;
        }
        Integer archiveIndex = columnIndexMap.get("档号");
        Integer pieceIndex = columnIndexMap.get("件号");
        Integer titleIndex = columnIndexMap.get(titleColumn);

        AimpClient client = new AimpClient(aimpServiceUrl);
        int checkedDocuments = 0;
        int passedDocuments = 0;
        int failedDocuments = 0;

        for (int rowIndex = 0; rowIndex < totalRows; rowIndex++) {
            if (task != null && task.shouldStop()) {
                logger.warn("Document seal check cancelled at row {}", rowIndex);
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

            boolean rowFailed = false;
            String archiveNo = cell(view, rowIndex, archiveIndex);
            String pieceNo = cell(view, rowIndex, pieceIndex);

            // 1. 必备要素（如案号 / 日期 / 责任者）缺失只记错，不阻断盖章检测
            for (String field : docType.requiredFields) {
                if (field == null || field.isEmpty()) {
                    continue;
                }
                Integer idx = columnIndexMap.get(field);
                if (idx == null || cell(view, rowIndex, idx).isEmpty()) {
                    rowFailed = true;
                    DocumentCheckError err = baseError(rowIndex, title, docType, archiveNo, pieceNo);
                    err.setErrorType("field_missing");
                    err.setMissingField(field);
                    err.setMessage(docType.typeName + "缺少必备要素：" + field);
                    result.getDocuments().add(err);
                    result.addError(plainError(rowIndex, field, title, "field_missing", err.getMessage()));
                }
            }

            // 2. 落款页盖章检测
            String resourcePath = ResourcePathBuilder.buildResourcePath(
                    view, rowIndex, columnIndexMap, resourceConfig, separator);
            File[] images = listImages(resourcePath);
            if (images.length == 0) {
                rowFailed = true;
                DocumentCheckError err = baseError(rowIndex, title, docType, archiveNo, pieceNo);
                err.setErrorType("seal_missing");
                err.setActualSealCount(0);
                err.setRequiredSealCount(docType.minSealCount);
                err.setMessage(docType.typeName + "未找到可检测的页图像，无法确认盖章"
                        + (resourcePath == null || resourcePath.isEmpty() ? "（未配置文件路径）" : ""));
                result.getDocuments().add(err);
                result.addError(plainError(rowIndex, "文件路径", title, "seal_missing", err.getMessage()));
                failedDocuments++;
                continue;
            }

            File lastPage = images[images.length - 1];
            ReviewElementResult detection;
            try {
                byte[] bytes = Files.readAllBytes(lastPage.toPath());
                detection = client.reviewElement(bytes, lastPage.getName(),
                        docType.checkFingerprint ? "seal,fingerprint" : "seal",
                        rule.regionJson(docType), docType.confidence);
            } catch (Exception e) {
                detection = new ReviewElementResult();
                detection.setMessage("读取页图像失败: " + e.getMessage());
            }
            if (!detection.isOk()) {
                // AI 服务不可用：整段中止，避免逐件反复等超时，并如实上报
                result.setServiceUnavailable(true);
                result.setServiceUnavailableMessage("AI服务模块不可用，请检查或重启模块"
                        + (detection.getMessage() == null ? "" : "（" + detection.getMessage() + "）"));
                logger.warn("Document seal check aborted: {}", detection.getMessage());
                break;
            }

            DocumentCheckError ok = baseError(rowIndex, title, docType, archiveNo, pieceNo);
            ok.setActualSealCount(detection.getSealCount());
            ok.setRequiredSealCount(docType.minSealCount);
            ok.getCheckedImages().add(lastPage.getName());

            if (docType.sealRequired && detection.getSealCount() < docType.minSealCount) {
                rowFailed = true;
                ok.setErrorType(detection.getSealCount() == 0 ? "seal_missing" : "seal_insufficient");
                ok.setMessage(detection.getSealCount() == 0
                        ? docType.typeName + "落款处未检测到印章"
                        : docType.typeName + "印章数不足（检测到 " + detection.getSealCount()
                                + " 个，要求至少 " + docType.minSealCount + " 个）");
                result.getDocuments().add(ok);
                result.addError(plainError(rowIndex, titleColumn, title, ok.getErrorType(), ok.getMessage()));
            } else {
                result.getDocuments().add(ok);
            }

            if (rowFailed) {
                failedDocuments++;
            } else {
                passedDocuments++;
            }
        }

        result.setCheckedRows(checkedDocuments);
        result.setPassedRows(passedDocuments);
        result.setFailedRows(failedDocuments);
        result.complete();
        logger.info("Document seal check completed - documents: {}, passed: {}, failed: {}, errors: {}",
                checkedDocuments, passedDocuments, failedDocuments, result.getErrors().size());
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

    /** 列出的页图像（按文件名排序，保证"末页"稳定）。 */
    private static File[] listImages(String resourcePath) {
        if (resourcePath == null || resourcePath.isEmpty()) {
            return new File[0];
        }
        File folder = new File(resourcePath);
        if (!folder.exists() || !folder.isDirectory()) {
            return new File[0];
        }
        File[] files = folder.listFiles((dir, name) -> {
            int dot = name.lastIndexOf('.');
            return dot >= 0 && IMAGE_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase());
        });
        if (files == null) {
            return new File[0];
        }
        Arrays.sort(files);
        return files;
    }

    private static DocumentCheckError baseError(int rowIndex, String title, DocType docType,
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
        double confidence = 0.5;
        boolean checkFingerprint;
        Map<String, Object> defaultSealRegion;
        final List<DocType> types = new ArrayList<>();

        static Rule from(Map<String, Object> raw) {
            if (raw == null || raw.isEmpty()) {
                return null;
            }
            Rule rule = new Rule();
            rule.enabled = boolValue(raw.get("enabled"), false);
            rule.confidence = doubleValue(raw.get("confidence"), 0.5);
            rule.checkFingerprint = boolValue(raw.get("checkFingerprint"), false);
            Object region = raw.get("defaultSealRegion");
            if (region instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) region;
                rule.defaultSealRegion = m;
            }
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

        /** 落款区域 JSON：文书类型覆盖优先，缺省用全局默认；都为空则全页。 */
        String regionJson(DocType type) {
            Map<String, Object> region = type.sealRegion != null ? type.sealRegion : defaultSealRegion;
            if (region == null) {
                return "";
            }
            ObjectNode node = mapper.createObjectNode();
            node.put("x", doubleValue(region.get("x"), 0.0));
            node.put("y", doubleValue(region.get("y"), 0.0));
            node.put("w", doubleValue(region.get("w"), 1.0));
            node.put("h", doubleValue(region.get("h"), 1.0));
            return node.toString();
        }
    }

    /** 单个文书类型（民事判决书等）的检测参数。 */
    static final class DocType {
        String typeCode;
        String typeName;
        String titlePattern;
        boolean sealRequired = true;
        int minSealCount = 1;
        boolean checkFingerprint;
        double confidence;
        Map<String, Object> sealRegion;
        List<String> requiredFields = new ArrayList<>();

        static DocType from(Map<String, Object> raw) {
            DocType type = new DocType();
            type.typeCode = strValue(raw.get("typeCode"), "");
            type.typeName = strValue(raw.get("typeName"), "");
            type.titlePattern = strValue(raw.get("titlePattern"), type.typeName);
            type.sealRequired = boolValue(raw.get("sealRequired"), true);
            type.minSealCount = (int) doubleValue(raw.get("minSealCount"), 1);
            type.checkFingerprint = boolValue(raw.get("checkFingerprint"), false);
            type.confidence = doubleValue(raw.get("confidence"), 0.5);
            Object region = raw.get("sealRegion");
            if (region instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) region;
                type.sealRegion = m;
            }
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

    private static double doubleValue(Object v, double def) {
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v != null) {
            try {
                return Double.parseDouble(String.valueOf(v).trim());
            } catch (NumberFormatException ignore) {
                return def;
            }
        }
        return def;
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
