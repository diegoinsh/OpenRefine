package com.google.refine.commands.cell;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.refine.RefineTest;
import com.google.refine.commands.Command;
import com.google.refine.model.Cell;
import com.google.refine.model.Project;
import com.google.refine.util.TestUtils;

/**
 * 「填充柄」批量填充命令测试：复制、序列（数字 / 前导零 / 文本尾号），以及单条历史与边界保护。
 */
public class FillCellsCommandTests extends RefineTest {

    protected Project project = null;
    protected HttpServletRequest request = null;
    protected HttpServletResponse response = null;
    protected Command command = null;
    protected StringWriter writer = null;

    @BeforeMethod
    public void setUpProject() throws IOException {
        project = createProject(
                new String[] { "first_column", "second_column" },
                new Serializable[][] {
                        { "a", "b" },
                        { "c", "d" },
                        { "e", "f" }
                });
        command = new FillCellsCommand();
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        writer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(writer));
    }

    private void stub(String mode, String source, String targets) {
        when(request.getParameter("project")).thenReturn(Long.toString(project.id));
        when(request.getParameter("mode")).thenReturn(mode);
        when(request.getParameter("source")).thenReturn(source);
        when(request.getParameter("targets")).thenReturn(targets);
        when(request.getParameter("csrf_token")).thenReturn(Command.csrfFactory.getFreshToken());
    }

    private static Object value(Project project, int row, int cell) {
        Cell c = project.rows.get(row).getCell(cell);
        return c == null ? null : c.value;
    }

    /** 复制内容：目标格全部变成源值。 */
    @Test
    public void testCopyCells() throws ServletException, IOException {
        stub("copy",
                "{\"row\":0,\"cell\":0,\"value\":\"a\",\"type\":\"text\"}",
                "[{\"row\":1,\"cell\":0},{\"row\":2,\"cell\":0}]");

        command.doPost(request, response);

        assertEquals(value(project, 0, 0), "a");
        assertEquals(value(project, 1, 0), "a");
        assertEquals(value(project, 2, 0), "a");
        // 只写入一条历史
        assertEquals(project.history.getLastPastEntries(1).size(), 1);
    }

    /** 序列填充：数字逐格 +1。 */
    @Test
    public void testSeriesNumber() throws ServletException, IOException {
        Project p = createProject(new String[] { "n" }, new Serializable[][] { { 5 }, { 0 }, { 0 } });
        this.project = p;
        stub("series",
                "{\"row\":0,\"cell\":0,\"value\":\"5\",\"type\":\"number\"}",
                "[{\"row\":1,\"cell\":0},{\"row\":2,\"cell\":0}]");

        command.doPost(request, response);

        assertEquals(value(p, 1, 0), Long.valueOf(6));
        assertEquals(value(p, 2, 0), Long.valueOf(7));
    }

    /** 序列填充：文本保留前导零与宽度。 */
    @Test
    public void testSeriesLeadingZeros() throws ServletException, IOException {
        Project p = createProject(new String[] { "件号" },
                new Serializable[][] { { "0001" }, { "0000" }, { "0000" } });
        this.project = p;
        stub("series",
                "{\"row\":0,\"cell\":0,\"value\":\"0001\",\"type\":\"text\"}",
                "[{\"row\":1,\"cell\":0},{\"row\":2,\"cell\":0}]");

        command.doPost(request, response);

        assertEquals(value(p, 1, 0), "0002");
        assertEquals(value(p, 2, 0), "0003");
    }

    /** 序列填充：文本尾号为数字，前缀原样、尾号 +k。 */
    @Test
    public void testSeriesTrailingDigits() throws ServletException, IOException {
        stub("series",
                "{\"row\":0,\"cell\":0,\"value\":\"A-5\",\"type\":\"text\"}",
                "[{\"row\":1,\"cell\":0},{\"row\":2,\"cell\":0}]");

        command.doPost(request, response);

        assertEquals(value(project, 1, 0), "A-6");
        assertEquals(value(project, 2, 0), "A-7");
    }

    /** 源单元格的就地编辑内容与填充值在同一条历史中提交（覆盖原值）。 */
    @Test
    public void testSourceEditCommittedTogether() throws ServletException, IOException {
        stub("copy",
                "{\"row\":0,\"cell\":0,\"value\":\"z\",\"type\":\"text\"}",
                "[{\"row\":1,\"cell\":0}]");

        command.doPost(request, response);

        assertEquals(value(project, 0, 0), "z");
        assertEquals(value(project, 1, 0), "z");
        assertEquals(project.history.getLastPastEntries(1).size(), 1);
    }

    /** 越界的目标格被忽略，不抛异常；无实际改动时不写入历史。 */
    @Test
    public void testOutOfBoundsTargetIgnored() throws ServletException, IOException {
        stub("copy",
                "{\"row\":0,\"cell\":0,\"value\":\"a\",\"type\":\"text\"}",
                "[{\"row\":99,\"cell\":0}]");

        command.doPost(request, response);

        assertTrue(project.history.getLastPastEntries(1).isEmpty());
        TestUtils.assertEqualsAsJson(writer.toString(), "{\"code\":\"ok\"}");
    }

    /** 缺少 CSRF 令牌时拒绝执行且不改动数据。 */
    @Test
    public void testMissingCSRFToken() throws ServletException, IOException {
        when(request.getParameter("project")).thenReturn(Long.toString(project.id));
        when(request.getParameter("mode")).thenReturn("copy");
        when(request.getParameter("source")).thenReturn("{\"row\":0,\"cell\":0,\"value\":\"a\",\"type\":\"text\"}");
        when(request.getParameter("targets")).thenReturn("[{\"row\":1,\"cell\":0}]");

        command.doPost(request, response);

        assertEquals(value(project, 1, 0), "c");
        TestUtils.assertEqualsAsJson(writer.toString(), "{\"code\":\"error\",\"message\":\"Missing or invalid csrf_token parameter\"}");
    }
}
