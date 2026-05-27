# AIMP 信息提取接口设计文档

## 1. 概述

本文档定义了 AIMP 服务端需要实现的信息提取接口，用于支持 OpenRefine FilesExtension 插件的档案要素提取功能。

## 2. 接口设计

### 2.1 单文件信息提取接口

#### 接口路径
```
POST /extract/upload
```

#### 功能描述
对单个文件（PDF或图像）进行OCR识别和结构化信息提取。

#### 请求方式
`multipart/form-data`

#### 请求参数

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| file | File | 是 | 待提取的文件（PDF或图像格式） |
| key_list | String | 是 | 提取要素列表，逗号分隔。如：`title,responsible_party,document_number,date` |
| sync | Boolean | 否 | 是否同步处理，默认true |

#### 支持的提取要素

| 要素键名 | 中文名称 | 说明 |
|----------|----------|------|
| title | 题名 | 文件标题 |
| responsible_party | 责任者 | 文件责任单位/人 |
| document_number | 文号 | 公文编号 |
| date | 成文日期 | 文件成文日期 |

#### 响应格式

**成功响应**
```json
{
  "success": true,
  "results": {
    "title": "关于XXX的通知",
    "responsible_party": "XX单位",
    "document_number": "XX字〔2024〕1号",
    "date": "2024年1月1日"
  }
}
```

**失败响应**
```json
{
  "success": false,
  "error": "错误信息描述"
}
```

### 2.2 批量信息提取接口（推荐）

#### 接口路径
```
POST /extract/archive/batch_extract
```

#### 功能描述
对多个文件夹或PDF文件进行批量信息提取，支持并行处理。

#### 请求方式
`multipart/form-data`

#### 请求参数

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| task_id | String | 是 | 任务唯一标识 |
| extraction_units | String (JSON) | 是 | 提取单元列表，JSON格式 |
| elements | String (JSON) | 是 | 提取要素列表，JSON数组格式 |
| confidence_threshold | Float | 否 | OCR置信度阈值，默认0.5 |
| enable_preprocessing | Boolean | 否 | 是否启用图像预处理，默认true |

#### extraction_units 格式说明

```json
[
  {
    "unit_id": "unit_001",
    "unit_type": "folder",
    "path": "D:/archives/2024/001",
    "files": ["001.jpg", "002.jpg", "003.jpg"]
  },
  {
    "unit_id": "unit_002", 
    "unit_type": "pdf",
    "path": "D:/archives/2024/document.pdf",
    "files": []
  }
]
```

| 字段 | 类型 | 说明 |
|------|------|------|
| unit_id | String | 单元唯一标识 |
| unit_type | String | 单元类型：`folder`（文件夹）或 `pdf`（PDF文件） |
| path | String | 文件夹路径或PDF文件路径 |
| files | Array | 文件夹内的图像文件名列表（PDF类型为空数组） |

#### elements 格式说明

```json
["title", "responsible_party", "document_number", "date"]
```

#### 响应格式

**成功响应**
```json
{
  "success": true,
  "task_id": "task_12345",
  "extraction_result": {
    "unit_001": {
      "title": "关于XXX的通知",
      "responsible_party": "XX单位",
      "document_number": "XX字〔2024〕1号",
      "date": "2024年1月1日",
      "confidence": {
        "title": 0.95,
        "responsible_party": 0.88,
        "document_number": 0.92,
        "date": 0.90
      }
    },
    "unit_002": {
      "title": "工作报告",
      "responsible_party": "YY部门",
      "document_number": "",
      "date": "2024年2月15日",
      "confidence": {
        "title": 0.85,
        "responsible_party": 0.75,
        "document_number": 0.0,
        "date": 0.82
      }
    }
  }
}
```

**部分失败响应**
```json
{
  "success": true,
  "task_id": "task_12345",
  "extraction_result": {
    "unit_001": {
      "error": "OCR识别失败",
      "title": "",
      "responsible_party": "",
      "document_number": "",
      "date": ""
    }
  }
}
```

**完全失败响应**
```json
{
  "success": false,
  "task_id": "task_12345",
  "error": "任务执行失败：原因描述"
}
```

### 2.3 异步任务状态查询接口

#### 接口路径
```
GET /extract/archive/task/{task_id}
```

#### 功能描述
查询异步提取任务的状态和结果。

#### 响应格式
```json
{
  "task_id": "task_12345",
  "status": "completed",
  "progress": 100,
  "result": {
    "extraction_result": { ... }
  }
}
```

| 字段 | 说明 |
|------|------|
| status | 任务状态：`pending`、`processing`、`completed`、`failed` |
| progress | 进度百分比（0-100） |

## 3. 实现要求

### 3.1 文件处理流程

```
输入文件 → 图像预处理 → OCR识别 → 文本清洗 → LLM要素提取 → 结构化输出
```

### 3.2 文件夹处理逻辑

1. 遍历文件夹内所有图像文件（支持jpg、png、tiff等格式）
2. 按文件名排序后依次进行OCR识别
3. 合并所有页面的OCR文本
4. 对合并后的文本进行LLM要素提取
5. 返回结构化结果

