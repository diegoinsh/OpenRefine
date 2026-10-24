/*

Copyright 2024, Open Refine.
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
    * Neither the name of Open Refine nor the names of its
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

package com.google.refine.licensing;

import java.util.List;
import java.util.Map;

import com.jiongche.sdk.authz.LicenseCapabilities;

/**
 * JinRefine 命令（{@code module/command-name}）→ 加密狗授权模块 的映射（纵深防御）。
 *
 * <p>映射本体已下沉 SDK {@link LicenseCapabilities}（与下沉能力引擎共用同一份），
 * 本类只保留 OR 侧既有入口与文档。能力平台已按模块级授权拦住直连请求；OR 侧再做一层：
 * 按**命令**校验加密狗的授权模块，堵住绕过平台直连 OR 端口、白用未授权 AIMP 能力
 * （质检 / OCR / 信息抽取）。模块名与能力平台过滤器、AIMP {@code src/licensing/modules.py} 同口径。
 *
 * <p>只列「会实际调用 AIMP 能力」的命令；其余（配置、规则、结果读写、任务控制，
 * 以及探活命令 {@code check-aimp-connection} / {@code test-aimp-connection}）不设门禁，
 * 未映射的命令一律放行。
 *
 * <table>
 * <caption>命令 → 模块</caption>
 * <tr><td>{@code data-quality/run-image-quality-check}</td><td>inspection（/blank、/stain、/edge 等）</td></tr>
 * <tr><td>{@code data-quality/run-quality-check}</td><td>info_extraction + aimp-chat（批量比对 + LLM 分析）</td></tr>
 * <tr><td>{@code data-quality/ocr-crop}</td><td>ocr（/ocr）</td></tr>
 * <tr><td>{@code files/batch-extraction}</td><td>info_extraction + aimp-chat（/extract/upload + /api/llm/analyze）</td></tr>
 * </table>
 */
public final class LicenseModuleMap {

    /** 命令键 → 所需模块（**全部**须在狗授权集内）。 */
    public static final Map<String, List<String>> DEFAULT = LicenseCapabilities.all();

    private LicenseModuleMap() {
    }

    /**
     * 该命令所需模块；未映射或该命令免授权时返回**空列表**。
     *
     * @param commandKey {@code module/command-name}（如 {@code data-quality/ocr-crop}）
     */
    public static List<String> modulesFor(String commandKey) {
        return LicenseCapabilities.modulesFor(commandKey);
    }
}
