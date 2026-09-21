package org.google.refine.filesExtension.importer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.openrefine.extensions.files.importer.BatchExtractionManager;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class CaseElementAccumulationTest {

    private Method accumulate;
    private Method accumulateByConfidence;
    private Method filterLowConfidence;

    @BeforeMethod
    public void setup() throws Exception {
        accumulate = BatchExtractionManager.class.getDeclaredMethod("accumulate", List.class);
        accumulate.setAccessible(true);
        accumulateByConfidence = BatchExtractionManager.class.getDeclaredMethod(
                "accumulateByConfidence", List.class, List.class);
        accumulateByConfidence.setAccessible(true);
        filterLowConfidence = BatchExtractionManager.class.getDeclaredMethod(
                "filterLowConfidence", Map.class, Map.class);
        filterLowConfidence.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> call(List<Map<String, String>> pageValues) throws Exception {
        return (Map<String, String>) accumulate.invoke(null, pageValues);
    }

    private Object merge(List<Map<String, String>> pageValues,
                         List<Map<String, Double>> pageConfidences) throws Exception {
        return accumulateByConfidence.invoke(null, pageValues, pageConfidences);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> valuesOf(Object merged) throws Exception {
        return (Map<String, String>) fieldOf(merged, "values");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Double> confidencesOf(Object merged) throws Exception {
        return (Map<String, Double>) fieldOf(merged, "confidences");
    }

    private Object fieldOf(Object merged, String name) throws Exception {
        Field f = merged.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(merged);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> callFilter(Map<String, String> values,
                                           Map<String, Double> confidences) throws Exception {
        return (Map<String, String>) filterLowConfidence.invoke(null, values, confidences);
    }

    @Test
    public void testFirstComeFirstServedAcrossPages() throws Exception {
        Map<String, String> p1 = new HashMap<>();
        p1.put("title", "关于XX工作的通知");
        Map<String, String> p3 = new HashMap<>();
        p3.put("responsible_party", "通江县人民政府办公室");
        p3.put("date", "2024年3月5日");
        Map<String, String> p4 = new HashMap<>();
        p4.put("document_number", "通府办发〔2024〕7号");

        List<Map<String, String>> pages = new ArrayList<>();
        pages.add(p1);
        pages.add(null);
        pages.add(p3);
        pages.add(p4);

        Map<String, String> merged = call(pages);
        Assert.assertEquals(merged.get("title"), "关于XX工作的通知");
        Assert.assertEquals(merged.get("responsible_party"), "通江县人民政府办公室");
        Assert.assertEquals(merged.get("date"), "2024年3月5日");
        Assert.assertEquals(merged.get("document_number"), "通府办发〔2024〕7号");
    }

    @Test
    public void testLaterPageFillsEmptyEarlierValue() throws Exception {
        Map<String, String> p1 = new HashMap<>();
        p1.put("date", "  ");
        Map<String, String> p2 = new HashMap<>();
        p2.put("date", "2024-03-05");

        List<Map<String, String>> pages = new ArrayList<>();
        pages.add(p1);
        pages.add(p2);

        Map<String, String> merged = call(pages);
        Assert.assertEquals(merged.get("date"), "2024-03-05");
    }

    @Test
    public void testFirstPageWinsOnConflict() throws Exception {
        Map<String, String> p1 = new HashMap<>();
        p1.put("title", "首页题名");
        Map<String, String> p2 = new HashMap<>();
        p2.put("title", "内页编造题名");

        List<Map<String, String>> pages = new ArrayList<>();
        pages.add(p1);
        pages.add(p2);

        Map<String, String> merged = call(pages);
        Assert.assertEquals(merged.get("title"), "首页题名");
    }

    @Test
    public void testEmptyAndNullPages() throws Exception {
        Assert.assertTrue(call(new ArrayList<>()).isEmpty());
        List<Map<String, String>> pages = new ArrayList<>();
        pages.add(null);
        Assert.assertTrue(call(pages).isEmpty());
    }

    @Test
    public void testHigherConfidencePageOverridesEarlierValue() throws Exception {
        Map<String, String> p2 = new HashMap<>();
        p2.put("date", "20230221");
        Map<String, String> p5 = new HashMap<>();
        p5.put("date", "20231201");
        List<Map<String, String>> pages = new ArrayList<>();
        pages.add(null);
        pages.add(p2);
        pages.add(p5);
        List<Map<String, Double>> confs = new ArrayList<>();
        confs.add(null);
        confs.add(conf("date", 0.0));
        confs.add(conf("date", 0.9));

        Object merged = merge(pages, confs);
        Assert.assertEquals(valuesOf(merged).get("date"), "20231201");
        Assert.assertEquals(confidencesOf(merged).get("date"), 0.9);
    }

    @Test
    public void testLowConfidenceValueIsDropped() throws Exception {
        Map<String, String> p1 = new HashMap<>();
        p1.put("date", "20230221");
        List<Map<String, String>> pages = new ArrayList<>();
        pages.add(p1);
        List<Map<String, Double>> confs = new ArrayList<>();
        confs.add(conf("date", 0.0));

        Object merged = merge(pages, confs);
        Assert.assertNull(valuesOf(merged).get("date"));
        Assert.assertTrue(valuesOf(merged).isEmpty());
    }

    @Test
    public void testMissingConfidenceFallsBackToFirstNonEmpty() throws Exception {
        Map<String, String> p1 = new HashMap<>();
        p1.put("title", "首页题名");
        Map<String, String> p2 = new HashMap<>();
        p2.put("title", "内页题名");
        List<Map<String, String>> pages = new ArrayList<>();
        pages.add(p1);
        pages.add(p2);

        Object merged = merge(pages, new ArrayList<>());
        Assert.assertEquals(valuesOf(merged).get("title"), "首页题名");
        Assert.assertTrue(confidencesOf(merged).isEmpty());
    }

    @Test
    public void testFilterLowConfidenceOnSingleMultiPageResult() throws Exception {
        Map<String, String> values = new HashMap<>();
        values.put("title", "关于XX的通知");
        values.put("date", "20230221");
        Map<String, Double> confs = new HashMap<>();
        confs.put("title", 0.95);
        confs.put("date", 0.0);

        Map<String, String> kept = callFilter(values, confs);
        Assert.assertEquals(kept.get("title"), "关于XX的通知");
        Assert.assertFalse(kept.containsKey("date"));
        Assert.assertTrue(callFilter(values, new HashMap<>()).containsKey("date"));
    }

    private Map<String, Double> conf(String key, double value) {
        Map<String, Double> m = new HashMap<>();
        m.put(key, value);
        return m;
    }
}
