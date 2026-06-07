/*

Copyright 2010, 2022 Google Inc., OpenRefine developers
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

package com.google.refine.importers;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.CharMatcher;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.formula.ConditionalFormattingEvaluator;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.ExcelNumberFormat;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.ProjectMetadata;
import com.google.refine.expr.ExpressionUtils;
import com.google.refine.importing.ImportingJob;
import com.google.refine.importing.ImportingUtilities;
import com.google.refine.model.Cell;
import com.google.refine.model.Column;
import com.google.refine.model.ColumnModel;
import com.google.refine.model.ModelException;
import com.google.refine.model.Project;
import com.google.refine.model.Row;
import com.google.refine.model.SheetData;
import com.google.refine.util.JSONUtilities;
import com.google.refine.util.ParsingUtilities;

public class ExcelImporter extends TabularImportingParserBase {

    static final Logger logger = LoggerFactory.getLogger(ExcelImporter.class);
    static final DataFormatter dataFormatter = new DataFormatter();
    // TODO: Positive;negative;zero;text formats & color codes e.g. $#,##0.00_);[Red]($#,##0.00)
    // TODO: Conditional codes like currency [$K-647]
    static final Pattern NUMERIC_FORMAT = Pattern.compile("^\\?*\\$?[#,]+(0?\\.0[0#\\?]*)?%?$");
    ConditionalFormattingEvaluator cfEvaluator;

    public ExcelImporter() {
        super(true);
    }

    @Override
    public ObjectNode createParserUIInitializationData(
            ImportingJob job, List<ObjectNode> fileRecords, String format) {
        ObjectNode options = super.createParserUIInitializationData(job, fileRecords, format);

        JSONUtilities.safePut(options, "forceText", false);
        ArrayNode sheetRecords = ParsingUtilities.mapper.createArrayNode();
        JSONUtilities.safePut(options, "sheetRecords", sheetRecords);
        try {
            for (int index = 0; index < fileRecords.size(); index++) {
                ObjectNode fileRecord = fileRecords.get(index);
                File file = ImportingUtilities.getFile(job, fileRecord);

                Workbook wb = null;
                try {
                    wb = FileMagic.valueOf(file) == FileMagic.OOXML ? new XSSFWorkbook(file) : new HSSFWorkbook(new POIFSFileSystem(file));
                    // TODO: Implement support for conditional formatting so that cells are rendered the same as in
                    // Excel
//                    cfEvaluator = new ConditionalFormattingEvaluator(wb,)
                    int sheetCount = wb.getNumberOfSheets();
                    for (int i = 0; i < sheetCount; i++) {
                        Sheet sheet = wb.getSheetAt(i);
                        int rows = sheet.getLastRowNum() - sheet.getFirstRowNum() + 1;

                        ObjectNode sheetRecord = ParsingUtilities.mapper.createObjectNode();
                        JSONUtilities.safePut(sheetRecord, "name", file.getName() + "#" + sheet.getSheetName());
                        JSONUtilities.safePut(sheetRecord, "fileNameAndSheetIndex", file.getName() + "#" + i);
                        JSONUtilities.safePut(sheetRecord, "rows", rows);
                        if (rows > 1) {
                            JSONUtilities.safePut(sheetRecord, "selected", true);
                        } else {
                            JSONUtilities.safePut(sheetRecord, "selected", false);
                        }
                        JSONUtilities.append(sheetRecords, sheetRecord);
                    }
                } finally {
                    if (wb != null) {
                        wb.close();
                    }
                }
            }
        } catch (IOException e) {
            JSONUtilities.safePut(options, "error", e.toString());
            logger.error("Error generating parser UI initialization data for Excel file", e);
        } catch (IllegalArgumentException e) {
            JSONUtilities.safePut(options, "error", e.toString());
            logger.error("Error generating parser UI initialization data for Excel file (only Excel 97 & later supported)", e);
        } catch (POIXMLException | InvalidFormatException e) {
            JSONUtilities.safePut(options, "error", e.toString());
            logger.error("Error generating parser UI initialization data for Excel file - invalid XML", e);
        }

        return options;
    }

    @Override
    public void parseOneFile(
            Project project,
            ProjectMetadata metadata,
            ImportingJob job,
            String fileSource,
            InputStream inputStream,
            int limit,
            ObjectNode options,
            List<Exception> exceptions) {
        Workbook wb;
        if (!inputStream.markSupported()) {
            inputStream = new BufferedInputStream(inputStream);
        }

        try {
            wb = FileMagic.valueOf(inputStream) == FileMagic.OOXML ? new XSSFWorkbook(inputStream)
                    : new HSSFWorkbook(new POIFSFileSystem(inputStream));
        } catch (IOException e) {
            exceptions.add(new ImportException(
                    "Attempted to parse as an Excel file but failed. " +
                            "Try to use Excel to re-save the file as a different Excel version or as TSV and upload again.",
                    e));
            return;
        } catch (ArrayIndexOutOfBoundsException e) {
            exceptions.add(new ImportException(
                    "Attempted to parse file as an Excel file but failed. " +
                            "This is probably caused by a corrupt excel file, or due to the file having previously been created or saved by a non-Microsoft application. "
                            +
                            "Please try opening the file in Microsoft Excel and resaving it, then try re-uploading the file. " +
                            "See https://issues.apache.org/bugzilla/show_bug.cgi?id=48261 for further details",
                    e));
            return;
        } catch (IllegalArgumentException e) {
            exceptions.add(new ImportException(
                    "Attempted to parse as an Excel file but failed. " +
                            "Only Excel 97 and later formats are supported.",
                    e));
            return;
        } catch (POIXMLException e) {
            exceptions.add(new ImportException(
                    "Attempted to parse as an Excel file but failed. " +
                            "Invalid XML.",
                    e));
            return;
        }

        final boolean forceText;
        if (options.get("forceText") != null) {
            forceText = options.get("forceText").asBoolean(false);
        } else {
            forceText = false;
        }
        ArrayNode sheets = (ArrayNode) options.get("sheets");
        ObjectNode sheetOptions = (ObjectNode) options.get("sheetOptions");
        
        logger.info("Processing {} sheets from fileSource: {}", sheets.size(), fileSource);
        logger.info("Sheet options: {}", sheetOptions != null ? sheetOptions.toString() : "null");
        
        boolean isMultiSheetImport = sheets.size() > 1;
        String firstSheetId = null;

        for (int i = 0; i < sheets.size(); i++) {
            String[] fileNameAndSheetIndex = new String[2];
            ObjectNode sheetObj = (ObjectNode) sheets.get(i);
            // value is fileName#sheetIndex
            fileNameAndSheetIndex = sheetObj.get("fileNameAndSheetIndex").asText().split("#");
            int sheetIndex = Integer.parseInt(fileNameAndSheetIndex[1]);

            logger.info("Processing sheet at index {}: fileNameAndSheetIndex[0]={}, fileSource={}", 
                i, fileNameAndSheetIndex[0], fileSource);

            if (!fileNameAndSheetIndex[0].equals(fileSource))
                continue;

            final Sheet sheet = wb.getSheetAt(sheetIndex);
            final String sheetName = sheet.getSheetName();
            final String sheetId = fileSource + "#" + sheetName;
            final int lastRow = sheet.getLastRowNum();
            
            logger.info("Processing sheet: {} (index: {}, lastRow: {})", sheetName, sheetIndex, lastRow);
            
            if (firstSheetId == null) {
                firstSheetId = sheetId;
            }
            
            ObjectNode currentSheetOptions = null;
            if (isMultiSheetImport && sheetOptions != null && sheetOptions.has(String.valueOf(sheetIndex))) {
                currentSheetOptions = (ObjectNode) sheetOptions.get(String.valueOf(sheetIndex));
                logger.info("Sheet {} has custom options: {}", sheetName, currentSheetOptions.toString());
            } else {
                logger.info("Sheet {} using global options, isMultiSheet: {}, sheetOptions: {}", 
                    sheetName, isMultiSheetImport, sheetOptions != null ? sheetOptions.toString() : "null");
            }

            TableDataReader dataReader = new TableDataReader() {

                int nextRow = 0;
                int maxColumnCount = 0;

                @Override
                public List<Object> getNextRowOfCells() throws IOException {
                    if (nextRow > lastRow) {
                        return null;
                    }

                    List<Object> cells = new ArrayList<Object>();
                    org.apache.poi.ss.usermodel.Row row = sheet.getRow(nextRow++);
                    if (row != null) {
                        short lastCell = row.getLastCellNum();
                        short firstCell = row.getFirstCellNum();
                        int physicalCells = row.getPhysicalNumberOfCells();
                        logger.info("Row {}: firstCell={}, lastCell={}, physicalCells={}", 
                            nextRow - 1, firstCell, lastCell, physicalCells);
                        
                        int actualLastCell;
                        if (physicalCells > 0) {
                            int maxCol = 0;
                            for (int i = 0; i < physicalCells; i++) {
                                org.apache.poi.ss.usermodel.Cell cell = row.getCell(firstCell + i);
                                if (cell != null) {
                                    int colIndex = cell.getColumnIndex();
                                    if (colIndex > maxCol) {
                                        maxCol = colIndex;
                                    }
                                }
                            }
                            actualLastCell = maxCol + 1;
                            logger.info("Row {}: actualLastCell={} (based on physical cells)", 
                                nextRow - 1, actualLastCell);
                        } else {
                            actualLastCell = firstCell;
                        }
                        
                        if (actualLastCell > maxColumnCount) {
                            maxColumnCount = actualLastCell;
                        }
                        
                        for (short cellIndex = firstCell; cellIndex < actualLastCell; cellIndex++) {
                            Cell cell = null;

                            org.apache.poi.ss.usermodel.Cell sourceCell = row.getCell(cellIndex);
                            if (sourceCell != null) {
                                cell = extractCell(sourceCell, forceText);
                                logger.debug("Cell [{}][{}]: {}", nextRow - 1, cellIndex, 
                                    cell != null ? cell.value : "null");
                            }
                            cells.add(cell);
                        }
                    }
                    return cells;
                }
            };

            if (isMultiSheetImport) {
                SheetData sheetData = new SheetData(sheetId, sheetName, fileSource);
                
                ObjectNode optionsToUse = currentSheetOptions != null ? currentSheetOptions : options;
                
                logger.info("Processing sheet {} with options: {}", sheetName, optionsToUse.toString());
                
                sheetData.ignoreLines = JSONUtilities.getInt(optionsToUse, "ignoreLines", -1);
                sheetData.headerLines = JSONUtilities.getInt(optionsToUse, "headerLines", 1);
                sheetData.skipDataLines = JSONUtilities.getInt(optionsToUse, "skipDataLines", 0);
                sheetData.limit = JSONUtilities.getInt(optionsToUse, "limit", -1);
                sheetData.storeBlankRows = JSONUtilities.getBoolean(optionsToUse, "storeBlankRows", true);
                sheetData.storeBlankColumns = JSONUtilities.getBoolean(optionsToUse, "storeBlankColumns", true);
                sheetData.storeBlankCellsAsNulls = JSONUtilities.getBoolean(optionsToUse, "storeBlankCellsAsNulls", true);
                sheetData.forceText = JSONUtilities.getBoolean(optionsToUse, "forceText", false);
                
                logger.info("Sheet {} config - headerLines: {}, ignoreLines: {}, skipDataLines: {}, limit: {}", 
                    sheetName, sheetData.headerLines, sheetData.ignoreLines, sheetData.skipDataLines, sheetData.limit);
                
                readTableToSheetData(project, metadata, job, dataReader, sheetData, optionsToUse, exceptions);
                
                project.addSheetData(sheetData);
            } else {
                TabularImportingParserBase.readTable(
                        project,
                        metadata,
                        job,
                        dataReader,
                        fileSource + "#" + sheet.getSheetName(),
                        limit,
                        options,
                        exceptions);
            }
        }
        
        if (isMultiSheetImport && firstSheetId != null) {
            project.activeSheetId = firstSheetId;
            project.isMultiSheetProject = true;
            
            SheetData firstSheetData = project.sheetDataMap.get(firstSheetId);
            if (firstSheetData != null) {
                project.rows = firstSheetData.rows;
                project.columnModel = firstSheetData.columnModel;
                project.recordModel = firstSheetData.recordModel;
            }
        }
    }
    
    static protected void readTableToSheetData(
            Project project,
            ProjectMetadata metadata,
            ImportingJob job,
            TableDataReader reader,
            SheetData sheetData,
            ObjectNode options,
            List<Exception> exceptions) {
        int ignoreLines = JSONUtilities.getInt(options, "ignoreLines", -1);
        int headerLines = JSONUtilities.getInt(options, "headerLines", 1);
        int skipDataLines = JSONUtilities.getInt(options, "skipDataLines", 0);
        int limit = JSONUtilities.getInt(options, "limit", -1);

        boolean guessCellValueTypes = JSONUtilities.getBoolean(options, "guessCellValueTypes", false);
        boolean storeBlankRows = JSONUtilities.getBoolean(options, "storeBlankRows", true);
        boolean storeBlankCellsAsNulls = JSONUtilities.getBoolean(options, "storeBlankCellsAsNulls", true);
        boolean trimStrings = JSONUtilities.getBoolean(options, "trimStrings", false);

        List<String> columnNames = new ArrayList<String>();
        boolean hasOurOwnColumnNames = headerLines > 0;

        List<Object> cells = null;
        int rowsWithData = 0;

        try {
            while (!job.canceled && (cells = reader.getNextRowOfCells()) != null) {
                if (ignoreLines > 0) {
                    ignoreLines--;
                    continue;
                }

                if (headerLines > 0) {
                    for (int c = 0; c < cells.size(); c++) {
                        Object cell = cells.get(c);

                        String columnName;
                        if (cell == null) {
                            columnName = "";
                        } else if (cell instanceof Cell) {
                            columnName = CharMatcher.whitespace().trimFrom(((Cell) cell).value.toString());
                        } else {
                            columnName = CharMatcher.whitespace().trimFrom(cell.toString());
                        }

                        ImporterUtilities.appendColumnName(columnNames, c, columnName);
                    }

                    headerLines--;
                    if (headerLines == 0) {
                        setupColumnsForSheetData(sheetData.columnModel, columnNames);
                    }
                } else {
                    Row row = new Row(cells.size());

                    if (storeBlankRows || cells.size() > 0) {
                        rowsWithData++;
                    }

                    if (skipDataLines <= 0 || rowsWithData > skipDataLines) {
                        boolean rowHasData = false;
                        
                        logger.info("Processing data row: cells.size()={}, columnModel.getMaxCellIndex()={}, columnNames.size()={}", 
                            cells.size(), sheetData.columnModel.getMaxCellIndex(), columnNames.size());

                        for (int c = 0; c < cells.size(); c++) {
                            Column column = getOrAllocateColumnForSheetData(
                                    sheetData.columnModel, columnNames, c, hasOurOwnColumnNames);
                            int cellIndex = column.getCellIndex();

                            logger.info("Row cell {}: column={}, cellIndex={}, value={}", 
                                c, column.getName(), cellIndex, cells.get(c));

                            Object value = cells.get(c);
                            if (value instanceof Cell) {
                                row.setCell(cellIndex, (Cell) value);
                                rowHasData = true;
                            } else if (ExpressionUtils.isNonBlankData(value)) {
                                Serializable storedValue;
                                if (value instanceof String) {
                                    if (trimStrings) {
                                        value = CharMatcher.whitespace().trimFrom(((String) value));
                                    }
                                    storedValue = guessCellValueTypes ? ImporterUtilities.parseCellValue((String) value) : (String) value;

                                } else {
                                    storedValue = ExpressionUtils.wrapStorable(value);
                                }

                                row.setCell(cellIndex, new Cell(storedValue, null));
                                rowHasData = true;
                            } else if (!storeBlankCellsAsNulls) {
                                row.setCell(cellIndex, new Cell("", null));
                            } else {
                                row.setCell(cellIndex, null);
                            }
                        }
                        
                        logger.info("After processing row: row.cells.size()={}, rowHasData={}, columnNames.size()={}", 
                            row.cells.size(), rowHasData, columnNames.size());

                        if (rowHasData || storeBlankRows) {
                            sheetData.rows.add(row);
                        }

                        if (limit > 0 && sheetData.rows.size() >= limit) {
                            break;
                        }
                    }
                }
            }
        } catch (IOException e) {
            exceptions.add(e);
        }
    }
    
    static protected void setupColumnsForSheetData(ColumnModel columnModel, List<String> columnNames) {
        Map<String, Integer> nameToIndex = new HashMap<String, Integer>();
        logger.info("setupColumnsForSheetData: columnNames.size() = {}", columnNames.size());
        for (int c = 0; c < columnNames.size(); c++) {
            String cell = CharMatcher.whitespace().trimFrom(columnNames.get(c));
            if (cell.isEmpty()) {
                cell = "Column";
            } else if (cell.startsWith("\"") && cell.endsWith("\"")) {
                cell = cell.substring(1, cell.length() - 1).trim();
            }

            if (nameToIndex.containsKey(cell)) {
                int index = nameToIndex.get(cell);
                nameToIndex.put(cell, index + 1);

                cell = cell.contains(" ") ? (cell + " " + index) : (cell + index);
            } else {
                nameToIndex.put(cell, 2);
            }

            columnNames.set(c, cell);
            if (columnModel.getColumnByName(cell) == null) {
                int cellIndex = columnModel.allocateNewCellIndex();
                Column column = new Column(cellIndex, cell);
                logger.info("Creating column: name={}, cellIndex={}", cell, cellIndex);
                try {
                    columnModel.addColumn(-1, column, false);
                } catch (ModelException e) {
                    // Ignore: shouldn't get in here since we just checked for duplicate names.
                }
            } else {
                Column existingColumn = columnModel.getColumnByName(cell);
                logger.info("Column already exists: name={}, cellIndex={}", cell, existingColumn.getCellIndex());
            }
        }
        columnModel.update();
        logger.info("setupColumnsForSheetData: after update, columnModel.columns.size() = {}", columnModel.columns.size());
    }
    
    static protected Column getOrAllocateColumnForSheetData(ColumnModel columnModel, List<String> currentFileColumnNames,
            int index, boolean hasOurOwnColumnNames) {
        if (index < currentFileColumnNames.size()) {
            Column column = columnModel.getColumnByName(currentFileColumnNames.get(index));
            logger.info("getOrAllocateColumnForSheetData: index={}, columnName={}, cellIndex={}", 
                index, currentFileColumnNames.get(index), column != null ? column.getCellIndex() : "null");
            return column;
        } else if (index >= currentFileColumnNames.size()) {
            String prefix = "Column ";
            int i = index + 1;
            String columnName = prefix + i;
            while (columnModel.getColumnByName(columnName) != null) {
                i++;
                columnName = prefix + i;
            }
            
            Column column = columnModel.getColumnByName(columnName);
            if (column == null) {
                int cellIndex = columnModel.allocateNewCellIndex();
                column = new Column(cellIndex, columnName);
                logger.info("getOrAllocateColumnForSheetData: creating new column: index={}, columnName={}, cellIndex={}", 
                    index, columnName, cellIndex);
                try {
                    columnModel.addColumn(-1, column, false);
                } catch (ModelException e) {
                    // Ignore
                }
            } else {
                logger.info("getOrAllocateColumnForSheetData: column already exists: index={}, columnName={}, cellIndex={}", 
                    index, columnName, column.getCellIndex());
            }
            
            currentFileColumnNames.add(columnName);
            logger.info("getOrAllocateColumnForSheetData: added columnName to currentFileColumnNames: index={}, columnName={}, columnNames.size()={}", 
                index, columnName, currentFileColumnNames.size());
            
            return column;
        } else {
            return null;
        }
    }

    static protected Cell extractCell(org.apache.poi.ss.usermodel.Cell cell, boolean forceText) {
        if (forceText) {
            return new Cell(dataFormatter.formatCellValue(cell), null);
        } else {
            return extractCell(cell);
        }
    }

    static protected Cell extractCell(org.apache.poi.ss.usermodel.Cell cell) {
        CellType cellType = cell.getCellType();
        if (cellType.equals(CellType.FORMULA)) {
            cellType = cell.getCachedFormulaResultType();
        }
        if (cellType.equals(CellType.ERROR) ||
                cellType.equals(CellType.BLANK)) {
            return null;
        }

        Serializable value = null;
        if (cellType.equals(CellType.BOOLEAN)) {
            value = cell.getBooleanCellValue();
        } else if (cellType.equals(CellType.NUMERIC)) {
            double d = cell.getNumericCellValue();
            ExcelNumberFormat nf = ExcelNumberFormat.from(cell, null);
            if (DateUtil.isCellDateFormatted(cell)) { // This checks range as well as format, so is more comprehensive
                // Excel supports dates, times, intervals (via format strings), but we only have a datetime type
                // all unsupported types (ie if it doesn't have both date & time components in the format string)
                // are rendered to text and imported as strings
                if (!isDatetimeFormat(nf)) {
                    value = dataFormatter.formatCellValue(cell);
                } else {
                    value = ParsingUtilities.toDate(DateUtil.getJavaDate(d));
                }
            } else {
                String format = nf.getFormat();
                if ("General".equals(format)) {
                    if (d % 1.0 == 0) {
                        value = (long) d;
                    } else {
                        value = d;
                    }
                } else if (isNumberFormat(nf)) {
                    if (format.contains(".")) { // if it's formatted with a decimal separator, always import as float
                        value = d;
                    } else {
                        value = (long) d; // we could be losing a fractional piece here, but it's not visible in Excel
                    }
                } else {
                    // Anything except a pure number (e.g. telephone #, postal code, SSN, etc) gets imported as string
                    value = dataFormatter.formatCellValue(cell);
                }
            }
        } else {
            String text = cell.getStringCellValue();
            if (text.length() > 0) {
                value = text;
            }
        }

        return new Cell(value, null);
    }

    /**
     * Checks whether a cell format is a datetime format compatible with Refine.
     *
     * @return true for datetimes, false for times, dates, intervals, etc
     */
    private static boolean isDatetimeFormat(ExcelNumberFormat format) {
        if (isInternalDatetimeFormat(format.getIdx())) {
            return true;
        }

        String formatString = format.getFormat();
        // Excel supports dates, times, intervals (via format strings), but we only have a datetime type
        // all unsupported types (ie if it doesn't have both date & time components in the format string)
        // are rendered to text and imported as strings
        // TODO: The check below is crude and could probably be improved
        if (!formatString.contains("y") || !formatString.contains("d") || !formatString.toLowerCase().contains("h")
                || !formatString.contains("mm")) {
            return false;
        }

        return true;
    }

    /**
     * A more restrictive version of Apache POI's isInternalDateFormat which is restricted to just datetimes
     */
    private static boolean isInternalDatetimeFormat(int format) {
        switch (format) {
            case 0xe: // "m/d/yy" TODO: do we want to allow date-only formats? We currently do, continuing our tradition
            case 0xf: // "d-mmm-yy"
            case 0x16: // "m/d/yy h:mm"
                return true;
            default:
                return false;
        }
    }

    private static boolean isNumberFormat(ExcelNumberFormat format) {
        if (isInternalNumberFormat(format.getIdx())) {
            return true;
        }

        String formatString = format.getFormat();
        return NUMERIC_FORMAT.matcher(formatString).matches() || formatString.contains("0E+0");

    }

    private static boolean isInternalNumberFormat(int format) {
        return isInternalIntegerFormat(format) || isInternalFloatFormat(format);
    }

    private static boolean isInternalIntegerFormat(int format) {
        switch (format) {
            case 0x01: // "0"
            case 0x03: // "#,##0"
            case 0x05: // "$#,##0_);($#,##0)"
            case 0x06: // "$#,##0_);[Red]($#,##0)
            case 0x25: // "#,##0_);(#,##0)"
            case 0x26: // , "#,##0_);[Red](#,##0)"
            case 0x29: // "_(* #,##0_);_(* (#,##0);_(* \"-\"_);_(@_)"
            case 0x2a: // "_($* #,##0_);_($* (#,##0);_($* \"-\"_);_(@_)"
            case 0x30: // "##0.0E+0"
                return true;
            default:
                return false;
        }
    }

    private static boolean isInternalFloatFormat(int format) {
        switch (format) {
            case 0x02: // "0.00"
            case 0x04: // "#,##0.00"
            case 0x07: // "$#,##0.00);($#,##0.00)"
            case 0x08: // "$#,##0.00_);[Red]($#,##0.00)"
            case 0x09: // "0%"<br>
            case 0x0a: // "0.00%"<br>
            case 0x0b: // "0.00E+00"<br>
            case 0x27: // "#,##0.00_);(#,##0.00)"
            case 0x28: // "#,##0.00_);[Red](#,##0.00)"
            case 0x2b: // "_(* #,##0.00_);_(* (#,##0.00);_(* \"-\"??_);_(@_)"
            case 0x2c: // "_($* #,##0.00_);_($* (#,##0.00);_($* \"-\"??_);_(@_)"
                return true;
            default:
                return false;
        }
    }
}
