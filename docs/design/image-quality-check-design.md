# OpenRefine 图像质量检查功能概要设计

## 1. 需求概述

### 1.1 功能目标
在 OpenRefine 数据质量模块中增加图像质量检查功能，支持对图像字段进行质量检测，与现有数据质量规则体系集成，实现规则配置、结果展示、持久化和导出的统一管理。

### 1.2 核心需求
1. 在质量规则中增加图像质量检查 Tab，用于配置图像质量规则
2. 图像质量检查规则对应 hegui 系统的图像审查规则
3. 质量规则持久化，与其他质量规则合并保存
4. 审查结果与其他数据质量规则审查结果合并，包括界面结果呈现、持久化、导出

### 1.3 范围界定
- **本期实现**：图像质量规则配置、数据模型设计、检查结果展示集成
- **后续实现**：后端 AI 审查服务集成、运行检查对话框优化

---

## 2. 系统架构

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                     OpenRefine Data Quality Module              │
├─────────────────────────────────────────────────────────────────┤
│  前端层 (module/scripts/)                                       │
│  ├── dialogs/manage-rules-dialog.js     ← 新增图像质量Tab      │
│  ├── index/data-quality-ui.js           ← 结果展示增强          │
│  └── styles/data-quality.css            ← 新增图像结果样式       │
├─────────────────────────────────────────────────────────────────┤
│  后端层 (src/com/google/refine/extension/quality/)             │
│  ├── model/                                                     │
│  │   ├── QualityRulesConfig.java       ← 新增imageRules        │
│  │   ├── ImageQualityRule.java         ← 新增图像质量规则模型   │
│  │   ├── ImageCheckConfig.java         ← 新增检查配置模型       │
│  │   └── CheckResult.java              ← 新增图像检查结果       │
│  ├── checker/                                                   │
│  │   ├── ImageQualityChecker.java      ← 新增图像质量检查器    │
│  │   ├── FormatChecker.java            ← 现有（保持）          │
│  │   ├── ResourceChecker.java          ← 现有（保持）          │
│  │   └── ContentChecker.java           ← 现有（保持）          │
│  ├── task/                                                      │
│  │   └── QualityCheckTask.java         ← 支持图像检查进度      │
│  └── commands/                                                  │
│       ├── RunQualityCheckCommand.java  ← 支持图像检查          │
│       └── ... (现有命令增强)                                     │
├─────────────────────────────────────────────────────────────────┤
│  外部集成 (后续实现)                                             │
│  ├── AI图像审查服务集成                 ← Hegui后端调用          │
│  └── 结果导出增强                       ← 包含图像检查结果       │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 与现有模块关系

```
QualityCheckTask (任务管理)
    │
    ├── FormatChecker    ← 格式检查
    ├── ResourceChecker  ← 资源检查
    ├── ContentChecker   ← 内容比较检查
    └── ImageQualityChecker  ← 图像质量检查 (新增)
```

---

## 3. 数据模型设计

### 3.1 图像质量规则 (ImageQualityRule)

```java
public class ImageQualityRule {
    private String id;                    // 规则唯一标识
    private CheckStandard standard;       // 检查标准 (SIMPLE/STANDARD/COMPLEX)
    private boolean enabled;              // 是否启用
    
    // 图像质量检查配置
    private List<ImageCheckCategory> categories;  // 检查类别列表
    
    // 检查标准枚举
    public enum CheckStandard {
        SIMPLE("简单", "基础图像质量检查"),
        STANDARD("标准", "标准图像质量检查"),
        COMPLEX("全面", "全面图像质量检查")
    }
    
    // 检查类别（基于hegui实际分类）
    public static class ImageCheckCategory {
        private CategoryType type;              // 类别类型
        private boolean enabled;                // 是否启用
        private List<ImageCheckItem> items;     // 检查项目列表
        private Map<String, Object> settings;   // 类别设置
    }
    
    // 类别类型枚举（基于档案四性检测标准）
    // 说明：按照档案"四性检测"标准进行分类
    public enum CategoryType {
        USABILITY("可用性", "检测文件可读性、格式兼容性、呈现正确性"),
        INTEGRITY("完整性", "检测内容齐全、无缺失、数据完整"),
        UNIQUENESS("唯一性", "检测关联正确、序列连续、防重复"),
        SECURITY("安全性", "检测参数合规、存储可靠、环境安全")
    }
    
    // 检查项目
    public static class ImageCheckItem {
        private String itemCode;           // 项目代码
        private String itemName;           // 项目名称
        private boolean enabled;           // 是否启用
        private Map<String, Object> parameters;  // 检查参数
    }
}
```

### 3.2 预置检查标准

基于 hegui filesDispose.tsx 中的规则分类，预置三套检查标准：

#### 3.2.1 简单 (SIMPLE)

```json
{
  "standard": "SIMPLE",
  "categories": [
    {
      "type": "USABILITY",
      "enabled": true,
      "items": [
        {
          "itemCode": "format",
          "itemName": "文件格式检查",
          "enabled": true,
          "parameters": {
            "formatList": ["jpeg", "jpg", "tiff", "pdf"]
          }
        },
        {
          "itemCode": "imageQuality",
          "itemName": "图像质量检查",
          "enabled": true,
          "parameters": {
            "minCompressionRatio": 80
          }
        }
      ]
    },
    {
      "type": "INTEGRITY",
      "enabled": true,
      "items": [
        {
          "itemCode": "blank",
          "itemName": "空白图片审查",
          "enabled": true,
          "parameters": {}
        }
      ]
    },
    {
      "type": "UNIQUENESS",
      "enabled": true,
      "items": [
        {
          "itemCode": "repeatImage",
          "itemName": "重复图片审查",
          "enabled": true,
          "parameters": {}
        }
      ]
    },
    {
      "type": "SECURITY",
      "enabled": false,
      "items": [
        {
          "itemCode": "dpi",
          "itemName": "DPI检查",
          "enabled": true,
          "parameters": {
            "dpiValue": 300
          }
        }
      ]
    }
  ]
}
```

#### 3.2.2 标准 (STANDARD)

```json
{
  "standard": "STANDARD",
  "categories": [
    {
      "type": "USABILITY",
      "enabled": true,
      "items": [
        {
          "itemCode": "format",
          "itemName": "文件格式检查",
          "enabled": true,
          "parameters": {
            "formatList": ["jpeg", "jpg", "tiff", "tif", "pdf"]
          }
        },
        {
          "itemCode": "imageQuality",
          "itemName": "图像质量检查",
          "enabled": true,
          "parameters": {
            "minCompressionRatio": 80
          }
        },
        {
          "itemCode": "houseAngle",
          "itemName": "文本方向",
          "enabled": true,
          "parameters": {}
        },
        {
          "itemCode": "bias",
          "itemName": "倾斜",
          "enabled": true,
          "parameters": {
            "rectify": "0.5"
          }
        },
        {
          "itemCode": "edgeRemove",
          "itemName": "黑边",
          "enabled": true,
          "parameters": {
            "edgeStrict": 0
          }
        }
      ]
    },
    {
      "type": "INTEGRITY",
      "enabled": true,
      "items": [
        {
          "itemCode": "blank",
          "itemName": "空白图片审查",
          "enabled": true,
          "parameters": {}
        },
        {
          "itemCode": "quantity",
          "itemName": "数量统计",
          "enabled": true,
          "parameters": {}
        }
      ]
    },
    {
      "type": "UNIQUENESS",
      "enabled": true,
      "items": [
        {
          "itemCode": "repeatImage",
          "itemName": "重复图片审查",
          "enabled": true,
          "parameters": {}
        }
      ]
    },
    {
      "type": "SECURITY",
      "enabled": true,
      "items": [
        {
          "itemCode": "dpi",
          "itemName": "DPI检查",
          "enabled": true,
          "parameters": {
            "dpiValue": 300
          }
        },
        {
          "itemCode": "kb",
          "itemName": "KB值检查",
          "enabled": true,
          "parameters": {
            "minKB": 10,
            "maxKB": 1000
          }
        }
      ]
    }
  ]
}
```

#### 3.2.3 全面 (COMPLEX)

```json
{
  "standard": "COMPLEX",
  "categories": [
    {
      "type": "USABILITY",
      "enabled": true,
      "items": [
        {
          "itemCode": "format",
          "itemName": "文件格式检查",
          "enabled": true,
          "parameters": {
            "formatList": ["jpeg", "jpg", "tiff", "tif", "pdf"]
          }
        },
        {
          "itemCode": "imageQuality",
          "itemName": "图像质量检查",
          "enabled": true,
          "parameters": {
            "minCompressionRatio": 80
          }
        },
        {
          "itemCode": "houseAngle",
          "itemName": "文本方向",
          "enabled": true,
          "parameters": {}
        },
        {
          "itemCode": "bias",
          "itemName": "倾斜",
          "enabled": true,
          "parameters": {
            "rectify": "0.5"
          }
        },
        {
          "itemCode": "edgeRemove",
          "itemName": "黑边",
          "enabled": true,
          "parameters": {
            "edgeStrict": 1
          }
        },
        {
          "itemCode": "stain",
          "itemName": "污点",
          "enabled": true,
          "parameters": {
            "stainValue": 10
          }
        },
        {
          "itemCode": "bindingHole",
          "itemName": "装订孔",
          "enabled": true,
          "parameters": {
            "hole": 1
          }
        }
      ]
    },
    {
      "type": "INTEGRITY",
      "enabled": true,
      "items": [
        {
          "itemCode": "blank",
          "itemName": "空白图片审查",
          "enabled": true,
          "parameters": {}
        },
        {
          "itemCode": "quantity",
          "itemName": "数量统计",
          "enabled": true,
          "parameters": {}
        },
        {
          "itemCode": "pageCount",
          "itemName": "篇幅统计",
          "enabled": true,
          "parameters": {}
        },
        {
          "itemCode": "emptyFolder",
          "itemName": "空文件夹检查",
          "enabled": true,
          "parameters": {}
        }
      ]
    },
    {
      "type": "UNIQUENESS",
      "enabled": true,
      "items": [
        {
          "itemCode": "repeatImage",
          "itemName": "重复图片审查",
          "enabled": true,
          "parameters": {}
        },
        {
          "itemCode": "pieceContinuous",
          "itemName": "件号连续性检查",
          "enabled": true,
          "parameters": {}
        },
        {
          "itemCode": "pageContinuous",
          "itemName": "图片页号连续性检查",
          "enabled": true,
          "parameters": {}
        }
      ]
    },
    {
      "type": "SECURITY",
      "enabled": true,
      "items": [
        {
          "itemCode": "dpi",
          "itemName": "DPI检查",
          "enabled": true,
          "parameters": {
            "dpiValue": 300
          }
        },
        {
          "itemCode": "kb",
          "itemName": "KB值检查",
          "enabled": true,
          "parameters": {
            "minKB": 10,
            "maxKB": 1000
          }
        }
      ]
    }
  ]
}
```
          "enabled": true,
          "parameters": {}
        },
        {
          "itemCode": "continuity",
          "itemName": "页号连续性",
          "enabled": true,
          "parameters": {}
        }
      ]
    },
    {
      "type": "PDF_QUALITY",
      "enabled": true,
      "items": [
        {
          "itemCode": "pdfImageUniformity",
          "itemName": "PDF与图片一致性",
          "enabled": true,
          "parameters": {}
        },
        {
          "itemCode": "ofdUniformity",
          "itemName": "OFD一致性",
          "enabled": true,
          "parameters": {}
        }
      ]
    }
  ]
}
```

### 3.3 图像检查配置 (ImageCheckConfig)

```java
public class ImageCheckConfig {
    private String imageColumn;           // 图像所在列
    private String sourceColumn;          // 源数据列（用于匹配）
    private String referenceColumn;       // 参考字段列
    
