/*
 * JinRefine · OR 数据源适配器（P0）单元测试
 */

package com.jiongche.refine.data;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.io.Serializable;
import java.util.List;

import com.jiongche.sdk.data.DataSource;
import com.jiongche.sdk.data.TableDriver;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.refine.ProjectManager;
import com.google.refine.RefineTest;
import com.google.refine.model.OverlayModel;
import com.google.refine.model.Project;
import com.google.refine.operations.OperationRegistry;

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

    /** 注册表测试用桩数据源（只用于验证按名取用/枚举）。 */
    private static final class StubDataSource implements DataSource {
        @Override
        public boolean exists(String projectId) {
            return false;
        }

        @Override
        public TableDriver open(String projectId) {
            return null;
        }

        @Override
        public TableDriver create(String projectId, String name) {
            return null;
        }

        @Override
        public void persist(String projectId) {
        }
    }

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

    @Test
    public void testRegisterPublishesBuiltinSourceAndOps() {
        OrDataSource.register();
        assertTrue(DataSourceRegistry.contains(DataSourceRegistry.OR), "应登记内建数据源");
        assertNotNull(DataSourceRegistry.orDefault(), "orDefault 应可取到内建数据源");
        assertTrue(DataSourceRegistry.names().contains(OrDataSource.NAME), "names 应含内建名");
        // 两个变更操作应已按 data 命名空间登记（history JSON 反解依赖）
        assertSame(OperationRegistry.resolveOperationId(
                OrDataSource.OPERATION_NAMESPACE + "/" + SetCellValueOperation.OPERATION_NAME),
                SetCellValueOperation.class, "set-cell-value 应可反解");
        assertSame(OperationRegistry.resolveOperationId(
                OrDataSource.OPERATION_NAMESPACE + "/" + PutOverlayJsonOperation.OPERATION_NAME),
                PutOverlayJsonOperation.class, "put-overlay-json 应可反解");
    }

    @Test
    public void testRegisterIsIdempotent() {
        OrDataSource.register();
        OrDataSource.register();
        // 幂等：重复调用不应破坏反解（追加语义下若重复登记，resolve 仍取末位，但列表会长——此处只验证可用性）
        assertSame(OperationRegistry.resolveOperationId(
                OrDataSource.OPERATION_NAMESPACE + "/" + SetCellValueOperation.OPERATION_NAME),
                SetCellValueOperation.class);
    }

    @Test
    public void testNamespacedRegisterAndLookup() {
        DataSource stub = new StubDataSource();
        DataSourceRegistry.register("data-quality", "or-view", stub);
        assertSame(DataSourceRegistry.get("data-quality/or-view"), stub, "按命名空间 key 应取到同一实例");
        assertTrue(DataSourceRegistry.names().contains("data-quality/or-view"));
        assertNull(DataSourceRegistry.get("no-such-source"));
    }
}
