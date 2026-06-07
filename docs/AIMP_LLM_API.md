# AIMP LLM分析接口文档

## 接口概述

本接口用于调用大语言模型（LLM）进行文本分析和语义理解。支持自定义提示词和上下文信息，返回JSON或文本格式的分析结果。

## 请求信息

### 基本信息
- **接口名称**: LLM分析接口
- **请求路径**: `/api/llm/analyze`
- **请求方法**: `POST`
- **Content-Type**: `multipart/form-data`

### 请求参数

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| prompt | String | 是 | 提示词文本，用于指导LLM进行分析 |
| context | String | 否 | 上下文信息，可选的补充说明 |
| response_format | String | 否 | 响应格式，可选值：`json`（默认）、`text` |

### 参数说明

#### prompt
- **类型**: String
- **必填**: 是
- **说明**: 发送给LLM的主要提示词，用于指导模型进行特定的分析任务
- **示例**: 
  ```
  请判断以下案卷题名是否与卷内题名集合匹配：

  案卷题名：关于2023年保密相关要求、资质现场审查的通知

  卷内题名列表：
  1. 关于重申项目加工现场保密管理要求的通知
  2. 关于开展资质现场审查的通知

  判断标准：
  1. 案卷题名与卷内题名主题是否一致
  2. 案卷题名是否体现了所有卷内题名的主题

  请返回JSON格式：
  {
    "passed": true/false,
    "reason": "判断原因说明"
  }
  ```

#### context
- **类型**: String
- **必填**: 否
- **说明**: 可选的上下文信息，用于为LLM提供额外的背景信息
- **示例**: 
  ```
  档案管理系统质量检查任务，用于验证案卷题名的完整性和一致性。
  ```

#### response_format
- **类型**: String
- **必填**: 否
- **说明**: 指定LLM返回结果的格式
- **可选值**:
  - `json`: 返回JSON格式（默认）
  - `text`: 返回纯文本格式
- **默认值**: `json`

## 请求示例

### cURL示例

```bash
curl -X POST "http://localhost:7998/api/llm/analyze" \
  -H "Content-Type: multipart/form-data; boundary=----WebKitFormBoundary7MA4YWxkTrZu0gW" \
  -F "prompt=请判断以下案卷题名是否与卷内题名集合匹配：

案卷题名：关于2023年保密相关要求、资质现场审查的通知

卷内题名列表：
1. 关于重申项目加工现场保密管理要求的通知
2. 关于开展资质现场审查的通知

判断标准：
1. 案卷题名与卷内题名主题是否一致
2. 案卷题名是否体现了所有卷内题名的主题

请返回JSON格式：
{
  \"passed\": true/false,
  \"reason\": \"判断原因说明\"
}" \
  -F "response_format=json"
```

### Python示例

```python
import requests

url = "http://localhost:7998/api/llm/analyze"

data = {
    "prompt": """请判断以下案卷题名是否与卷内题名集合匹配：

案卷题名：关于2023年保密相关要求、资质现场审查的通知

卷内题名列表：
1. 关于重申项目加工现场保密管理要求的通知
2. 关于开展资质现场审查的通知

判断标准：
1. 案卷题名与卷内题名主题是否一致
2. 案卷题名是否体现了所有卷内题名的主题

请返回JSON格式：
{
  "passed": true/false,
  "reason": "判断原因说明"
}""",
    "response_format": "json"
}

response = requests.post(url, data=data)
print(response.json())
```

### Java示例

```java
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class LlmAnalyzeClient {
    public static void main(String[] args) throws Exception {
        String url = "http://localhost:7998/api/llm/analyze";
        String boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW";
        
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setDoOutput(true);
        
        try (OutputStream os = conn.getOutputStream()) {
            String prompt = "请判断以下案卷题名是否与卷内题名集合匹配：\n\n" +
                          "案卷题名：关于2023年保密相关要求、资质现场审查的通知\n\n" +
                          "卷内题名列表：\n" +
                          "1. 关于重申项目加工现场保密管理要求的通知\n" +
                          "2. 关于开展资质现场审查的通知\n\n" +
                          "判断标准：\n" +
                          "1. 案卷题名与卷内题名主题是否一致\n" +
                          "2. 案卷题名是否体现了所有卷内题名的主题\n\n" +
                          "请返回JSON格式：\n" +
                          "{\n" +
                          "  \"passed\": true/false,\n" +
                          "  \"reason\": \"判断原因说明\"\n" +
                          "}";
            
            StringBuilder sb = new StringBuilder();
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"prompt\"\r\n\r\n");
            sb.append(prompt).append("\r\n");
            
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"response_format\"\r\n\r\n");
            sb.append("json").append("\r\n");
            
            sb.append("--").append(boundary).append("--\r\n");
            
            os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        
        int responseCode = conn.getResponseCode();
        System.out.println("Response Code: " + responseCode);
    }
}
```

## 响应信息