    // 批处理配置
    private int batchSize;                // 批处理大小，默认10
    private int timeoutSeconds;           // 超时时间，默认60
    
    // 结果映射
    private String errorCodeColumn;       // 错误代码列
    private String errorMsgColumn;        // 错误消息列
    private String qualityScoreColumn;    // 质量评分列
}
```

### 3.4 增强的 QualityRulesConfig

```java
public class QualityRulesConfig implements OverlayModel {
    // 现有字段
    private Map<String, FormatRule> formatRules;
    private ResourceCheckConfig resourceConfig;
    private List<ContentComparisonRule> contentRules;
    private AimpConfig aimpConfig;
    
    // 新增字段
    private Map<String, ImageQualityRule> imageRules;
    private ImageCheckConfig imageCheckConfig;
}
```

### 3.5 增强的 CheckResult

```java
public class CheckResult implements OverlayModel {
    // 现有字段
    private int totalRows;
    private int checkedRows;
    private int passedRows;
    private int failedRows;
    private List<CheckError> errors;
    private String checkType;
    private long startTime;
    private long endTime;
    
    // 新增字段
    private List<ImageCheckError> imageErrors;
    
    // 图像检查错误详情
    public static class ImageCheckError extends CheckError {
        private String imagePath;           // 图像路径
        private String categoryType;        // 类别类型
        private String itemCode;            // 检查项目代码
        private String itemName;            // 检查项目名称
        private double qualityScore;        // 质量评分
        private String aiModel;             // 使用的AI模型
        private Map<String, Object> details; // 详细检查结果
    }
}
```

---

## 4. 前端界面设计

### 4.1 规则管理对话框新增图像质量 Tab

```
┌─────────────────────────────────────────────────────────────────┐
│  质量规则管理                                      [×]          │
├───────────┬─────────────┬─────────────────┬────────────────────┤
│ 格式规则  │ 资源规则    │ 内容规则        │ 图像质量           │
├───────────┼─────────────┼─────────────────┼────────────────────┤
│                                                                │
│  图像质量规则列表                                            │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │ 检查标准: (○ 简单  ● 标准  ○ 全面)                      │  │
│  ├─────────────────────────────────────────────────────────┤  │
│  │ ☑ 可用性 (USABILITY)                                    │  │
│  │   ├☑ 破损文件检查                          [参数: 无]  │  │
│  │   ├☑ 空白图片审查                          [参数: 无]  │  │
│  │   ├☑ 文件格式检查    支持格式: [jpeg,jpg,tiff,tif,pdf] │  │
│  │   ├☑ 图像质量检查    最低压缩比: [80_]      (0-100)    │  │
│  │   ├☑ 文本方向检测                          [参数: 无]  │  │
│  │   ├☑ 倾斜检测        倾斜角容错: [0.5_]°   (度)        │  │
│  │   └☑ 黑边检测        检测模式: (●宽松 ○严格)           │  │
│  │                                                        │  │
│  │ ☑ 完整性 (INTEGRITY)                                   │  │
│  │   ├☑ 数量统计                            [参数: 无]  │  │
│  │   ├☑ 篇幅统计                            [参数: 无]  │  │
│  │   └☑ 空文件夹检查                        [参数: 无]  │  │
│  │                                                        │  │
│  │ ☑ 唯一性 (UNIQUENESS)                                  │  │
│  │   ├☑ 重复图片审查                          [参数: 无]  │  │
│  │   ├☑ 件号连续性检查                        [参数: 无]  │  │
│  │   └☑ 图片页号连续性检查                    [参数: 无]  │  │
│  │                                                        │  │
│  │ ☐ 安全性 (SECURITY)                                    │  │
│  │   ├☑ DPI检查         最低DPI值: [300_]                │  │
│  │   └☑ KB值检查        最小: [10_KB_] 最大: [1000_KB_]  │  │
│  │                                                        │  │
│  │ ☐ 污点/装订孔 (仅全面标准)                             │  │
│  │   ├☑ 污点检测       污点容错: [10_] 个                 │  │
│  │   └☑ 装订孔检测     装订孔容错: [1_] 个                │  │
│  └─────────────────────────────────────────────────────────┘  │
│                                                           │
│ ──────────────────────────────────────────────────────── │
│  资源定位方式                                              │
│                                                           │
│  [未设置 - 请先在"文件资源关联检查"tab中配置资源定位方式]  │
│  [前往设置 >>]                                            │
│                                                           │
└─────────────────────────────────────────────────────────────────┘
[保存规则]
```

### 4.2 检查标准选择说明

| 标准 | 包含类别 | 启用检测项 | 适用场景 |
|------|----------|------------|----------|
| 简单 | 可用性、完整性、唯一性 | 破损文件、空白图片、文件格式、图像质量、文本方向、倾斜、黑边、数量统计、重复图片 | 快速筛查基础问题 |
| 标准 | 可用性、完整性、唯一性、安全性 | 简单+安全性(DPI、KB值) | 一般业务场景 |
| 全面 | 全部类别 | 全部检测项+污点检测+装订孔检测 | 高精度要求场景 |

### 4.3 类别与项目配置

#### 4.3.1 可用性检查类别

| 项目代码 | 项目名称 | 默认参数 | 参数说明 |
|----------|----------|----------|----------|
| damage | 破损文件检查(可读性) | 无 | 检测文件是否可读、是否有损坏 |
| blank | 空白图片审查 | 无 | 检测图片是否为空白或全黑/全白 |
| format | 文件格式检查 | formatList: ["jpeg","jpg","tiff","tif","pdf"] | 支持的文件格式列表 |
| imageQuality | 图像质量检查 | minCompressionRatio: 80 | 最低图像压缩比(0-100) |
| houseAngle | 文本方向检测 | 无 | 检测文字方向是否正确 |
| bias | 倾斜检测 | rectify: 0.5 | 倾斜角容错值(度)，超过此值报错 |
| edgeRemove | 黑边检测 | edgeStrict: 0 | 检测模式(0=宽松/1=严格) |

#### 4.3.2 完整性检查类别

| 项目代码 | 项目名称 | 默认参数 | 参数说明 |
|----------|----------|----------|----------|
| blank | 空白图片审查 | 无 | 检测内容是否齐全、是否有空白 |
| quantity | 数量统计 | 无 | 统计文件数量是否符合预期 |
| pageCount | 篇幅统计 | 无 | 统计页数是否连续完整 |
| emptyFolder | 空文件夹检查 | 无 | 检测是否存在空文件夹 |

#### 4.3.3 唯一性检查类别

| 项目代码 | 项目名称 | 默认参数 | 参数说明 |
|----------|----------|----------|----------|
| repeatImage | 重复图片审查 | 无 | 检测是否存在重复图片 |
| pieceContinuous | 件号连续性检查 | 无 | 检测件号是否连续 |
| pageContinuous | 图片页号连续性检查 | 无 | 检测页号是否连续 |

#### 4.3.4 安全性检查类别

| 项目代码 | 项目名称 | 默认参数 | 参数说明 |
|----------|----------|----------|----------|
| dpi | DPI检查 | dpiValue: 300 | 最低DPI值要求 |
| kb | KB值检查 | minKB: 10, maxKB: 1000 | 文件大小范围(KB) |

#### 4.3.5 全面检查额外项目（仅COMPLEX标准启用）

| 项目代码 | 项目名称 | 默认参数 | 参数说明 |
|----------|----------|----------|----------|
| stain | 污点检测 | stainValue: 10 | 污点容错数量(个)，超过此值报错 |
| bindingHole | 装订孔检测 | hole: 1 | 装订孔容错数量(个)，超过此值报错 |

### 4.4 检查结果展示增强

#### 4.4.1 检查摘要区域增强

```
┌─────────────────────────────────────────────────────┐
│ 检查摘要                                            │
├─────────────────────────────────────────────────────┤
│ 总行数: 1,000    已检查: 1,000    通过: 950         │
│ 格式错误: 20     资源错误: 5      内容错误: 15       │
│ 图像质量问题: 10  ← 新增                              │
└─────────────────────────────────────────────────────┘
```

#### 4.4.2 筛选选项增强

```
筛选: [分类 ▼] [错误类型 ▼] 搜索: [________]

分类选项（按档案四性）:
├── 全部
├── 图像质量问题
├── 可用性问题 (USABILITY)
├── 完整性问题 (INTEGRITY)
├── 唯一性问题 (UNIQUENESS)
└── 安全性问题 (SECURITY)

错误类型选项（按检测项）:
├── 全部
├── 破损文件检查
├── 空白图片审查
├── 文件格式检查
├── 图像质量检查
├── 文本方向检测
├── 倾斜检测
├── 黑边检测
├── 污点检测
├── 装订孔检测
├── 数量统计
├── 篇幅统计
├── 空文件夹检查
├── 重复图片审查
├── 件号连续性检查
├── 图片页号连续性检查
├── DPI检查
└── KB值检查
```

#### 4.4.3 质量结果表格增强

```
┌─────────────────────────────────────────────────────────────┐
│ 行   │ 列名        │ 错误类型      │ 错误信息     │ 操作  │
├──────┼─────────────┼───────────────┼──────────────┼───────┤
│  15  │ img_url     │ 图像质量问题 │ 空白图片     │ [查看]│
│  23  │ img_url     │ 图像质量问题 │ 重复图片     │ [查看]│
│  156 │ img_url     │ 图像质量问题 │ 图像倾斜超限 │ [查看]│
│  412 │ img_url     │ 图像质量问题 │ 黑边检测     │ [查看]│
└─────────────────────────────────────────────────────────────┘
```

---

## 5. 后端实现设计

### 5.1 新增类结构

```
src/com/google/refine/extension/quality/
├── model/
│   ├── ImageQualityRule.java         # 图像质量规则模型
│   ├── ImageCheckConfig.java         # 图像检查配置模型
│   └── ImageCheckError.java          # 图像检查错误模型
├── checker/
│   ├── ImageQualityChecker.java      # 图像质量检查器
│   └── ImageQualityUtil.java         # 图像处理工具类
└── commands/
    └── TestImageRuleCommand.java     # 测试规则命令（可选）
```

### 5.2 ImageQualityChecker 核心逻辑

```java
public class ImageQualityChecker {
    
    private ImageQualityRule rule;
    private ImageCheckConfig config;
    
    public CheckResult check(Project project, List<Row> rows) {
        CheckResult result = new CheckResult("IMAGE_QUALITY");
        List<ImageCheckError> imageErrors = new ArrayList<>();
        
        int processedRows = 0;
        for (Row row : rows) {
            String imagePath = getImagePath(row, config.getImageColumn());
            if (imagePath == null) {
                processedRows++;
                continue;
            }
            
            List<ImageCheckError> rowErrors = checkImage(imagePath, rule);
            imageErrors.addAll(rowErrors);
            processedRows++;
            
            // 更新检查进度
            updateProgress(processedRows, rows.size());
        }
        
        result.setImageErrors(imageErrors);
        result.setCheckedRows(rows.size());
        result.setPassedRows(rows.size() - imageErrors.size());
        result.setFailedRows(imageErrors.size());
        result.complete();
        
        return result;
    }
    
    private List<ImageCheckError> checkImage(String imagePath, ImageQualityRule rule) {
        List<ImageCheckError> errors = new ArrayList<>();
        
        for (ImageQualityRule.ImageCheckCategory category : rule.getCategories()) {
            if (!category.isEnabled()) continue;
            
            for (ImageQualityRule.ImageCheckItem item : category.getItems()) {
                if (!item.isEnabled()) continue;
                
                CheckResult itemResult = executeCheck(
                    imagePath, 
                    category.getType(), 
                    item.getItemCode(),
                    item.getParameters()
                );
                
                if (!itemResult.isPassed()) {
                    ImageCheckError error = new ImageCheckError();
                    error.setImagePath(imagePath);
                    error.setCategoryType(category.getType().name());
                    error.setItemCode(item.getItemCode());
                    error.setItemName(item.getItemName());
                    error.setMessage(itemResult.getErrorMessage());
                    error.setDetails(itemResult.getDetails());
                    errors.add(error);
                }
            }
        }
        
        return errors;
    }
    
