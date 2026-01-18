package com.google.refine.extension.quality.checker;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.quality.model.ImageCheckError;
import com.google.refine.extension.quality.model.ImageCheckItem;
import com.google.refine.extension.quality.model.ImageQualityRule;
import com.google.refine.extension.quality.util.AntivirusDetector;
import com.google.refine.model.Project;
import com.google.refine.model.Row;

public class AntivirusChecker implements ImageChecker {

    private static final Logger logger = LoggerFactory.getLogger(AntivirusChecker.class);

    @Override
    public String getItemCode() {
        return "antivirus_check";
    }

    @Override
    public String getItemName() {
        return "杀毒软件检测";
    }

    @Override
    public boolean isEnabled(ImageQualityRule rule) {
        ImageCheckItem item = rule.getItemByCode("antivirusCheck");
        return item != null && item.isEnabled();
    }

    @Override
    public List<ImageCheckError> check(Project project, List<Row> rows, ImageQualityRule rule) {
        logger.info("=== 开始杀毒软件检测 ===");
        List<ImageCheckError> errors = new ArrayList<>();

        ImageCheckItem item = rule.getItemByCode("antivirusCheck");
        if (item == null || !isEnabled(rule)) {
            logger.info("杀毒软件检测未启用: item={}, enabled={}", item, item != null ? item.isEnabled() : false);
            return errors;
        }

        logger.info("杀毒软件检测已启用");

        String checkMode = (String) item.getParameter("checkMode", String.class);
        String processName = (String) item.getParameter("processName", String.class);

        if ("auto".equals(checkMode)) {
            logger.info("杀毒软件检测模式: 自动");
            List<AntivirusDetector.AntivirusInfo> antivirusList = AntivirusDetector.detectAntivirus();
            if (antivirusList.isEmpty()) {
                logger.warn("未检测到杀毒软件，安全性检测不通过");
                errors.add(ImageCheckError.createSecurityError(
                    -1,
                    "",
                    "",
                    "杀毒软件检测",
                    "未检测到杀毒软件，请安装并启用杀毒软件以保障档案安全"));
            } else {
                boolean allEnabled = true;
                StringBuilder statusInfo = new StringBuilder();
                String antivirusName = "";
                
                for (AntivirusDetector.AntivirusInfo info : antivirusList) {
                    logger.info("检测到杀毒软件: {}", info);
                    statusInfo.append(info.toString()).append("\n");
                    if (antivirusName.isEmpty()) {
                        antivirusName = info.name;
                    }
                    
                    if (info.status.contains("已禁用")) {
                        allEnabled = false;
                        logger.warn("杀毒软件已禁用: {}", info.name);
                    }
                }
                
                if (!allEnabled) {
                    logger.warn("部分或全部杀毒软件已禁用，安全性检测不通过");
                    errors.add(ImageCheckError.createSecurityError(
                        -1,
                        "(空)",
                        antivirusName,
                        "杀毒软件检测",
                        "检测到杀毒软件但处于禁用状态，请启用杀毒软件以保障档案安全\n" + statusInfo.toString()));
                } else {
                    logger.info("所有杀毒软件已启用: {}", AntivirusDetector.getAntivirusStatus());
                }
            }
        } else if ("custom".equals(checkMode) && processName != null && !processName.trim().isEmpty()) {
            logger.info("杀毒软件检测模式: 自定义，进程名称: {}", processName);
            boolean processRunning = AntivirusDetector.isProcessRunning(processName);
            if (!processRunning) {
                logger.warn("指定的杀毒软件进程未运行: {}", processName);
                errors.add(ImageCheckError.createSecurityError(
                    -1,
                    "",
                    processName,
                    "杀毒软件检测",
                    "指定的杀毒软件进程 " + processName + " 未运行，请检查杀毒软件是否已启动"));
            } else {
                logger.info("指定的杀毒软件进程正在运行: {}", processName);
            }
        } else {
            logger.info("未启用杀毒软件检测");
        }

        logger.info("杀毒软件检测完成，发现 {} 个错误", errors.size());
        logger.info("=== 杀毒软件检测结束 ===");
        return errors;
    }
}
