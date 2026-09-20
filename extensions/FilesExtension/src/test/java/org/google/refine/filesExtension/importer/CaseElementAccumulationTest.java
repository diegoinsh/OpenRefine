package org.google.refine.filesExtension.importer;

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

    @BeforeMethod
    public void setup() throws Exception {
        accumulate = BatchExtractionManager.class.getDeclaredMethod("accumulate", List.class);
        accumulate.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> call(List<Map<String, String>> pageValues) throws Exception {
        return (Map<String, String>) accumulate.invoke(null, pageValues);
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
}
