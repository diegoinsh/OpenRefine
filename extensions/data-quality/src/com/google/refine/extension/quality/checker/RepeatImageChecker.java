/*
 * Data Quality Extension - Repeat Image Checker
 * Checks for duplicate images using file hashing
 * 
 * 长期优化方案TODO：
 * 1. 引入数据库存储哈希映射：使用SQLite或H2数据库存储文件哈希和路径，降低内存占用
 *    - 实现位置：在checkWithHashes方法中，从数据库读取哈希映射而非内存Map
 *    - 优势：支持亿级文件处理，内存占用极低
 *    - 参考：https://github.com/xerial/sqlite-jdbc
 * 
 * 2. 分布式处理：支持多机并行处理大规模图片检查
 *    - 实现位置：在check方法中，将文件夹列表分片到不同工作节点
 *    - 优势：线性扩展处理能力，缩短处理时间
 *    - 参考：使用消息队列（RabbitMQ/Kafka）或分布式任务调度
 * 
 * 3. 增量检查：只检查新增或修改的文件
 *    - 实现位置：在检查前，读取上次检查的哈希数据库，对比文件修改时间
 *    - 优势：大幅减少重复检查时间，提高效率
 *    - 参考：维护文件哈希索引表，记录文件路径、哈希、最后检查时间
 */
package com.google.refine.extension.quality.checker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.quality.model.FileInfo;
import com.google.refine.extension.quality.model.ImageCheckError;
import com.google.refine.extension.quality.model.ImageCheckItem;
import com.google.refine.extension.quality.model.ImageQualityRule;

public class RepeatImageChecker {

    private static final Logger logger = LoggerFactory.getLogger(RepeatImageChecker.class);

    public String getItemCode() {
        return "repeat_image";
    }
    public String getItemName() {
        return "重复图片审查";
    }

    public boolean isEnabled(ImageQualityRule rule) {
        ImageCheckItem item = rule.getItemByCode("repeat_image");
        return item != null && item.isEnabled();
    }

    public List<ImageCheckError> checkWithHashes(Map<String, List<FileInfo>> hashToFiles) {
        List<ImageCheckError> errors = new ArrayList<>();

        if (hashToFiles == null || hashToFiles.isEmpty()) {
            logger.info("重复图片审查 - 哈希映射为空，跳过检查");
            return errors;
        }

        int totalFiles = 0;
        for (List<FileInfo> files : hashToFiles.values()) {
            totalFiles += files.size();
        }

        logger.info("重复图片审查开始 - 使用预计算哈希值，总文件数: {}, 哈希组数: {}", totalFiles, hashToFiles.size());

        return findDuplicateImages(hashToFiles);
    }

    private List<ImageCheckError> findDuplicateImages(Map<String, List<FileInfo>> hashToFiles) {
        List<ImageCheckError> errors = new ArrayList<>();

        logger.info("开始查找重复图片，哈希组数: {}", hashToFiles.size());

        for (Map.Entry<String, List<FileInfo>> entry : hashToFiles.entrySet()) {
            List<FileInfo> files = entry.getValue();
            if (files.size() > 1) {
                logger.info("发现重复哈希组 - hash: {}, 文件数: {}", entry.getKey(), files.size());
                
                List<String> duplicateImagePaths = new ArrayList<>();
                StringBuilder messageBuilder = new StringBuilder();
                messageBuilder.append("发现重复图片 (").append(files.size()).append("张): ");
                
                for (int i = 0; i < files.size(); i++) {
                    FileInfo fileInfo = files.get(i);
                    String fullPath = fileInfo.getResourcePath() + fileInfo.getFileName();
                    duplicateImagePaths.add(fullPath);
                    
                    if (i > 0) {
                        messageBuilder.append("; ");
                    }
                    messageBuilder.append(truncatePath(fullPath, 50));
                }
                
                FileInfo firstFile = files.get(0);
                ImageCheckError error = ImageCheckError.createRepeatImageError(
                    -1,
                    "resource",
                    firstFile.getResourcePath(),
                    firstFile.getFileName(),
                    files.size() - 1,
                    duplicateImagePaths);
                error.setMessage(messageBuilder.toString());
                logger.info("[RepeatImageChecker] 创建ImageCheckError - duplicateImagePaths大小: {}, 内容: {}", 
                    duplicateImagePaths.size(), duplicateImagePaths);
                errors.add(error);
                
                logger.info("添加重复图片错误 - 主文件: {}, 重复数: {}", 
                    firstFile.getFileName(), files.size() - 1);
            }
        }

        logger.info("重复图片审查完成，发现 {} 组重复图片", errors.size());
        return errors;
    }

    private String truncatePath(String path, int maxLength) {
        if (path == null || path.length() <= maxLength) {
            return path;
        }
        return "..." + path.substring(path.length() - maxLength);
    }
}
