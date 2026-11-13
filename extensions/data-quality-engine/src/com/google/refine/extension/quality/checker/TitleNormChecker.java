/*
 * Data Quality Extension - Title Norm Checker
 * 题名规范检查：按四级定制模板核对层级归属与题名写法（单条记录 + 模板比对）
 */
package com.google.refine.extension.quality.checker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.jiongche.sdk.data.TableView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.quality.model.CheckResult;
import com.google.refine.extension.quality.model.CheckResult.CheckError;
import com.google.refine.extension.quality.model.QualityRulesConfig;
import com.google.refine.extension.quality.task.QualityCheckTask;
import com.google.refine.extension.quality.util.TableViews;

/**
 * 题名规范检查：依据「四级定制模板」（编目规则导入模板）核对提交上来的
 * 一级 / 二级 / 四级题名是否合规。
 *
 * <p>只做<b>单条记录 + 模板比对</b>：跨件跨卷的勾稽（重复件、件序、必备件覆盖）归数据校审，
 * 本检查器不涉及，也不产出任何跨记录结论。</p>
 *
 * <p>检查项（可由规则逐项开关，缺省全开）：</p>
 * <ol>
 *   <li>层级完整性：一级 / 二级 / 四级题名均非空</li>
 *   <li>类别归属合法：一级 / 二级须在模板层级清单内</li>
 *   <li>四级题名模板匹配：题名须命中同二级下的新题名模板</li>
 *   <li>别名归一：题名等于模板原题名但未按新题名模板书写</li>
 *   <li>占位符缺失：命中模板但 {{责任者}} / {{文号}} 等占位内容为空</li>
 *   <li>当事人归属匹配：模板声明当事人归属时题名须体现原告 / 被告等角色</li>
 *   <li>模板版本一致：模板含无法识别的占位符（模板与引擎口径不一致）</li>
 *   <li>字符规范：控制字符、全角空格、首尾空白、中英文括号混用</li>
 *   <li>长度上限：题名超过规则设定的最大字数</li>
 *   <li>案号 / 文号格式：括号须用全角、须为「（年份）法院代字类型序号号」</li>
 * </ol>
 */
public class TitleNormChecker {

    /** 结果类别名（前端 / 报告按此归类）。 */
    public static final String CATEGORY = "title_norm";

    private static final Logger logger = LoggerFactory.getLogger(TitleNormChecker.class);

    /** 各检查项的键（与前端开关、平台模板 checks 字段一致）。 */
    public static final List<String> CHECK_KEYS = Arrays.asList(
            "levelNotEmpty", "levelOwnership", "templateMatch", "aliasNormalize",
            "placeholderMissing", "partyOwnership", "templateVersion",
            "charSet", "lengthLimit", "docNumberFormat");

    /**
     * 编目规则允许的占位符。
     *
     * <p>除系统变量外，还收客户真值模板里实际出现的 {@code 代理人}、{@code 第三人}、
     * {@code 成文日期}（可带 {@code :yyyymmdd} 之类的格式后缀，按冒号前变量名判定）。</p>
     */
    private static final Set<String> PLACEHOLDERS = new LinkedHashSet<>(Arrays.asList(
            "原告", "被告", "上诉人", "被上诉人", "当事人", "第三人", "代理人",
            "角色", "责任者", "文号", "原题名", "案号", "开庭日期", "成文日期"));

