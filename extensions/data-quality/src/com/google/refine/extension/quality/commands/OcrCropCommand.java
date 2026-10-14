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

            // 本层不再做空结果重试：此前按"向外补白"重试是为贴边文字设计的，但对印章是
            // 负向的——框越大，章在"短边抬到 736"的送检图里占比越小、绝对像素越少，det 行框
            // 更弱、rec 行图更小，实测同一枚章外扩 8% 常由十余字崩到 0~3 字。鲁棒性已下沉到
            // AIMP 侧：印章链路内多尺度重试，印章空结果回落通用 OCR。
            AimpClient.OcrCropResult ocr =
                    client.ocrCrop(FileImageRenderer.toPngBytes(crop), "crop_" + page + ".png", mode);

            if (!ocr.isSuccess()) {
                result.put("status", "error");
                result.put("code", "ocr-failed");
                result.put("message", ocr.getError());
                respondJSON(response, result);
                return;
            }

            result.put("status", "ok");
            result.put("text", normalizeOcrText(ocr.getText()));
            result.put("category", ocr.getCategory());
            result.put("confidence", ocr.getConfidence());
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
     * OCR 结果规范化为一行：回车换行连同两侧空白换成单个空格，避免写回单元格时带回多行内容，
     * 同时保留各文本段之间的间隔。分格章面（归档方章等）由 OCR 按格返回多个文本块，例如
     * '33\n2019\n218\n30\n12'，若直接粘连成 '3320192183012' 会丢掉分格信息、无法人工核对。
     */
    static String normalizeOcrText(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("\\s*[\\r\\n]+\\s*", " ").trim();
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
