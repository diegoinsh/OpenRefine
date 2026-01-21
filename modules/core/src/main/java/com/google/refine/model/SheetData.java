/*

Copyright 2025, OpenRefine contributors
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

package com.google.refine.model;

import java.util.ArrayList;
import java.util.List;

public class SheetData {
    public String sheetId;
    public String sheetName;
    public String sourceFileName;
    public List<Row> rows;
    public ColumnModel columnModel;
    public RecordModel recordModel;
    public int activeRowIndex;
    
    public int ignoreLines;
    public int headerLines;
    public int skipDataLines;
    public int limit;
    public boolean storeBlankRows;
    public boolean storeBlankColumns;
    public boolean storeBlankCellsAsNulls;
    public boolean includeFileSources;
    public boolean includeArchiveFileName;
    public boolean forceText;
    
    public SheetData(String sheetId, String sheetName, String sourceFileName) {
        this.sheetId = sheetId;
        this.sheetName = sheetName;
        this.sourceFileName = sourceFileName;
        this.rows = new ArrayList<>();
        this.columnModel = new ColumnModel();
        this.recordModel = new RecordModel();
        this.activeRowIndex = 0;
        
        this.ignoreLines = -1;
        this.headerLines = 0;
        this.skipDataLines = 0;
        this.limit = -1;
        this.storeBlankRows = false;
        this.storeBlankColumns = false;
        this.storeBlankCellsAsNulls = false;
        this.includeFileSources = false;
        this.includeArchiveFileName = false;
        this.forceText = false;
    }
    
    public int getRowCount() {
        return rows.size();
    }
    
    public Row getRow(int index) {
        if (index >= 0 && index < rows.size()) {
            return rows.get(index);
        }
        return null;
    }
    
    public void dispose() {
        if (rows != null) {
            rows.clear();
        }
        if (columnModel != null) {
            columnModel.clearPrecomputes();
        }
    }
}
