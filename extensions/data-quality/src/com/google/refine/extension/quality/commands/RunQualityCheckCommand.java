/*
 * Data Quality Extension - Run Quality Check Command
 * Supports async execution with progress tracking
 */
package com.google.refine.extension.quality.commands;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jiongche.refine.data.OrViews;
import com.jiongche.sdk.data.TableView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.ProjectManager;
import com.google.refine.commands.Command;
import com.google.refine.extension.quality.checker.ContentChecker;
import com.google.refine.extension.quality.checker.DocumentElementChecker;
import com.google.refine.extension.quality.checker.FormatChecker;
import com.google.refine.extension.quality.checker.ImageQualityChecker;
import com.google.refine.extension.quality.checker.ResourceChecker;
import com.google.refine.extension.quality.checker.TitleNormChecker;
import com.google.refine.extension.quality.checker.TypoChecker;
import com.google.refine.extension.quality.model.CheckResult;
import com.google.refine.extension.quality.model.NatureMapping;
import com.google.refine.extension.quality.model.NatureType;
import com.google.refine.extension.quality.model.OverlayQualityCheckResult;
import com.google.refine.extension.quality.model.QualityRulesConfig;
import com.google.refine.extension.quality.operations.SaveQualityResultOperation;
import com.google.refine.extension.quality.operations.SaveQualityRulesOperation;
import com.google.refine.extension.quality.task.QualityCheckTask;
import com.google.refine.extension.quality.task.QualityCheckTask.TaskStatus;
import com.google.refine.extension.quality.task.TaskPersistence;
import com.google.refine.model.Project;

/**
 * Command to run quality check on project data.
 * Supports async execution with progress tracking.
 */
public class RunQualityCheckCommand extends Command {

    private static final Logger logger = LoggerFactory.getLogger(RunQualityCheckCommand.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        if (!hasValidCSRFToken(request)) {
            respondCSRFError(response);
            return;
        }

        try {
            Project project = getProject(request);
            if (project == null) {
                respondJSON(response, createErrorResponse("error-project-not-found", "Project not found"));
                return;
            }
            // 判定输入固定取「条目」表：双表项目的活动表若停在目录层级表，
            // 后续检查会按目录表取列（没有题名 → 文书级检查静默跳过、documents 为空）
            activateEntrySheet(project);

            // Check if async mode is requested
            String asyncParam = request.getParameter("async");
            boolean async = "true".equals(asyncParam);

            // Get rules from project overlay
            QualityRulesConfig rules = (QualityRulesConfig) project.overlayModels.get(
                    SaveQualityRulesOperation.OVERLAY_MODEL_KEY);

            if (rules == null) {
                respondJSON(response, createErrorResponse("error-no-rules-configured", "No quality rules configured"));
                return;
            }

            // Debug: log rules info
            logger.info("Rules loaded - formatRules: " + (rules.getFormatRules() != null ? rules.getFormatRules().size() : 0) +
                       ", contentRules: " + (rules.getContentRules() != null ? rules.getContentRules().size() : 0) +
                       ", aimpConfig: " + (rules.getAimpConfig() != null ? rules.getAimpConfig().getServiceUrl() : "null"));

            try {
                // Log all image quality check parameters
                logger.info("=== Image Quality Check Parameters ===");
                com.google.refine.extension.quality.model.ImageQualityRule imageQualityRule = rules.getImageQualityRule();
                if (imageQualityRule != null) {
                    logger.info("ImageQualityRule - id: " + imageQualityRule.getId() + 
                               ", name: " + imageQualityRule.getName() +
                               ", enabled: " + imageQualityRule.isEnabled() +
                               ", standard: " + imageQualityRule.getStandard());
                    
                    if (imageQualityRule.getCategories() != null) {
                        for (com.google.refine.extension.quality.model.ImageCheckCategory category : imageQualityRule.getCategories()) {
                            logger.info("  Category - code: " + category.getCategoryCode() + 
                                       ", name: " + category.getCategoryName() +
                                       ", enabled: " + category.isEnabled());
                            
                            if (category.getItems() != null) {
                                for (com.google.refine.extension.quality.model.ImageCheckItem item : category.getItems()) {
                                    logger.info("    Item - code: " + item.getItemCode() + 
                                               ", name: " + item.getItemName() +
                                               ", enabled: " + item.isEnabled());
                                    
                                    if (item.getParameters() != null && !item.getParameters().isEmpty()) {
                                        StringBuilder paramBuilder = new StringBuilder("    Parameters: ");
                                        for (Map.Entry<String, Object> entry : item.getParameters().entrySet()) {
                                            paramBuilder.append(entry.getKey()).append("=").append(entry.getValue()).append(", ");
                                        }
                                        logger.info(paramBuilder.toString());
                                    } else {
                                        logger.info("    Parameters: (none)");
                                    }
                                }
                            }
                        }
                    }
                } else {
                    logger.info("ImageQualityRule: null");
                }
                logger.info("=== End Image Quality Check Parameters ===");
            } catch (Exception e) {
                logger.warn("记录图像质量检查参数时发生异常", e);
                return;
            }
            
            // Get AIMP URL - priority: request param > global config > project rules config
            String aimpServiceUrl = request.getParameter("aimpServiceUrl");
            logger.info("aimpServiceUrl from request param: " + aimpServiceUrl);
            if (aimpServiceUrl == null || aimpServiceUrl.isEmpty()) {
                // Try global config first
                aimpServiceUrl = CheckAimpConnectionCommand.getConfiguredServiceUrl();
                logger.info("aimpServiceUrl from global config: " + aimpServiceUrl);
                
                // Fallback to project rules config
                if ((aimpServiceUrl == null || aimpServiceUrl.isEmpty()) && rules.getAimpConfig() != null) {
                    aimpServiceUrl = rules.getAimpConfig().getServiceUrl();
                    logger.info("aimpServiceUrl from rules config: " + aimpServiceUrl);
                }
            }

            if (async) {
                // Async mode: start task and return task ID
                String taskId = QualityCheckTask.generateTaskId(project.id);
                QualityCheckTask task = new QualityCheckTask(taskId, project.id);
                task.setTotalRows(project.rows.size());

                final String finalAimpUrl = aimpServiceUrl;
                executor.submit(() -> runCheckAsync(project, rules, finalAimpUrl, task));

                ObjectNode responseNode = mapper.createObjectNode();
                responseNode.put("code", "ok");
                responseNode.put("async", true);
                responseNode.put("taskId", taskId);
                respondJSON(response, responseNode);
            } else {
                // Sync mode: run and wait for result
                ObjectNode result = runCheckSync(project, rules, aimpServiceUrl);
                respondJSON(response, result);
            }

        } catch (Exception e) {
            logger.error("Error running quality check", e);
            respondJSON(response, createErrorResponse(e.getMessage()));
        }
    }

