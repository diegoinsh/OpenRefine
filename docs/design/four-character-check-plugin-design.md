# 四性检测插件设计文档

## 1. 概述

### 1.1 文档信息

| 项目 | 内容 |
|------|------|
| 文档名称 | 四性检测插件设计文档 |
| 插件名称 | four-character-check |
| 版本 | 1.0.0 |
| 创建日期 | 2025-01-29 |
| 关联插件 | data-quality（数据质量检测插件） |

### 1.2 背景与目标

#### 1.2.1 背景

根据国家档案局发布的《电子档案移交接收系统技术规范》和《电子文件归档与电子档案管理规范》（DA/T 63-2019），电子档案需要满足"四性"检测要求：

- **真实性**：确保电子档案的内容、结构与原始状态一致，无篡改
- **完整性**：确保电子档案及其元数据齐全完整，无缺失
- **可用性**：确保电子档案可被正常读取和使用
- **安全性**：确保电子档案无病毒、无恶意代码，访问安全

现有的 data-quality 插件已实现格式检查、资源检查和内容检查，覆盖了真实性、完整性和可用性的大部分需求，但存在以下不足：

- 缺乏专门针对四性的分类和报告
- 缺少安全性检测（病毒扫描、路径安全等）
- 检测结果未按四性维度组织

#### 1.2.2  四性检测的具体要求

根据江苏档案信息网的描述：

真实性 ：来源可靠、程序规范、要素合规
- 来源真实性：电子签名、电子印章验证
- 内容真实性：电子属性与元数据一致性
- 元数据与内容关联一致性

完整性 ：内容、结构、背景信息齐全
- 数据总量检测
- 内容完整性检测
- 信息包完整性检测

可用性 ：可检索、可呈现、可理解
- 内容数据可读性
- 软硬件环境合理性
- 文件格式可读性

安全性 ：过程可控、存储可靠
- 病毒感染检测
- 安全漏洞检测
- 环境保护措施
  

#### 1.2.3  现有体系与四性的对应关系
当前 OpenRefine 数据质量检测体系
检测类型 检查器 检查项目 对应四性 
格式检查 FormatChecker 唯一性、非空、值域、日期格式、正则 真实性、完整性 
资源检查 ResourceChecker 文件存在性、数量一致性、命名规范、连续性 完整性、可用性 
内容检查 ContentChecker OCR抽取与元数据比对 真实性、完整性 
图像质量 ImageQualityChecker 可用性、唯一性、角度、瑕疵、其他、PDF质量 真实性、完整性、可用性

┌─────────────────────────────────────────────────────────────────────┐
│                         四性检测框架                                 │
├─────────────────────────────────────────────────────────────────────┤
│  真实性                                                               │
│  ├── 格式检查：唯一性（非空字段不重复）                               │
│  ├── 内容检查：OCR抽取与元数据一致性                                 │
│  └── 图像质量：重复图片检测、PDF/OFD一致性                           │
├─────────────────────────────────────────────────────────────────────┤
│  完整性                                                               │
│  ├── 格式检查：非空检查、值域检查                                    │
│  ├── 资源检查：文件存在性、数量一致性、连续性                        │
│  └── 图像质量：空白图片、页号/件号连续性                             │
├─────────────────────────────────────────────────────────────────────┤
│  可用性                                                               │
│  ├── 格式检查：日期格式可解析                                        │
│  ├── 资源检查：文件命名规范、文件夹命名规范、路径有效（TODO：需增加开发）│
│  └── 图像质量：破损文件(可读性)、文件格式、倾斜(影响阅读)、DPI检查      │
├─────────────────────────────────────────────────────────────────────┤
│  安全性 ← TODO：当前系统未覆盖                                             │
│  ├── 病毒检测                                                        │
│  ├── 安全漏洞检测                                                    │
│  └── 访问权限控制                                                    │
└─────────────────────────────────────────────────────────────────────┘

#### 1.2.4 目标

设计并实现一个独立的四性检测插件，实现以下目标：

1. **只读集成**：不修改现有 data-quality 插件，通过公开 API 读取检测配置和结果
2. **四性映射**：将现有检测结果映射到四性分类，计算各维度得分
3. **安全增强**：补充安全性检测能力，包括病毒检测、路径安全、权限检查、敏感信息检测
4. **独立报告**：生成符合档案行业标准的四性检测报告
5. **松耦合架构**：与现有插件解耦，可独立部署和升级

### 1.3 设计原则

| 原则 | 说明 |
|------|------|
| 松耦合 | 通过 HTTP API 与现有插件通信，不直接依赖内部实现 |
| 零侵入 | 不修改现有插件代码，保持向后兼容 |
| 可扩展 | 安全检测模块支持插件化扩展 |
| 标准化 | 输出符合档案行业标准的检测报告 |
| 独立性 | 独立存储四性检测结果，不影响现有数据结构 |

---

## 2. 系统架构

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         OpenRefine 核心框架                              │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                      四性检测插件 (four-character-check)         │   │
│  ├─────────────────────────────────────────────────────────────────┤   │
│  │                                                                  │   │
│  │  ┌──────────────────────────────────────────────────────────┐   │   │
│  │  │                   四性检测控制器层                         │   │   │
│  │  │  ┌──────────────────────────────────────────────────────┐│   │   │
│  │  │  │              FourCharacterCheckController             ││   │   │
│  │  │  │  - startCheck()        启动四性检测                   ││   │   │
│  │  │  │  - getReport()         获取四性报告                   ││   │   │
│  │  │  │  - getStatus()         获取检测状态                   ││   │   │
│  │  │  │  - analyzeCoverage()   分析检测覆盖                   ││   │   │
│  │  │  └──────────────────────────────────────────────────────┘│   │   │
│  │  └──────────────────────────────────────────────────────────┘   │   │
│  │                              │                                    │   │
│  │              ┌───────────────┼───────────────┐                    │   │
│  │              ▼               ▼               ▼                    │   │
│  │  ┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐  │   │
│  │  │   数据读取模块    │ │   安全检测模块    │ │   四性分析模块    │  │   │
│  │  │  (DataReader)    │ │ (SecurityChecker)│ │ (FourCharAnalyzer)│  │   │
│  │  │                  │ │                  │ │                  │  │   │
│  │  │ - readRules()    │ │ - virusCheck()   │ │ - mapToFourChar()│  │   │
│  │  │ - readResults()  │ │ - pathCheck()    │ │ - calcScores()   │  │   │
│  │  │ - readTaskStatus()│ │ - permissionCheck│ │ - checkCoverage()│  │   │
│  │  └──────────────────┘ └──────────────────┘ └──────────────────┘  │   │
│  │                                                                  │   │
│  │  ┌──────────────────────────────────────────────────────────┐   │   │
│  │  │                      报告存储层                            │   │   │
│  │  │  ┌──────────────────────────────────────────────────────┐│   │   │
│  │  │  │              FourCharacterReport (OverlayModel)       ││   │   │
│  │  │  └──────────────────────────────────────────────────────┘│   │   │
│  │  └──────────────────────────────────────────────────────────┘   │   │
│  │                                                                  │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                    │                                    │
│                                    ▼                                    │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                     data-quality 插件 (现有)                      │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐   │   │
│  │  │ FormatChecker│  │ResourceChecker│  │   ContentChecker     │   │   │
│  │  │ (真实性)      │  │ (完整性)      │  │   (真实性+完整性)    │   │   │
│  │  └──────────────┘  └──────────────┘  └──────────────────────┘   │   │
│  │                                                                  │   │
│  │  ┌──────────────────────────────────────────────────────────┐   │   │
│  │  │                   对外 API (只读访问)                      │   │   │
│  │  │  GET /command/data-quality/get-quality-rules              │   │   │
│  │  │  GET /command/data-quality/get-quality-result             │   │   │
│  │  │  POST /command/data-quality/run-quality-check             │   │   │
│  │  │  GET /command/data-quality/get-check-progress             │   │   │
│  │  └──────────────────────────────────────────────────────────┘   │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### 2.2 模块职责

