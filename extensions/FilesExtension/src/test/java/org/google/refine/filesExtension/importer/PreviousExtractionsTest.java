package org.google.refine.filesExtension.importer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openrefine.extensions.files.importer.BatchExtractionManager;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PreviousExtractionsTest {

    @SuppressWarnings("unchecked")
    private String build(List<Map<String, String>> pageValues, List<Map<String, Double>> pageConfidences) throws Exception {
        Method m = BatchExtractionManager.class.getDeclaredMethod("buildPreviousExtractions", List.class, List.class);
        m.setAccessible(true);
        return (String) m.invoke(BatchExtractionManager.get(), pageValues, pageConfidences);
    }

    @Test
    public void testEmptyPageValuesReturnsNull() throws Exception {
        Assert.assertNull(build(Collections.emptyList(), null));
    }

    @Test
    public void testBlankValuesAndFailedPagesAreSkipped() throws Exception {
        Map<String, String> p1 = new LinkedHashMap<>();
        p1.put("title", "关于做好水旱灾害防御工作的通知");
        p1.put("responsible_party", "");
        p1.put("date", "20190621");

        List<Map<String, String>> pageValues = new ArrayList<>();
        pageValues.add(p1);
        pageValues.add(null);
        List<Map<String, Double>> confidences = new ArrayList<>();
        Map<String, Double> c1 = new HashMap<>();
        c1.put("title", 0.95);
        c1.put("date", 0.0);
        confidences.add(c1);
        confidences.add(null);

        JsonNode arr = new ObjectMapper().readTree(build(pageValues, confidences));
        Assert.assertEquals(arr.size(), 1, "空值与失败页不应写入上下文");
        Assert.assertEquals(arr.get(0).path("page_index").asInt(), 1);
        JsonNode elements = arr.get(0).path("elements");
        Assert.assertEquals(elements.path("title").path("value").asText(), "关于做好水旱灾害防御工作的通知");
        Assert.assertEquals(elements.path("title").path("confidence").asDouble(), 0.95);
        Assert.assertEquals(elements.path("date").path("value").asText(), "20190621");
        Assert.assertEquals(elements.path("date").path("confidence").asDouble(), 0.0);
        Assert.assertFalse(elements.has("responsible_party"), "空值要素不应出现");
    }

    @Test
    public void testConfidenceOmittedWhenMissing() throws Exception {
        Map<String, String> p1 = new HashMap<>();
        p1.put("title", "关于XX的通知");
        List<Map<String, String>> pageValues = new ArrayList<>();
        pageValues.add(p1);

        JsonNode arr = new ObjectMapper().readTree(build(pageValues, null));
        Assert.assertFalse(arr.get(0).path("elements").path("title").has("confidence"),
                "无置信度时不应写入 confidence 字段");
    }

    @Test
    public void testNullWhenAllValuesBlank() throws Exception {
        Map<String, String> p1 = new HashMap<>();
        p1.put("title", "   ");
        List<Map<String, String>> pageValues = new ArrayList<>();
        pageValues.add(p1);
        Assert.assertNull(build(pageValues, null));
    }
}