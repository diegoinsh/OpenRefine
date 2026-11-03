package com.google.refine.commands.workspace;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;

import com.google.refine.commands.Command;

/**
 * 运行期设置日志级别（平台「系统配置 → 日志」页保存后内部下发，无需重启引擎）。
 *
 * <p>只接受 ERROR / WARN / INFO / DEBUG 四档，同时作用于根记录器与 {@code refine}
 * 记录器（业务日志主要走后者）。级别只影响日志过滤，不改变任何业务行为，
 * 因此不做额外授权区分（与 LicenseGuard 的模块门禁无关）。
 */
public class SetLogLevelCommand extends Command {

    private static final Map<String, Level> LEVELS = Map.of(
            "ERROR", Level.ERROR,
            "WARN", Level.WARN,
            "INFO", Level.INFO,
            "DEBUG", Level.DEBUG);

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String raw = request.getParameter("level");
        Level level = raw == null ? null : LEVELS.get(raw.trim().toUpperCase(Locale.ROOT));
        if (level == null) {
            respondJSON(response, Map.of("ok", false, "message", "level 必须是 ERROR/WARN/INFO/DEBUG"));
            return;
        }
        Configurator.setRootLevel(level);
        Configurator.setLevel("refine", level);
        respondJSON(response, Map.of("ok", true, "level", level.name()));
    }
}