### 响应格式

#### 成功响应（JSON格式）

```json
{
  "success": true,
  "result": {
    "passed": true,
    "reason": "案卷题名与卷内题名主题一致，且包含了所有卷内题名的主题"
  }
}
```

#### 成功响应（文本格式）

```json
{
  "success": true,
  "result": "案卷题名与卷内题名主题一致，且包含了所有卷内题名的主题"
}
```

#### 失败响应

```json
{
  "success": false,
  "result": null,
  "error": "LLM服务不可用：Connection refused"
}
```

### 响应字段说明

| 字段名 | 类型 | 说明 |
|--------|------|------|
| success | Boolean | 请求是否成功 |
| result | Object/String | LLM分析结果，格式取决于`response_format`参数 |
| error | String | 错误信息，仅在`success`为`false`时返回 |

#### result字段（JSON格式）

当`response_format`为`json`时，`result`字段包含LLM返回的JSON对象。具体结构取决于提示词中要求的格式。

**示例结构**：
```json
{
  "passed": true,
  "reason": "判断原因说明"
}
```

| 字段名 | 类型 | 说明 |
|--------|------|------|
| passed | Boolean | 判断结果，`true`表示通过，`false`表示不通过 |
| reason | String | 判断原因说明 |

#### result字段（文本格式）

当`response_format`为`text`时，`result`字段为纯文本字符串。

## 错误处理

### HTTP状态码

| 状态码 | 说明 |
|--------|------|
| 200 | 请求成功 |
| 400 | 请求参数错误 |
| 500 | 服务器内部错误 |

### 错误响应示例

#### 参数错误

```json
{
  "success": false,
  "result": null,
  "error": "缺少必要参数：prompt"
}
```

#### LLM服务错误

```json
{
  "success": false,
  "result": null,
  "error": "LLM调用失败：模型响应超时"
}
```

#### 服务不可用

```json
{
  "success": false,
  "result": null,
  "error": "LLM服务不可用：Connection refused"
}
```

## 使用场景

### 场景1：案卷题名一致性检查

**提示词示例**：
```
请判断以下案卷题名是否与卷内题名集合匹配：

案卷题名：关于2023年保密相关要求、资质现场审查的通知

卷内题名列表：
1. 关于重申项目加工现场保密管理要求的通知
2. 关于开展资质现场审查的通知

判断标准：
1. 案卷题名与卷内题名主题是否一致
2. 案卷题名是否体现了所有卷内题名的主题

请返回JSON格式：
{
  "passed": true/false,
  "reason": "判断原因说明"
}
```

**预期响应**：
```json
{
  "success": true,
  "result": {
    "passed": true,
    "reason": "案卷题名与卷内题名主题一致，且包含了所有卷内题名的主题"
  }
}
```

### 场景2：文本分类

**提示词示例**：
```
请将以下文本分类为以下类别之一：[通知、公告、报告、函件]

文本：关于开展2023年度保密检查工作的通知

请返回JSON格式：
{
  "category": "类别名称",
  "confidence": 0.95
}
```

**预期响应**：
```json
{
  "success": true,
  "result": {
    "category": "通知",
    "confidence": 0.95
  }
}
```

### 场景3：文本摘要

**提示词示例**：
```
请为以下文本生成摘要，不超过50字：

文本：为了进一步加强档案管理工作，提高档案管理水平，确保档案的完整性、准确性和安全性，特制定本档案管理制度。本制度适用于公司所有部门和员工，包括档案的收集、整理、保管、利用和销毁等各个环节。

请返回JSON格式：
{
  "summary": "摘要内容"
}
```

**预期响应**：
```json
{
  "success": true,
  "result": {
    "summary": "制定档案管理制度，规范档案收集、整理、保管、利用和销毁各环节。"
  }
}
```

## 技术要求

### 性能要求
- 响应时间：建议不超过30秒（取决于LLM模型和输入长度）
- 并发支持：建议支持至少10个并发请求

### 安全要求
- 对输入的提示词进行长度限制，建议不超过10000字符
- 对输出结果进行长度限制，建议不超过5000字符
- 防止注入攻击，对特殊字符进行转义处理

### 日志要求
- 记录每次请求的参数（敏感信息脱敏）
- 记录响应时间和状态
- 记录错误信息

## 注意事项

1. **提示词设计**: 提示词应清晰、明确，包含具体的任务要求和输出格式说明
2. **错误处理**: 当LLM服务不可用时，应返回明确的错误信息
3. **超时设置**: 建议设置合理的超时时间（如30秒），避免长时间等待
4. **格式验证**: 当`response_format`为`json`时，应对LLM返回的结果进行JSON格式验证
5. **上下文使用**: `context`参数可用于提供额外的背景信息，但不是必填项

## 版本历史

| 版本 | 日期 | 说明 |
|------|------|------|
| 1.0.0 | 2026-01-28 | 初始版本，支持基本的LLM分析功能 |