| 模块 | 职责 | 依赖 |
|------|------|------|
| FourCharacterCheckController | 协调各模块工作，提供对外 API | DataReader, SecurityChecker, FourCharAnalyzer |
| DataReader | 调用 data-quality 插件 API，读取检测配置和结果 | data-quality 插件 HTTP API |
| SecurityChecker | 执行病毒检测、路径安全、权限检查、敏感信息检测 | 病毒扫描引擎 |
| FourCharAnalyzer | 将检测结果映射到四性，计算得分，生成报告 | DataReader, SecurityChecker |
| FourCharacterReport | 存储四性检测结果 | Project OverlayModel |

### 2.3 数据流

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           数据流图                                       │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  1. 读取检测配置                                                         │
│  ┌─────────┐    GET /get-quality-rules     ┌─────────────────┐         │
│  │  用户   │ ─────────────────────────────► │  DataReader     │         │
│  └─────────┘                               └────────┬────────┘         │
│                                                     │                  │
│  2. 触发数据质量检测（可选）                                 │                  │
│  ┌─────────┐    POST /run-quality-check    ┌─────────────────┐         │
│  │  用户   │ ─────────────────────────────► │  DataReader     │         │
│  └─────────┘                               └────────┬────────┘         │
│                                                     │                  │
│  3. 等待检测完成                                         │                  │
│  ┌─────────┐    GET /get-check-progress    ┌─────────────────┐         │
│  │  用户   │ ─────────────────────────────► │  DataReader     │         │
│  └─────────┘                               └────────┬────────┘         │
│                                                     │                  │
│  4. 读取检测结果                                         │                  │
│  ┌─────────┐    GET /get-quality-result    ┌─────────────────┐         │
│  │  用户   │ ─────────────────────────────► │  DataReader     │         │
│  └─────────┘                               └────────┬────────┘         │
│                                                     │                  │
│  5. 执行安全检测                                         │                  │
│                                                     ▼                  │
│                                             ┌─────────────────┐        │
│                                             │SecurityChecker  │        │
│                                             └────────┬────────┘        │
│                                                     │                  │
│  6. 四性映射与得分计算                                   │                  │
│                                                     ▼                  │
│                                             ┌─────────────────┐        │
│                                             │FourCharAnalyzer │        │
│                                             └────────┬────────┘        │
│                                                     │                  │
│  7. 生成并保存报告                                       │                  │
│                                                     ▼                  │
│                                             ┌─────────────────┐        │
│                                             │FourCharacter    │        │
│                                             │Report           │        │
│                                             └────────┬────────┘        │
│                                                     │                  │
│  8. 返回四性报告                                         │                  │
│  ┌─────────┐    GET /get-four-char-report  ┌─────────────────┐        │
│  │  用户   │ ◄───────────────────────────── │  Controller     │        │
│  └─────────┘                               └─────────────────┘        │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 3. 模块详细设计

### 3.1 数据读取模块（DataReader）

#### 3.1.1 功能描述

数据读取模块负责与现有的 data-quality 插件通信，读取检测配置、检测结果和任务状态。

#### 3.1.2 核心代码设计