    /**
     * Run quality check synchronously
     */
    private ObjectNode runCheckSync(Project project, QualityRulesConfig rules, String aimpServiceUrl) {
        int totalRows = project.rows.size();
        long checkStartTime = System.currentTimeMillis();

        // 经 SDK 契约（缺省活视图，逐值等价）驱动下沉内核
        TableView view = OrViews.viewFor(project);

        // 「仅目录」项目没有条目数据：条目类检查（含条目-图像关联项）无输入，静默跳过
        boolean dirOnly = isDirectoryOnly(project);

        // 法院定制模板：内容已是人工校对后回传的终稿，再抽取比对必然错位，跳过内容比对
        boolean courtProfile = isCourtProfile(rules);

        // Run format checks
        CheckResult formatResult = dirOnly ? skipped("format") : new FormatChecker(view, rules).runCheck();

        // Run resource checks
        CheckResult resourceResult = dirOnly ? skipped("resource") : new ResourceChecker(view, rules).runCheck();

        // Run content checks
        logger.info("Content check AIMP service URL: " + aimpServiceUrl);
        CheckResult contentResult = new CheckResult("content");
        if (!dirOnly && !courtProfile && aimpServiceUrl != null && !aimpServiceUrl.isEmpty()) {
            logger.info("Running content check with AIMP service: " + aimpServiceUrl);
            ContentChecker contentChecker = new ContentChecker(view, rules, aimpServiceUrl);
            contentResult = contentChecker.runCheck();
            logger.info("Content check completed, errors: " + contentResult.getErrors().size());
        } else {
            logger.info("Skipping content check - no AIMP service URL configured");
            contentResult.complete();
        }

        // Run typo checks
        CheckResult typoResult = new CheckResult("typo");
        if (!dirOnly && aimpServiceUrl != null && !aimpServiceUrl.isEmpty()) {
            logger.info("Running typo check with AIMP service: " + aimpServiceUrl);
            int formatBatchSize = 20;
            Map<String, Object> globalConfig = GetConfigCommand.loadConfig();
            if (globalConfig != null && globalConfig.containsKey("format.batchSize")) {
                Object bsObj = globalConfig.get("format.batchSize");
                if (bsObj instanceof Number) {
                    formatBatchSize = ((Number) bsObj).intValue();
                }
            }
            TypoChecker typoChecker = new TypoChecker(view, rules, aimpServiceUrl, formatBatchSize);
            typoResult = typoChecker.runCheck();
            logger.info("Typo check completed, errors: " + typoResult.getErrors().size());
        } else {
            logger.info("Skipping typo check - no AIMP service URL configured");
            typoResult.complete();
        }

        // Run image quality checks
        logger.info("Image quality check AIMP service URL: " + aimpServiceUrl);
        CheckResult imageQualityResult = new CheckResult("image_quality");
        if (aimpServiceUrl != null && !aimpServiceUrl.isEmpty()) {
            logger.info("Running image quality check with AIMP service: " + aimpServiceUrl);
            ImageQualityChecker imageChecker = new ImageQualityChecker(view, rules, aimpServiceUrl);
            imageQualityResult = imageChecker.runCheck();
            logger.info("Image quality check completed, errors: " + imageQualityResult.getErrors().size());
        } else {
            logger.info("Skipping image quality check - no AIMP service URL configured");
            imageQualityResult.complete();
        }

        long checkEndTime = System.currentTimeMillis();
        formatResult.setStartTime(checkStartTime);
        formatResult.setEndTime(checkEndTime);

        // [移交校审·待删] 2026-11-13 案件质量评查（裁判文书必备要素核对）整体移交数据校审。
        // 合规侧不再执行，原实现原样保留供校审对照搬运：
        //   按 courtProfile 用 DocumentElementChecker 读 documentQualityRule 的文书类型与必备要素，
        //   逐件核对「该文书是否缺案号 / 日期 / 责任者等必备要素」，题名按最长模式优先匹配。
        // CheckResult documentQualityResult = new CheckResult(DocumentElementChecker.CATEGORY);
        // if (!dirOnly && courtProfile) {
        //     logger.info("Running document element check");
        //     DocumentElementChecker documentChecker = new DocumentElementChecker(view, rules);
        //     documentQualityResult = documentChecker.runCheck();
        //     logger.info("Document element check completed, errors: " + documentQualityResult.getErrors().size());
        // } else {
        //     logger.info("Skipping document element check - courtProfile=" + courtProfile + ", dirOnly=" + dirOnly);
        //     documentQualityResult.complete();
        // }
        CheckResult documentQualityResult = new CheckResult(DocumentElementChecker.CATEGORY);
        documentQualityResult.complete();

        // 题名规范检查：只做单条记录 + 四级定制模板比对；跨件跨卷勾稽归数据校审
        logger.info("Running title norm check");
        CheckResult titleNormResult;
        try {
            TitleNormChecker titleNormChecker = new TitleNormChecker(view, rules);
            titleNormResult = titleNormChecker.runCheck();
            logger.info("Title norm check completed, errors: " + titleNormResult.getErrors().size());
        } catch (Exception e) {
            logger.warn("Title norm check failed", e);
            titleNormResult = new CheckResult(TitleNormChecker.CATEGORY);
            titleNormResult.complete();
        }

        return buildResponse(totalRows, formatResult, resourceResult, contentResult, typoResult,
                imageQualityResult, documentQualityResult, titleNormResult, rules);
    }