    private CheckResult executeCheck(String imagePath, 
                                     ImageQualityRule.CategoryType category,
                                     String itemCode,
                                     Map<String, Object> parameters) {
        switch (category) {
            case USABILITY:
                return checkUsability(imagePath, itemCode, parameters);
            case UNIQUENESS:
                return checkUniqueness(imagePath, itemCode, parameters);
            case ANGLE:
                return checkAngle(imagePath, itemCode, parameters);
            case FLAW:
                return checkFlaw(imagePath, itemCode, parameters);
            case OTHER:
                return checkOther(imagePath, itemCode, parameters);
            case PDF_QUALITY:
                return checkPdfQuality(imagePath, itemCode, parameters);
            default:
                return CheckResult.passed();
        }
    }
    
    private CheckResult checkUsability(String imagePath, String itemCode, 
                                       Map<String, Object> parameters);
    private CheckResult checkUniqueness(String imagePath, String itemCode, 
                                        Map<String, Object> parameters);
    private CheckResult checkAngle(String imagePath, String itemCode, 
                                   Map<String, Object> parameters);
    private CheckResult checkFlaw(String imagePath, String itemCode, 
                                  Map<String, Object> parameters);
    private CheckResult checkOther(String imagePath, String itemCode, 
                                   Map<String, Object> parameters);
    private CheckResult checkPdfQuality(String imagePath, String itemCode, 
                                        Map<String, Object> parameters);
}
```

---

## 6. 实施计划

### Phase 1: 基础框架搭建
1. 创建 ImageQualityRule 数据模型
2. 创建 ImageCheckConfig 数据模型
3. 创建 ImageCheckError 数据模型
4. 修改 QualityRulesConfig 添加图像规则字段
5. 修改 CheckResult 添加图像检查结果字段

### Phase 2: 检查器实现
1. 创建 ImageQualityChecker 基础框架
2. 实现可用性检查逻辑（破损文件、空白图片、格式检查）
3. 实现唯一性检查逻辑（重复图片）
4. 实现角度检查逻辑（文本方向、倾斜）
5. 实现瑕疵检查逻辑（黑边、污点、装订孔）
6. 实现其他检查逻辑（DPI、大小、连续性、路径）
7. 实现PDF文件质量检查逻辑（PDF图片一致性、OFD一致性）

### Phase 3: 前端界面开发
1. 新增图像质量规则 Tab
2. 实现检查标准选择器
3. 实现类别与项目配置界面
4. 增强检查结果摘要
5. 增强筛选选项
6. 增强结果表格

### Phase 4: 结果与导出
1. 完善结果持久化
2. 增强导出报告功能
3. 集成测试与 Bug 修复

### 后续优化（标记为后续实现）
1. 运行检查对话框图像检查选项优化
2. AI 审查服务集成
3. 批量处理性能优化
4. 自定义规则配置界面

---

## 7. 与现有功能集成

### 7.1 规则持久化
- **存储位置**: 随 QualityRulesConfig 一起保存到项目 overlay
- **JSON 格式**: 兼容现有结构，新增 imageRules 和 imageCheckConfig 字段
- **版本兼容**: 添加版本号字段，支持向后兼容

### 7.2 结果持久化
- **存储位置**: 随 CheckResult 一起保存
- **导出**: 增强 ExportQualityReportCommand 支持图像检查结果
- **UI 展示**: 增强 data-quality-ui.js 的渲染逻辑

### 7.3 任务管理
- **进度追踪**: QualityCheckTask 支持图像检查进度
- **状态管理**: 支持 PAUSED/CANCELLED 操作
- **断点续检**: 实现 Checkpoint 机制

---

## 8. 文件清单

### 8.1 新增文件

| 文件路径 | 描述 |
|----------|------|
| `extensions/data-quality/src/.../model/ImageQualityRule.java` | 图像质量规则模型 |
| `extensions/data-quality/src/.../model/ImageCheckConfig.java` | 图像检查配置模型 |
| `extensions/data-quality/src/.../model/ImageCheckError.java` | 图像检查错误模型 |
| `extensions/data-quality/src/.../checker/ImageQualityChecker.java` | 图像质量检查器 |
| `extensions/data-quality/src/.../checker/ImageQualityUtil.java` | 图像处理工具类 |
| `extensions/data-quality/src/.../commands/TestImageRuleCommand.java` | 测试规则命令（可选） |
| `extensions/data-quality/module/scripts/dialogs/image-quality-tab.js` | 图像质量规则Tab |

### 8.2 修改文件

| 文件路径 | 修改内容 |
|----------|----------|
| `extensions/data-quality/src/.../model/QualityRulesConfig.java` | 新增 imageRules 和 imageCheckConfig 字段 |
| `extensions/data-quality/src/.../model/CheckResult.java` | 新增 imageErrors 字段和 ImageCheckError 类 |
| `extensions/data-quality/src/.../task/QualityCheckTask.java` | 支持图像检查进度追踪 |
| `extensions/data-quality/src/.../commands/RunQualityCheckCommand.java` | 支持图像检查 |
| `extensions/data-quality/module/scripts/dialogs/manage-rules-dialog.js` | 新增图像质量Tab |
| `extensions/data-quality/module/scripts/index/data-quality-ui.js` | 结果展示增强 |

---

## 9. 错误类型定义

### 9.1 图像检查错误类型

| 错误类型 | 错误代码 | 描述 |
|----------|----------|------|
| 破损文件 | IMAGE_DAMAGE | 文件无法正常读取或已损坏 |
| 空白图片 | IMAGE_BLANK | 图片内容为空或近乎空白 |
| 格式错误 | IMAGE_FORMAT | 文件格式不符合要求 |
| 重复图片 | IMAGE_DUPLICATE | 检测到重复的图片内容 |
| 文本方向错误 | IMAGE_DIRECTION | 文本方向检测异常 |
| 图像倾斜 | IMAGE_SKEW | 图像倾斜角度超过限制 |
| 黑边检测 | IMAGE_EDGE | 检测到图像边缘存在黑边 |
| 污点检测 | IMAGE_STAIN | 检测到图像存在污点或噪点 |
| 装订孔检测 | IMAGE_HOLE | 检测到装订孔痕迹 |
| DPI不足 | IMAGE_DPI | 图像DPI值低于要求 |
| 大小异常 | IMAGE_SIZE | 图像文件大小超出范围 |
| 连续性错误 | IMAGE_SEQUENCE | 页号或件号连续性检查失败 |
| 路径错误 | IMAGE_PATH | 图像路径无效或无法访问 |
| PDF图片不一致 | IMAGE_PDF_MISMATCH | PDF与对应图片内容不一致 |
| OFD不一致 | IMAGE_OFD_MISMATCH | OFD与对应图片内容不一致 |

### 9.2 错误等级

| 等级 | 说明 |
|------|------|
| ERROR | 严重问题，需要处理 |
| WARNING | 警告，可能需要处理 |
| INFO | 提示信息 |

---

## 10. 验收标准

1. 规则配置界面包含图像质量 Tab，可选择检查标准
2. 三套预置规则（简单/标准/全面）可正常应用
3. 图像检查结果可与格式、资源、内容检查结果合并展示
4. 检查摘要包含图像问题统计
5. 筛选功能支持按图像错误类型筛选
6. 规则配置可保存和加载
7. 检查结果可导出（CSV/Excel）

---

## 11. 风险与注意事项

1. **图像访问权限**: 确保检查器有权限访问项目中的图像文件
2. **大图像处理**: 考虑实现图像缩放和分块处理，避免内存溢出
3. **AI 服务依赖**: 当前版本使用规则检测，后续 AI 服务集成需保持接口兼容
4. **性能影响**: 图像检查可能较耗时，需优化批处理和缓存策略

---

## 13. AI 服务接口规范

### 13.1 接口设计原则

1. **接口隔离**: AI 检测与规则检测使用统一的抽象接口，便于切换
2. **模型可插拔**: 支持多种 AI 模型接入，通过配置选择
3. **结果标准化**: AI 检测结果与规则检测结果格式一致
4. **向后兼容**: 当前规则检测结果可无缝对接 AI 检测结果

---

**⚠️ 重要说明**: 以下接口定义基于对 Hegui 源代码的实际分析，确保与现有系统完全兼容。

---

### 13.2 Hegui AI 服务接口

根据 Hegui 源代码分析 (`AiApi.java`)，AI 服务提供以下 RESTful 接口：

| 接口 | 方法 | 功能 | URL 模式 |
|------|------|------|---------|
| 开启接口 | POST | 创建审核任务 | `{aiServer}/alot/chek` |
| 检测接口 | POST | 图像检测及参数传递 | `{aiServer}/alot/chek/inspect` |
| 重启接口 | POST | 重启 AI 后台服务 | `{aimpServer}/admin/reload` |

#### 13.2.1 开启审核任务接口

```
POST {aiServer}/alot/chek?blank=true&house_angle=true&rectify=true&edge_remove=true&stain=true&hole=true&dpi=true&format=true&kb=true&page_size=false&bit_depth=false
```

**Query Parameters**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| blank | Boolean | 否 | 空白图片审查，默认开启 |
| house_angle | Boolean | 否 | 文本方向检测 |
| rectify | Boolean | 否 | 倾斜检测 |
| edge_remove | Boolean | 否 | 黑边检测 |
| stain | Boolean | 否 | 污点检测 |
| hole | Boolean | 否 | 装订孔检测 |
| dpi | Boolean | 否 | DPI检查 |
| format | Boolean | 否 | 格式检查 |
| kb | Boolean | 否 | KB值检查 |
| page_size | Boolean | 否 | 篇幅统计 |
| bit_depth | Boolean | 否 | 位深度检查（固定为 false） |

#### 13.2.2 图像检测接口

```
POST {aiServer}/alot/chek/inspect?set_sensitivity=3&set_angle=0.5&set_stain=10&set_hole=1&set_dpi=300&set_format=.jpeg&set_format=.tiff&set_kb=10&max_kb=1000&set_quality=80&edge_strict=0&tolerance=0.5
```

**请求头**:
```
Content-Type: multipart/form-data
```

**Query Parameters**（检测参数）:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| set_sensitivity | Integer | 是 | 灵敏度，默认 3 |
| set_angle | String | 否 | 倾斜角容错值，如 "0.5" |
| set_stain | Integer | 否 | 污点容错值，如 10 |
| set_hole | Integer | 否 | 装订孔容错值，如 1 |
| set_dpi | String | 否 | DPI值，如 "300" |
| set_format | String | 否 | 支持的格式，重复参数，如 ".jpeg", ".tiff", ".pdf" |
| set_kb | Integer | 否 | KB最小值，如 10 |
| max_kb | Integer | 否 | KB最大值，如 1000 |
| set_quality | Integer | 否 | 图像质量评分，如 80 |
| edge_strict | Integer | 否 | 黑边检测模式：0=宽松(默认)，1=严格 |
| tolerance | Float | 否 | 篇幅容差：0.1（JFJ定制），0.5（标准） |
| blank_level | Integer | 否 | 空白检测增强模式：0=普通，1=增强（JFJ定制） |

**请求体**: 图像文件（二进制）

**响应格式**:
```json
{
  "rectify": 0.5,
  "house_angle": 90,
  "edge_remove": [[100, 200, 50, 50]],
  "stain": [[300, 400, 30, 30]],
  "hole": [[500, 100, 20, 20]],
  "dpi": 300,
  "bit_depth": 24,
  "format": true,
  "kb": 256,
  "page_size": "A4",
  "blank": false,
  "quality": 85
}
```

### 13.3 AI 检测请求参数

基于 Hegui 源代码 `CheckImageHtmlResultParam.java`：

```java
@Data
public class CheckImageHtmlResultParam {
    // ========== 可用性类别 ==========
    private Boolean blank;           // 空白图片审查
    private Boolean repeatImage;     // 重复图片审查
    private Boolean houseAngle;      // 文本方向
    private Boolean bias;            // 倾斜
    private String rectify;          // 倾斜角容错
    private Boolean edgeRemove;      // 黑边
    private Integer edgeStrict;      // 黑边检测模式: 0=宽松(默认), 1=严格
    private Boolean stain;           // 污点检测
    private Integer stainValue;      // 污点容错值
    private Boolean bindingHole;     // 装订孔检测
    private Integer hole;            // 装订孔容错值
    private Boolean format;          // 格式检查
    private String[] formatList;     // 格式列表: 'JPEG', 'TIFF', 'PDF', 'GIF', 'RAW', 'BMP', 'FPX', 'PNG'
    private Boolean imageQuality;    // 图像质量检查
    private Integer imageScore;      // 图像质量评分