    /** 案号 / 文号规范形式：（2024）苏0505民初123号 */
    private static final Pattern DOC_NUMBER = Pattern.compile(
            "^[（(]\\d{4}[）)]\\s*[\\u4e00-\\u9fa5]{0,8}[A-Za-z]{0,3}\\d{0,6}\\s*"
                    + "[\\u4e00-\\u9fa5]{0,8}\\s*\\d{1,6}\\s*号$");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)}");

    private final TableView view;
    private final QualityRulesConfig rules;
    private QualityCheckTask task;

    public TitleNormChecker(TableView view, QualityRulesConfig rules) {
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

        Rule rule = Rule.from(rules == null ? null : rules.getTitleNormRule());
        if (rule == null || !rule.enabled) {
            return skip(result, "rule_disabled");
        }
        if (rule.rows.isEmpty()) {
            return skip(result, "no_template_rules");
        }

        Map<String, Integer> columnIndexMap = TableViews.columnIndexMap(view);
        String titleColumn = findColumn(columnIndexMap, "题名");
        if (titleColumn == null) {
            return skip(result, "no_title_column");
        }
        Integer titleIndex = columnIndexMap.get(titleColumn);
        Integer level1Index = findIndex(columnIndexMap, "一级分类", "一级目录");
        Integer level2Index = findIndex(columnIndexMap, "二级分类", "二级目录");
        Integer partyIndex = findIndex(columnIndexMap, "当事人");

        result.setExecuted(true);

        int checkedRows = 0;
        int passedRows = 0;
        int failedRows = 0;

        for (int rowIndex = 0; rowIndex < totalRows; rowIndex++) {
            if (task != null && task.shouldStop()) {
                logger.warn("Title norm check cancelled at row {}", rowIndex);
                break;
            }
            String title = cell(view, rowIndex, titleIndex);
            String level1 = cell(view, rowIndex, level1Index);
            String level2 = cell(view, rowIndex, level2Index);
            String party = cell(view, rowIndex, partyIndex);
            if (title.isEmpty() && level1.isEmpty() && level2.isEmpty()) {
                continue;  // 空行不参与（真正的空值由层级完整性项在有效行上报出）
            }
            checkedRows++;
            if (task != null) {
                task.setCurrentPhase("题名规范检查");
                task.setCheckedRows(checkedRows);
            }
            int before = result.getErrors().size();
            checkRow(result, rule, rowIndex, level1, level2, title, party, titleColumn);
            if (result.getErrors().size() > before) {
                failedRows++;
            } else {
                passedRows++;
            }
        }

        result.setCheckedRows(checkedRows);
        result.setPassedRows(passedRows);
        result.setFailedRows(failedRows);
        result.complete();
        logger.info("Title norm check completed - rows: {}, passed: {}, failed: {}, errors: {}",
                checkedRows, passedRows, failedRows, result.getErrors().size());
        return result;
    }

    /** 未执行：如实标记原因，不产出任何「通过」假象。 */
    private static CheckResult skip(CheckResult result, String reason) {
        result.setExecuted(false);
        result.setSkipReason(reason);
        result.complete();
        logger.info("Skipping title norm check - {}", reason);
        return result;
    }

    private void checkRow(CheckResult result, Rule rule, int rowIndex, String level1, String level2,
                          String title, String party, String titleColumn) {
        // 1) 层级完整性
        if (rule.on("levelNotEmpty")) {
            if (level1.isEmpty()) {
                add(result, rowIndex, "一级分类", "", "level_missing", "一级分类为空");
            }
            if (level2.isEmpty()) {
                add(result, rowIndex, "二级分类", "", "level_missing", "二级分类为空");
            }
            if (title.isEmpty()) {
                add(result, rowIndex, titleColumn, "", "level_missing", "四级题名为空");
            }
        }

        // 2) 类别归属合法
        if (rule.on("levelOwnership") && !level1.isEmpty()) {
            if (!rule.level1Names.isEmpty() && !rule.level1Names.contains(level1)) {
                add(result, rowIndex, "一级分类", level1, "category_illegal",
                        "一级分类「" + level1 + "」不在模板清单内");
            } else if (!level2.isEmpty()) {
                List<String> allowed = rule.level2ByLevel1.get(level1);
                if (allowed != null && !allowed.isEmpty() && !allowed.contains(level2)) {
                    add(result, rowIndex, "二级分类", level2, "category_illegal",
                            "二级分类「" + level2 + "」不在「" + level1 + "」的模板清单内");
                }
            }
        }

        if (title.isEmpty()) {
            return;  // 题名缺失时后续题名类检查无输入，不重复报
        }

        // 3~7) 模板匹配 / 别名归一 / 占位符 / 当事人 / 模板版本
        Match match = rule.match(level1, level2, title);
        boolean reported = false;

        if (rule.on("templateVersion") && match.row != null && !match.row.unknownPlaceholders.isEmpty()) {
            add(result, rowIndex, titleColumn, title, "template_version",
                    "模板含无法识别的占位符：" + String.join("、", match.row.unknownPlaceholders)
                            + "，请重新加载模板");
            reported = true;
        }

        if (rule.on("aliasNormalize") && match.aliasRow != null) {
            add(result, rowIndex, titleColumn, title, "alias_normalize",
                    "题名「" + title + "」应归一为「" + match.aliasRow.titleTemplate + "」");
            reported = true;
        }

        if (!reported && rule.on("templateMatch")) {
            if (match.row == null) {
                add(result, rowIndex, titleColumn, title, "template_mismatch",
                        "题名不符合四级题名模板" + describeCandidates(rule, level1, level2));
                reported = true;
            } else if (rule.on("placeholderMissing") && !match.row.placeholders.isEmpty()
                    && !match.placeholdersComplete) {
                add(result, rowIndex, titleColumn, title, "placeholder_missing",
                        "题名缺少模板占位符内容：" + String.join("、", match.row.placeholders));
                reported = true;
            }
        }

        if (!reported && rule.on("partyOwnership") && match.row != null
                && match.row.party != null && !match.row.party.isEmpty()) {
            if (!party.isEmpty() && !match.row.party.equals(party)) {
                add(result, rowIndex, "当事人", party, "party_mismatch",
                        "当事人归属应为「" + match.row.party + "」");
            } else if (party.isEmpty() && !title.contains(match.row.party)) {
                add(result, rowIndex, titleColumn, title, "party_mismatch",
                        "题名未体现当事人归属「" + match.row.party + "」");
            }
        }

        // 8) 字符规范
        if (rule.on("charSet")) {
            String problem = charSetProblem(title);
            if (problem != null) {
                add(result, rowIndex, titleColumn, title, "title_charset", problem);
            }
        }

        // 9) 长度上限
        if (rule.on("lengthLimit") && title.length() > rule.maxTitleLength) {
            add(result, rowIndex, titleColumn, title, "title_length",
                    "题名超过 " + rule.maxTitleLength + " 字上限（当前 " + title.length() + " 字）");
        }

        // 10) 案号 / 文号格式
        if (rule.on("docNumberFormat")) {
            String problem = docNumberProblem(title);
            if (problem != null) {
                add(result, rowIndex, titleColumn, title, "doc_number_format", problem);
            }
        }
    }

    /** 题名字符规范：返回问题描述，合规返回 null。 */
    private static String charSetProblem(String title) {
        if (!title.equals(title.trim())) {
            return "题名首尾含空白字符";
        }
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            if (c < 0x20 || c == '\u3000' || c == '\u00a0' || c == '\u200b' || c == '\ufeff') {
                return "题名含不可见或控制字符（位置 " + i + "）";
            }
        }
        if (title.contains("  ")) {
            return "题名含连续空格";
        }
        boolean halfOpen = title.indexOf('(') >= 0;
        boolean halfClose = title.indexOf(')') >= 0;
        boolean fullOpen = title.indexOf('（') >= 0;
        boolean fullClose = title.indexOf('）') >= 0;
        if ((halfOpen || halfClose) && (fullOpen || fullClose)) {
            return "题名中英文括号混用，请统一使用中文括号";
        }
        return null;
    }

    /** 案号 / 文号格式：括号须全角，且为「（年份）法院代字类型序号号」。 */
    private static String docNumberProblem(String title) {
        Matcher m = Pattern.compile("[（(]\\s*\\d{4}\\s*[）)]").matcher(title);
        if (!m.find()) {
            return null;  // 题名不含年份式文号，不做判定
        }
        String token = title.substring(m.start());
        int end = token.indexOf('号');
        token = end >= 0 ? token.substring(0, end + 1) : token;
        if (token.indexOf('(') >= 0 || token.indexOf(')') >= 0) {
            return "案号 / 文号须使用全角括号：" + token;
        }
        if (!DOC_NUMBER.matcher(token).matches()) {
            return "案号 / 文号书写不规范：" + token;
        }
        return null;
    }

    private static String describeCandidates(Rule rule, String level1, String level2) {
        List<String> names = new ArrayList<>();
        for (Row row : rule.rows) {
            if (!level2.isEmpty() && level2.equals(row.level2) && !names.contains(row.titleTemplate)) {
                names.add(row.titleTemplate);
            }
            if (names.size() >= 3) {
                break;
            }
        }
        if (names.isEmpty()) {
            return "";
        }
        return "；该二级分类可用模板：" + String.join(" / ", names);
    }

    private static void add(CheckResult result, int rowIndex, String column, String value,
                            String errorType, String message) {
        result.addError(new CheckError(rowIndex, column, value, errorType, message));
    }

    private static String cell(TableView view, int rowIndex, Integer columnIndex) {
        if (columnIndex == null) {
            return "";
        }
        Object v = view.value(rowIndex, columnIndex);
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static Integer findIndex(Map<String, Integer> columnIndexMap, String... names) {
        for (String name : names) {
            Integer idx = columnIndexMap.get(name);
            if (idx != null) {
                return idx;
            }
        }
        return null;
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

    /** 单行匹配结果。 */
    private static final class Match {
        Row row;            // 命中模板（整体正则命中）
        boolean placeholdersComplete;
        Row aliasRow;       // 仅原题名命中、未按新题名模板书写
    }

    /** 模板条目（编目规则一行）。 */
    static final class Row {
        String level1;
        String level2;
        String sortNo;
        String originalTitle;
        String titleTemplate;
        String party;
        final List<String> placeholders = new ArrayList<>();
        final List<String> unknownPlaceholders = new ArrayList<>();
        Pattern pattern;

        static Row from(Map<String, Object> raw) {
            Row row = new Row();
            row.level1 = str(raw.get("level1"));
            row.level2 = str(raw.get("level2"));
            row.sortNo = str(raw.get("sortNo"));
            row.originalTitle = str(raw.get("originalTitle"));
            row.titleTemplate = str(raw.get("titleTemplate"));
            row.party = str(raw.get("party"));
            if (row.titleTemplate.isEmpty()) {
                row.titleTemplate = row.originalTitle;
            }
            row.compile();
            return row;
        }

        /** 模板编译：占位符 → 非贪婪捕获组，便于同时判定「占位符缺失」。 */
        private void compile() {
            if (titleTemplate.isEmpty()) {
                return;
            }
            StringBuilder regex = new StringBuilder("^");
            Matcher m = PLACEHOLDER.matcher(titleTemplate);
            int last = 0;
            while (m.find()) {
                regex.append(Pattern.quote(titleTemplate.substring(last, m.start())));
                String raw = m.group(1).trim();
                // 占位符可带格式后缀（如 成文日期:yyyymmdd），按冒号前的变量名判定是否受支持
                String name = raw;
                int colon = raw.indexOf(':');
                int ccolon = raw.indexOf('：');
                if (colon < 0) { colon = ccolon; } else if (ccolon >= 0 && ccolon < colon) { colon = ccolon; }
                if (colon > 0) {
                    name = raw.substring(0, colon).trim();
                }
                placeholders.add(name);
                if (!PLACEHOLDERS.contains(name)) {
                    unknownPlaceholders.add("{" + name + "}");
                }
                regex.append("(.*?)");
                last = m.end();
            }
            regex.append(Pattern.quote(titleTemplate.substring(last))).append("$");
            try {
                pattern = Pattern.compile(regex.toString());
            } catch (PatternSyntaxException e) {
                pattern = null;
            }
        }
    }

    /**
     * {@code titleNormRule} 的解析结果。
     *
     * <p>payload 里是 JSON 对象，模型侧是 Map，故按 Map 逐字段读取，
     * 与 {@code documentQualityRule} 的处理方式保持一致。</p>
     */
    static final class Rule {
        boolean enabled;
        int maxTitleLength = 120;
        final Map<String, Boolean> checks = new LinkedHashMap<>();
        final List<Row> rows = new ArrayList<>();
        final Set<String> level1Names = new LinkedHashSet<>();
        final Map<String, List<String>> level2ByLevel1 = new LinkedHashMap<>();

        static Rule from(Map<String, Object> raw) {
            if (raw == null || raw.isEmpty()) {
                return null;
            }
            Rule rule = new Rule();
            rule.enabled = bool(raw.get("enabled"), true);
            Object maxLen = raw.get("maxTitleLength");
            if (maxLen instanceof Number) {
                rule.maxTitleLength = ((Number) maxLen).intValue();
            }
            Object checksRaw = raw.get("checks");
            for (String key : CHECK_KEYS) {
                boolean value = true;
                if (checksRaw instanceof Map) {
                    value = bool(((Map<?, ?>) checksRaw).get(key), true);
                }
                rule.checks.put(key, value);
            }
            Object rowsRaw = raw.get("rules");
            if (rowsRaw instanceof List) {
                for (Object item : (List<?>) rowsRaw) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Row row = Row.from((Map<String, Object>) item);
                        if (row.titleTemplate.isEmpty()) {
                            continue;
                        }
                        rule.rows.add(row);
                        if (!row.level1.isEmpty()) {
                            rule.level1Names.add(row.level1);
                            List<String> level2s = rule.level2ByLevel1.get(row.level1);
                            if (level2s == null) {
                                level2s = new ArrayList<>();
                                rule.level2ByLevel1.put(row.level1, level2s);
                            }
                            if (!row.level2.isEmpty() && !level2s.contains(row.level2)) {
                                level2s.add(row.level2);
                            }
                        }
                    }
                }
            }
            return rule;
        }

        boolean on(String key) {
            Boolean value = checks.get(key);
            return value == null || value;
        }

        /** 匹配模板：先同二级，再全表按原题名（别名）兜底。 */
        Match match(String level1, String level2, String title) {
            Match result = new Match();
            List<Row> scoped = new ArrayList<>();
            for (Row row : rows) {
                boolean level1Hit = level1.isEmpty() || row.level1.isEmpty() || level1.equals(row.level1);
                boolean level2Hit = level2.isEmpty() || row.level2.isEmpty() || level2.equals(row.level2);
                if (level1Hit && level2Hit) {
                    scoped.add(row);
                }
            }
            if (scoped.isEmpty()) {
                scoped = rows;
            }
            Row looseMatch = null;
            for (Row row : scoped) {
                if (row.pattern == null) {
                    continue;
                }
                Matcher m = row.pattern.matcher(title);
                if (!m.matches()) {
                    continue;
                }
                if (placeholdersFilled(m, row)) {
                    result.row = row;
                    result.placeholdersComplete = true;
                    return result;
                }
                if (looseMatch == null) {
                    looseMatch = row;
                }
            }
            if (looseMatch != null) {
                result.row = looseMatch;
                result.placeholdersComplete = false;
                return result;
            }
            // 别名：题名 / 原题名写法命中，但未按新题名模板书写
            for (Row row : scoped) {
                if (!row.originalTitle.isEmpty() && row.originalTitle.equals(title)
                        && !row.originalTitle.equals(row.titleTemplate)) {
                    result.aliasRow = row;
                    return result;
                }
            }
            for (Row row : scoped) {
                if (!row.originalTitle.isEmpty() && row.originalTitle.length() >= 4
                        && title.contains(row.originalTitle)
                        && !row.originalTitle.equals(row.titleTemplate)) {
                    result.aliasRow = row;
                    return result;
                }
            }
            return result;
        }

        private static boolean placeholdersFilled(Matcher m, Row row) {
            for (int i = 0; i < row.placeholders.size(); i++) {
                String value = m.group(i + 1);
                if (value == null || value.trim().isEmpty()) {
                    return false;
                }
            }
            return true;
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static boolean bool(Object v, boolean def) {
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