    /**
     * Run quality check asynchronously with progress tracking
     */
    private void runCheckAsync(Project project, QualityRulesConfig rules, String aimpServiceUrl, QualityCheckTask task) {
        try {
            task.setStatus(TaskStatus.RUNNING);
            long checkStartTime = System.currentTimeMillis();
            int totalErrors = 0;

            // 经 SDK 契约（缺省活视图，逐值等价）驱动下沉内核
            TableView view = OrViews.viewFor(project);

            // 「仅目录」项目没有条目数据：条目类检查（含条目-图像关联项）无输入，静默跳过
            boolean dirOnly = isDirectoryOnly(project);

            // 法院定制模板：内容已是人工校对后回传的终稿，再抽取比对必然错位，跳过内容比对
            boolean courtProfile = isCourtProfile(rules);

            // Phase 1: Format checks
            task.setCurrentPhase("格式检查");
            CheckResult formatResult = dirOnly ? skipped("format") : new FormatChecker(view, rules).runCheck();
            task.setFormatErrors(formatResult.getErrors().size());
            totalErrors += formatResult.getErrors().size();

            // Phase 2: Resource checks
            task.setCurrentPhase("资源检查");
            CheckResult resourceResult = dirOnly ? skipped("resource") : new ResourceChecker(view, rules).runCheck();
            task.setResourceErrors(resourceResult.getErrors().size());
            totalErrors += resourceResult.getErrors().size();

            // Phase 3: Content checks
            task.setCurrentPhase("内容检查");
            CheckResult contentResult = new CheckResult("content");
            if (!dirOnly && !courtProfile && aimpServiceUrl != null && !aimpServiceUrl.isEmpty()) {
                logger.info("Running async content check with AIMP service: " + aimpServiceUrl);
                ContentChecker contentChecker = new ContentChecker(view, rules, aimpServiceUrl);
                contentChecker.setTask(task); // Set task for progress updates
                contentResult = contentChecker.runCheck();
                task.setContentErrors(contentResult.getErrors().size());
                totalErrors += contentResult.getErrors().size();
            } else {
                contentResult.complete();
            }

            // Phase 4: Typo checks
            task.setCurrentPhase("错别字检查");
            CheckResult typoResult = new CheckResult("typo");
            if (!dirOnly && aimpServiceUrl != null && !aimpServiceUrl.isEmpty()) {
                logger.info("Running async typo check with AIMP service: " + aimpServiceUrl);
                int formatBatchSize = 20;
                Map<String, Object> globalConfig = GetConfigCommand.loadConfig();
                if (globalConfig != null && globalConfig.containsKey("format.batchSize")) {
                    Object bsObj = globalConfig.get("format.batchSize");
                    if (bsObj instanceof Number) {
                        formatBatchSize = ((Number) bsObj).intValue();
                    }
                }
                TypoChecker typoChecker = new TypoChecker(view, rules, aimpServiceUrl, formatBatchSize);
                typoChecker.setTask(task);
                typoResult = typoChecker.runCheck();
                totalErrors += typoResult.getErrors().size();
                task.setFormatErrors(task.getFormatErrors() + typoResult.getErrors().size());
            } else {
                typoResult.complete();
            }

            // Phase 5: Image quality checks
            task.setCurrentPhase("图像质量检查");
            CheckResult imageQualityResult = new CheckResult("image_quality");
            if (aimpServiceUrl != null && !aimpServiceUrl.isEmpty()) {
                logger.info("Running async image quality check with AIMP service: " + aimpServiceUrl);
                ImageQualityChecker imageChecker = new ImageQualityChecker(view, rules, aimpServiceUrl);
                imageChecker.setTask(task); // Set task for progress updates
                imageQualityResult = imageChecker.runCheck();
                task.setImageQualityErrors(imageQualityResult.getErrors().size());
                totalErrors += imageQualityResult.getErrors().size();
            } else {
                imageQualityResult.complete();
            }

            long checkEndTime = System.currentTimeMillis();
            formatResult.setStartTime(checkStartTime);
            formatResult.setEndTime(checkEndTime);

            // [移交校审·待删] 2026-11-13 案件质量评查（裁判文书必备要素核对）整体移交数据校审。
            // 原异步实现原样保留供校审对照搬运：
            //   task.setCurrentPhase("案件质量评查");
            //   CheckResult documentQualityResult = new CheckResult(DocumentElementChecker.CATEGORY);
            //   if (!dirOnly && courtProfile) {
            //       DocumentElementChecker documentChecker = new DocumentElementChecker(view, rules);
            //       documentChecker.setTask(task);
            //       documentQualityResult = documentChecker.runCheck();
            //       totalErrors += documentQualityResult.getErrors().size();
            //   } else {
            //       documentQualityResult.complete();
            //   }
            CheckResult documentQualityResult = new CheckResult(DocumentElementChecker.CATEGORY);
            documentQualityResult.complete();

            // Phase 6: 题名规范检查（单条记录 + 四级定制模板比对）
            task.setCurrentPhase("题名规范检查");
            TitleNormChecker titleNormChecker = new TitleNormChecker(view, rules);
            titleNormChecker.setTask(task);
            CheckResult titleNormResult = titleNormChecker.runCheck();
            totalErrors += titleNormResult.getErrors().size();

            // Build and store result
            ObjectNode result = buildResponse(project.rows.size(), formatResult, resourceResult, contentResult,
                    typoResult, imageQualityResult, documentQualityResult, titleNormResult, rules);
            task.setResult(result);
            task.setStatus(TaskStatus.COMPLETED);
            task.setCompletedAt(checkEndTime);

            // Persist result to project overlay for later retrieval
            try {
                OverlayQualityCheckResult combinedResult = new OverlayQualityCheckResult("combined");
                combinedResult.setTotalRows(project.rows.size());
                combinedResult.setCheckedRows(project.rows.size());
                combinedResult.setStartTime(formatResult.getStartTime());
                combinedResult.setEndTime(formatResult.getEndTime());
                // 复制serviceUnavailable状态
                combinedResult.setServiceUnavailable(
                    formatResult.isServiceUnavailable() || 
                    resourceResult.isServiceUnavailable() || 
                    contentResult.isServiceUnavailable() || 
                    typoResult.isServiceUnavailable() ||
                    imageQualityResult.isServiceUnavailable() ||
                    documentQualityResult.isServiceUnavailable()
                );
                if (contentResult.isServiceUnavailable()) {
                    combinedResult.setServiceUnavailableMessage(contentResult.getServiceUnavailableMessage());
                } else if (typoResult.isServiceUnavailable()) {
                    combinedResult.setServiceUnavailableMessage(typoResult.getServiceUnavailableMessage());
                } else if (imageQualityResult.isServiceUnavailable()) {
                    combinedResult.setServiceUnavailableMessage(imageQualityResult.getServiceUnavailableMessage());
                } else if (formatResult.isServiceUnavailable()) {
                    combinedResult.setServiceUnavailableMessage(formatResult.getServiceUnavailableMessage());
                } else if (resourceResult.isServiceUnavailable()) {
                    combinedResult.setServiceUnavailableMessage(resourceResult.getServiceUnavailableMessage());
                } else if (documentQualityResult.isServiceUnavailable()) {
                    combinedResult.setServiceUnavailableMessage(documentQualityResult.getServiceUnavailableMessage());
                }
                // 设置imageQualityResult以保留统计数据
                combinedResult.setImageQualityResult(imageQualityResult);
                logger.info("=== 保存 combinedResult ===");
                logger.info("combinedResult.imageQualityResult: {}", combinedResult.getImageQualityResult());
                if (combinedResult.getImageQualityResult() != null) {
                    logger.info("combinedResult.imageQualityResult.errors: {}", combinedResult.getImageQualityResult().getErrors().size());
                    logger.info("combinedResult.imageQualityResult.checkType: {}", combinedResult.getImageQualityResult().getCheckType());
                }
                // Add all errors to combined result
                for (CheckResult.CheckError err : formatResult.getErrors()) {
                    err.setCategory("format");
                    combinedResult.addError(err);
                }
                for (CheckResult.CheckError err : resourceResult.getErrors()) {
                    err.setCategory("resource");
                    combinedResult.addError(err);
                }
                for (CheckResult.CheckError err : contentResult.getErrors()) {
                    err.setCategory("content");
                    combinedResult.addError(err);
                }
                for (CheckResult.CheckError err : typoResult.getErrors()) {
                    err.setCategory("typo");
                    combinedResult.addError(err);
                }
                for (CheckResult.CheckError err : imageQualityResult.getErrors()) {
                    err.setCategory("image_quality");
                    combinedResult.addError(err);
                }
                for (CheckResult.CheckError err : documentQualityResult.getErrors()) {
                    err.setCategory(DocumentElementChecker.CATEGORY);
                    combinedResult.addError(err);
                }
                // 题名规范错误同样落盘，否则刷新页面后结果页/报告会丢失该类别
                for (CheckResult.CheckError err : titleNormResult.getErrors()) {
                    err.setCategory(TitleNormChecker.CATEGORY);
                    combinedResult.addError(err);
                }
                // 文书级明细随 combined 落盘，结果页/下游才能看到「哪件文书缺哪个要素」
                if (documentQualityResult.getDocuments() != null) {
                    combinedResult.setDocuments(documentQualityResult.getDocuments());
                }
                // 嵌套整段：下游只读 combined 也能拿到 executed / skipReason / 件级计数
                combinedResult.setDocumentQualityResult(documentQualityResult);

                // 四性汇总随 combined 落盘：结果接口按此输出，报告据此区分「未检测」与「合格」
                JsonNode summaryNode = result.get("summary");
                if (summaryNode != null) {
                    combinedResult.setFourNatureRules(summaryNode.path("fourNatureRules").asInt(0));
                    combinedResult.setFourNatureErrors(summaryNode.path("fourNatureErrors").asInt(0));
                    combinedResult.setAnyRuleExecuted(summaryNode.path("anyRuleExecuted").asBoolean(false));
                    JsonNode statsNode = summaryNode.get("natureStats");
                    if (statsNode != null && statsNode.isArray()) {
                        List<CheckResult.NatureStat> stats = new ArrayList<>();
                        for (JsonNode n : statsNode) {
                            stats.add(new CheckResult.NatureStat(
                                    n.path("nature").asText(""),
                                    n.path("name").asText(""),
                                    n.path("rules").asInt(0),
                                    n.path("errors").asInt(0),
                                    n.path("executed").asBoolean(false)));
                        }
                        combinedResult.setNatureStats(stats);
                    }
                }

                synchronized (project) {
                    project.overlayModels.put(SaveQualityResultOperation.OVERLAY_MODEL_KEY, combinedResult);
                }
                project.getMetadata().updateModified();

                // Force save project to disk
                ProjectManager.singleton.ensureProjectSaved(project.id);
                logger.info("Quality result persisted and saved to disk");
            } catch (Exception e) {
                logger.error("Failed to persist quality result", e);
            }

            // Persist task state to disk
            TaskPersistence.saveTask(task);
            logger.info("Async quality check completed. Task: " + task.getTaskId() + ", Errors: " + totalErrors);

        } catch (Exception e) {
            logger.error("Error in async quality check", e);
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage(e.getMessage());
            task.setErrorKey("error-unknown");
            task.setCompletedAt(System.currentTimeMillis());
            // Persist failed task state to disk
            TaskPersistence.saveTask(task);
        }
    }