```java
package com.google.refine.extension.fourchar.reader;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.fourchar.model.*;
import com.google.refine.model.Project;

public class DataReader {
    
    private static final Logger logger = LoggerFactory.getLogger(DataReader.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String DATA_QUALITY_COMMAND_PATH = "/command/data-quality/";
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int POLLING_INTERVAL_MS = 1000;
    private static final long DEFAULT_TIMEOUT_MS = 600000;
    
    private final HttpClient httpClient;
    private final String baseUrl;
    
    public DataReader(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS))
            .build();
    }
    
    public QualityRulesConfig readRules(Project project) {
        logger.info("读取项目 {} 的质量检测规则配置", project.id);
        String url = buildUrl("get-quality-rules", Map.of("project", String.valueOf(project.id)));
        JsonNode response = executeGet(url);
        
        if (response == null || !"ok".equals(response.path("code").asText())) {
            logger.warn("获取规则配置失败，返回: {}", response);
            return new QualityRulesConfig();
        }
        
        JsonNode rulesNode = response.path("rules");
        return mapper.convertValue(rulesNode, QualityRulesConfig.class);
    }
    
    public CheckResult readResults(Project project) {
        logger.info("读取项目 {} 的质量检测结果", project.id);
        String url = buildUrl("get-quality-result", Map.of("project", String.valueOf(project.id)));
        JsonNode response = executeGet(url);
        
        if (response == null || !response.path("hasResult").asBoolean()) {
            logger.info("项目 {} 暂无检测结果", project.id);
            return null;
        }
        
        JsonNode resultNode = response.path("result");
        return mapper.convertValue(resultNode, CheckResult.class);
    }
    
    public TaskStatusInfo readTaskStatus(String taskId) {
        String url = buildUrl("get-check-progress", Map.of("taskId", taskId));
        JsonNode response = executeGet(url);
        
        if (response == null || !"ok".equals(response.path("code").asText())) {
            return null;
        }
        
        TaskStatusInfo status = new TaskStatusInfo();
        status.setTaskId(taskId);
        status.setStatus(response.path("status").asText());
        status.setProgress(response.path("progress").asInt());
        status.setCurrentPhase(response.path("currentPhase").asText());
        
        return status;
    }
    
    public String triggerCheck(Project project, boolean async) {
        logger.info("触发项目 {} 的质量检测 (async={})", project.id, async);
        String url = buildUrl("run-quality-check", Map.of(
            "project", String.valueOf(project.id),
            "async", String.valueOf(async)
        ));
        
        JsonNode response = executePost(url, null);
        
        if (response != null && "ok".equals(response.path("code").asText())) {
            return response.path("taskId").asText();
        }
        
        return null;
    }
    
    public boolean waitForCompletion(String taskId) {
        return waitForCompletion(taskId, DEFAULT_TIMEOUT_MS);
    }
    
    public boolean waitForCompletion(String taskId, long timeoutMs) {
        logger.info("等待任务 {} 完成 (超时: {}ms)", taskId, timeoutMs);
        long startTime = System.currentTimeMillis();
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            TaskStatusInfo status = readTaskStatus(taskId);
            
            if (status == null) {
                logger.warn("无法获取任务状态: {}", taskId);
                return false;
            }
            
            String taskStatus = status.getStatus();
            if ("COMPLETED".equals(taskStatus)) {
                logger.info("任务 {} 已完成", taskId);
                return true;
            } else if ("FAILED".equals(taskStatus) || "CANCELLED".equals(taskStatus)) {
                logger.warn("任务 {} 状态: {}", taskId, taskStatus);
                return false;
            }
            
            try {
                Thread.sleep(POLLING_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        
        logger.warn("等待任务 {} 超时", taskId);
        return false;
    }
    
    public CoverageAnalysis analyzeCoverage(QualityRulesConfig rules) {
        CoverageAnalysis coverage = new CoverageAnalysis();
        
        boolean authenticityCovered = false;
        boolean integrityCovered = false;
        boolean usabilityCovered = false;
        int checkedCount = 0;
        
        if (rules.getFormatRules() != null && !rules.getFormatRules().isEmpty()) {
            authenticityCovered = true;
            checkedCount++;
            coverage.addCheckItem("真实性", "格式检查");
        }
        
        if (rules.getContentRules() != null && !rules.getContentRules().isEmpty()) {
            authenticityCovered = true;
            integrityCovered = true;
            checkedCount++;
            coverage.addCheckItem("真实性", "内容比对");
            coverage.addCheckItem("完整性", "内容比对");
        }
        
        if (rules.getResourceConfig() != null && rules.getResourceConfig().isEnabled()) {
            integrityCovered = true;
            checkedCount++;
            coverage.addCheckItem("完整性", "资源检查");
        }
        
        if (rules.getAimpConfig() != null && 
            rules.getAimpConfig().getServiceUrl() != null && 
            !rules.getAimpConfig().getServiceUrl().isEmpty()) {
            usabilityCovered = true;
            checkedCount++;
            coverage.addCheckItem("可用性", "AIMP服务检测");
        }
        
        coverage.setCovered("真实性", authenticityCovered);
        coverage.setCovered("完整性", integrityCovered);
        coverage.setCovered("可用性", usabilityCovered);
        coverage.setCheckedCount(checkedCount);
        coverage.setCoverageRate((double) checkedCount / 4);
        
        return coverage;
    }
    
    private String buildUrl(String command, Map<String, String> params) {
        StringBuilder url = new StringBuilder(baseUrl);
        url.append(DATA_QUALITY_COMMAND_PATH).append(command);
        
        if (params != null && !params.isEmpty()) {
            url.append("?");
            boolean first = true;
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (!first) url.append("&");
                url.append(entry.getKey()).append("=").append(entry.getValue());
                first = false;
            }
        }
        
        return url.toString();
    }
    
    private JsonNode executeGet(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
            
            HttpResponse<String> response = httpClient.send(request, 
                HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                return mapper.readTree(response.body());
            }
            
            logger.warn("HTTP GET 请求失败: {} - {}", url, response.statusCode());
            return null;
            
        } catch (IOException | InterruptedException e) {
            logger.error("执行 HTTP GET 请求失败: {}", url, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }
    
    private JsonNode executePost(String url, String body) {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json");
            
            if (body != null && !body.isEmpty()) {
                requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body));
            } else {
                requestBuilder.POST(HttpRequest.BodyPublishers.noBody());
            }
            
            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                return mapper.readTree(response.body());
            }
            
            logger.warn("HTTP POST 请求失败: {} - {}", url, response.statusCode());
            return null;
            
        } catch (IOException | InterruptedException e) {
            logger.error("执行 HTTP POST 请求失败: {}", url, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }
}
```

### 3.2 安全检测模块（SecurityChecker）

#### 3.2.1 功能描述

安全检测模块负责执行文件安全性检测，包括病毒检测、路径安全检测、文件权限检测和敏感信息检测。

#### 3.2.2 检测项目

| 检测项 | 说明 | 严重级别 | 关联四性 |
|--------|------|----------|----------|
| 病毒检测 | 检测文件是否包含病毒或恶意代码 | HIGH | 安全性 |
| 路径遍历检测 | 检测是否存在路径遍历攻击风险 | HIGH | 安全性 |
| 敏感路径检测 | 检测是否尝试访问敏感系统路径 | MEDIUM | 安全性 |
| 文件权限检测 | 检测文件权限是否过于宽松 | MEDIUM | 安全性 |
| 敏感信息检测 | 检测是否包含敏感个人信息 | MEDIUM | 安全性 |

#### 3.2.3 核心代码设计

