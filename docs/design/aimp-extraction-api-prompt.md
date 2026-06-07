# AIMP 信息提取接口实现提示词

## 任务目标

在 AIMP 服务端实现档案信息提取接口，用于支持 OpenRefine FilesExtension 插件的档案要素提取功能。

## 现有参考实现

建议参考已实现的 `/extract/archive/batch_compare` 接口进行实现。

**现有 batch_compare 接口特点**：
- 路径：`POST /extract/archive/batch_compare`
- 请求格式：multipart/form-data
- 支持图像预处理、OCR识别、LLM要素提取

## 需要实现的接口

### 接口1：单文件信息提取

**路径**：`POST /extract/upload`

**请求方式**：multipart/form-data

**请求参数**：

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| file | File | 是 | 待提取的文件（PDF或图像格式） |
| key_list | String | 是 | 提取要素列表，逗号分隔。如：`title,responsible_party,document_number,date` |
| sync | Boolean | 否 | 是否同步处理，默认true |

**支持的提取要素**（使用英文字段名）：

| 要素键名 | 中文名称 | 说明 |
|----------|----------|------|
| title | 题名 | 文件标题 |
| responsible_party | 责任者 | 文件责任单位/人 |
| document_number | 文号 | 公文编号 |
| date | 成文日期 | 文件成文日期 |

**响应格式**：

成功响应：
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

失败响应：
```json
{
  "success": false,
  "error": "错误信息描述"
}
```

---

### 接口2：批量信息提取（推荐）

**路径**：`POST /extract/archive/batch_extract`

**请求方式**：multipart/form-data

**请求参数**：

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| task_id | String | 是 | 任务唯一标识 |
| extraction_units | String (JSON) | 是 | 提取单元列表，JSON格式 |
| elements | String (JSON) | 是 | 提取要素列表，JSON数组格式 |
| confidence_threshold | Float | 否 | OCR置信度阈值，默认0.5 |
| enable_preprocessing | Boolean | 否 | 是否启用图像预处理，默认true |

**extraction_units 格式**：
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

**elements 格式**：
```json
["title", "responsible_party", "document_number", "date"]
```

**响应格式**：

成功响应：
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
    }
  }
}
```

---

## 实现要求

### 文件夹处理逻辑

1. 遍历文件夹内所有图像文件（支持jpg、png、tiff等格式）
2. 按文件名排序后依次进行OCR识别
3. 合并所有页面的OCR文本
4. 对合并后的文本进行LLM要素提取
5. 返回结构化结果

### PDF处理逻辑

1. 将PDF转换为图像（每页一张）
2. 对每页图像进行OCR识别
3. 合并所有页面的OCR文本
4. 对合并后的文本进行LLM要素提取
5. 返回结构化结果

### 错误处理

- 文件不存在：返回 `{"success": false, "error": "文件不存在"}`
- 文件格式不支持：返回 `{"success": false, "error": "不支持的文件格式"}`
- OCR识别失败：在结果中标记 `error` 字段，其他字段返回空字符串
- LLM提取失败：返回已识别的OCR文本，要素字段返回空字符串

### 性能要求

- 单文件处理超时：60秒
- 批量任务支持并行处理
- 支持任务取消

## 复用现有代码

建议复用 `batch_compare` 接口中的以下模块：
- OCR识别模块
- 图像预处理模块
- LLM要素提取模块
- 文件夹/PDF处理逻辑

## 测试用例

### 单文件提取测试

```bash
curl -X POST "http://localhost:7998/extract/upload" \
  -F "file=@test.pdf" \
  -F "key_list=title,responsible_party,document_number,date" \
  -F "sync=true"
```

### 批量提取测试

```bash
curl -X POST "http://localhost:7998/extract/archive/batch_extract" \
  -F "task_id=test_001" \
  -F 'extraction_units=[{"unit_id":"u1","unit_type":"folder","path":"/data/test","files":["1.jpg","2.jpg"]}]' \
  -F 'elements=["title","responsible_party","document_number","date"]'
```

## 注意事项

1. **字段名必须使用英文**：key_list 和 elements 参数中使用英文字段名（title, responsible_party, document_number, date），而不是中文字段名
2. **响应格式必须与上述格式完全一致**
3. **支持同步和异步两种模式**：通过 sync 参数控制
4. **参考现有的 batch_compare 接口代码结构进行实现**
