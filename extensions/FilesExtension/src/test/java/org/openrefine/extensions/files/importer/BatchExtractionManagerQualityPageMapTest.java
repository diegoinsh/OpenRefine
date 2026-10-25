package org.openrefine.extensions.files.importer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * pageMap 的抽取质量字段（q / qf / w）与胜出标记判定。
 *
 * <p>`w` 只回答一件事：列表里的哪一条候选就是写入单元格的取值来源。前端据此取质量，
 * 从而放弃「候选值与单元格文本逐字比对」——单元格值会被日期归一、当事人规范化、
 * 身份证明清责任者、证件清日期、题名由卷级模型合成等改写，逐字比对必然失配。
 * 因此规则是：
 * <ul>
 *   <li>最终值被改写或清空 → 不打标（质量已不适用于当前值）；</li>
 *   <li>候选本身没有质量（score/flags 都空）→ 不打标（打标也无可展示）；</li>
 *   <li>题名额外要求候选落在件首页；</li>
 *   <li>每列最多一条，重复写行前先清空旧标记。</li>
 * </ul>
 */
public class BatchExtractionManagerQualityPageMapTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    private Method accumulateByConfidence;
    private Method markWinners;
    private Method writeCandidates;

    @BeforeMethod
    public void setup() throws Exception {
        accumulateByConfidence = BatchExtractionManager.class.getDeclaredMethod(
                "accumulateByConfidence", List.class, List.class, int.class, List.class, List.class);
        accumulateByConfidence.setAccessible(true);
        markWinners = BatchExtractionManager.class.getDeclaredMethod(
                "markWinners", Map.class, Map.class, Map.class, Map.class);
        markWinners.setAccessible(true);
        writeCandidates = BatchExtractionManager.class.getDeclaredMethod(
                "writeCandidates", ObjectNode.class, String.class, List.class);
        writeCandidates.setAccessible(true);
    }

    // ── 构造与调用辅助 ──

    private static AimpLlmClient.ElementCandidate candidate(String value, double conf, int page,
                                                            Double score, List<String> flags) {
        return new AimpLlmClient.ElementCandidate(value, conf, page, null, score, flags, null);
    }

    private Object merge(List<Map<String, String>> values, List<Map<String, Double>> confidences,
                         int startPage, List<Map<String, Double>> scores,
                         List<Map<String, List<String>>> flags) throws Exception {
        return accumulateByConfidence.invoke(null, values, confidences, startPage, scores, flags);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<AimpLlmClient.ElementCandidate>> candidatesOf(Object merged)
            throws Exception {
        java.lang.reflect.Field f = merged.getClass().getDeclaredField("candidates");
        f.setAccessible(true);
        return (Map<String, List<AimpLlmClient.ElementCandidate>>) f.get(merged);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, AimpLlmClient.ElementCandidate> winnersOf(Object merged)
            throws Exception {
        java.lang.reflect.Field f = merged.getClass().getDeclaredField("winners");
        f.setAccessible(true);
        return (Map<String, AimpLlmClient.ElementCandidate>) f.get(merged);
    }

    private void mark(Map<String, List<AimpLlmClient.ElementCandidate>> candidates,
                      Map<String, AimpLlmClient.ElementCandidate> winners,
                      Map<String, String> finalValues,
                      Map<String, Integer> pageConstraints) throws Exception {
        markWinners.invoke(null, candidates, winners, finalValues, pageConstraints);
    }

    private ArrayNodeOf write(Map<String, List<AimpLlmClient.ElementCandidate>> candidates,
                              String column) throws Exception {
        ObjectNode rowNode = mapper.createObjectNode();
        writeCandidates.invoke(null, rowNode, column, candidates.get(column));
        return new ArrayNodeOf(rowNode.get(column));
    }

    /** 轻量断言包装：避免在测试里反复判空。 */
    private static final class ArrayNodeOf {
        private final JsonNode node;

        ArrayNodeOf(JsonNode node) {
            this.node = node;
        }

        int size() {
            return node.size();
        }

        JsonNode at(int i) {
            return node.get(i);
        }

        JsonNode findWithWinner() {
            for (JsonNode item : node) {
                if (item.has("w")) return item;
            }
            return null;
        }
    }

    private static Map<String, List<String>> flagsOf(String key, String... flags) {
        Map<String, List<String>> m = new HashMap<>();
        m.put(key, Arrays.asList(flags));
        return m;
    }

    // ── 质量随页传递并随胜出值切换 ──

    @Test
    public void mergedCandidateCarriesWinnerQualityAndReference() throws Exception {
        List<Map<String, String>> values = new ArrayList<>();
        values.add(Map.of("title", "起诉状"));
        List<Map<String, Double>> confidences = new ArrayList<>();
        confidences.add(Map.of("title", 0.9));
        List<Map<String, Double>> scores = new ArrayList<>();
        scores.add(Map.of("title", 0.42));
        List<Map<String, List<String>>> flags = new ArrayList<>();
        flags.add(flagsOf("title", "truncated"));

        Object merged = merge(values, confidences, 1, scores, flags);

        AimpLlmClient.ElementCandidate mergedCandidate = candidatesOf(merged).get("title").get(0);
        Assert.assertEquals(mergedCandidate.qualityScore, Double.valueOf(0.42));
        Assert.assertEquals(mergedCandidate.qualityFlags, List.of("truncated"));
        // 胜出 map 必须持有同一个对象引用：withFileNames 原地补文件名正是为了不把它断开
        Assert.assertSame(winnersOf(merged).get("title"), mergedCandidate);
    }

    @Test
    public void winnerSwitchTakesNewPagesQuality() throws Exception {
        List<Map<String, String>> values = new ArrayList<>();
        values.add(Map.of("title", "起诉状"));
        values.add(Map.of("title", "民事起诉状"));
        List<Map<String, Double>> confidences = new ArrayList<>();
        confidences.add(Map.of("title", 0.5));
        confidences.add(Map.of("title", 0.95));
        List<Map<String, Double>> scores = new ArrayList<>();
        scores.add(Map.of("title", 0.2));
        scores.add(Map.of("title", 0.88));
        List<Map<String, List<String>>> flags = new ArrayList<>();
        flags.add(flagsOf("title", "truncated"));
        flags.add(new HashMap<>());

        Object merged = merge(values, confidences, 1, scores, flags);

        AimpLlmClient.ElementCandidate winner = winnersOf(merged).get("title");
        Assert.assertEquals(winner.value, "民事起诉状");
        Assert.assertEquals(winner.page, 2);
        Assert.assertEquals(winner.qualityScore, Double.valueOf(0.88));
        Assert.assertNull(winner.qualityFlags, "胜出值切换后不得沿用旧页的 flags");
    }

    // ── `w` 判定规则 ──

    @Test
    public void marksExactlyOneWinnerWhenValueMatches() throws Exception {
        Map<String, List<AimpLlmClient.ElementCandidate>> candidates = new HashMap<>();
        candidates.put("title", new ArrayList<>(List.of(
                candidate("起诉状", 0.8, 1, 0.42, List.of("truncated")),
                candidate("起诉状", 0.9, 2, 0.91, null))));
        Map<String, AimpLlmClient.ElementCandidate> winners = new HashMap<>();

        mark(candidates, winners, Map.of("title", "起诉状"), new HashMap<>());

        int winnersCount = 0;
        for (AimpLlmClient.ElementCandidate c : candidates.get("title")) {
            if (Boolean.TRUE.equals(c.winner)) winnersCount++;
        }
        Assert.assertEquals(winnersCount, 1, "每列最多一条胜出标记");
        Assert.assertTrue(Boolean.TRUE.equals(candidates.get("title").get(0).winner),
                "无 winners 记录时取列表首个可用候选（列表已按置信度排序前先原样）");
    }

    @Test
    public void noWinnerWhenFinalValueWasRewritten() throws Exception {
        // 典型场景：日期归一（2024年3月5日 → 20240305）、证件类日期清空、
        // 题名由卷级模型合成——候选值与最终值不再逐字相同
        Map<String, List<AimpLlmClient.ElementCandidate>> candidates = new HashMap<>();
        candidates.put("date", new ArrayList<>(List.of(
                candidate("二〇二四年三月五日", 0.9, 1, 0.5, null))));
        Map<String, AimpLlmClient.ElementCandidate> winners = new HashMap<>();

        mark(candidates, winners, Map.of("date", "20240305"), new HashMap<>());

        Assert.assertNull(candidates.get("date").get(0).winner, "值被改写后不得打标");
        Assert.assertNull(write(candidates, "date").findWithWinner(), "pageMap 不得写 w");
    }

    @Test
    public void noWinnerWhenFinalValueIsEmpty() throws Exception {
        Map<String, List<AimpLlmClient.ElementCandidate>> candidates = new HashMap<>();
        candidates.put("responsible_party", new ArrayList<>(List.of(
                candidate("苏州市中级人民法院", 0.9, 1, 0.95, null))));
        Map<String, AimpLlmClient.ElementCandidate> winners = new HashMap<>();

        mark(candidates, winners, Map.of("responsible_party", ""), new HashMap<>());

        Assert.assertNull(candidates.get("responsible_party").get(0).winner,
                "空格值（身份证明类件清空责任者）不得打标");
    }

    @Test
    public void noWinnerWhenCandidateHasNoQuality() throws Exception {
        Map<String, List<AimpLlmClient.ElementCandidate>> candidates = new HashMap<>();
        candidates.put("title", new ArrayList<>(List.of(candidate("原告身份证明", 0.9, 1, null, null))));
        Map<String, AimpLlmClient.ElementCandidate> winners = new HashMap<>();

        mark(candidates, winners, Map.of("title", "原告身份证明"), new HashMap<>());

        Assert.assertNull(candidates.get("title").get(0).winner,
                "无质量的候选打标也无可展示，不打标");
    }

    @Test
    public void titleWinnerMustBeOnPieceStartPage() throws Exception {
        Map<String, List<AimpLlmClient.ElementCandidate>> candidates = new HashMap<>();
        candidates.put("title", new ArrayList<>(List.of(
                candidate("被告举证材料", 0.7, 3, null, null),
                candidate("被告举证材料", 0.95, 4, 0.6, null))));
        Map<String, AimpLlmClient.ElementCandidate> winners = new HashMap<>();

        // 件首页是第 3 页：第 3 页候选没有质量，第 4 页有质量但不在件首页 → 都不打标
        mark(candidates, winners, Map.of("title", "被告举证材料"), Map.of("title", 3));
        Assert.assertNull(candidates.get("title").get(0).winner);
        Assert.assertNull(candidates.get("title").get(1).winner);

        // 件首页候选有质量时正常打标
        AimpLlmClient.ElementCandidate head = candidate("被告举证材料", 0.7, 3, 0.6, null);
        candidates.put("title", new ArrayList<>(List.of(head,
                candidate("被告举证材料", 0.95, 4, 0.6, null))));
        mark(candidates, winners, Map.of("title", "被告举证材料"), Map.of("title", 3));
        Assert.assertTrue(Boolean.TRUE.equals(head.winner));
    }

    @Test
    public void staleWinnerFlagIsClearedBeforeRemarking() throws Exception {
        AimpLlmClient.ElementCandidate old = candidate("起诉状", 0.8, 1, 0.4, null);
        old.winner = true;
        Map<String, List<AimpLlmClient.ElementCandidate>> candidates = new HashMap<>();
        candidates.put("title", new ArrayList<>(List.of(old)));
        Map<String, AimpLlmClient.ElementCandidate> winners = new HashMap<>();

        // 最终值被清空：上一次留下的标记必须撤销，不能沿用
        mark(candidates, winners, Map.of("title", ""), new HashMap<>());

        Assert.assertNull(old.winner);
    }

    // ── pageMap 序列化 ──

    @Test
    public void writeCandidatesEmitsQualityAndWinnerMarkers() throws Exception {
        Map<String, List<AimpLlmClient.ElementCandidate>> candidates = new HashMap<>();
        candidates.put("title", new ArrayList<>(List.of(
                candidate("起诉状", 0.9, 1, 0.42, List.of("truncated", "repetition")))));
        mark(candidates, new HashMap<>(), Map.of("title", "起诉状"), new HashMap<>());

        ArrayNodeOf arr = write(candidates, "title");
        JsonNode item = arr.at(0);
        Assert.assertEquals(arr.size(), 1);
        Assert.assertEquals(item.get("q").asDouble(), 0.42);
        Assert.assertEquals(item.get("qf").size(), 2);
        Assert.assertEquals(item.get("qf").get(0).asText(), "truncated");
        Assert.assertEquals(arr.findWithWinner(), item, "胜出候选写 w=1");
    }

    @Test
    public void writeCandidatesOmitsAbsentQuality() throws Exception {
        Map<String, List<AimpLlmClient.ElementCandidate>> candidates = new HashMap<>();
        candidates.put("title", new ArrayList<>(List.of(candidate("起诉状", 0.9, 1, null, null))));

        JsonNode item = write(candidates, "title").at(0);
        Assert.assertFalse(item.has("q"), "无评分不写 q");
        Assert.assertFalse(item.has("qf"), "空 flags 不写 qf");
        Assert.assertFalse(item.has("w"), "无质量不打 w");
    }
}