    // ========== 完整性类别 ==========
    private Boolean counting;        // 数量统计
    private Boolean pageSize;        // 篇幅统计
    private Boolean blankFilesCheck; // 空文件夹检查

    // ========== 唯一性类别 ==========
    private Boolean excelImageAccord;    // 条目数量图片一致性检查
    private Boolean pieceContinuous;     // 件号连续性检查
    private Boolean continuity;          // 图片页号连续性检查

    // ========== 安全性类别 ==========
    private Boolean dpi;             // DPI检查
    private String dpiValue;         // DPI值
    private Boolean kb;              // KB值检查
    private Integer minKB;           // KB最小值
    private Integer maxKB;           // KB最大值

    // ========== 其他 ==========
    private String reImageName;      // 图片名正则
    private String reImagePath;      // 路径正则
    private String[] pdfCheck;       // PDF文件质量审查
    private Boolean pdfImageUniformity;  // PDF图像与图片一致性
    private Boolean ofdUniformity;       // OFD一致性
}
```

### 13.4 AI 检测响应结果

基于 Hegui 源代码 `CheckImageAiResultDTO.java`：

```java
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CheckImageAiResultDTO {
    // 倾斜检测结果（角度值）
    @JsonProperty("rectify")
    private Float rectify;

    // 文本方向（度数）
    @JsonProperty("house_angle")
    private Integer houseAngle;

    // 黑边检测结果（坐标数组）
    @JsonProperty("edge_remove")
    private List<Integer[]> edgeRemove;

    // 污点检测结果（坐标数组）
    @JsonProperty("stain")
    private List<Integer[]> stain;

    // 装订孔检测结果（坐标数组）
    @JsonProperty("hole")
    private List<Integer[]> hole;

    // DPI检测结果
    @JsonProperty("dpi")
    private Integer dpi;

    // 位深度
    @JsonProperty("bitDepth")
    private Integer bitDepth;

    // 格式检查结果
    @JsonProperty("format")
    private Boolean format;

    // KB值检测结果
    @JsonProperty("kb")
    private Integer kb;

    // 篇幅检查结果
    @JsonProperty("page_size")
    private String pageSize;

    // 空白检查结果
    @JsonProperty("blank")
    private Boolean blank;

    // 图像质量检查结果
    @JsonProperty("quality")
    private Integer quality;
}
```

### 13.5 错误类型与 AI 结果字段映射

| 错误类型 | 错误代码 | AI响应字段 | 返回值类型 |
|----------|----------|-----------|-----------|
| 文本方向错误 | IMAGE_DIRECTION | house_angle | Integer（角度值） |
| 图像倾斜 | IMAGE_SKEW | rectify | Float（角度值） |
| 黑边检测 | IMAGE_EDGE | edge_remove | List<Integer[]>（坐标数组） |
| 污点检测 | IMAGE_STAIN | stain | List<Integer[]>（坐标数组） |
| 装订孔检测 | IMAGE_HOLE | hole | List<Integer[]>（坐标数组） |
| DPI不足 | IMAGE_DPI | dpi | Integer |
| 格式错误 | IMAGE_FORMAT | format | Boolean |
| 大小异常 | IMAGE_SIZE | kb | Integer |
| 篇幅检查 | IMAGE_PAGE_SIZE | page_size | String |
| 空白图片 | IMAGE_BLANK | blank | Boolean |
| 图像质量 | IMAGE_QUALITY | quality | Integer |

**坐标数组格式**: `Integer[x, y, width, height]` 表示检测到的区域位置和大小

### 13.6 OpenRefine 适配器接口设计

为保持与 Hegui AI 服务的兼容，设计以下适配器接口：

```java
/**
 * Hegui AI 图像检测服务适配器
 */
@Component
public class HeguiImageAIDetector implements ImageQualityDetector {

    @Value("${hegui.ai.server:http://localhost:8000}")
    private String aiServer;

    @Value("${hegui.aimp.server:http://localhost:8081}")
    private String aimpServer;

    @Autowired
    private RestTemplate restTemplate;

    /**
     * 开启审核任务
     */
    public boolean openTask(CheckImageHtmlResultParam param) {
        String url = aiServer + "/alot/chek" + buildOpenQueryParams(param);
        try {
            restTemplate.postForEntity(url, null, Void.class);
            return true;
        } catch (Exception e) {
            logger.error("开启审核任务失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 检测图像
     */
    public CheckImageAiResultDTO detect(File image, CheckImageHtmlResultParam param) {
        String url = aiServer + "/alot/chek/inspect" + buildDetectQueryParams(param);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTART_FORM_DATA);
            HttpEntity<File> entity = new HttpEntity<>(image, headers);
            ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.POST, entity, String.class);
            return JsonHelper.fromJson(response.getBody(), CheckImageAiResultDTO.class);
        } catch (Exception e) {
            logger.error("图像检测失败: {}", e.getMessage());
            return new CheckImageAiResultDTO();
        }
    }

    /**
     * 重启 AI 服务
     */
    public void reloadAIService() {
        try {
            restTemplate.postForEntity(aimpServer + "/admin/reload", null, Void.class);
        } catch (Exception e) {
            logger.error("重启 AI 服务失败: {}", e.getMessage());
        }
    }
}
```

### 13.7 统一检测接口

```java
/**
 * 图像质量检测器接口
 * 统一规则检测和 AI 检测的抽象接口
 */
public interface ImageQualityDetector {
    
    /**
     * 检测图像质量
     * @param imagePath 图像路径
     * @param rule 质量规则
     * @return 检测结果
     */
    ImageQualityResult detect(String imagePath, ImageQualityRule rule);
    
    /**
     * 获取检测器类型
     * @return 检测器类型 (RULE_BASED 或 AI_BASED)
     */
    DetectorType getType();
    
    /**
     * 检查检测器是否可用
     * @return 可用状态
     */
    boolean isAvailable();
}

/**
 * 统一的检测结果
 */
public class ImageQualityResult {
    private String imageId;
    private boolean success;
    private List<ImageCheckError> errors;
    private double qualityScore;
    private DetectionMethod method;  // RULE_BASED 或 AI_BASED
    private String detectorId;       // 检测器标识
    private long processingTimeMs;
    private CheckImageAiResultDTO aiRawResult;  // AI 原始结果（AI检测时）
}

/**
 * 检测方法枚举
 */
public enum DetectionMethod {
    RULE_BASED("规则检测"),
    AI_BASED("AI检测"),
    HYBRID("混合检测");
    
    private String displayName;
}
```

### 13.8 配置参数定义

#### 13.8.1 AI 服务全局配置

```json
{
  "hegui": {
    "ai": {
      "server": "http://localhost:8000",
      "timeoutSeconds": 30,
      "maxRetryCount": 3
    },
    "aimp": {
      "server": "http://localhost:8081"
    }
  }
}
```

#### 13.8.2 检测参数默认值

| 参数 | 默认值 | 可选值 | 说明 |
|------|--------|--------|------|
| sensitivity | 3 | 1-5 | 检测灵敏度 |
| angle | 0.5 | 0.1-5.0 | 倾斜角容错（度） |
| stainValue | 10 | 1-100 | 污点容错（个） |
| holeValue | 1 | 0-10 | 装订孔容错（个） |
| dpiValue | 300 | 72-1200 | DPI最低值 |
| qualityScore | 80 | 0-100 | 图像质量评分 |
| edgeStrict | 0 | 0,1 | 黑边模式：0=宽松，1=严格 |
| tolerance | 0.5 | 0.1, 0.5 | 篇幅容差 |

### 13.9 接口兼容性设计

#### 13.9.1 版本演进策略

1. **向后兼容**: 新增字段不影响旧版本解析
2. **渐进式降级**: AI 服务不可用时自动切换到规则检测
3. **功能标志**: 通过配置控制 AI 功能启用/禁用

#### 13.9.2 响应格式版本

```java
public class APIResponse<T> {
    private int version;              // API 版本号
    private boolean success;
    private T data;                  // 响应数据
    private ErrorInfo error;         // 错误信息
    private MetaInfo meta;           // 元信息（处理时间、请求ID等）
}

public class MetaInfo {
    private String requestId;        // 请求唯一标识
    private long processingTimeMs;   // 处理时间
    private String apiVersion;       // API 版本
    private Map<String, String> warnings;  // 警告信息
}
```

### 13.10 集成步骤

1. **Phase 1**: 实现 Hegui AI 服务适配器（复用现有接口）
2. **Phase 2**: 实现统一检测器接口
3. **Phase 3**: 添加 AI 结果到统一结果格式
4. **Phase 4**: 添加 AI 服务健康检查
5. **Phase 5**: 支持备用 AI 模型切换

### 13.11 参考资料

- `AiApi.java`: AI 服务接口定义
- `CheckImageAiResultDTO.java`: AI 检测结果定义
- `CheckImageHtmlResultParam.java`: AI 检测参数定义
- `AiClient.java`: AI 客户端实现（包含 URL 构建逻辑）

---

## 12. 附录

### 12.1 术语对照表

| 术语 | 说明 |
|------|------|
| 检查标准 | 预定义的规则集合，包括简单、标准、全面三套 |
| 检查类别 | 检查维度分类，如可用性、唯一性、角度、瑕疵等 |
| 检查项目 | 具体检查项，如破损文件检查、空白图片、文件格式、重复图片、文本方向、倾斜、黑边、污点等 |
| 图像列 | 包含图像路径或图像数据的列 |
| 可用性类别 | 检测文件可读性、格式、空白等基础可用性问题的类别 |
| 唯一性类别 | 检测重复图片等唯一性问题的类别 |
| AI 检测服务 | 集成外部 AI 能力进行图像质量检测的后端服务 |
| 检测器类型 | 区分 RULE_BASED（规则检测）和 AI_BASED（AI检测） |
| 坐标数组 | AI 检测返回的区域坐标，格式为 `[x, y, width, height]` |

### 12.2 参考资料
- Hegui 规则配置模块实现
- OpenRefine 数据质量模块现有实现
- 图像质量评估相关标准

---

## 14. 开发计划

本开发计划采用自底向上的开发策略，从基础数据模型开始，逐步构建完整功能。计划分为五个主要阶段，每个阶段包含具体的开发任务、文件清单和验收标准。

### 14.1 开发阶段总览

| 阶段 | 阶段名称 | 主要目标 | 预计工作量 |
|------|----------|----------|-----------|
| Phase 1 | 基础模型层 | 定义核心数据模型和枚举类型 | 3-4 天 |
| Phase 2 | 后端检查器 | 实现图像质量检查核心逻辑 | 4-5 天 |
| Phase 3 | 前端界面 | 实现规则配置和结果展示 UI | 4-5 天 |
| Phase 4 | 模块集成 | 与现有质量模块无缝集成 | 2-3 天 |
| Phase 5 | AI 服务集成 | 对接 Hegui AI 审查服务 | 3-4 天 |
| **合计** | - | **完整功能交付** | **16-21 天** |

---

### 14.2 Phase 1：基础模型层

**目标**：定义图像质量检查功能所需的核心数据模型、枚举类型和配置类。

#### 14.2.1 开发任务清单

| 序号 | 任务描述 | 涉及文件 | 优先级 |
|------|----------|----------|--------|
| 1.1 | 定义检查类别枚举（CategoryType） | 新建 ImageCheckCategory.java | 高 |
| 1.2 | 定义检查项目枚举（CheckItem） | 新建 ImageCheckItem.java | 高 |
| 1.3 | 定义检查标准枚举（CheckStandard） | 新建 ImageCheckStandard.java | 高 |
| 1.4 | 定义图像质量规则主类 | 新建 ImageQualityRule.java | 高 |
| 1.5 | 定义检查配置类 | 新建 ImageCheckConfig.java | 高 |
| 1.6 | 定义检查结果类 | 新建 ImageCheckResult.java | 高 |
| 1.7 | 定义检查错误详情类 | 新建 ImageCheckError.java | 高 |
| 1.8 | 定义 AI 检测请求和响应类 | 新建 AiDetectionModels.java | 中 |
| 1.9 | 增强 QualityRulesConfig | 修改 QualityRulesConfig.java | 高 |
| 1.10 | 增强 CheckResult | 修改 CheckResult.java | 高 |

#### 14.2.2 核心类定义

**ImageQualityRule.java**（新建）：
```java
package com.google.refine.quality.model;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

