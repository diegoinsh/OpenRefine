/*
 * JinRefine · OR 数据源适配器（P0）单元测试
 */

package com.jiongche.refine.data;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;

import java.io.Serializable;
import java.util.List;

import com.jiongche.sdk.data.TableDriver;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.refine.ProjectManager;
import com.google.refine.RefineTest;
import com.google.refine.model.OverlayModel;
import com.google.refine.model.Project;

/**
 * {@link OrDataSource} / {@link OrTableDriver} 的 P0 回归：读（schema/行/单元格）、
 * 写（经 OR 变更路径）、overlay 序列化与写回。
 */
public class OrDataSourceTest extends RefineTest {

    private static final String OVERLAY = "sdkTestOverlay";

    /** 测试用 overlay 模型（公开字段，Jackson 可直序列化/反序列化）。 */
    public static class TestOverlay implements OverlayModel {

        public int count;

        public TestOverlay() {
        }

        @Override
        public void onBeforeSave(Project project) {
        }

        @Override
        public void onAfterSave(Project project) {
        }

        @Override
        public void dispose(Project project) {
        }
    }

    private Project project;

    @BeforeMethod
    public void setUp() {
        Project.registerOverlayModel(OVERLAY, TestOverlay.class);
        project = createProject(new String[] { "name", "value" },
                new Serializable[][] { { "a", "1" }, { "b", "2" } });
    }

    private TableDriver open() {
        return new OrDataSource(ProjectManager.singleton).open(String.valueOf(project.id));
    }

    @Test
    public void testReadSchemaAndValues() {
        TableDriver d = open();
        assertEquals(d.schema().columnNames(), List.of("name", "value"));
        assertEquals(d.schema().columnIndex("value"), 1);
        assertEquals(d.schema().columnIndex("missing"), -1);
        assertEquals(d.rowCount(), 2);
        assertEquals(d.value(0, 0), "a");
        assertEquals(d.value(1, 1), "2");
        assertNull(d.value(5, 0));
    }

    @Test
    public void testSetValueGoesThroughOperation() {
        TableDriver d = open();
        d.setValue(0, 1, "9");
        assertEquals(project.rows.get(0).getCellValue(1), "9");
        // 写入必须走 OR 变更路径：应留下 1 条 history
        assertEquals(project.history.getLastPastEntries(1).size(), 1);
    }

    @Test
    public void testPutOverlayJsonRoundTrip() {
        TableDriver d = open();
        d.putOverlayJson(OVERLAY, "{\"count\":7}");
        assertNotNull(project.overlayModels.get(OVERLAY));
        String json = d.overlayJson(OVERLAY);
        assertNotNull(json);
        assertEquals(true, json.contains("7"), "overlay 应回读出写入值：" + json);
    }

    @Test
    public void testUnknownOverlayRejected() {
        TableDriver d = open();
        try {
            d.putOverlayJson("no-such-overlay", "{}");
            Assert.fail("未注册的 overlay 名应被拒绝");
        } catch (RuntimeException expected) {
            // 预期：非法 overlay 名被拒
        }
    }
}
