/*
 * Data Quality Extension - Render File Page Command
 *
 * Returns a raster image of a file page so that the client can draw a crop
 * rectangle on top of it. Images are returned as-is, PDF pages are rendered
 * with PDFBox.
 */

package com.google.refine.extension.quality.commands;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.commands.Command;
import com.google.refine.extension.quality.util.FileImageRenderer;
import com.google.refine.util.ParsingUtilities;

public class RenderFilePageCommand extends Command {

    private static final Logger logger = LoggerFactory.getLogger(RenderFilePageCommand.class);

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setCharacterEncoding("UTF-8");

        ObjectNode result = ParsingUtilities.mapper.createObjectNode();

        try {
            String root = request.getParameter("root");
            String path = request.getParameter("path");
            int page = OcrCropCommand.parseInt(request.getParameter("page"), 1);

            File file = OcrCropCommand.resolveFile(root, path);

            // PDF 只加载解析一次，同时拿到总页数并渲染目标页
            boolean isPdf = FileImageRenderer.isPdfFile(file.getName());
            int pageCount = 1;
            int renderedPage = Math.max(1, page);
            BufferedImage image;
            if (isPdf) {
                try (PDDocument document = PDDocument.load(file)) {
                    pageCount = document.getNumberOfPages();
                    renderedPage = Math.max(1, Math.min(page, Math.max(1, pageCount)));
                    image = FileImageRenderer.renderPdfPage(document, renderedPage);
                }
            } else {
                image = FileImageRenderer.readImage(file);
            }

            result.put("status", "ok");
            result.put("previewType", "image");
            // 源文件类型：前端据此判断「页」的含义（PDF 为文件内页号，图片为目录内文件序号）
            result.put("sourceType", isPdf ? "pdf" : "image");
            result.put("preview", FileImageRenderer.toJpegDataUrl(image));
            result.put("width", image.getWidth());
            result.put("height", image.getHeight());
            result.put("page", renderedPage);
            result.put("pageCount", pageCount);
            respondJSON(response, result);

        } catch (IOException e) {
            logger.warn("Render file page failed: {}", e.getMessage());
            result.put("status", "error");
            result.put("code", "file-not-found");
            result.put("message", e.getMessage());
            respondJSON(response, result);
        } catch (Exception e) {
            logger.error("Render file page failed", e);
            result.put("status", "error");
            result.put("code", "render-failed");
            result.put("message", e.getMessage());
            respondJSON(response, result);
        }
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        doGet(request, response);
    }
}
