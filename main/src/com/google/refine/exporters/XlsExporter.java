/*

Copyright 2010,2011 Google Inc.
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

    * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
    * Neither the name of Google Inc. nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,           
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY           
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

*/

package com.google.refine.exporters;

import java.io.IOException;
import java.io.OutputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Properties;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import com.google.refine.ProjectManager;
import com.google.refine.browsing.Engine;
import com.google.refine.model.ColumnModel;
import com.google.refine.model.Project;
import com.google.refine.model.Row;
import com.google.refine.model.SheetData;
import com.google.refine.util.ParsingUtilities;

public class XlsExporter implements StreamExporter {

    final private boolean xml;

    public XlsExporter(boolean xml) {
        this.xml = xml;
    }

    @Override
    public String getContentType() {
        return xml ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" : "application/vnd.ms-excel";
    }

    @Override
    public void export(final Project project, Properties params, Engine engine,
            OutputStream outputStream) throws IOException {

        final Workbook wb = xml ? new SXSSFWorkbook() : new HSSFWorkbook();
        final int maxRows = getSpreadsheetVersion().getMaxRows();
        final int maxColumns = getSpreadsheetVersion().getMaxColumns();
        final int maxTextLength = getSpreadsheetVersion().getMaxTextLength();

        String sheetIdsParam = params.getProperty("sheetIds");
        
        if (sheetIdsParam != null && project.isMultiSheetProject && project.sheetDataMap != null) {
            String[] sheetIds = sheetIdsParam.split(",");
            exportMultipleSheets(project, params, engine, wb, sheetIds, maxRows, maxColumns, maxTextLength);
        } else {
            exportSingleSheet(project, params, engine, wb, maxRows, maxColumns, maxTextLength);
        }

        wb.write(outputStream);
        outputStream.flush();
        wb.close();
    }

    private void exportSingleSheet(final Project project, Properties params, Engine engine,
            final Workbook wb, final int maxRows, final int maxColumns, final int maxTextLength) {
        TabularSerializer serializer = new TabularSerializer() {

            Sheet s;
            int rowCount = 0;
            CellStyle dateStyle;

            @Override
            public void startFile(JsonNode options) {
                s = wb.createSheet();
                String sheetName = WorkbookUtil.createSafeSheetName(
                        ProjectManager.singleton.getProjectMetadata(project.id).getName());
                wb.setSheetName(0, sheetName);

                dateStyle = wb.createCellStyle();
                dateStyle.setDataFormat(
                        wb.getCreationHelper().createDataFormat().getFormat("YYYY-MM-DD"));
            }

            @Override
            public void endFile() {
            }

            @Override
            public void addRow(List<CellData> cells, boolean isHeader) {
                if (rowCount >= maxRows) {
                    throw new ExporterException(String.format("Maximum number of rows exceeded for export format (%d)", maxRows));
                }
                org.apache.poi.ss.usermodel.Row r = s.createRow(rowCount++);

                for (int i = 0; i < cells.size(); i++) {
                    Cell c = r.createCell(i);
                    if (i == (maxColumns - 1) && cells.size() > maxColumns) {
                        throw new ExporterException(String.format("Maximum number of columns exceeded for export format (%d)", maxColumns));
                    } else {
                        CellData cellData = cells.get(i);

                        if (cellData != null && cellData.text != null && cellData.value != null) {
                            Object v = cellData.value;
                            if (v instanceof Number) {
                                c.setCellValue(((Number) v).doubleValue());
                            } else if (v instanceof Boolean) {
                                c.setCellValue(((Boolean) v).booleanValue());
                            } else if (v instanceof OffsetDateTime) {
                                OffsetDateTime odt = (OffsetDateTime) v;
                                c.setCellValue(ParsingUtilities.offsetDateTimeToCalendar(odt));
                                c.setCellStyle(dateStyle);
                            } else {
                                String s = cellData.text;
                                if (s.length() > maxTextLength) {
                                    throw new ExporterException(
                                            String.format("Maximum size (%d) of cell [%d, %d] exceeded for export format", maxTextLength,
                                                    rowCount, i));
                                }
                                c.setCellValue(s);
                            }

                            if (cellData.link != null) {
                                try {
                                    Hyperlink hl = wb.getCreationHelper().createHyperlink(HyperlinkType.URL);
                                    hl.setLabel(cellData.text);
                                    hl.setAddress(cellData.link);
                                    c.setHyperlink(hl);
                                } catch (IllegalArgumentException e) {
                                    // If we failed to create the hyperlink and add it to the cell,
                                    // we just use the string value as fallback
                                }
                            }
                        }
                    }
                }
            }
        };

        CustomizableTabularExporterUtilities.exportRows(
                project, engine, params, serializer);
    }