public class ImageQualityRule implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String id;
    private ImageCheckStandard standard;
    private boolean enabled;
    private List<ImageCheckCategory> categories;
    private Map<String, Object> customSettings;
    private long createdAt;
    private long updatedAt;
    
    // 内部类：检查类别
    public static class ImageCheckCategory implements Serializable {
        private CategoryType type;
        private boolean enabled;
        private List<ImageCheckItem> items;
        private Map<String, Object> settings;
    }
    
    // 内部类：检查项目
    public static class ImageCheckItem implements Serializable {
        private String itemCode;
        private String itemName;
        private boolean enabled;
        private Map<String, Object> parameters;
    }
}
```

**ImageCheckCategory.java**（新建）：
```java
package com.google.refine.quality.model;

public enum ImageCheckCategory {
    USABILITY("可用性", "检测文件可读性、格式兼容性、呈现正确性"),
    INTEGRITY("完整性", "检测内容齐全、无缺失、数据完整"),
    UNIQUENESS("唯一性", "检测关联正确、序列连续、防重复"),
    SECURITY("安全性", "检测参数合规、存储可靠、环境安全"),
    PDF_QUALITY("PDF质量", "PDF文件专项质量检查");
    
    private final String displayName;
    private final String description;
}
```

**ImageCheckItem.java**（新建）：
```java
package com.google.refine.quality.model;

public enum ImageCheckItem {
    // 可用性类别
    DAMAGE("破损文件检查", CategoryType.USABILITY),
    BLANK("空白图片审查", CategoryType.USABILITY),
    FORMAT("文件格式检查", CategoryType.USABILITY),
    IMAGE_QUALITY("图像质量检查", CategoryType.USABILITY),
    HOUSE_ANGLE("文本方向检测", CategoryType.USABILITY),
    BIAS("倾斜检测", CategoryType.USABILITY),
    EDGE_REMOVE("黑边检测", CategoryType.USABILITY),
    STAIN("污点检测", CategoryType.USABILITY),
    BINDING_HOLE("装订孔检测", CategoryType.USABILITY),
    
    // 完整性类别
    QUANTITY("数量统计", CategoryType.INTEGRITY),
    PAGE_COUNT("篇幅统计", CategoryType.INTEGRITY),
    EMPTY_FOLDER("空文件夹检查", CategoryType.INTEGRITY),
    
    // 唯一性类别
    repeatimage("重复图片审查", CategoryType.UNIQUENESS),
    PIECE_CONTINUOUS("件号连续性检查", CategoryType.UNIQUENESS),
    PAGE_CONTINUOUS("图片页号连续性检查", CategoryType.UNIQUENESS),
    
    // 安全性类别
    DPI("分辨率检查", CategoryType.SECURITY),
    KB("KB值检查", CategoryType.SECURITY),
    
    // PDF质量类别
    PDF_IMAGE_UNIFORMITY("PDF与图片一致性", CategoryType.PDF_QUALITY);
    
    private final String displayName;
    private final ImageCheckCategory category;
}
```

**ImageCheckResult.java**（新建）：
```java
package com.google.refine.quality.result;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

public class ImageCheckResult implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String imageId;
    private String imagePath;
    private boolean passed;
    private double qualityScore;
    private List<ImageCheckError> errors;
    private Map<String, Object> rawAiResult;
    private long processingTimeMs;
}
```

**ImageCheckError.java**（新建）：
```java
package com.google.refine.quality.result;

import java.io.Serializable;

public class ImageCheckError implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String errorCode;
    private String errorType;
    private String categoryType;
    private String itemCode;
    private String itemName;
    private String message;
    private double confidence;
    private Integer[] boundingBox;
    private Map<String, Object> details;
}
```

#### 14.2.3 文件清单

| 文件路径 | 类型 | 说明 |
|----------|------|------|
| `extensions/quality/src/com/google/refine/quality/model/ImageCheckCategory.java` | 新建 | 检查类别枚举 |
| `extensions/quality/src/com/google/refine/quality/model/ImageCheckItem.java` | 新建 | 检查项目枚举 |
| `extensions/quality/src/com/google/refine/quality/model/ImageCheckStandard.java` | 新建 | 检查标准枚举 |
| `extensions/quality/src/com/google/refine/quality/model/ImageQualityRule.java` | 新建 | 图像质量规则主类 |
| `extensions/quality/src/com/google/refine/quality/model/ImageCheckConfig.java` | 新建 | 检查配置类 |
| `extensions/quality/src/com/google/refine/quality/result/ImageCheckResult.java` | 新建 | 检查结果类 |
| `extensions/quality/src/com/google/refine/quality/result/ImageCheckError.java` | 新建 | 检查错误详情类 |
| `extensions/quality/src/com/google/refine/quality/ai/AiDetectionModels.java` | 新建 | AI 检测相关模型 |

#### 14.2.4 验收标准

- [ ] 所有枚举类型定义完整，包含中英文描述
- [ ] 数据模型支持 JSON 序列化和反序列化
- [ ] 与现有 QualityRulesConfig 兼容
- [ ] 提供无参构造函数和全参构造函数
- [ ] 编写单元测试覆盖所有枚举值和模型字段

---

### 14.3 Phase 2：后端检查器实现

**目标**：实现图像质量检查的核心业务逻辑，包括规则解析、图像处理和结果生成。

#### 14.3.1 开发任务清单

| 序号 | 任务描述 | 涉及文件 | 优先级 |
|------|----------|----------|--------|
| 2.1 | 创建图像质量检查器主类 | 新建 ImageQualityChecker.java | 高 |
| 2.2 | 实现格式检查逻辑 | 新建 FormatImageChecker.java | 高 |
| 2.3 | 实现 DPI 检查逻辑 | 新建 DpiImageChecker.java | 高 |
| 2.4 | 实现 KB 值检查逻辑 | 新建 SizeImageChecker.java | 高 |
| 2.5 | 实现图像质量评分逻辑 | 新建 QualityImageChecker.java | 高 |
| 2.6 | 实现检查结果聚合器 | 新建 ImageResultAggregator.java | 中 |
| 2.7 | 增强 RunQualityCheckCommand | 修改 RunQualityCheckCommand.java | 高 |
| 2.8 | 增强 QualityCheckTask | 修改 QualityCheckTask.java | 高 |

#### 14.3.2 核心类实现

**ImageQualityChecker.java**（新建）：
```java
package com.google.refine.quality.checker;

import com.google.refine.quality.model.ImageQualityRule;
import com.google.refine.quality.result.ImageCheckResult;
import com.google.refine.quality.config.ImageQualityCheckerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ImageQualityChecker implements QualityChecker {
    private static final Logger logger = LoggerFactory.getLogger(ImageQualityChecker.class);
    
    private ImageQualityRule rule;
    private List<ImageChecker> checkers;
    
    @Override
    public void initialize(QualityRule rule) {
        this.rule = (ImageQualityRule) rule;
        this.checkers = createCheckers(rule);
    }
    
    @Override
    public List<ImageCheckResult> check(Project project, List<Row> rows) {
        List<ImageCheckResult> results = new ArrayList<>();
        for (Row row : rows) {
            ImageCheckResult result = checkRow(row);
            if (result != null) {
                results.add(result);
            }
        }
        return results;
    }
    
    private ImageCheckResult checkRow(Row row) {
        // 获取图像路径
        String imagePath = getImagePath(row);
        if (imagePath == null) {
            return createEmptyResult(row);
        }
        
        ImageCheckResult result = new ImageCheckResult();
        result.setImageId(row.getCell(project.getColumnIndex(imageColumn)).getValue().toString());
        result.setImagePath(imagePath);
        
        // 执行各类检查
        for (ImageChecker checker : checkers) {
            if (checker.isEnabled(rule)) {
                ImageCheckError error = checker.check(imagePath, rule);
                if (error != null) {
                    result.getErrors().add(error);
                }
            }
        }
        
        // 计算质量评分
        result.setQualityScore(calculateQualityScore(result));
        result.setPassed(result.getErrors().isEmpty());
        
        return result;
    }
    
    private List<ImageChecker> createCheckers(ImageQualityRule rule) {
        return Arrays.asList(
            new FormatImageChecker(),
            new DpiImageChecker(),
            new SizeImageChecker(),
            new QualityImageChecker(),
            new BlankImageChecker(),
            new StainImageChecker(),
            new HoleImageChecker(),
            new SkewImageChecker(),
            new EdgeImageChecker()
        );
    }
}
```

**FormatImageChecker.java**（新建）：
```java
package com.google.refine.quality.checker;

import com.google.refine.quality.model.ImageQualityRule;
import com.google.refine.quality.result.ImageCheckError;

public class FormatImageChecker implements ImageChecker {
    private static final Set<String> SUPPORTED_FORMATS = 
        Set.of("jpeg", "jpg", "tiff", "tif", "png", "pdf");
    
    @Override
    public ImageCheckError check(String imagePath, ImageQualityRule rule) {
        String extension = getFileExtension(imagePath).toLowerCase();
        if (!SUPPORTED_FORMATS.contains(extension)) {
            return createError(
                ImageCheckItem.FORMAT,
                "不支持的文件格式: " + extension
            );
        }
        
        List<String> allowedFormats = rule.getFormats();
        if (allowedFormats != null && !allowedFormats.isEmpty()) {
            if (!allowedFormats.contains(extension)) {
                return createError(
                    ImageCheckItem.FORMAT,
                    "文件格式不在允许列表中: " + String.join(", ", allowedFormats)
                );
            }
        }
        
        return null;
    }
    