```java
package com.google.refine.extension.fourchar.checker;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.fourchar.model.CheckError;
import com.google.refine.extension.fourchar.model.CheckResult;
import com.google.refine.extension.fourchar.model.SecurityCheckConfig;
import com.google.refine.model.Cell;
import com.google.refine.model.Project;
import com.google.refine.model.Row;

public class SecurityChecker {
    
    private static final Logger logger = LoggerFactory.getLogger(SecurityChecker.class);
    
    private static final Pattern PATH_TRAVERSAL_PATTERN = Pattern.compile(
        "(\\.|\\.\\.|\\\\|\\/)\\.\\.(\\\\|\\/)"
    );
    
    private static final Set<String> SENSITIVE_PATHS = new HashSet<>(Arrays.asList(
        "/etc/passwd", "/etc/shadow", "/etc/sudoers",
        "/root", "/home/root",
        "C:\\Windows\\System32", "C:\\Windows\\SysWOW64",
        "C:\\Users\\Administrator"
    ));
    
    private static final Pattern[] SENSITIVE_PATTERNS = {
        Pattern.compile("\\d{17}[\\dXx]"),
        Pattern.compile("1[3-9]\\d{9}"),
        Pattern.compile("\\d{16,19}"),
        Pattern.compile("(?i)(password|passwd|pwd|secret|密钥)"),
        Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
    };
    
    private static final Set<String> EXCLUDED_COLUMNS = new HashSet<>(Arrays.asList(
        "id", "row_number", "index", "序号", "编号"
    ));
    
    private final Project project;
    private final SecurityCheckConfig config;
    private final VirusScanner virusScanner;
    
    public interface VirusScanner {
        ScanResult scan(String filePath);
        String getVersion();
        
        class ScanResult {
            private final boolean infected;
            private final String virusName;
            private final String message;
            
            public ScanResult(boolean infected, String virusName, String message) {
                this.infected = infected;
                this.virusName = virusName;
                this.message = message;
            }
            
            public boolean isInfected() { return infected; }
            public String getVirusName() { return virusName; }
            public String getMessage() { return message; }
        }
    }
    
    public SecurityChecker(Project project, SecurityCheckConfig config) {
        this(project, config, null);
    }
    
    public SecurityChecker(Project project, SecurityCheckConfig config, VirusScanner virusScanner) {
        this.project = project;
        this.config = config;
        this.virusScanner = virusScanner;
    }
    
    public CheckResult performSecurityCheck() {
        logger.info("开始执行安全检测");
        CheckResult result = new CheckResult("security");
        
        if (config == null) {
            logger.warn("安全检测配置为空，跳过检测");
            result.complete();
            return result;
        }
        
        List<CheckError> allErrors = new ArrayList<>();
        
        if (config.isEnableVirusCheck()) {
            allErrors.addAll(checkViruses());
        }
        
        if (config.isEnablePathCheck()) {
            allErrors.addAll(checkPathSecurity());
        }
        
        if (config.isEnablePermissionCheck()) {
            allErrors.addAll(checkFilePermissions());
        }
        
        if (config.isEnableSensitiveInfoCheck()) {
            allErrors.addAll(checkSensitiveInfo());
        }
        
        for (CheckError error : allErrors) {
            result.addError(error);
        }
        
        result.complete();
        logger.info("安全检测完成，发现 {} 个安全问题", allErrors.size());
        
        return result;
    }
    
    private List<CheckError> checkViruses() {
        List<CheckError> errors = new ArrayList<>();
        
        if (!config.isEnableVirusCheck() || virusScanner == null) {
            return errors;
        }
        
        for (Row row : project.rows) {
            for (int cellIndex = 0; cellIndex < row.cells.size(); cellIndex++) {
                Cell cell = row.cells.get(cellIndex);
                String column = getColumnName(cellIndex);
                
                if (!isFilePathColumn(column)) continue;
                
                String value = cell.value != null ? cell.value.toString() : "";
                String filePath = extractFilePath(value);
                
                if (filePath == null || filePath.isEmpty()) continue;
                
                try {
                    VirusScanner.ScanResult scanResult = virusScanner.scan(filePath);
                    
                    if (scanResult.isInfected()) {
                        CheckError error = new CheckError();
                        error.setRowIndex(row.rowIndex);
                        error.setColumn(column);
                        error.setValue(value);
                        error.setErrorType("virus_detected");
                        error.setMessage("检测到病毒: " + scanResult.getVirusName());
                        error.setExtractedValue(filePath);
                        error.setSeverity("HIGH");
                        error.setCategory("安全性");
                        errors.add(error);
                    }
                } catch (Exception e) {
                    logger.warn("扫描文件 {} 时出错: {}", filePath, e.getMessage());
                }
            }
        }
        
        return errors;
    }
    
    private List<CheckError> checkPathSecurity() {
        List<CheckError> errors = new ArrayList<>();
        
        for (Row row : project.rows) {
            for (int cellIndex = 0; cellIndex < row.cells.size(); cellIndex++) {
                Cell cell = row.cells.get(cellIndex);
                String column = getColumnName(cellIndex);
                
                if (!isFilePathColumn(column)) continue;
                
                String value = cell.value != null ? cell.value.toString() : "";
                if (value == null || value.isEmpty()) continue;
                
                Matcher matcher = PATH_TRAVERSAL_PATTERN.matcher(value);
                if (matcher.find()) {
                    CheckError error = new CheckError();
                    error.setRowIndex(row.rowIndex);
                    error.setColumn(column);
                    error.setValue(value);
                    error.setErrorType("path_traversal");
                    error.setMessage("检测到路径遍历风险: " + value);
                    error.setSeverity("HIGH");
                    error.setCategory("安全性");
                    errors.add(error);
                    continue;
                }
                
                if (accessesSensitivePath(value)) {
                    CheckError error = new CheckError();
                    error.setRowIndex(row.rowIndex);
                    error.setColumn(column);
                    error.setValue(value);
                    error.setErrorType("sensitive_path");
                    error.setMessage("尝试访问敏感路径: " + value);
                    error.setSeverity("MEDIUM");
                    error.setCategory("安全性");
                    errors.add(error);
                }
            }
        }
        
        return errors;
    }
    
    private List<CheckError> checkFilePermissions() {
        List<CheckError> errors = new ArrayList<>();
        
        for (Row row : project.rows) {
            for (int cellIndex = 0; cellIndex < row.cells.size(); cellIndex++) {
                Cell cell = row.cells.get(cellIndex);
                String column = getColumnName(cellIndex);
                
                if (!isFilePathColumn(column)) continue;
                
                String value = cell.value != null ? cell.value.toString() : "";
                String filePath = extractFilePath(value);
                
                if (filePath == null || filePath.isEmpty()) continue;
                
                File file = new File(filePath);
                if (file.exists() && file.isFile() && hasInsecurePermissions(file)) {
                    CheckError error = new CheckError();
                    error.setRowIndex(row.rowIndex);
                    error.setColumn(column);
                    error.setValue(value);
                    error.setErrorType("insecure_permission");
                    error.setMessage("文件权限过于宽松: " + getPermissionString(file));
                    error.setExtractedValue(filePath);
                    error.setSeverity("MEDIUM");
                    error.setCategory("安全性");
                    errors.add(error);
                }
            }
        }
        
        return errors;
    }
    
    private List<CheckError> checkSensitiveInfo() {
        List<CheckError> errors = new ArrayList<>();
        
        for (Row row : project.rows) {
            for (int cellIndex = 0; cellIndex < row.cells.size(); cellIndex++) {
                Cell cell = row.cells.get(cellIndex);
                String column = getColumnName(cellIndex);
                
                if (isExcludedColumn(column)) continue;
                
                String value = cell.value != null ? cell.value.toString() : "";
                if (value == null || value.isEmpty()) continue;
                
                for (int i = 0; i < SENSITIVE_PATTERNS.length; i++) {
                    if (SENSITIVE_PATTERNS[i].matcher(value).find()) {
                        CheckError error = new CheckError();
                        error.setRowIndex(row.rowIndex);
                        error.setColumn(column);
                        error.setValue(maskSensitiveData(value, i));
                        error.setErrorType("sensitive_info");
                        error.setMessage("检测到敏感信息: " + getSensitiveType(i));
                        error.setSeverity("MEDIUM");
                        error.setCategory("安全性");
                        errors.add(error);
                        break;
                    }
                }
            }
        }
        
        return errors;
    }
    
    private boolean isFilePathColumn(String column) {
        if (column == null) return false;
        String lowerColumn = column.toLowerCase();
        return lowerColumn.contains("path") || 
               lowerColumn.contains("file") ||
               lowerColumn.contains("url") ||
               lowerColumn.contains("地址") ||
               lowerColumn.contains("路径");
    }
    
    private String extractFilePath(String value) {
        if (value == null || value.isEmpty()) return null;
        value = value.trim();
        if (value.startsWith("file://")) value = value.substring(7);
        if (value.startsWith("http://") || value.startsWith("https://")) return null;
        return value;
    }
    
    private boolean accessesSensitivePath(String path) {
        String normalizedPath = path.toLowerCase().replace("\\", "/");
        for (String sensitivePath : SENSITIVE_PATHS) {
            if (normalizedPath.contains(sensitivePath.toLowerCase())) return true;
        }
        return false;
    }
    
    private boolean hasInsecurePermissions(File file) {
        try {
            if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                return false;
            }
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file.toPath());
            return perms.contains(PosixFilePermission.OTHERS_READ) ||
                   perms.contains(PosixFilePermission.OTHERS_WRITE);
        } catch (Exception e) {
            return false;
        }
    }
    
    private String getPermissionString(File file) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file.toPath());
            StringBuilder sb = new StringBuilder();
            sb.append(perms.contains(PosixFilePermission.OWNER_READ) ? "r" : "-");
            sb.append(perms.contains(PosixFilePermission.OWNER_WRITE) ? "w" : "-");
            sb.append(perms.contains(PosixFilePermission.OWNER_EXECUTE) ? "x" : "-");
            sb.append(perms.contains(PosixFilePermission.GROUP_READ) ? "r" : "-");
            sb.append(perms.contains(PosixFilePermission.GROUP_WRITE) ? "w" : "-");
            sb.append(perms.contains(PosixFilePermission.GROUP_EXECUTE) ? "x" : "-");
            sb.append(perms.contains(PosixFilePermission.OTHERS_READ) ? "r" : "-");
            sb.append(perms.contains(PosixFilePermission.OTHERS_WRITE) ? "w" : "-");
            sb.append(perms.contains(PosixFilePermission.OTHERS_EXECUTE) ? "x" : "-");
            return sb.toString();
        } catch (Exception e) {
            return "未知";
        }
    }
    
    private boolean isExcludedColumn(String column) {
        if (column == null) return true;
        String lowerColumn = column.toLowerCase();
        for (String excluded : EXCLUDED_COLUMNS) {
            if (lowerColumn.contains(excluded.toLowerCase())) return true;
        }
        return false;
    }
    
    private String maskSensitiveData(String value, int patternIndex) {
        if (value == null || value.length() < 4) return "****";
        switch (patternIndex) {
            case 0: return value.substring(0, 3) + "***********" + value.substring(value.length() - 1);
            case 1: return value.substring(0, 3) + "****" + value.substring(7);
            case 2: return "****" + value.substring(value.length() - 4);
            case 3: return "****";
            case 4: return value.substring(0, 3) + "***@***.com";
            default: return "****";
        }
    }
    
    private String getSensitiveType(int patternIndex) {
        switch (patternIndex) {
            case 0: return "身份证号";
            case 1: return "手机号";
            case 2: return "银行卡号";
            case 3: return "密码/密钥";
            case 4: return "邮箱地址";
            default: return "敏感信息";
        }
    }
    
    private String getColumnName(int cellIndex) {
        if (project.columnModel == null || cellIndex >= project.columnModel.columnGroups.size()) {
            return "column_" + cellIndex;
        }
        return project.columnModel.columnGroups.get(cellIndex).getName();
    }
}
```

