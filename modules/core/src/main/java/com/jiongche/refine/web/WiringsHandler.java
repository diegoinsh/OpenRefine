/*
 * JinRefine 容器改造 · wirings.js 输出
 *
 * 等价于 Butterfly.js 的 Butterfly.prototype.getWirings：
 * 输出 { moduleName: mountPath }，前端以 ModuleWirings[module] 消费。
 */

package com.jiongche.refine.web;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import com.jiongche.refine.kernel.ModuleRegistry;

import com.google.refine.util.ParsingUtilities;

public final class WiringsHandler {

    private WiringsHandler() {
    }

    public static void write(ModuleRegistry registry, HttpServletResponse response) throws IOException {
        Map<String, String> mounts = registry.mounts();
        response.setContentType("text/javascript");
        response.setCharacterEncoding("UTF-8");
        PrintWriter writer = response.getWriter();
        writer.write("var ModuleWirings = ");
        writer.write(ParsingUtilities.mapper.writeValueAsString(mounts));
        writer.write(";");
    }
}
