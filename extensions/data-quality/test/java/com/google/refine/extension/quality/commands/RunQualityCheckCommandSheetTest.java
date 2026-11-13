/*
 * Data Quality Extension - 检查入口活动表归一化测试
 * 双表项目的判定输入必须取「条目」表
 */
package com.google.refine.extension.quality.commands;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

import org.testng.annotations.Test;

import com.google.refine.model.Project;
import com.google.refine.model.SheetData;

/**
 * 回归：双表项目（条目 + 目录层级）的活动表若停在目录表，
 * 检查会按目录表取列（没有题名），文书级检查静默跳过、documents 为空。
 */
public class RunQualityCheckCommandSheetTest {

    @Test
    public void switchesActiveSheetBackToEntry() {
        Project project = new Project();
        project.addSheetData(new SheetData(IngestProjectCommand.ENTRY_SHEET_ID,
                IngestProjectCommand.ENTRY_SHEET_NAME, ""));
        project.addSheetData(new SheetData(IngestProjectCommand.DIR_SHEET_ID,
                IngestProjectCommand.DIR_SHEET_NAME, ""));
        project.setActiveSheet(IngestProjectCommand.DIR_SHEET_ID);

        RunQualityCheckCommand.activateEntrySheet(project);

        assertEquals(project.activeSheetId, IngestProjectCommand.ENTRY_SHEET_ID);
    }

    @Test
    public void keepsDirectoryOnlyProjectUntouched() {
        Project project = new Project();
        project.addSheetData(new SheetData(IngestProjectCommand.DIR_SHEET_ID,
                IngestProjectCommand.DIR_SHEET_NAME, ""));
        project.setActiveSheet(IngestProjectCommand.DIR_SHEET_ID);

        RunQualityCheckCommand.activateEntrySheet(project);

        // 天然多表但只有目录表时不会凭空切表（保持 null / 原值）
        assertNull(project.sheetDataMap.get(IngestProjectCommand.ENTRY_SHEET_ID));
    }
}