### 3.3 四性分析模块（FourCharAnalyzer）

#### 3.3.1 功能描述

四性分析模块负责将检测结果映射到四性分类，计算各维度得分，并生成四性检测报告。

#### 3.3.2 核心代码设计

```java
package com.google.refine.extension.fourchar.analyzer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.fourchar.model.*;

public class FourCharAnalyzer {
    
    private static final Logger logger = LoggerFactory.getLogger(FourCharAnalyzer.class);
    
    private static final Map<String, String> ERROR_TO_FOUR_CHAR_MAP = new HashMap<>();
    
    static {
        ERROR_TO_FOUR_CHAR_MAP.put("format_mismatch", "真实性");
        ERROR_TO_FOUR_CHAR_MAP.put("regex_failed", "真实性");
        ERROR_TO_FOUR_CHAR_MAP.put("unique_violation", "真实性");
        ERROR_TO_FOUR_CHAR_MAP.put("content_mismatch", "真实性");
        ERROR_TO_FOUR_CHAR_MAP.put("content_comparison_failed", "真实性");
        
        ERROR_TO_FOUR_CHAR_MAP.put("folder_not_found", "完整性");
        ERROR_TO_FOUR_CHAR_MAP.put("file_count_mismatch", "完整性");
        ERROR_TO_FOUR_CHAR_MAP.put("missing_file", "完整性");
        ERROR_TO_FOUR_CHAR_MAP.put("metadata_mismatch", "完整性");
        
        ERROR_TO_FOUR_CHAR_MAP.put("file_not_accessible", "可用性");
        ERROR_TO_FOUR_CHAR_MAP.put("format_not_supported", "可用性");
        ERROR_TO_FOUR_CHAR_MAP.put("damaged_file", "可用性");
        ERROR_TO_FOUR_CHAR_MAP.put("blank_page", "可用性");
        
        ERROR_TO_FOUR_CHAR_MAP.put("virus_detected", "安全性");
        ERROR_TO_FOUR_CHAR_MAP.put("path_traversal", "安全性");
        ERROR_TO_FOUR_CHAR_MAP.put("sensitive_path", "安全性");
        ERROR_TO_FOUR_CHAR_MAP.put("insecure_permission", "安全性");
        ERROR_TO_FOUR_CHAR_MAP.put("sensitive_info", "安全性");
    }
    
    public Map<String, List<CheckError>> mapErrorsToFourCharacter(
            CheckResult existingResult, 
            CheckResult securityResult) {
        
        Map<String, List<CheckError>> fourCharErrors = new HashMap<>();
        fourCharErrors.put("真实性", new ArrayList<>());
        fourCharErrors.put("完整性", new ArrayList<>());
        fourCharErrors.put("可用性", new ArrayList<>());
        fourCharErrors.put("安全性", new ArrayList<>());
        
        if (existingResult != null) {
            for (CheckError error : existingResult.getErrors()) {
                String fourChar = mapErrorToFourChar(error);
                fourCharErrors.get(fourChar).add(error);
            }
        }
        
        if (securityResult != null) {
            for (CheckError error : securityResult.getErrors()) {
                error.setCategory("安全性");
                fourCharErrors.get("安全性").add(error);
            }
        }
        
        return fourCharErrors;
    }
    
    public Map<String, Double> calculateFourCharacterScores(
            Map<String, List<CheckError>> fourCharErrors,
            int totalRows) {
        
        Map<String, Double> scores = new HashMap<>();
        
        for (Map.Entry<String, List<CheckError>> entry : fourCharErrors.entrySet()) {
            String fourChar = entry.getKey();
            List<CheckError> errors = entry.getValue();
            
            double score = totalRows > 0 ? 
                ((double)(totalRows - errors.size()) / totalRows) * 100 : 100.0;
            
            scores.put(fourChar, Math.round(score * 100.0) / 100.0);
        }
        
        return scores;
    }
    
    public FourCharacterReport generateReport(
            String reportId,
            long projectId,
            String sourceCheckId,
            CheckResult existingResult,
            CheckResult securityResult,
            CoverageAnalysis coverage,
            SecurityCheckConfig securityConfig) {
        
        FourCharacterReport report = new FourCharacterReport();
        report.setReportId(reportId);
        report.setProjectId(projectId);
        report.setSourceCheckId(sourceCheckId);
        report.setGeneratedAt(System.currentTimeMillis());
        
        Map<String, List<CheckError>> fourCharErrors = mapErrorsToFourCharacter(
            existingResult, securityResult);
        
        int totalRows = existingResult != null ? existingResult.getTotalRows() : 
            (project != null ? project.rows.size() : 0);
        
        Map<String, Double> scores = calculateFourCharacterScores(fourCharErrors, totalRows);
        
        Map<String, FourCharacterReport.FourCharacterResult> results = new HashMap<>();
        
        for (String fourChar : Arrays.asList("真实性", "完整性", "可用性", "安全性")) {
            FourCharacterReport.FourCharacterResult result = new FourCharacterReport.FourCharacterResult();
            result.setCharacteristic(fourChar);
            result.setScore(scores.getOrDefault(fourChar, 100.0));
            result.setErrorCount(fourCharErrors.get(fourChar).size());
            result.setErrors(fourCharErrors.get(fourChar));
            result.setIsCovered(coverage.isCovered(fourChar));
            result.setCheckItems(coverage.getCheckItems(fourChar));
            results.put(fourChar, result);
        }
        
        report.setFourCharacterResults(results);
        report.setOverallScore(calcOverallScore(scores));
        
        FourCharacterReport.CoverageAnalysis reportCoverage = new FourCharacterReport.CoverageAnalysis();
        reportCoverage.setTotalCharacteristics(4);
        reportCoverage.setCheckedCharacteristics(coverage.getCheckedCount());
        reportCoverage.setCoverageRate(coverage.getCoverageRate());
        
        List<String> missing = new ArrayList<>();
        if (!coverage.isCovered("真实性")) missing.add("真实性");
        if (!coverage.isCovered("完整性")) missing.add("完整性");
        if (!coverage.isCovered("可用性")) missing.add("可用性");
        if (!coverage.isCovered("安全性")) missing.add("安全性");
        reportCoverage.setMissingCharacteristics(missing);
        
        report.setCoverageAnalysis(reportCoverage);
        
        FourCharacterReport.SecurityCheckDetails securityDetails = new FourCharacterReport.SecurityCheckDetails();
        if (securityConfig != null) {
            securityDetails.setVirusCheckEnabled(securityConfig.isEnableVirusCheck());
            securityDetails.setPathCheckEnabled(securityConfig.isEnablePathCheck());
            securityDetails.setPermissionCheckEnabled(securityConfig.isEnablePermissionCheck());
            securityDetails.setSensitiveInfoCheckEnabled(securityConfig.isEnableSensitiveInfoCheck());
            
            List<CheckError> securityErrors = fourCharErrors.get("安全性");
            securityDetails.setVirusErrors((int) securityErrors.stream()
                .filter(e -> "virus_detected".equals(e.getErrorType())).count());
            securityDetails.setPathTraversalErrors((int) securityErrors.stream()
                .filter(e -> "path_traversal".equals(e.getErrorType()) || "sensitive_path".equals(e.getErrorType())).count());
            securityDetails.setPermissionErrors((int) securityErrors.stream()
                .filter(e -> "insecure_permission".equals(e.getErrorType())).count());
            securityDetails.setSensitiveInfoErrors((int) securityErrors.stream()
                .filter(e -> "sensitive_info".equals(e.getErrorType())).count());
        }
        report.setSecurityCheckDetails(securityDetails);
        
        report.setRecommendations(generateRecommendations(report, fourCharErrors, coverage));
        
        return report;
    }
    
    private String mapErrorToFourChar(CheckError error) {
        String errorType = error.getErrorType();
        String originalCategory = error.getCategory();
        
        if (errorType != null && ERROR_TO_FOUR_CHAR_MAP.containsKey(errorType)) {
            return ERROR_TO_FOUR_CHAR_MAP.get(errorType);
        }
        
        if ("format".equals(originalCategory)) return "真实性";
        else if ("resource".equals(originalCategory)) {
            if ("file_not_accessible".equals(errorType)) return "可用性";
            return "完整性";
        } else if ("content".equals(originalCategory)) return "真实性";
        
        return "真实性";
    }
    
    private double calcOverallScore(Map<String, Double> scores) {
        double total = 0.0;
        int count = 0;
        for (Double score : scores.values()) {
            total += score;
            count++;
        }
        return count > 0 ? Math.round((total / count) * 100.0) / 100.0 : 0.0;
    }
    
    private List<String> generateRecommendations(
            FourCharacterReport report,
            Map<String, List<CheckError>> fourCharErrors,
            CoverageAnalysis coverage) {
        
        List<String> recommendations = new ArrayList<>();
        
        if (report.getOverallScore() < 60) {
            recommendations.add("总体得分较低，建议全面检查数据质量配置并重新执行检测。");
        }
        
        for (String fourChar : Arrays.asList("真实性", "完整性", "可用性", "安全性")) {
            FourCharacterReport.FourCharacterResult result = report.getFourCharacterResults().get(fourChar);
            if (result.getScore() < 60) {
                recommendations.add(String.format("%s 得分较低 (%.2f%%)，建议重点检查相关检测规则。", 
                    fourChar, result.getScore()));
            }
        }
        
        if (!coverage.isCovered("真实性")) {
            recommendations.add("未配置真实性检测，建议启用格式检查和内容比对功能。");
        }
        if (!coverage.isCovered("完整性")) {
            recommendations.add("未配置完整性检测，建议启用资源检查功能。");
        }
        if (!coverage.isCovered("可用性")) {
            recommendations.add("未配置可用性检测，建议配置 AIMP 服务进行可用性检测。");
        }
        if (!coverage.isCovered("安全性")) {
            recommendations.add("未配置安全性检测，建议启用安全检测模块。");
        }
        
        long virusErrors = report.getSecurityCheckDetails() != null ?
            report.getSecurityCheckDetails().getVirusErrors() : 0;
        if (virusErrors > 0) {
            recommendations.add(String.format("检测到 %d 个病毒风险，请立即处理受感染文件。", virusErrors));
        }
        
        return recommendations;
    }
}
```