    @Override
    public boolean isEnabled(ImageQualityRule rule) {
        return rule.isItemEnabled(ImageCheckItem.FORMAT);
    }
}
```

#### 14.3.3 文件清单

| 文件路径 | 类型 | 说明 |
|----------|------|------|
| `extensions/quality/src/com/google/refine/quality/checker/ImageQualityChecker.java` | 新建 | 检查器主类 |
| `extensions/quality/src/com/google/refine/quality/checker/FormatImageChecker.java` | 新建 | 格式检查器 |
| `extensions/quality/src/com/google/refine/quality/checker/DpiImageChecker.java` | 新建 | DPI 检查器 |
| `extensions/quality/src/com/google/refine/quality/checker/SizeImageChecker.java` | 新建 | 大小检查器 |
| `extensions/quality/src/com/google/refine/quality/checker/QualityImageChecker.java` | 新建 | 质量检查器 |
| `extensions/quality/src/com/google/refine/quality/checker/BlankImageChecker.java` | 新建 | 空白检查器 |
| `extensions/quality/src/com/google/refine/quality/checker/StainImageChecker.java` | 新建 | 污点检查器 |
| `extensions/quality/src/com/google/refine/quality/checker/HoleImageChecker.java` | 新建 | 装订孔检查器 |
| `extensions/quality/src/com/google/refine/quality/checker/SkewImageChecker.java` | 新建 | 倾斜检查器 |
| `extensions/quality/src/com/google/refine/quality/checker/EdgeImageChecker.java` | 新建 | 黑边检查器 |
| `extensions/quality/src/com/google/refine/quality/checker/ImageResultAggregator.java` | 新建 | 结果聚合器 |

#### 14.3.4 验收标准

- [ ] ImageQualityChecker 正确加载和解析规则配置
- [ ] 各检查器正确识别并报告对应问题
- [ ] 检查器支持启用/禁用配置
- [ ] 检查结果包含完整的错误信息
- [ ] 性能：单张图像检查耗时小于 500ms
- [ ] 编写单元测试覆盖所有检查器

---

### 14.4 Phase 3：前端界面实现

**目标**：实现规则配置界面、结果展示界面和用户交互逻辑。

#### 14.4.1 开发任务清单

| 序号 | 任务描述 | 涉及文件 | 优先级 |
|------|----------|----------|--------|
| 3.1 | 创建图像质量 Tab UI 组件 | 新建 image-quality-tab.html | 高 |
| 3.2 | 实现 Tab 交互逻辑 | 新建 image-quality-tab.js | 高 |
| 3.3 | 实现规则配置面板 | 新建 image-quality-config.js | 高 |
| 3.4 | 实现检查结果展示 | 修改 data-quality-results.js | 高 |
| 3.5 | 添加图像结果样式 | 修改 data-quality.css | 高 |
| 3.6 | 添加国际化资源 | 修改 i18nstrings.properties | 中 |
| 3.7 | 集成到规则管理对话框 | 修改 manage-rules-dialog.js | 高 |

#### 14.4.2 核心 UI 组件

**image-quality-tab.html**（新建）：
```html
<div class="image-quality-tab">
    <div class="tab-header">
        <h3>图像质量规则</h3>
    </div>
    
    <div class="standard-selector">
        <label>
            <input type="radio" name="checkStandard" value="SIMPLE" />
            <span>简单</span>
        </label>
        <label>
            <input type="radio" name="checkStandard" value="STANDARD" checked />
            <span>标准</span>
        </label>
        <label>
            <input type="radio" name="checkStandard" value="COMPLEX" />
            <span>全面</span>
        </label>
    </div>
    
    <div class="categories-container">
        <!-- 可用性类别 -->
        <div class="category-section" data-category="USABILITY">
            <div class="category-header">
                <input type="checkbox" class="category-toggle" checked />
                <span class="category-name">可用性</span>
                <span class="category-desc">检测文件可读性、格式兼容性、呈现正确性</span>
            </div>
            <div class="items-container">
                <!-- 检查项目列表动态生成 -->
            </div>
        </div>
        
        <!-- 完整性类别 -->
        <div class="category-section" data-category="INTEGRITY">
            <div class="category-header">
                <input type="checkbox" class="category-toggle" checked />
                <span class="category-name">完整性</span>
                <span class="category-desc">检测内容齐全、无缺失、数据完整</span>
            </div>
            <div class="items-container"></div>
        </div>
        
        <!-- 唯一性类别 -->
        <div class="category-section" data-category="UNIQUENESS">
            <div class="category-header">
                <input type="checkbox" class="category-toggle" checked />
                <span class="category-name">唯一性</span>
                <span class="category-desc">检测关联正确、序列连续、防重复</span>
            </div>
            <div class="items-container"></div>
        </div>
        
        <!-- 安全性类别 -->
        <div class="category-section" data-category="SECURITY">
            <div class="category-header">
                <input type="checkbox" class="category-toggle" />
                <span class="category-name">安全性</span>
                <span class="category-desc">检测参数合规、存储可靠、环境安全</span>
            </div>
            <div class="items-container"></div>
        </div>
    </div>
    
    <div class="resource-config">
        <label>资源定位方式</label>
        <div class="resource-status">
            <span class="status-text">未设置</span>
            <a href="#" class="go-to-config">前往设置</a>
        </div>
    </div>
</div>
```

**image-quality-tab.js**（新建）：
```javascript
// 图像质量 Tab 控制器
function ImageQualityTab() {
    this.currentStandard = 'STANDARD';
    this.categories = [];
}

ImageQualityTab.prototype.render = function(container) {
    this.container = container;
    this.loadRuleConfig();
    this.renderUI();
};

ImageQualityTab.prototype.loadRuleConfig = function() {
    var ruleConfig = QualityRulesManager.getImageQualityRule();
    if (ruleConfig) {
        this.currentStandard = ruleConfig.standard || 'STANDARD';
        this.categories = ruleConfig.categories || [];
    } else {
        this.categories = this.getDefaultCategories();
    }
};

ImageQualityTab.prototype.renderUI = function() {
    this.container.html(this.getTemplate());
    this.bindEvents();
    this.renderCategoryItems();
};

ImageQualityTab.prototype.getTemplate = function() {
    return `
        <div class="image-quality-tab">
            <div class="standard-selector">
                ${this.renderStandardOptions()}
            </div>
            <div class="categories-container">
                ${this.renderCategories()}
            </div>
            <div class="resource-config">
                ${this.renderResourceConfig()}
            </div>
        </div>
    `;
};

ImageQualityTab.prototype.renderStandardOptions = function() {
    var standards = [
        { value: 'SIMPLE', label: '简单' },
        { value: 'STANDARD', label: '标准' },
        { value: 'COMPLEX', label: '全面' }
    ];
    
    return standards.map(s => `
        <label class="radio-label">
            <input type="radio" name="checkStandard" 
                   value="${s.value}" 
                   ${this.currentStandard === s.value ? 'checked' : ''} />
            <span>${s.label}</span>
        </label>
    `).join('');
};

ImageQualityTab.prototype.renderCategories = function() {
    var self = this;
    var categoryMap = {
        'USABILITY': { name: '可用性', desc: '检测文件可读性、格式兼容性、呈现正确性' },
        'INTEGRITY': { name: '完整性', desc: '检测内容齐全、无缺失、数据完整' },
        'UNIQUENESS': { name: '唯一性', desc: '检测关联正确、序列连续、防重复' },
        'SECURITY': { name: '安全性', desc: '检测参数合规、存储可靠、环境安全' }
    };
    
    return Object.keys(categoryMap).map(catKey => {
        var cat = categoryMap[catKey];
        var items = self.categories.filter(c => c.type === catKey);
        var isEnabled = items.length > 0 && items[0].enabled;
        
        return `
            <div class="category-section" data-category="${catKey}">
                <div class="category-header">
                    <input type="checkbox" class="category-toggle" 
                           ${isEnabled ? 'checked' : ''} />
                    <span class="category-name">${cat.name}</span>
                    <span class="category-desc">${cat.desc}</span>
                </div>
                <div class="items-container">
                    ${self.renderItems(catKey, items)}
                </div>
            </div>
        `;
    }).join('');
};

ImageQualityTab.prototype.renderItems = function(categoryType, categories) {
    var self = this;
    var itemMap = this.getItemMap();
    
    if (!categories || categories.length === 0) {
        return '';
    }
    
    var items = categories[0].items || [];
    return items.map(item => {
        var itemConfig = itemMap[item.itemCode] || {};
        return self.renderItem(categoryType, item, itemConfig);
    }).join('');
};

ImageQualityTab.prototype.renderItem = function(categoryType, item, itemConfig) {
    var paramsHtml = this.renderItemParameters(item, itemConfig);
    
    return `
        <div class="check-item" data-item-code="${item.itemCode}">
            <label class="item-label">
                <input type="checkbox" class="item-toggle" 
                       ${item.enabled ? 'checked' : ''} />
                <span>${item.itemName}</span>
            </label>
            <div class="item-params">
                ${paramsHtml}
            </div>
        </div>
    `;
};

ImageQualityTab.prototype.renderItemParameters = function(item, itemConfig) {
    var params = item.parameters || {};
    
    // 根据项目类型渲染不同参数控件
    switch (item.itemCode) {
        case 'format':
            return this.renderFormatParams(params);
        case 'dpi':
            return this.renderDpiParams(params);
        case 'kb':
            return this.renderKbParams(params);
        case 'bias':
            return this.renderSkewParams(params);
        case 'stain':
            return this.renderStainParams(params);
        case 'bindingHole':
            return this.renderHoleParams(params);
        case 'imageQuality':
            return this.renderQualityParams(params);
        case 'edgeRemove':
            return this.renderEdgeParams(params);
        default:
            return '';
    }
};

ImageQualityTab.prototype.renderFormatParams = function(params) {
    var formats = params.formatList || ['jpeg', 'jpg', 'tiff', 'tif', 'pdf'];
    var formatOptions = ['jpeg', 'jpg', 'tiff', 'tif', 'pdf', 'png'];
    
    return `
        <div class="param-group">
            <label>支持格式:</label>
            <div class="checkbox-group">
                ${formatOptions.map(f => `
                    <label class="checkbox-label">
                        <input type="checkbox" name="format" value="${f}"
                               ${formats.includes(f) ? 'checked' : ''} />
                        <span>${f.toUpperCase()}</span>
                    </label>
                `).join('')}
            </div>
        </div>
    `;
};

ImageQualityTab.prototype.renderDpiParams = function(params) {
    return `
        <div class="param-group">
            <label>最低DPI值:</label>
            <input type="number" class="param-input dpi-value" 
                   value="${params.dpiValue || 300}" min="72" max="1200" />
        </div>
    `;
};

ImageQualityTab.prototype.renderKbParams = function(params) {
    return `
        <div class="param-group">
            <label>大小范围:</label>
            <input type="number" class="param-input min-kb" 
                   value="${params.minKB || 10}" placeholder="最小" />
            <span> - </span>
            <input type="number" class="param-input max-kb" 
                   value="${params.maxKB || 1000}" placeholder="最大" />
            <span>KB</span>
        </div>
    `;
};

ImageQualityTab.prototype.renderSkewParams = function(params) {
    return `
        <div class="param-group">
            <label>倾斜角容错:</label>
            <input type="number" class="param-input skew-value" 
                   value="${params.rectify || 0.5}" step="0.1" min="0.1" max="5.0" />
            <span>度</span>
        </div>
    `;
};

ImageQualityTab.prototype.renderStainParams = function(params) {
    return `
        <div class="param-group">
            <label>污点容错:</label>
            <input type="number" class="param-input stain-value" 
                   value="${params.stainValue || 10}" min="1" max="100" />
            <span>个</span>
        </div>
    `;
};

ImageQualityTab.prototype.renderHoleParams = function(params) {
    return `
        <div class="param-group">
            <label>装订孔容错:</label>
            <input type="number" class="param-input hole-value" 
                   value="${params.hole || 1}" min="0" max="10" />
            <span>个</span>
        </div>
    `;
};

ImageQualityTab.prototype.renderQualityParams = function(params) {
    return `
        <div class="param-group">
            <label>最低压缩比:</label>
            <input type="range" class="param-input quality-value" 
                   value="${params.minCompressionRatio || 80}" min="0" max="100" />
            <span class="quality-display">${params.minCompressionRatio || 80}%</span>
        </div>
    `;
};