    private void exportMultipleSheets(final Project project, Properties params, Engine engine,
            final Workbook wb, String[] sheetIds, final int maxRows, final int maxColumns, final int maxTextLength) {
        for (int sheetIndex = 0; sheetIndex < sheetIds.length; sheetIndex++) {
            String sheetId = sheetIds[sheetIndex].trim();
            SheetData sheetData = project.sheetDataMap.get(sheetId);
            
            if (sheetData == null) {
                continue;
            }

            final SheetData currentSheetData = sheetData;
            final int currentSheetIndex = sheetIndex;
            
            List<Row> originalRows = project.rows;
            ColumnModel originalColumnModel = project.columnModel;
            
            try {
                project.rows = currentSheetData.rows;
                project.columnModel = currentSheetData.columnModel;

                TabularSerializer serializer = new TabularSerializer() {

                    Sheet s;
                    int rowCount = 0;
                    CellStyle dateStyle;

                    @Override
                    public void startFile(JsonNode options) {
                        s = wb.createSheet();
                        String sheetName = WorkbookUtil.createSafeSheetName(currentSheetData.sheetName);
                        wb.setSheetName(currentSheetIndex, sheetName);

                        dateStyle = wb.createCellStyle();
                        dateStyle.setDataFormat(
                                wb.getCreationHelper().createDataFormat().getFormat("YYYY-MM-DD"));
                    }

                    @Override
                    public void endFile() {
                    }

                    @Override
                    public void addRow(List<CellData> cells, boolean isHeader) {
                        if (rowCount >= maxRows) {
                            throw new ExporterException(String.format("Maximum number of rows exceeded for export format (%d)", maxRows));
                        }
                        org.apache.poi.ss.usermodel.Row r = s.createRow(rowCount++);

                        for (int i = 0; i < cells.size(); i++) {
                            Cell c = r.createCell(i);
                            if (i == (maxColumns - 1) && cells.size() > maxColumns) {
                                throw new ExporterException(String.format("Maximum number of columns exceeded for export format (%d)", maxColumns));
                            } else {
                                CellData cellData = cells.get(i);

                                if (cellData != null && cellData.text != null && cellData.value != null) {
                                    Object v = cellData.value;
                                    if (v instanceof Number) {
                                        c.setCellValue(((Number) v).doubleValue());
                                    } else if (v instanceof Boolean) {
                                        c.setCellValue(((Boolean) v).booleanValue());
                                    } else if (v instanceof OffsetDateTime) {
                                        OffsetDateTime odt = (OffsetDateTime) v;
                                        c.setCellValue(ParsingUtilities.offsetDateTimeToCalendar(odt));
                                        c.setCellStyle(dateStyle);
                                    } else {
                                        String s = cellData.text;
                                        if (s.length() > maxTextLength) {
                                            throw new ExporterException(
                                                    String.format("Maximum size (%d) of cell [%d, %d] exceeded for export format", maxTextLength,
                                                            rowCount, i));
                                        }
                                        c.setCellValue(s);
                                    }

                                    if (cellData.link != null) {
                                        try {
                                            Hyperlink hl = wb.getCreationHelper().createHyperlink(HyperlinkType.URL);
                                            hl.setLabel(cellData.text);
                                            hl.setAddress(cellData.link);
                                            c.setHyperlink(hl);
                                        } catch (IllegalArgumentException e) {
                                            // If we failed to create the hyperlink and add it to the cell,
                                            // we just use the string value as fallback
                                        }
                                    }
                                }
                            }
                        }
                    }
                };

                CustomizableTabularExporterUtilities.exportRows(
                        project, engine, params, serializer);
            } finally {
                project.rows = originalRows;
                project.columnModel = originalColumnModel;
            }
        }
    }

    /**
     * @return POI <code></code>SpreadsheetVersion</code> with metadata about cell, row and column limits
     */
    SpreadsheetVersion getSpreadsheetVersion() {
        return xml ? SpreadsheetVersion.EXCEL2007 : SpreadsheetVersion.EXCEL97;
    }

}
