/*

Copyright 2024, OpenRefine developers
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that following conditions are
met:

    * Redistributions of source code must retain above copyright
notice, this list of conditions and following disclaimer.
    * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and following disclaimer
in documentation and/or other materials provided with
distribution.
    * Neither name of Google Inc. nor the names of its
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

package com.google.refine.commands.project;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.refine.commands.Command;
import com.google.refine.model.Project;
import com.google.refine.model.SheetData;

public class SwitchSheetCommand extends Command {

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        
        Project project = getProject(request);
        
        String sheetId = request.getParameter("sheetId");
        
        if (sheetId == null || sheetId.isEmpty()) {
            respondException(response, new IllegalArgumentException("sheetId parameter is required"));
            return;
        }
        
        if (project.sheetDataMap == null || !project.sheetDataMap.containsKey(sheetId)) {
            respondException(response, new IllegalArgumentException("Sheet not found: " + sheetId));
            return;
        }
        
        project.setActiveSheet(sheetId);
        
        SheetData sheetData = project.sheetDataMap.get(sheetId);
        
        Map<String, Object> result = new HashMap<>();
        result.put("sheetId", sheetData.sheetId);
        result.put("sheetName", sheetData.sheetName);
        result.put("rowCount", sheetData.getRowCount());
        
        respondJSON(response, result);
    }
}
