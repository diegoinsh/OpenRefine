/*******************************************************************************
 * Copyright (C) 2025, OpenRefine contributors
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/

package com.google.refine.operations.row;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.node.TextNode;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.Test;

import com.google.refine.RefineTest;
import com.google.refine.browsing.Engine;
import com.google.refine.browsing.EngineConfig;
import com.google.refine.history.HistoryEntry;
import com.google.refine.model.ColumnsDiff;
import com.google.refine.model.Project;
import com.google.refine.operations.OperationDescription;
import com.google.refine.operations.OperationRegistry;
import com.google.refine.util.ParsingUtilities;
import com.google.refine.util.TestUtils;

public class SplitMarkedRowsOperationTests extends RefineTest {

    @BeforeSuite
    public void registerOperation() {
        OperationRegistry.registerOperation(getCoreModule(), "split-marked-rows", SplitMarkedRowsOperation.class);
    }

    private static EngineConfig defaultEngineConfig() {
        return new EngineConfig(Collections.emptyList(), Engine.Mode.RowBased);
    }

    private static SplitMarkedRowsOperation operation() {
        return new SplitMarkedRowsOperation(defaultEngineConfig());
    }

    private static List<Object> values(Project project, int cellIndex) {
        return project.rows.stream().map(row -> row.getCellValue(cellIndex)).collect(Collectors.toList());
    }

    @Test
    public void serializeSplitMarkedRowsOperation() throws Exception {
        String json = "{"
                + "\"op\":\"core/split-marked-rows\","
                + "\"description\":" + new TextNode(OperationDescription.row_split_marked_brief()).toString() + ","
                + "\"engineConfig\":{\"mode\":\"row-based\",\"facets\":[]}}";
        TestUtils.isSerializedTo(ParsingUtilities.mapper.readValue(json, SplitMarkedRowsOperation.class), json);
    }

    @Test
    public void testColumnDependencies() {
        assertEquals(operation().getColumnsDiff(), Optional.of(ColumnsDiff.empty()));
        assertEquals(operation().getColumnDependencies(), Optional.of(Set.of()));
    }

    /** 无“件号”列时，纯复制一行，新行继承标记。 */
    @Test
    public void testDuplicateMarkedRowWithoutNumberColumn() throws Exception {
        Project project = createProject(new String[] { "foo", "bar" },
                new Serializable[][] {
                        { "a", "b" },
                        { "c", "d" },
                        { "e", "f" }
                });
        project.rows.get(1).starred = true;

        runOperation(operation(), project);

        assertEquals(project.rows.size(), 4);
        assertEquals(values(project, 0), Arrays.asList("a", "c", "c", "e"));
        assertEquals(values(project, 1), Arrays.asList("b", "d", "d", "f"));
        assertTrue(project.rows.get(2).starred, "新行应继承原行的星标");
    }

    /** 件号列：原件不动、新行 +1、其后严格递增链顺延。 */
    @Test
    public void testNumberColumnAutoIncrement() throws Exception {
        Project project = createProject(new String[] { "件号", "名称" },
                new Serializable[][] {
                        { "0001", "a" },
                        { "0002", "b" },
                        { "0003", "c" },
                        { "0004", "d" }
                });
        project.rows.get(1).starred = true;

        runOperation(operation(), project);

        assertEquals(project.rows.size(), 5);
        assertEquals(values(project, 0), Arrays.asList("0001", "0002", "0003", "0004", "0005"));
        assertEquals(values(project, 1), Arrays.asList("a", "b", "b", "c", "d"));
    }

    /** 严格递增判定：出现不再递增（<=）即停止，其后行不动。 */
    @Test
    public void testIncrementStopsWhenNotIncreasing() throws Exception {
        Project project = createProject(new String[] { "件号" },
                new Serializable[][] {
                        { "0001" },
                        { "0002" },
                        { "0003" },
                        { "0001" }
                });
        project.rows.get(1).flagged = true;

        runOperation(operation(), project);

        assertEquals(values(project, 0), Arrays.asList("0001", "0002", "0003", "0004", "0001"));
    }

    /** 列名大小写不敏感匹配、按原值宽度补零。 */
    @Test
    public void testWidthPreservedAndCaseInsensitiveColumnName() throws Exception {
        Project project = createProject(new String[] { "JH" },
                new Serializable[][] {
                        { "00009" },
                        { "00010" }
                });
        project.rows.get(0).starred = true;

        runOperation(operation(), project);

        assertEquals(values(project, 0), Arrays.asList("00009", "00010", "00011"));
    }

    /** 多个标记行各自拆分，件号整体保持连续。 */
    @Test
    public void testMultipleMarkedRows() throws Exception {
        Project project = createProject(new String[] { "jianhao" },
                new Serializable[][] {
                        { "0001" },
                        { "0002" },
                        { "0003" },
                        { "0004" }
                });
        project.rows.get(1).starred = true;
        project.rows.get(2).flagged = true;

        runOperation(operation(), project);

        assertEquals(values(project, 0), Arrays.asList("0001", "0002", "0003", "0004", "0005", "0006"));
    }

    /** 值非“0 开头多位数字”时不做编号，仅复制。 */
    @Test
    public void testNonZeroLeadingValueIsCopiedVerbatim() throws Exception {
        Project project = createProject(new String[] { "件号" },
                new Serializable[][] {
                        { "1" },
                        { "abc" }
                });
        project.rows.get(0).starred = true;

        runOperation(operation(), project);

        assertEquals(values(project, 0), Arrays.asList("1", "1", "abc"));
    }

    /** 结果计入历史：可撤销、可重做。 */
    @Test
    public void testUndoRedo() throws Exception {
        Project project = createProject(new String[] { "件号" },
                new Serializable[][] {
                        { "0001" },
                        { "0002" },
                        { "0003" }
                });
        project.rows.get(1).starred = true;

        runOperation(operation(), project);
        assertEquals(project.rows.size(), 4);
        assertEquals(values(project, 0), Arrays.asList("0001", "0002", "0003", "0004"));

        HistoryEntry entry = project.history.getLastPastEntries(1).get(0);
        entry.revert(project);
        assertEquals(project.rows.size(), 3);
        assertEquals(values(project, 0), Arrays.asList("0001", "0002", "0003"));

        entry.apply(project);
        assertEquals(project.rows.size(), 4);
        assertEquals(values(project, 0), Arrays.asList("0001", "0002", "0003", "0004"));
    }

    /** dryRun 计数：只统计当前视图内被标记的行。 */
    @Test
    public void testCountMarkedRows() {
        Project project = createProject(new String[] { "件号" },
                new Serializable[][] {
                        { "0001" },
                        { "0002" },
                        { "0003" }
                });
        project.rows.get(0).starred = true;
        project.rows.get(2).flagged = true;

        Engine engine = new Engine(project);
        engine.initializeFromConfig(defaultEngineConfig());

        assertEquals(SplitMarkedRowsOperation.countMarkedRows(project, engine), 2);
    }
}