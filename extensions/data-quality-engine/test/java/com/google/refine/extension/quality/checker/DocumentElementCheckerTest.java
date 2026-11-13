/*
 * Data Quality Extension - Document Element Checker Test
 *
 * 回归：文书必备要素核对必须逐件落明细（全部合格时也要有一行），
 * 否则报告/前端的「文书级明细」为空，看起来像没执行。
 */
package com.google.refine.extension.quality.checker;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jiongche.sdk.data.TableSchema;
import com.jiongche.sdk.data.TableView;
import org.testng.annotations.Test;

import com.google.refine.extension.quality.model.CheckResult;
import com.google.refine.extension.quality.model.DocumentCheckError;
import com.google.refine.extension.quality.model.QualityRulesConfig;

public class DocumentElementCheckerTest {

    private static final List<String> HEADERS =
            Arrays.asList("档号", "件号", "题名", "案号", "成文日期", "责任者");

    /** 一份必备要素齐全的民事调解书（案号 / 日期 / 责任者都在）。 */
    private static final List<String> ROW =
            Arrays.asList("HB07-2024-M1-1638", "12", "民事调解书", "(2024)冀0722民初1638号", "2024-06-18", "审判员");

    @Test
    public void recordsPassedDocumentInDetails() {
        CheckResult result = new DocumentElementChecker(view(ROW), rules()).runCheck();

        assertEquals(1, result.getCheckedRows());
        assertEquals(1, result.getPassedRows());
        assertEquals(0, result.getFailedRows());

        assertEquals(1, result.getDocuments().size(), "全部合格也要落一条件级明细");
        DocumentCheckError doc = result.getDocuments().get(0);
        assertEquals("民事调解书", doc.getDocumentTitle());
        assertEquals("12", doc.getPieceNo());
        assertNull(doc.getErrorType(), "合格的件不应带错误类型");
    }

    @Test
    public void recordsMissingFieldError() {
        List<String> row = Arrays.asList("HB-1", "13", "民事调解书", "", "2024-06-18", "审判员");
        CheckResult result = new DocumentElementChecker(view(row), rules()).runCheck();

        assertEquals(1, result.getFailedRows());
        assertEquals(1, result.getDocuments().size());
        DocumentCheckError doc = result.getDocuments().get(0);
        assertEquals("field_missing", doc.getErrorType());
        assertEquals("案号", doc.getMissingField());
    }

    private static QualityRulesConfig rules() {
        Map<String, Object> type = new LinkedHashMap<>();
        type.put("typeCode", "civil_mediation");
        type.put("typeName", "民事调解书");
        type.put("titlePattern", "民事调解书");
        type.put("requiredFields", Arrays.asList("案号", "日期", "责任者"));

        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("enabled", true);
        rule.put("documentTypes", List.of(type));

        QualityRulesConfig rules = new QualityRulesConfig();
        rules.setDocumentQualityRule(rule);
        return rules;
    }

    private static TableView view(final List<String> row) {
        return new TableView() {
            @Override
            public TableSchema schema() {
                return new TableSchema() {
                    @Override
                    public List<String> columnNames() {
                        return HEADERS;
                    }

                    @Override
                    public int columnIndex(String name) {
                        return HEADERS.indexOf(name);
                    }
                };
            }

            @Override
            public int rowCount() {
                return 1;
            }

            @Override
            public Object value(int r, int c) {
                return row.get(c);
            }

            @Override
            public String overlayJson(String name) {
                return null;
            }
        };
    }
}