### 3.4 四性报告存储模型

```java
package com.google.refine.extension.fourchar.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.google.refine.model.OverlayModel;
import com.google.refine.model.Project;

public class FourCharacterReport implements OverlayModel {
    
    @JsonProperty("reportId")
    private String reportId;
    
    @JsonProperty("projectId")
    private long projectId;
    
    @JsonProperty("sourceCheckId")
    private String sourceCheckId;
    
    @JsonProperty("generatedAt")
    private long generatedAt;
    
    @JsonProperty("fourCharacterResults")
    private Map<String, FourCharacterResult> fourCharacterResults;
    
    @JsonProperty("overallScore")
    private double overallScore;
    
    @JsonProperty("coverageAnalysis")
    private CoverageAnalysis coverageAnalysis;
    
    @JsonProperty("securityCheckDetails")
    private SecurityCheckDetails securityCheckDetails;
    
    @JsonProperty("recommendations")
    private List<String> recommendations;
    
    public FourCharacterReport() {
        this.fourCharacterResults = new HashMap<>();
        this.recommendations = new ArrayList<>();
        this.generatedAt = System.currentTimeMillis();
    }
    
    public static class FourCharacterResult {
        @JsonProperty("characteristic")
        private String characteristic;
        
        @JsonProperty("score")
        private double score;
        
        @JsonProperty("errorCount")
        private int errorCount;
        
        @JsonProperty("errors")
        private List<CheckError> errors;
        
        @JsonProperty("isCovered")
        private boolean isCovered;
        
        @JsonProperty("checkItems")
        private List<String> checkItems;
        
        public String getCharacteristic() { return characteristic; }
        public void setCharacteristic(String characteristic) { this.characteristic = characteristic; }
        
        public double getScore() { return score; }
        public void setScore(double score) { this.score = score; }
        
        public int getErrorCount() { return errorCount; }
        public void setErrorCount(int errorCount) { this.errorCount = errorCount; }
        
        public List<CheckError> getErrors() { return errors; }
        public void setErrors(List<CheckError> errors) { this.errors = errors; }
        
        public boolean isCovered() { return isCovered; }
        public void setIsCovered(boolean isCovered) { this.isCovered = isCovered; }
        
        public List<String> getCheckItems() { return checkItems; }
        public void setCheckItems(List<String> checkItems) { this.checkItems = checkItems; }
    }
    
    public static class CoverageAnalysis {
        @JsonProperty("totalCharacteristics")
        private int totalCharacteristics = 4;
        
        @JsonProperty("checkedCharacteristics")
        private int checkedCharacteristics;
        
        @JsonProperty("coverageRate")
        private double coverageRate;
        
        @JsonProperty("missingCharacteristics")
        private List<String> missingCharacteristics;
        
        public int getTotalCharacteristics() { return totalCharacteristics; }
        public void setTotalCharacteristics(int totalCharacteristics) { this.totalCharacteristics = totalCharacteristics; }
        
        public int getCheckedCharacteristics() { return checkedCharacteristics; }
        public void setCheckedCharacteristics(int checkedCharacteristics) { this.checkedCharacteristics = checkedCharacteristics; }
        
        public double getCoverageRate() { return coverageRate; }
        public void setCoverageRate(double coverageRate) { this.coverageRate = coverageRate; }
        
        public List<String> getMissingCharacteristics() { return missingCharacteristics; }
        public void setMissingCharacteristics(List<String> missingCharacteristics) { this.missingCharacteristics = missingCharacteristics; }
    }
    
    public static class SecurityCheckDetails {
        @JsonProperty("virusCheckEnabled")
        private boolean virusCheckEnabled;
        
        @JsonProperty("virusErrors")
        private int virusErrors;
        
        @JsonProperty("pathCheckEnabled")
        private boolean pathCheckEnabled;
        
        @JsonProperty("pathTraversalErrors")
        private int pathTraversalErrors;
        
        @JsonProperty("permissionCheckEnabled")
        private boolean permissionCheckEnabled;
        
        @JsonProperty("permissionErrors")
        private int permissionErrors;
        
        @JsonProperty("sensitiveInfoCheckEnabled")
        private boolean sensitiveInfoCheckEnabled;
        
        @JsonProperty("sensitiveInfoErrors")
        private int sensitiveInfoErrors;
        
        public boolean isVirusCheckEnabled() { return virusCheckEnabled; }
        public void setVirusCheckEnabled(boolean virusCheckEnabled) { this.virusCheckEnabled = virusCheckEnabled; }
        
        public boolean isPathCheckEnabled() { return pathCheckEnabled; }
        public void setPathCheckEnabled(boolean pathCheckEnabled) { this.pathCheckEnabled = pathCheckEnabled; }
        
        public boolean isPermissionCheckEnabled() { return permissionCheckEnabled; }
        public void setPermissionCheckEnabled(boolean permissionCheckEnabled) { this.permissionCheckEnabled = permissionCheckEnabled; }
        
        public boolean isSensitiveInfoCheckEnabled() { return sensitiveInfoCheckEnabled; }
        public void setSensitiveInfoCheckEnabled(boolean sensitiveInfoCheckEnabled) { this.sensitiveInfoCheckEnabled = sensitiveInfoCheckEnabled; }
        
        public int getVirusErrors() { return virusErrors; }
        public void setVirusErrors(int virusErrors) { this.virusErrors = virusErrors; }
        
        public int getPathTraversalErrors() { return pathTraversalErrors; }
        public void setPathTraversalErrors(int pathTraversalErrors) { this.pathTraversalErrors = pathTraversalErrors; }
        
        public int getPermissionErrors() { return permissionErrors; }
        public void setPermissionErrors(int permissionErrors) { this.permissionErrors = permissionErrors; }
        
        public int getSensitiveInfoErrors() { return sensitiveInfoErrors; }
        public void setSensitiveInfoErrors(int sensitiveInfoErrors) { this.sensitiveInfoErrors = sensitiveInfoErrors; }
    }
    
    @Override
    public void onBeforeSave(Project project) {}
    @Override
    public void onAfterSave(Project project) {}
    @Override
    public void dispose(Project project) {}
    
    @JsonProperty("reportId")
    public String getReportId() { return reportId; }
    public void setReportId(String reportId) { this.reportId = reportId; }
    
    @JsonProperty("projectId")
    public long getProjectId() { return projectId; }
    public void setProjectId(long projectId) { this.projectId = projectId; }
    
    @JsonProperty("sourceCheckId")
    public String getSourceCheckId() { return sourceCheckId; }
    public void setSourceCheckId(String sourceCheckId) { this.sourceCheckId = sourceCheckId; }
    
    @JsonProperty("generatedAt")
    public long getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(long generatedAt) { this.generatedAt = generatedAt; }
    
    @JsonProperty("fourCharacterResults")
    public Map<String, FourCharacterResult> getFourCharacterResults() { return fourCharacterResults; }
    public void setFourCharacterResults(Map<String, FourCharacterResult> fourCharacterResults) { this.fourCharacterResults = fourCharacterResults; }
    
    @JsonProperty("overallScore")
    public double getOverallScore() { return overallScore; }
    public void setOverallScore(double overallScore) { this.overallScore = overallScore; }
    
    @JsonProperty("coverageAnalysis")
    public CoverageAnalysis getCoverageAnalysis() { return coverageAnalysis; }
    public void setCoverageAnalysis(CoverageAnalysis coverageAnalysis) { this.coverageAnalysis = coverageAnalysis; }
    
    @JsonProperty("securityCheckDetails")
    public SecurityCheckDetails getSecurityCheckDetails() { return securityCheckDetails; }
    public void setSecurityCheckDetails(SecurityCheckDetails securityCheckDetails) { this.securityCheckDetails = securityCheckDetails; }
    
    @JsonProperty("recommendations")
    public List<String> getRecommendations() { return recommendations; }
    public void setRecommendations(List<String> recommendations) { this.recommendations = recommendations; }
}
```

