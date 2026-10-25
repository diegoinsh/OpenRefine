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
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from this
 *    software without specific prior written permission.
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

package com.google.refine.commands.row;

import java.io.IOException;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.refine.browsing.Engine;
import com.google.refine.browsing.EngineConfig;
import com.google.refine.commands.EngineDependentCommand;
import com.google.refine.model.AbstractOperation;
import com.google.refine.model.Project;
import com.google.refine.operations.row.SplitMarkedRowsOperation;

/**
 * 命令入口“split-marked-rows”：拆分当前视图中被标记（星标/旗标）的行。
 *
 * <p>
 * 传入 {@code dryRun=true} 时仅返回当前视图中被标记行数 {@code {"count": n}}，不产生历史记录，
 * 供前端确认框使用；否则按标准流程将 {@link SplitMarkedRowsOperation} 入队执行并写入历史。
 */
public class SplitMarkedRowsCommand extends EngineDependentCommand {

    @Override
    protected AbstractOperation createOperation(
            Project project, HttpServletRequest request, EngineConfig engineConfig) throws Exception {
        return new SplitMarkedRowsOperation(engineConfig);
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        if ("true".equals(request.getParameter("dryRun"))) {
            if (!hasValidCSRFToken(request)) {
                respondCSRFError(response);
                return;
            }
            try {
                Project project = getProject(request);
                Engine engine = new Engine(project);
                engine.initializeFromConfig(getEngineConfig(request));
                int count = SplitMarkedRowsOperation.countMarkedRows(project, engine);
                respondJSON(response, Map.of("count", count));
            } catch (Exception e) {
                respondException(response, e);
            }
            return;
        }

        super.doPost(request, response);
    }
}