ImageQualityTab.prototype.renderEdgeParams = function(params) {
    return `
        <div class="param-group">
            <label>检测模式:</label>
            <div class="radio-group">
                <label class="radio-label">
                    <input type="radio" name="edgeMode" value="0"
                           ${(params.edgeStrict || 0) === 0 ? 'checked' : ''} />
                    <span>宽松</span>
                </label>
                <label class="radio-label">
                    <input type="radio" name="edgeMode" value="1"
                           ${params.edgeStrict === 1 ? 'checked' : ''} />
                    <span>严格</span>
                </label>
            </div>
        </div>
    `;
};

ImageQualityTab.prototype.renderResourceConfig = function() {
    var resourceConfig = QualityRulesManager.getResourceConfig();
    var isConfigured = resourceConfig && resourceConfig.imageColumn;
    
    if (isConfigured) {
        return `
            <div class="resource-config configured">
                <label>资源定位方式:</label>
                <span class="resource-column">${resourceConfig.imageColumn}</span>
            </div>
        `;
    } else {
        return `
            <div class="resource-config not-configured">
                <label>资源定位方式:</label>
                <span class="warning-text">未设置 - 请先在"文件资源关联检查"tab中配置</span>
                <a href="#" class="go-to-config">前往设置</a>
            </div>
        `;
    }
};

ImageQualityTab.prototype.getItemMap = function() {
    return {
        'damage': { name: '破损文件检查', category: 'USABILITY' },
        'blank': { name: '空白图片审查', category: 'USABILITY' },
        'format': { name: '文件格式检查', category: 'USABILITY' },
        'imageQuality': { name: '图像质量检查', category: 'USABILITY' },
        'houseAngle': { name: '文本方向检测', category: 'USABILITY' },
        'bias': { name: '倾斜检测', category: 'USABILITY' },
        'edgeRemove': { name: '黑边检测', category: 'USABILITY' },
        'stain': { name: '污点检测', category: 'USABILITY' },
        'bindingHole': { name: '装订孔检测', category: 'USABILITY' },
        'quantity': { name: '数量统计', category: 'INTEGRITY' },
        'pageCount': { name: '篇幅统计', category: 'INTEGRITY' },
        'emptyFolder': { name: '空文件夹检查', category: 'INTEGRITY' },
        'repeatImage': { name: '重复图片审查', category: 'UNIQUENESS' },
        'pieceContinuous': { name: '件号连续性检查', category: 'UNIQUENESS' },
        'pageContinuous': { name: '图片页号连续性检查', category: 'UNIQUENESS' },
        'dpi': { name: '分辨率检查', category: 'SECURITY' },
        'kb': { name: 'KB值检查', category: 'SECURITY' }
    };
};

ImageQualityTab.prototype.getDefaultCategories = function() {
    return this.getStandardCategories('STANDARD');
};

ImageQualityTab.prototype.getStandardCategories = function(standard) {
    var defaults = {
        'SIMPLE': {
            'USABILITY': ['format', 'imageQuality'],
            'INTEGRITY': ['blank'],
            'UNIQUENESS': ['repeatImage'],
            'SECURITY': ['dpi']
        },
        'STANDARD': {
            'USABILITY': ['format', 'imageQuality', 'houseAngle', 'bias', 'edgeRemove'],
            'INTEGRITY': ['blank', 'quantity'],
            'UNIQUENESS': ['repeatImage'],
            'SECURITY': ['dpi', 'kb']
        },
        'COMPLEX': {
            'USABILITY': ['format', 'imageQuality', 'houseAngle', 'bias', 'edgeRemove', 'stain', 'bindingHole'],
            'INTEGRITY': ['blank', 'quantity', 'pageCount', 'emptyFolder'],
            'UNIQUENESS': ['repeatImage', 'pieceContinuous', 'pageContinuous'],
            'SECURITY': ['dpi', 'kb']
        }
    };
    
    var selectedDefaults = defaults[standard] || defaults['STANDARD'];
    var categories = [];
    var itemMap = this.getItemMap();
    
    Object.keys(selectedDefaults).forEach(catKey => {
        var category = {
            type: catKey,
            enabled: true,
            items: selectedDefaults[catKey].map(itemCode => ({
                itemCode: itemCode,
                itemName: itemMap[itemCode] ? itemMap[itemCode].name : itemCode,
                enabled: true,
                parameters: this.getDefaultParameters(itemCode)
            }))
        };
        categories.push(category);
    });
    
    return categories;
};

ImageQualityTab.prototype.getDefaultParameters = function(itemCode) {
    var defaults = {
        'format': { formatList: ['jpeg', 'jpg', 'tiff', 'tif', 'pdf'] },
        'dpi': { dpiValue: 300 },
        'kb': { minKB: 10, maxKB: 1000 },
        'bias': { rectify: '0.5' },
        'stain': { stainValue: 10 },
        'bindingHole': { hole: 1 },
        'imageQuality': { minCompressionRatio: 80 },
        'edgeRemove': { edgeStrict: 0 }
    };
    return defaults[itemCode] || {};
};

ImageQualityTab.prototype.bindEvents = function() {
    var self = this;
    
    // 标准切换
    this.container.find('input[name="checkStandard"]').on('change', function() {
        self.currentStandard = $(this).val();
        self.categories = self.getStandardCategories(self.currentStandard);
        self.renderCategoryItems();
    });
    
    // 类别切换
    this.container.find('.category-toggle').on('change', function() {
        var section = $(this).closest('.category-section');
        var itemsContainer = section.find('.items-container');
        itemsContainer.toggle(this.checked);
    });
    
    // 项目切换
    this.container.find('.item-toggle').on('change', function() {
        var itemRow = $(this).closest('.check-item');
        itemRow.find('.item-params').toggle(this.checked);
    });
    
    // 前往设置链接
    this.container.find('.go-to-config').on('click', function(e) {
        e.preventDefault();
        QualityRulesManager.showTab('resource');
    });
    
    // 参数输入事件
    this.container.find('.quality-value').on('input', function() {
        $(this).siblings('.quality-display').text(this.value + '%');
    });
};

ImageQualityTab.prototype.renderCategoryItems = function() {
    var self = this;
    var itemMap = this.getItemMap();
    
    this.container.find('.items-container').each(function() {
        var section = $(this).closest('.category-section');
        var categoryType = section.data('category');
        var categoryData = self.categories.find(c => c.type === categoryType);
        
        if (categoryData && categoryData.items) {
            var html = categoryData.items.map(item => {
                var itemConfig = itemMap[item.itemCode] || {};
                return self.renderItem(categoryType, item, itemConfig);
            }).join('');
            $(this).html(html);
        }
    });
    
    this.bindEvents();
};

ImageQualityTab.prototype.getConfig = function() {
    var config = {
        standard: this.currentStandard,
        categories: []
    };
    
    this.container.find('.category-section').each(function() {
        var section = $(this);
        var categoryType = section.data('category');
        var enabled = section.find('.category-toggle').is(':checked');
        
        var category = {
            type: categoryType,
            enabled: enabled,
            items: []
        };
        
        section.find('.check-item').each(function() {
            var itemCode = $(this).data('item-code');
            var itemEnabled = $(this).find('.item-toggle').is(':checked');
            
            var item = {
                itemCode: itemCode,
                itemName: $(this).find('.item-label span').text(),
                enabled: itemEnabled,
                parameters: this.extractParameters(itemCode)
            };
            category.items.push(item);
        });
        
        config.categories.push(category);
    });
    
    return config;
};

ImageQualityTab.prototype.extractParameters = function(itemCode) {
    var params = {};
    var container = this.container.find(`.check-item[data-item-code="${itemCode}"]`);
    
    switch (itemCode) {
        case 'format':
            var formats = [];
            container.find('input[name="format"]:checked').each(function() {
                formats.push($(this).val());
            });
            params.formatList = formats;
            break;
        case 'dpi':
            params.dpiValue = parseInt(container.find('.dpi-value').val()) || 300;
            break;
        case 'kb':
            params.minKB = parseInt(container.find('.min-kb').val()) || 10;
            params.maxKB = parseInt(container.find('.max-kb').val()) || 1000;
            break;
        case 'bias':
            params.rectify = container.find('.skew-value').val() || '0.5';
            break;
        case 'stain':
            params.stainValue = parseInt(container.find('.stain-value').val()) || 10;
            break;
        case 'bindingHole':
            params.hole = parseInt(container.find('.hole-value').val()) || 1;
            break;
        case 'imageQuality':
            params.minCompressionRatio = parseInt(container.find('.quality-value').val()) || 80;
            break;
        case 'edgeRemove':
            params.edgeStrict = parseInt(container.find('input[name="edgeMode"]:checked').val()) || 0;
            break;
    }
    
    return params;
};
```

#### 14.4.3 文件清单

| 文件路径 | 类型 | 说明 |
|----------|------|------|
| `extensions/quality/module/scripts/tabs/image-quality-tab.html` | 新建 | Tab 模板 |
| `extensions/quality/module/scripts/tabs/image-quality-tab.js` | 新建 | Tab 控制器 |
| `extensions/quality/module/scripts/dialogs/image-quality-config.js` | 新建 | 配置面板 |
| `extensions/quality/module/scripts/results/image-quality-results.js` | 新建 | 结果展示 |
| `extensions/quality/module/styles/image-quality.css` | 新建 | 样式文件 |
| `extensions/quality/module/scripts/dialogs/manage-rules-dialog.js` | 修改 | 集成 Tab |
| `extensions/quality/module/scripts/results/data-quality-results.js` | 修改 | 结果集成 |
| `extensions/quality/module/styles/data-quality.css` | 修改 | 添加样式 |

#### 14.4.4 验收标准

- [ ] 规则配置界面包含所有四性类别
- [ ] 检查标准切换正确加载对应规则
- [ ] 所有参数控件可正常交互
- [ ] 资源定位配置提示正确显示
- [ ] 检查结果正确展示图像错误详情
- [ ] 界面支持响应式布局
- [ ] 提供完整的国际化支持

---

### 14.5 Phase 4：模块集成

**目标**：将图像质量检查功能与现有数据质量模块无缝集成。

#### 14.5.1 开发任务清单

| 序号 | 任务描述 | 涉及文件 | 优先级 |
|------|----------|----------|--------|
| 4.1 | 修改模块初始化逻辑 | 修改 QualityModule.java | 高 |
| 4.2 | 添加路由配置 | 修改 quality-module.json | 高 |
| 4.3 | 实现规则持久化 | 新建 ImageQualityRuleStorage.java | 高 |
| 4.4 | 实现规则导入导出 | 修改 QualityRulesImporter.java | 中 |
| 4.5 | 添加进度展示 | 修改 QualityCheckProgress.java | 中 |
| 4.6 | 添加缓存支持 | 修改 QualityCacheManager.java | 中 |

#### 14.5.2 核心集成点

**QualityModule.java**（修改）：
```java
@Override
public void start(Project project, Properties options) {
    super.start(project, options);
    
    // 初始化图像质量规则存储
    ImageQualityRuleStorage.initialize(project);
    
    // 注册图像质量检查器
    QualityCheckerRegistry.register("image", new ImageQualityCheckerFactory());
}
```

**ImageQualityRuleStorage.java**（新建）：
```java
package com.google.refine.quality.storage;

import com.google.refine.model.Project;
import com.google.refine.quality.model.ImageQualityRule;
import com.google.refine.quality.config.QualityRulesConfig;

public class ImageQualityRuleStorage {
    
    private static final String IMAGE_QUALITY_RULES_KEY = "quality.imageRules";
    
    public static void initialize(Project project) {
        if (project.getOverlayData(IMAGE_QUALITY_RULES_KEY) == null) {
            project.putOverlayData(IMAGE_QUALITY_RULES_KEY, new HashMap<>());
        }
    }
    
