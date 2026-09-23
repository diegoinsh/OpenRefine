/*
 * Data Quality Extension - OCR Crop Command
 *
 * Takes a rectangle (in source image pixel coordinates) of the file currently
 * previewed in the file view panel, crops it and sends it to the AIMP service
 * for layout classification followed by OCR recognition.
 */

package com.google.refine.extension.quality.commands;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.commands.Command;
import com.google.refine.extension.quality.aimp.AimpClient;
import com.google.refine.extension.quality.util.FileImageRenderer;
import com.google.refine.util.ParsingUtilities;

public class OcrCropCommand extends Command {

    private static final Logger logger = LoggerFactory.getLogger(OcrCropCommand.class);

    /** 空结果重试时每边的补白比例（相对裁剪框长边），首项 0 表示先用原框识别一次 */
    private static final double[] RETRY_PAD_RATIOS = { 0, 0.08, 0.20 };

    /** 补白最小像素数，避免小框补白过少不起作用 */
    private static final int MIN_RETRY_PAD = 4;

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        if (!hasValidCSRFToken(request)) {
            respondCSRFError(response);
            return;
        }

        response.setCharacterEncoding("UTF-8");

        ObjectNode result = ParsingUtilities.mapper.createObjectNode();

        try {
            String root = request.getParameter("root");
            String path = request.getParameter("path");
            int page = parseInt(request.getParameter("page"), 1);
            int x = parseInt(request.getParameter("x"), 0);
            int y = parseInt(request.getParameter("y"), 0);
            int width = parseInt(request.getParameter("width"), 0);
            int height = parseInt(request.getParameter("height"), 0);
            String mode = request.getParameter("mode");

            File file = resolveFile(root, path);

            BufferedImage source = FileImageRenderer.render(file, page);
            if (width <= 0 || height <= 0) {
                width = source.getWidth() - x;
                height = source.getHeight() - y;
            }
            BufferedImage crop = FileImageRenderer.crop(source, x, y, width, height);

            String serviceUrl = CheckAimpConnectionCommand.getConfiguredServiceUrl();
            if (serviceUrl == null || serviceUrl.isEmpty()) {
                result.put("status", "error");
                result.put("code", "aimp-unavailable");
                respondJSON(response, result);
                return;
            }

            AimpClient client = new AimpClient(serviceUrl);

            // 空结果重试：OCR 检测模型对文字是否贴边很敏感，拉框稍紧时贴边文字会被当作
            // 边界裁掉而识别不出（框只差几像素结果就不同）。识别为空时向外补白重试，
            // 让文字四周重新获得留白，共最多 3 次尝试（原框 + 2 档补白）。
            AimpClient.OcrCropResult ocr = null;
            String text = "";
            int attempts = 0;
            for (int i = 0; i < RETRY_PAD_RATIOS.length; i++) {
                attempts = i + 1;
                BufferedImage candidate = crop;
                if (i > 0) {
                    int pad = (int) Math.round(Math.max(
                            MIN_RETRY_PAD,
                            Math.max(crop.getWidth(), crop.getHeight()) * RETRY_PAD_RATIOS[i]));
                    candidate = FileImageRenderer.pad(crop, pad);
                }
                ocr = client.ocrCrop(FileImageRenderer.toPngBytes(candidate), "crop_" + page + ".png", mode);
                if (!ocr.isSuccess()) {
                    result.put("status", "error");
                    result.put("code", "ocr-failed");
                    result.put("message", ocr.getError());
                    respondJSON(response, result);
                    return;
                }
                text = normalizeOcrText(ocr.getText());
                if (!text.isEmpty()) {
                    break;
                }
                if (i < RETRY_PAD_RATIOS.length - 1) {
                    logger.info("OCR 第 {} 次识别为空（框 {}x{} @ {},{}），补白后重试",
                            attempts, crop.getWidth(), crop.getHeight(), x, y);
                }
            }

            result.put("status", "ok");
            result.put("text", text);
            result.put("category", ocr.getCategory());
            result.put("confidence", ocr.getConfidence());
            result.put("attempts", attempts);
            result.put("imageWidth", source.getWidth());
            result.put("imageHeight", source.getHeight());
            respondJSON(response, result);

        } catch (IOException e) {
            logger.warn("OCR crop failed: {}", e.getMessage());
            result.put("status", "error");
            result.put("code", "file-not-found");
            result.put("message", e.getMessage());
            respondJSON(response, result);
        } catch (Exception e) {
            logger.error("OCR crop failed", e);
            result.put("status", "error");
            result.put("code", "ocr-failed");
            result.put("message", e.getMessage());
            respondJSON(response, result);
        }
    }

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        doPost(request, response);
    }

    /**
     * OCR 结果规范化为一行：去掉回车换行，换行前后的空白一并去掉再拼接，
     * 避免写回单元格时带回多行内容。
     */
    static String normalizeOcrText(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("\\s*[\\r\\n]+\\s*", "").trim();
    }

    static int parseInt(String raw, int defaultValue) {
        if (raw == null || raw.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 将 root + path 解析为一个真实文件，并确保其落在 root 目录之内。
     */
    static File resolveFile(String root, String path) throws IOException {
        if (root == null || root.isEmpty()) {
            throw new IOException("missing root parameter");
        }
        File rootDir = new File(root);
        if (!rootDir.isDirectory()) {
            throw new IOException("root is not a directory: " + root);
        }
        File target = (path == null || path.isEmpty()) ? rootDir : new File(rootDir, path);

        String rootCanonical = rootDir.getCanonicalPath();
        String targetCanonical = target.getCanonicalPath();
        if (!targetCanonical.equals(rootCanonical)
                && !targetCanonical.startsWith(rootCanonical + File.separator)) {
            throw new IOException("path is outside of the resource root");
        }
        if (!target.isFile()) {
            throw new IOException("file does not exist: " + targetCanonical);
        }
        return target;
    }
}
