package org.openrefine.extensions.files.importer;

import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.commands.Command;

/**
 * 数据抽取控制台「高级设置」读写接口。
 *
 * GET  command/files/extraction-config  读取（与默认值合并后返回）
 * POST command/files/extraction-config  保存（表单参数 config = JSON 字符串）
 */
public class ExtractionConfigCommand extends Command {

    private static final Logger logger = LoggerFactory.getLogger(ExtractionConfigCommand.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, java.io.IOException {
        try {
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Content-Type", "application/json");
            String json = mapper.writeValueAsString(ExtractionConfigStore.load());
            response.getWriter().write("{\"code\":\"ok\",\"config\":" + json + "}");
        } catch (Exception e) {
            logger.error("读取抽取配置失败", e);
            respondException(response, e);
        }
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, java.io.IOException {
        try {
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Content-Type", "application/json");

            String configJson = request.getParameter("config");
            if (configJson == null || configJson.trim().isEmpty()) {
                response.getWriter().write("{\"code\":\"error\",\"message\":\"缺少 config 参数\"}");
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> config = mapper.readValue(configJson, Map.class);
            ExtractionConfigStore.save(config);
            response.getWriter().write("{\"code\":\"ok\"}");
        } catch (Exception e) {
            logger.error("保存抽取配置失败", e);
            respondException(response, e);
        }
    }
}