    private ObjectNode buildResponse(int totalRows, CheckResult formatResult, CheckResult resourceResult, 
            CheckResult contentResult, CheckResult typoResult, CheckResult imageQualityResult,
            CheckResult documentQualityResult, CheckResult titleNormResult, QualityRulesConfig rules) {
        ObjectNode responseNode = mapper.createObjectNode();
        responseNode.put("code", "ok");

        int totalErrors = formatResult.getErrors().size() + resourceResult.getErrors().size() + 
                         contentResult.getErrors().size() + typoResult.getErrors().size() +
                         imageQualityResult.getErrors().size() + documentQualityResult.getErrors().size() +
                         titleNormResult.getErrors().size();

        // 添加开始和结束时间
        responseNode.put("startTime", formatResult.getStartTime());
        responseNode.put("endTime", formatResult.getEndTime());

        // 检查是否有服务不可用状态
        boolean serviceUnavailable = formatResult.isServiceUnavailable() || 
                                     resourceResult.isServiceUnavailable() || 
                                     contentResult.isServiceUnavailable() || 
                                     typoResult.isServiceUnavailable() ||
                                     imageQualityResult.isServiceUnavailable() ||
                                     documentQualityResult.isServiceUnavailable() ||
                                     titleNormResult.isServiceUnavailable();
        responseNode.put("serviceUnavailable", serviceUnavailable);
        
        String serviceUnavailableMessage = null;
        if (contentResult.isServiceUnavailable()) {
            serviceUnavailableMessage = contentResult.getServiceUnavailableMessage();
        } else if (typoResult.isServiceUnavailable()) {
            serviceUnavailableMessage = typoResult.getServiceUnavailableMessage();
        } else if (imageQualityResult.isServiceUnavailable()) {
            serviceUnavailableMessage = imageQualityResult.getServiceUnavailableMessage();
        } else if (formatResult.isServiceUnavailable()) {
            serviceUnavailableMessage = formatResult.getServiceUnavailableMessage();
        } else if (resourceResult.isServiceUnavailable()) {
            serviceUnavailableMessage = resourceResult.getServiceUnavailableMessage();
        } else if (documentQualityResult.isServiceUnavailable()) {
            serviceUnavailableMessage = documentQualityResult.getServiceUnavailableMessage();
        }
        if (serviceUnavailableMessage != null) {
            responseNode.put("serviceUnavailableMessage", serviceUnavailableMessage);
        }

        // Summary with image quality errors
        ObjectNode summary = mapper.createObjectNode();
        summary.put("totalRows", totalRows);
        summary.put("totalErrors", totalErrors);
        summary.put("formatErrors", formatResult.getErrors().size() + typoResult.getErrors().size());
        summary.put("resourceErrors", resourceResult.getErrors().size());
        summary.put("contentErrors", contentResult.getErrors().size());
        summary.put("typoErrors", typoResult.getErrors().size());
        summary.put("imageQualityErrors", imageQualityResult.getErrors().size());
        summary.put("documentQualityErrors", documentQualityResult.getErrors().size());
        // 未执行要如实透出，避免「0 错误」被下游当成「全部合格」
        summary.put("documentQualityExecuted", documentQualityResult.getExecuted() == null
                ? false : documentQualityResult.getExecuted());
        if (documentQualityResult.getSkipReason() != null) {
            summary.put("documentQualitySkipReason", documentQualityResult.getSkipReason());
        }
        summary.put("documentCheckedCount", documentQualityResult.getCheckedRows());
        summary.put("documentPassedCount", documentQualityResult.getPassedRows());
        summary.put("documentFailedCount", documentQualityResult.getFailedRows());

        // 题名规范检查（第 4 个 tab）：同样如实透出「是否执行 / 为什么没执行」
        summary.put("titleNormErrors", titleNormResult.getErrors().size());
        summary.put("titleNormExecuted", titleNormResult.getExecuted() == null
                ? false : titleNormResult.getExecuted());
        if (titleNormResult.getSkipReason() != null) {
            summary.put("titleNormSkipReason", titleNormResult.getSkipReason());
        }
        summary.put("titleNormCheckedCount", titleNormResult.getCheckedRows());
        summary.put("titleNormPassedCount", titleNormResult.getPassedRows());
        summary.put("titleNormFailedCount", titleNormResult.getFailedRows());

        ArrayNode allErrors = mapper.createArrayNode();
        for (CheckResult.CheckError err : formatResult.getErrors()) {
            tagNature(err, "format");
            allErrors.add(mapper.valueToTree(err));
        }
        for (CheckResult.CheckError err : resourceResult.getErrors()) {
            tagNature(err, "resource");
            allErrors.add(mapper.valueToTree(err));
        }
        for (CheckResult.CheckError err : contentResult.getErrors()) {
            tagNature(err, "content");
            allErrors.add(mapper.valueToTree(err));
        }
        for (CheckResult.CheckError err : typoResult.getErrors()) {
            tagNature(err, "format");
            allErrors.add(mapper.valueToTree(err));
        }
        for (CheckResult.CheckError err : imageQualityResult.getErrors()) {
            tagNature(err, "image_quality");
            allErrors.add(mapper.valueToTree(err));
        }
        for (CheckResult.CheckError err : documentQualityResult.getErrors()) {
            tagNature(err, DocumentElementChecker.CATEGORY);
            allErrors.add(mapper.valueToTree(err));
        }
        for (CheckResult.CheckError err : titleNormResult.getErrors()) {
            tagNature(err, TitleNormChecker.CATEGORY);
            allErrors.add(mapper.valueToTree(err));
        }
        responseNode.set("errors", allErrors);

        // 四性统计：检查项数按规则口径（含未执行的项不数），问题数按本轮明细；未归类不计入四性合计
        Map<String, Integer> ruleCounts = NatureMapping.ruleStats(rules);
        Map<String, Integer> errorCounts = new LinkedHashMap<>();
        for (int i = 0; i < allErrors.size(); i++) {
            String code = NatureType.fromCode(allErrors.get(i).path("nature").asText("")).getCode();
            Integer current = errorCounts.get(code);
            errorCounts.put(code, current == null ? 1 : current + 1);
        }
        ArrayNode natureStats = mapper.createArrayNode();
        int fourNatureRules = 0;
        int fourNatureErrors = 0;
        for (NatureType nature : NatureMapping.ORDER) {
            String code = nature.getCode();
            int ruleCount = ruleCounts.get(code) == null ? 0 : ruleCounts.get(code);
            int errorCount = errorCounts.get(code) == null ? 0 : errorCounts.get(code);
            ObjectNode item = mapper.createObjectNode();
            item.put("nature", code);
            item.put("name", nature.getDisplayName());
            item.put("rules", ruleCount);
            item.put("errors", errorCount);
            // 该性没有启用的检查项时为 false：报告须显示"未检测"，不能算合格
            item.put("executed", ruleCount > 0);
            item.put("passed", ruleCount > 0 && errorCount == 0);
            natureStats.add(item);
            if (nature.isFourNature()) {
                fourNatureRules += ruleCount;
                fourNatureErrors += errorCount;
            }
        }
        summary.set("natureStats", natureStats);
        summary.put("fourNatureRules", fourNatureRules);
        summary.put("fourNatureErrors", fourNatureErrors);
        // 一条规则都没启用：整体判为「未检测」，避免 0 错误被当成全部合格
        summary.put("anyRuleExecuted", fourNatureRules > 0);
        responseNode.set("summary", summary);

        // Individual results
        responseNode.set("formatResult", mapper.valueToTree(formatResult));
        responseNode.set("resourceResult", mapper.valueToTree(resourceResult));
        responseNode.set("contentResult", mapper.valueToTree(contentResult));
        responseNode.set("typoResult", mapper.valueToTree(typoResult));
        responseNode.set("imageQualityResult", mapper.valueToTree(imageQualityResult));
        responseNode.set("documentQualityResult", mapper.valueToTree(documentQualityResult));
        responseNode.set("titleNormResult", mapper.valueToTree(titleNormResult));

        return responseNode;
    }