---

## 4. API 设计

### 4.1 API 列表

| 方法 | 路径 | 功能 |
|------|------|------|
| POST | /command/four-character/start-check | 启动四性检测 |
| GET | /command/four-character/get-report | 获取四性检测报告 |
| GET | /command/four-character/get-status | 获取检测状态 |
| POST | /command/four-character/save-report | 保存四性检测报告 |
| GET | /command/four-character/list-reports | 列出项目报告 |

### 4.2 API 详细设计

#### 4.2.1 启动四性检测

```
POST /command/four-character/start-check
```

**请求参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| project | long | 是 | 项目 ID |
| async | boolean | 否 | 是否异步执行，默认 true |
| runDataQualityCheck | boolean | 否 | 是否先执行数据质量检测，默认 true |
| securityCheckEnabled | boolean | 否 | 是否启用安全检测，默认 true |

**响应示例**：

```json
{
  "code": "ok",
  "taskId": "fc-123-1701234567890",
  "async": true,
  "message": "四性检测任务已启动"
}
```

#### 4.2.2 获取四性检测报告

```
GET /command/four-character/get-report?project=123&reportId=fc-xxx
```

**响应示例**：

```json
{
  "code": "ok",
  "hasResult": true,
  "report": {
    "reportId": "fc-123-1701234567890",
    "projectId": 123,
    "sourceCheckId": "qc-123-1701234567000",
    "generatedAt": "2025-01-29T10:00:00",
    "overallScore": 93.75,
    "fourCharacterResults": {
      "真实性": { "score": 95.0, "errorCount": 5, "isCovered": true },
      "完整性": { "score": 88.0, "errorCount": 12, "isCovered": true },
      "可用性": { "score": 92.0, "errorCount": 8, "isCovered": true },
      "安全性": { "score": 100.0, "errorCount": 0, "isCovered": true }
    },
    "coverageAnalysis": { "totalCharacteristics": 4, "checkedCharacteristics": 4, "coverageRate": 1.0 },
    "recommendations": ["完整性得分较低，建议检查文件资源关联配置。"]
  }
}
```

