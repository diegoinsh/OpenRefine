/*
 * JinRefine 容器改造 · .vt 模板渲染
 *
 * 复刻 core controller.js 的 templatedFiles 逻辑：
 * params / projectID / styleInjection / scriptInjection / encodingJson。
 * 用 Velocity 直接 evaluate，避免依赖 Butterfly 的模板引擎封装。
 */

package com.jiongche.refine.web;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.jiongche.refine.kernel.RefineModule;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;

import com.google.refine.ClientSideResourceManager;
import com.google.refine.ClientSideResourceManager.QualifiedPath;
import com.google.refine.util.ParsingUtilities;

public final class TemplateService {

    private static final VelocityEngine ENGINE = createEngine();

    private TemplateService() {
    }

    private static VelocityEngine createEngine() {
        VelocityEngine engine = new VelocityEngine();
        try {
            engine.init();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
        return engine;
    }

    public static boolean isTemplated(String lastSegment) {
        return "index".equals(lastSegment) || "project".equals(lastSegment) || "preferences".equals(lastSegment);
    }

    public static boolean render(RefineModule module, String templateName, String requestPath,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        URL template = module.getResource(templateName + ".vt");
        if (template == null) {
            return false;
        }

        VelocityContext context = new VelocityContext();
        context.put("params", buildParams(request));
        context.put("projectID", digits(request.getParameter("project")));
        context.put("scriptInjection",
                "<script type=\"text/javascript\" src=\"" + requestPath + "-bundle.js\"></script>");
        context.put("styleInjection", buildStyleInjection(templateName));
        if ("index".equals(templateName) || "project".equals(templateName)) {
            context.put("encodingJson", buildEncodingJson());
        }

        response.setContentType("text/html");
        response.setCharacterEncoding("UTF-8");
        PrintWriter writer = response.getWriter();
        try (InputStreamReader reader = new InputStreamReader(template.openStream(), StandardCharsets.UTF_8)) {
            ENGINE.evaluate(context, writer, templateName + ".vt", reader);
        }
        return true;
    }

    public static boolean render(RefineModule module, String templateName, VelocityContext context,
            HttpServletResponse response) throws IOException {
        URL template = module.getResource(templateName);
        if (template == null) {
            return false;
        }
        response.setContentType("text/html");
        response.setCharacterEncoding("UTF-8");
        PrintWriter writer = response.getWriter();
        try (InputStreamReader reader = new InputStreamReader(template.openStream(), StandardCharsets.UTF_8)) {
            ENGINE.evaluate(context, writer, templateName, reader);
        }
        return true;
    }

    private static Properties buildParams(HttpServletRequest request) {
        Properties params = new Properties();
        java.util.Enumeration<String> names = request.getParameterNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            params.put(name, request.getParameterValues(name)[0]);
        }
        return params;
    }

    private static String digits(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isDigit(c)) {
                result.append(c);
            }
        }
        return result.toString();
    }

    private static String buildStyleInjection(String templateName) {
        QualifiedPath[] styles = ClientSideResourceManager.getPaths(templateName + "/styles");
        List<String> injections = new ArrayList<>(styles.length);
        for (QualifiedPath style : styles) {
            String path = style.fullPath;
            String withoutSlash = path.startsWith("/") ? path.substring(1) : path;
            String source;
            if (withoutSlash.startsWith("extension/")) {
                String[] parts = path.split("/");
                source = parts.length > 2 ? parts[2] : "core";
            } else {
                source = "core";
            }
            injections.add("@import url(" + withoutSlash + ") layer(" + source + ");");
        }
        return "<style>@layer core;\n" + String.join("\n", injections) + "</style>";
    }

    private static String buildEncodingJson() {
        List<Map<String, Object>> encodings = new ArrayList<>();
        for (Map.Entry<String, Charset> entry : Charset.availableCharsets().entrySet()) {
            Charset charset = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", entry.getKey());
            item.put("name", charset.displayName());
            item.put("aliases", new ArrayList<>(charset.aliases()));
            encodings.add(item);
        }
        try {
            return ParsingUtilities.mapper.writeValueAsString(encodings);
        } catch (Exception e) {
            return "[]";
        }
    }
}
