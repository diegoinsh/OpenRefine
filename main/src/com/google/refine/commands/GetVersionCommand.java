/*

Copyright 2010, Google Inc.
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

package com.google.refine.commands;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.google.refine.RefineServlet;

public class GetVersionCommand extends Command {

    protected class VersionResponse {

        @JsonProperty("version")
        public String version = RefineServlet.VERSION;
        @JsonProperty("revision")
        public String revision = RefineServlet.REVISION;
        @JsonProperty("full_version")
        public String full_version = RefineServlet.FULL_VERSION;
        @JsonProperty("full_name")
        public String full_name = RefineServlet.FULLNAME;
        @JsonProperty("java_vm_name")
        public String java_vm_name = System.getProperty("java.vm.name", "?");
        @JsonProperty("java_vm_version")
        public String java_vm_version = System.getProperty("java.vm.version", "?");
        @JsonProperty("java_runtime_name")
        public String java_runtime_name = System.getProperty("java.runtime.name", "?");
        @JsonProperty("java_runtime_version")
        public String java_runtime_version = Runtime.getRuntime().version().toString();
        @JsonProperty("display_new_version_notice")
        // 默认 "false"：本产品暂不提供在线版本升级，客户界面不应出现任何升级/订阅提示。
        // 原实现无默认值，未设属性时返回 null 依赖于前端判断；这里显式钉死，
        // 需要开启时才由外部显式设置 -Drefine.display.new.version.notice=true。
        public String display_new_version_notice = System.getProperty("refine.display.new.version.notice", "false");
        @JsonProperty("module_names")
        public String[] modules = servlet.getModuleNames();
    }

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        respondJSON(response, new VersionResponse());
    }
}