---

## 5. 目录结构

```
extensions/
  four-character-check/
    ├── module/
    │   ├── MOD-INF/
    │   │   ├── controller.js           # 控制器注册
    │   │   ├── module.properties       # 模块配置
    │   │   └── lib/                    # 依赖库
    │   ├── langs/
    │   │   ├── translation-zh.json     # 中文翻译
    │   │   └── translation-en.json     # 英文翻译
    │   ├── scripts/
    │   │   ├── menu-bar-extension.js   # 菜单扩展
    │   │   ├── dialogs/
    │   │   │   ├── four-char-dialog.html
    │   │   │   └── four-char-dialog.js
    │   │   └── four-char-report.js
    │   └── styles/
    │       ├── four-char-dialog.css
    │       └── four-char-report.css
    ├── src/
    │   └── com/
    │       └── google/
    │           └── refine/
    │               └── extension/
    │                   └── fourchar/
    │                       ├── FourCharacterCheckController.java
    │                       ├── reader/
    │                       │   ├── DataReader.java
    │                       │   └── CoverageAnalysis.java
    │                       ├── checker/
    │                       │   ├── SecurityChecker.java
    │                       │   └── VirusScanner.java
    │                       ├── analyzer/
    │                       │   └── FourCharAnalyzer.java
    │                       └── model/
    │                           ├── FourCharacterReport.java
    │                           ├── CheckResult.java
    │                           ├── CheckError.java
    │                           └── SecurityCheckConfig.java
    └── pom.xml
```

---

## 6. pom.xml 配置

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    
    <parent>
        <groupId>org.openrefine</groupId>
        <artifactId>extensions</artifactId>
        <version>3.11.0.01</version>
    </parent>
    
    <groupId>com.google.refine.extension</groupId>
    <artifactId>four-character-check</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>
    
    <name>Four Character Check Extension</name>
    <description>四性检测插件 - 真实性、完整性、可用性、安全性检测</description>
    
    <properties>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <maven.compiler.source>11</maven.compiler.source>
        <maven.compiler.target>11</maven.compiler.target>
    </properties>
    
    <dependencies>
        <dependency>
            <groupId>org.openrefine</groupId>
            <artifactId>main</artifactId>
            <version>3.11.0.01</version>
            <scope>provided</scope>
        </dependency>
        
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <version>2.15.2</version>
            <scope>provided</scope>
        </dependency>
        
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
            <version>2.0.9</version>
            <scope>provided</scope>
        </dependency>
    </dependencies>
    
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.11.0</version>
                <configuration>
                    <source>11</source>
                    <target>11</target>
                </configuration>
            </plugin>
            
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <version>3.3.0</version>
                <configuration>
                    <archive>
                        <manifest>
                            <mainClass>com.google.refine.extension.fourchar.FourCharacterCheckController</mainClass>
                        </manifest>
                    </archive>
                </configuration>
            </plugin>
            
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-assembly-plugin</artifactId>
                <version>3.6.0</version>
                <configuration>
                    <descriptors>
                        <descriptor>src/assembly/extension.xml</descriptor>
                    </descriptors>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

---

## 7. 安全性考虑

### 7.1 病毒检测集成

插件支持集成外部病毒扫描引擎：

```java
// 可选的病毒扫描器接口
public interface VirusScanner {
    ScanResult scan(String filePath);
    String getVersion();
}

// ClamAV 集成示例
public class ClamAVScanner implements VirusScanner {
    private String socketPath;
    private int timeout;
    
    @Override
    public ScanResult scan(String filePath) {
        // 通过 ClamAV socket 进行扫描
        // 返回扫描结果
    }
    
    @Override
    public String getVersion() {
        return "ClamAV " + getVersionFromSocket();
    }
}
```

### 7.2 敏感信息脱敏

检测到的敏感信息在报告中自动脱敏：

| 敏感类型 | 原始示例 | 脱敏后 |
|----------|----------|--------|
| 身份证号 | 310101199001011234 | 310**************34 |
| 手机号 | 13812345678 | 138****5678 |
| 银行卡号 | 6222021234567890123 | ************0123 |
| 密码/密钥 | mySecretPassword | **** |
| 邮箱 | zhangsan@example.com | zha***@***.com |

---

## 8. 总结

### 8.1 方案优势

| 优势 | 说明 |
|------|------|
| ✅ 零侵入 | 不修改现有 data-quality 插件 |
| ✅ 职责清晰 | 现有插件专注数据质量，四性插件专注四性分析 |
| ✅ 松耦合 | 通过公开 HTTP API 通信 |
| ✅ 可扩展 | 安全检测模块支持按需启用 |
| ✅ 独立存储 | 四性报告独立存储，不影响现有结构 |

### 8.2 四性映射关系

| 四性 | 数据来源 | 检测项 |
|------|----------|--------|
| 真实性 | data-quality | 格式检查、内容比对 |
| 完整性 | data-quality | 资源检查、元数据比对 |
| 可用性 | data-quality + AIMP | 可读性检查、AIMP服务 |
| 安全性 | four-character-check | 病毒检测、路径安全、权限检查、敏感信息检测 |

### 8.3 后续工作

1. 实现完整的控制器和命令类
2. 实现前端 UI 界面
3. 添加报告导出功能（PDF/HTML）
4. 集成病毒扫描引擎
5. 编写单元测试和集成测试
6. 完善错误处理和日志记录