    public static Map<String, ImageQualityRule> getRules(Project project) {
        return project.getOverlayData(IMAGE_QUALITY_RULES_KEY);
    }
    
    public static void saveRules(Project project, Map<String, ImageQualityRule> rules) {
        project.putOverlayData(IMAGE_QUALITY_RULES_KEY, rules);
    }
    
    public static void saveRule(Project project, String id, ImageQualityRule rule) {
        Map<String, ImageQualityRule> rules = getRules(project);
        rules.put(id, rule);
        saveRules(project, rules);
    }
}
```

#### 14.5.3 文件清单

| 文件路径 | 类型 | 说明 |
|----------|------|------|
| `extensions/quality/src/com/google/refine/quality/QualityModule.java` | 修改 | 模块初始化 |
| `extensions/quality/module/module.json` | 修改 | 模块配置 |
| `extensions/quality/src/com/google/refine/quality/storage/ImageQualityRuleStorage.java` | 新建 | 规则存储 |
| `extensions/quality/src/com/google/refine/quality/importing/QualityRulesImporter.java` | 修改 | 导入支持 |
| `extensions/quality/src/com/google/refine/quality/exporting/QualityRulesExporter.java` | 修改 | 导出支持 |

#### 14.5.4 验收标准

- [ ] 项目打开时自动初始化图像质量规则存储
- [ ] 规则配置保存后持久化到项目
- [ ] 规则管理对话框正确读取和保存规则
- [ ] 检查结果与现有结果合并展示
- [ ] 支持规则的导入导出
- [ ] 与项目保存和加载流程兼容

---

### 14.6 Phase 5：AI 服务集成

**目标**：对接 Hegui AI 审查服务，实现智能图像质量检测。

#### 14.6.1 开发任务清单

| 序号 | 任务描述 | 涉及文件 | 优先级 |
|------|----------|----------|--------|
| 6.1 | 创建 AI 服务客户端 | 新建 HeguiAiClient.java | 高 |
| 6.2 | 实现 API 适配器 | 新建 AiApiAdapter.java | 高 |
| 6.3 | 实现请求构建器 | 新建 AiRequestBuilder.java | 中 |
| 6.4 | 实现响应解析器 | 新建 AiResponseParser.java | 中 |
| 6.5 | 实现服务健康检查 | 新建 AiHealthChecker.java | 中 |
| 6.6 | 添加配置管理 | 修改 QualityConfig.java | 中 |

#### 14.6.2 核心类实现

**HeguiAiClient.java**（新建）：
```java
package com.google.refine.quality.ai;

import com.google.refine.quality.ai.model.CheckImageAiResultDTO;
import com.google.refine.quality.ai.model.CheckImageHtmlResultParam;
import org.springframework.web.client.RestTemplate;

public class HeguiAiClient {
    
    private String aiServer;
    private String aimpServer;
    private RestTemplate restTemplate;
    
    public HeguiAiClient(String aiServer, String aimpServer) {
        this.aiServer = aiServer;
        this.aimpServer = aimpServer;
        this.restTemplate = new RestTemplate();
    }
    
    /**
     * 开启审核任务
     */
    public boolean openTask(CheckImageHtmlResultParam param) {
        String url = aiServer + "/alot/chek" + buildOpenQueryParams(param);
        try {
            restTemplate.postForEntity(url, null, Void.class);
            return true;
        } catch (Exception e) {
            log.error("开启审核任务失败: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 检测图像
     */
    public CheckImageAiResultDTO detect(File image, CheckImageHtmlResultParam param) {
        String url = aiServer + "/alot/chek/inspect" + buildDetectQueryParams(param);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            HttpEntity<File> entity = new HttpEntity<>(image, headers);
            ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.POST, entity, String.class);
            return parseResponse(response.getBody());
        } catch (Exception e) {
            log.error("图像检测失败: {}", e.getMessage());
            return createEmptyResult();
        }
    }
    
    /**
     * 重启 AI 服务
     */
    public void reloadAIService() {
        try {
            restTemplate.postForEntity(aimpServer + "/admin/reload", null, Void.class);
        } catch (Exception e) {
            log.error("重启 AI 服务失败: {}", e.getMessage());
        }
    }
    
    private String buildOpenQueryParams(CheckImageHtmlResultParam param) {
        return String.format(
            "?blank=%s&house_angle=%s&rectify=%s&edge_remove=%s&stain=%s&hole=%s&dpi=%s&format=%s&kb=%s",
            param.getBlank(), param.getHouseAngle(), param.getBias(),
            param.getEdgeRemove(), param.getStain(), param.getBindingHole(),
            param.getDpi(), param.getFormat(), param.getKb()
        );
    }
    
    private String buildDetectQueryParams(CheckImageHtmlResultParam param) {
        StringBuilder sb = new StringBuilder();
        sb.append("?set_sensitivity=3");
        
        if (param.getRectify() != null) {
            sb.append("&set_angle=").append(param.getRectify());
        }
        if (param.getStainValue() != null) {
            sb.append("&set_stain=").append(param.getStainValue());
        }
        if (param.getHole() != null) {
            sb.append("&set_hole=").append(param.getHole());
        }
        if (param.getDpiValue() != null) {
            sb.append("&set_dpi=").append(param.getDpiValue());
        }
        if (param.getFormatList() != null) {
            for (String format : param.getFormatList()) {
                sb.append("&set_format=").append(format);
            }
        }
        if (param.getMinKB() != null) {
            sb.append("&set_kb=").append(param.getMinKB());
        }
        if (param.getMaxKB() != null) {
            sb.append("&max_kb=").append(param.getMaxKB());
        }
        if (param.getImageScore() != null) {
            sb.append("&set_quality=").append(param.getImageScore());
        }
        if (param.getEdgeStrict() != null) {
            sb.append("&edge_strict=").append(param.getEdgeStrict());
        }
        
        return sb.toString();
    }
    
    private CheckImageAiResultDTO parseResponse(String json) {
        // JSON 解析逻辑
        return objectMapper.readValue(json, CheckImageAiResultDTO.class);
    }
}
```

#### 14.6.3 文件清单

| 文件路径 | 类型 | 说明 |
|----------|------|------|
| `extensions/quality/src/com/google/refine/quality/ai/HeguiAiClient.java` | 新建 | AI 客户端 |
| `extensions/quality/src/com/google/refine/quality/ai/AiApiAdapter.java` | 新建 | API 适配器 |
| `extensions/quality/src/com/google/refine/quality/ai/AiRequestBuilder.java` | 新建 | 请求构建器 |
| `extensions/quality/src/com/google/refine/quality/ai/AiResponseParser.java` | 新建 | 响应解析器 |
| `extensions/quality/src/com/google/refine/quality/ai/AiHealthChecker.java` | 新建 | 健康检查 |
| `extensions/quality/src/com/google/refine/quality/ai/model/AiDetectionModels.java` | 新建 | 数据模型 |

#### 14.6.4 验收标准

- [ ] AI 客户端正确调用 Hegui 服务接口
- [ ] 请求参数与 Hegui 期望格式一致
- [ ] 响应结果正确解析为内部格式
- [ ] 服务不可用时正确降级到规则检测
- [ ] 健康检查功能正常
- [ ] 支持异步批量检测

---

### 14.7 依赖关系图

```
Phase 1: 基础模型层
    │
    ▼
Phase 2: 后端检查器
    │
    ├── ImageQualityChecker (依赖 Phase 1 模型)
    │   │
    │   └── 各专业检查器 (Format, Dpi, Size, Quality...)
    │
    ▼
Phase 3: 前端界面
    │
    ├── image-quality-tab.js (依赖 Phase 1 模型)
    │   │
    │   └── results.js (依赖 Phase 2 检查器结果)
    │
    ▼
Phase 4: 模块集成
    │
    ├── QualityModule.java (集成 Phase 2-3)
    │   │
    │   └── RuleStorage (依赖 Phase 1 模型)
    │
    ▼
Phase 5: AI 服务集成 (可选)
    │
    ├── HeguiAiClient (依赖 Phase 1 AI 模型)
    │   │
    │   └── ImageQualityChecker (增强 AI 检测能力)
```

---

### 14.8 测试计划

#### 14.8.1 单元测试

| 测试范围 | 测试内容 | 预期结果 |
|----------|----------|----------|
| 数据模型 | 枚举值完整、序列化/反序列化 | 所有枚举值可正确转换 |
| 检查器 | 各检查器逻辑正确性 | 正确识别各类图像问题 |
| UI 组件 | 交互逻辑、状态管理 | 用户操作正确响应 |
| AI 客户端 | HTTP 请求/响应处理 | 正确对接 Hegui 服务 |

#### 14.8.2 集成测试

| 测试场景 | 测试步骤 | 验证点 |
|----------|----------|--------|
| 规则保存 | 配置规则→保存→重新打开 | 规则正确持久化和加载 |
| 检查执行 | 选择规则→执行检查→查看结果 | 结果正确展示 |
| AI 集成 | 配置 AI→执行检查→对比结果 | AI 检测结果与规则检测结果一致 |

#### 14.8.3 性能测试

| 测试项 | 性能指标 |
|--------|----------|
| 单张图像检查 | < 500ms |
| 1000 张图像批量检查 | < 2min |
| 规则加载 | < 100ms |
| UI 渲染 | < 200ms |

---

### 14.9 风险与对策

| 风险 | 影响 | 可能性 | 对策 |
|------|------|--------|------|
| Hegui API 变更 | AI 集成失败 | 中 | 隔离 AI 客户端，便于适配 |
| 图像格式不支持 | 部分检查无法执行 | 低 | 明确支持格式列表，提供友好提示 |
| 性能不达标 | 用户体验差 | 中 | 优化批处理，支持异步执行 |
| 前端复杂度高 | 开发周期延长 | 中 | 分阶段交付，优先核心功能 |

---

### 14.10 里程碑

| 里程碑 | 时间点 | 交付物 |
|--------|--------|--------|
| M1: 模型完成 | 第 1 周 | Phase 1 所有代码和单元测试 |
| M2: 检查器完成 | 第 2 周 | Phase 2 所有代码和单元测试 |
| M3: UI 完成 | 第 3 周 | Phase 3 所有代码和集成测试 |
| M4: 集成完成 | 第 4 周 | Phase 4 所有代码和集成测试 |
| M5: AI 集成完成 | 第 5 周 | Phase 5 所有代码和集成测试 |
| M6: 发布 | 第 6 周 | 完整功能、文档、发布包 |

---

### 14.11 开发环境配置

#### 14.11.1 后端环境

| 依赖 | 版本要求 | 说明 |
|------|----------|------|
| JDK | 11+ | OpenRefine 依赖 |
| Maven | 3.6+ | 构建工具 |
| Hegui Server | 最新版本 | AI 服务依赖（Phase 5） |

#### 14.11.2 前端环境

| 依赖 | 版本要求 | 说明 |
|------|----------|------|
| Node.js | 16+ | 构建工具 |
| npm | 8+ | 包管理 |
| OpenRefine | 3.7+ | 主项目 |

#### 14.11.3 本地运行

```bash
# 编译后端
mvn clean compile -pl extensions/quality

# 编译前端
cd extensions/quality/module
npm install
npm run build

# 启动测试
mvn test -pl extensions/quality
```

---

### 14.12 代码审查清单

- [ ] 代码符合 OpenRefine 编码规范
- [ ] 关键方法添加 Javadoc 注释
- [ ] 单元测试覆盖率 > 80%
- [ ] 无代码异味（SonarQube 通过）
- [ ] 提交信息遵循规范格式
- [ ] 相关文档同步更新

---

**文档版本**: 1.0  
**创建日期**: 2025-01-02  
**最后更新**: 2025-01-02  
**维护者**: OpenRefine Data Quality Team
