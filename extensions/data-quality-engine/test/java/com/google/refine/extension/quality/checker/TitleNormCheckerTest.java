/*
 * Data Quality Extension - Title Norm Checker Test
 *
 * 回归：题名规范检查的取列必须兼容两套口径——
 * 质检表「题名 / 一级分类 / 二级分类 / 当事人」与自动著录抽取结果
 * 「四级题名 / 案卷号（一级/二级/件）/ 材料类别 / 原告·被告·第三人」。
 * 旧实现只认前者，对抽取结果会逐行误报「一级/二级分类为空」。
 */
package com.google.refine.extension.quality.checker;

import static org.testng.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jiongche.sdk.data.TableSchema;
import com.jiongche.sdk.data.TableView;
import org.testng.annotations.Test;

import com.google.refine.extension.quality.model.CheckResult;
import com.google.refine.extension.quality.model.QualityRulesConfig;

public class TitleNormCheckerTest {

    @Test
    public void supportsExtractionResultColumns() {
        List<String> headers = Arrays.asList(
                "案卷号", "件号", "题名", "四级题名", "材料类别", "原告", "被告", "第三人");
        List<List<String>> rows = new ArrayList<>();
        // 一级/二级来自「案卷号」路径前两段；当事人来自「原告」列；四级题名有值
        rows.add(Arrays.asList("正卷/举证与调查材料/09 授权委托书", "09",
                "授权委托书", "授权委托书", "原告举证材料", "张三", "", ""));
        // 四级题名为空时按行回退「题名」
        rows.add(Arrays.asList("正卷/举证与调查材料/10 借条", "10",
                "借条", "", "原告举证材料", "", "李四", ""));

        CheckResult result = new TitleNormChecker(view(headers, rows), rules()).runCheck();

        assertEquals(2, result.getCheckedRows());
        assertEquals(0, result.getFailedRows(),
                "案卷号路径提供一级/二级、原告/被告列提供当事人时，不应产生层级缺失误报");
        assertEquals(0, result.getErrors().size());
    }

    @Test
    public void stillSupportsQualitySheetColumns() {
        List<String> headers = Arrays.asList("题名", "一级分类", "二级分类", "当事人");
        List<List<String>> rows = new ArrayList<>();
        rows.add(Arrays.asList("授权委托书", "正卷", "举证与调查材料", "原告"));

        CheckResult result = new TitleNormChecker(view(headers, rows), rules()).runCheck();

        assertEquals(1, result.getCheckedRows());
        assertEquals(0, result.getFailedRows());
    }

    @Test
    public void stripsOrderPrefixFromLevel2() {
        // 下游回传的二级分类可能带序号前缀（"13 举证与调查材料"），词表里不带序号；
        // 不裁剪会被判「二级分类不在模板清单内」，属误报。
        List<String> headers = Arrays.asList("题名", "一级分类", "二级分类", "当事人");
        List<List<String>> rows = new ArrayList<>();
        rows.add(Arrays.asList("授权委托书", "正卷", "13 举证与调查材料", "原告"));

        CheckResult result = new TitleNormChecker(view(headers, rows), rules()).runCheck();

        assertEquals(1, result.getCheckedRows());
        assertEquals(0, result.getErrors().size(), "带序号前缀的二级分类应先裁剪再与词表比对");
    }

    private static QualityRulesConfig rules() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("level1", "正卷");
        first.put("level2", "举证与调查材料");
        first.put("originalTitle", "授权委托书");
        first.put("titleTemplate", "授权委托书");
        first.put("party", "原告");

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("level1", "正卷");
        second.put("level2", "举证与调查材料");
        second.put("originalTitle", "借条");
        second.put("titleTemplate", "借条");
        second.put("party", "被告");

        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("enabled", true);
        rule.put("rules", Arrays.asList(first, second));

        QualityRulesConfig rules = new QualityRulesConfig();
        rules.setTitleNormRule(rule);
        return rules;
    }

    private static TableView view(final List<String> headers, final List<List<String>> rows) {
        return new TableView() {
            @Override
            public TableSchema schema() {
                return new TableSchema() {
                    @Override
                    public List<String> columnNames() {
                        return headers;
                    }

                    @Override
                    public int columnIndex(String name) {
                        return headers.indexOf(name);
                    }
                };
            }

            @Override
            public int rowCount() {
                return rows.size();
            }

            @Override
            public Object value(int r, int c) {
                return rows.get(r).get(c);
            }

            @Override
            public String overlayJson(String name) {
                return null;
            }
        };
    }
}