    /** 归类四性：规则已显式声明则保留，否则按检查项类型 / 检查类别兜底。 */
    private static void tagNature(CheckResult.CheckError err, String category) {
        // 保留检查器已写的细分类别（如图像错误的「可用性」），缺失时才用段落类别兜底
        String effectiveCategory = err.getCategory();
        if (effectiveCategory == null || effectiveCategory.isEmpty()) {
            err.setCategory(category);
            effectiveCategory = category;
        }
        String existing = err.getNature();
        if (existing == null || existing.isEmpty()) {
            err.setNature(NatureMapping.ofErrorType(err.getErrorType(), effectiveCategory).getCode());
        }
    }

    /** 「仅目录」项目（只有目录层级表、没有条目表）：条目类检查无输入，静默跳过。 */
    private static boolean isDirectoryOnly(Project project) {
        Object kind = project.getMetadata() == null ? null
                : project.getMetadata().getCustomMetadata(IngestProjectCommand.META_SHEET_KIND);
        return kind != null && "dir".equals(String.valueOf(kind));
    }

    /**
     * 有「条目」表时把活动表切回它：目录层级表只有一级目录/文件数量/图像数量三列，
     * 若活动表停留在目录表，检查取到的列与行都不是判定对象。
     */
    static void activateEntrySheet(Project project) {
        if (!project.isMultiSheetProject
                || IngestProjectCommand.ENTRY_SHEET_ID.equals(project.activeSheetId)) {
            return;
        }
        if (project.getSheetData(IngestProjectCommand.ENTRY_SHEET_ID) != null) {
            project.setActiveSheet(IngestProjectCommand.ENTRY_SHEET_ID);
        }
    }

    /** 法院定制模板类别（如 fyss-litigation）：内容比对不参与，避免对人工终稿再抽取比对 */
    private static boolean isCourtProfile(QualityRulesConfig rules) {
        if (rules == null) {
            return false;
        }
        String type = rules.getTemplateType();
        if (type != null && type.toLowerCase().startsWith("fyss")) {
            return true;
        }
        // 兜底：规则里已带文书级配置即视为法院档位（兼容 templateType 未落库的历史项目）
        return rules.getDocumentQualityRule() != null && !rules.getDocumentQualityRule().isEmpty();
    }

    /** 未执行的检查类别：返回已完成标记的空结果。 */
    private static CheckResult skipped(String category) {
        CheckResult result = new CheckResult(category);
        result.complete();
        return result;
    }

    private ObjectNode createErrorResponse(String errorKey, String defaultMessage) {
        ObjectNode response = mapper.createObjectNode();
        response.put("code", "error");
        response.put("errorKey", errorKey);
        response.put("message", defaultMessage);
        return response;
    }

    private ObjectNode createErrorResponse(String message) {
        return createErrorResponse("error-unknown", message);
    }
}