### 3.3 PDF处理逻辑

1. 将PDF转换为图像（每页一张）
2. 对每页图像进行OCR识别
3. 合并所有页面的OCR文本
4. 对合并后的文本进行LLM要素提取
5. 返回结构化结果

### 3.4 错误处理

- 文件不存在：返回 `{"success": false, "error": "文件不存在"}`
- 文件格式不支持：返回 `{"success": false, "error": "不支持的文件格式"}`
- OCR识别失败：在结果中标记 `error` 字段，其他字段返回空字符串
- LLM提取失败：返回已识别的OCR文本，要素字段返回空字符串

### 3.5 性能要求

- 单文件处理超时：60秒
- 批量任务支持并行处理
- 支持任务取消

## 4. Java端调用示例

### 4.1 单文件提取

```java
public Map<String, String> extractContent(String filePath, String keyList) {
    Map<String, String> result = new HashMap<>();
    
    File file = new File(filePath);
    String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
    byte[] fileBytes = Files.readAllBytes(file.toPath());
    
    StringBuilder bodyBuilder = new StringBuilder();
    bodyBuilder.append("--").append(boundary).append("\r\n");
    bodyBuilder.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
               .append(file.getName()).append("\"\r\n");
    bodyBuilder.append("Content-Type: application/octet-stream\r\n\r\n");
    
    byte[] bodyStart = bodyBuilder.toString().getBytes(StandardCharsets.UTF_8);
    
    StringBuilder keyListPart = new StringBuilder();
    keyListPart.append("\r\n--").append(boundary).append("\r\n");
    keyListPart.append("Content-Disposition: form-data; name=\"key_list\"\r\n\r\n");
    keyListPart.append(keyList);
    keyListPart.append("\r\n--").append(boundary).append("\r\n");
    keyListPart.append("Content-Disposition: form-data; name=\"sync\"\r\n\r\n");
    keyListPart.append("true");
    keyListPart.append("\r\n--").append(boundary).append("--\r\n");
    
    byte[] bodyEnd = keyListPart.toString().getBytes(StandardCharsets.UTF_8);
    
    HttpURLConnection conn = (HttpURLConnection) 
        new URL(serviceUrl + "/extract/upload").openConnection();
    conn.setRequestMethod("POST");
    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
    conn.setDoOutput(true);
    
    try (OutputStream os = conn.getOutputStream()) {
        os.write(bodyStart);
        os.write(fileBytes);
        os.write(bodyEnd);
    }
    
    if (conn.getResponseCode() == 200) {
        JsonNode json = mapper.readTree(readStream(conn.getInputStream()));
        if (json.has("results") && json.get("results").isObject()) {
            json.get("results").fields()
                .forEachRemaining(e -> result.put(e.getKey(), e.getValue().asText()));
        }
    }
    
    return result;
}
```

### 4.2 批量提取

```java
public BatchExtractResult batchExtract(
        String taskId,
        List<Map<String, Object>> extractionUnits,
        List<String> elements,
        double confidenceThreshold) {
    
    String boundary = "----AimpBoundary" + System.currentTimeMillis();
    URL url = new URL(serviceUrl + "/extract/archive/batch_extract");
    
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod("POST");
    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
    conn.setDoOutput(true);
    
    try (OutputStream os = conn.getOutputStream()) {
        writeFormField(os, boundary, "task_id", taskId);
        writeFormField(os, boundary, "extraction_units", mapper.writeValueAsString(extractionUnits));
        writeFormField(os, boundary, "elements", mapper.writeValueAsString(elements));
        writeFormField(os, boundary, "confidence_threshold", String.valueOf(confidenceThreshold));
        writeFormField(os, boundary, "enable_preprocessing", "true");
        os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    }
    
    // 解析响应...
}
```

## 5. 与现有接口的关系

### 5.1 与 batch_compare 接口的区别

| 特性 | batch_extract | batch_compare |
|------|---------------|---------------|
| 功能 | 纯信息提取 | 信息提取+与Excel数据比对 |
| 输入 | 文件列表 | 文件列表+Excel数据 |
| 输出 | 提取结果 | 比对结果（包含差异） |
| 使用场景 | 新建档案著录 | 档案质量检查 |

### 5.2 复用现有代码

建议复用 `batch_compare` 接口中的以下模块：
- OCR识别模块
- 图像预处理模块
- LLM要素提取模块
- 文件夹/PDF处理逻辑

## 6. 测试用例

### 6.1 单文件提取测试

```bash
curl -X POST "http://localhost:7998/extract/upload" \
  -F "file=@test.pdf" \
  -F "key_list=title,responsible_party,document_number,date" \
  -F "sync=true"
```

### 6.2 批量提取测试

```bash
curl -X POST "http://localhost:7998/extract/archive/batch_extract" \
  -F "task_id=test_001" \
  -F 'extraction_units=[{"unit_id":"u1","unit_type":"folder","path":"/data/test","files":["1.jpg","2.jpg"]}]' \
  -F 'elements=["title","responsible_party","document_number","date"]'
```
