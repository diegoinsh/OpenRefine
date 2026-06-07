# 数据金炼系统（JinRefine Platform）打包部署说明

## 一、核心设计变更

**之前**：所有文件混在一起，模型内嵌到 AIMP 的 `_internal` 目录中
**现在**：程序与模型分离，模型文件放在 EXE 同级目录下

### 目录结构

```
deploy/jinrefine/                    ← 统一输出目录
├── jinrefine/                       ← JinRefine 主程序 (约315MB)
│   ├── jinrefine.exe
│   ├── server/target/jre/           ← 内嵌 JRE
│   └── webapp/
│
├── aimp/                            ← AIMP 服务 (精简后约1.5GB)
│   ├── aimp.exe                     ← 主程序
│   ├── _internal/                   ← Python依赖 (无models/rapidocr)
│   ├── config/                      ← 配置文件
│   ├── models/                      ← 外置模型 (从_internal移出)
│   │   ├── inspection/              ← d0.pt, d1.pt, k0.pt, l0.pt (590MB)
│   │   └── seal_detection/          ← la_model_ir10.onnx, sealdet_model.onnx (113MB)
│   └── rapidocr/                    ← 外置OCR (精简后约21MB)
│       ├── models/                  ← 仅ppocrv5 mobile + cls
│       ├── ch_ppocr_det/
│       ├── ch_ppocr_cls/
│       ├── ch_ppocr_rec/
│       └── ...
│
├── ollama/                          ← Ollama 运行时 (约7.1GB)
│   ├── ollama.exe
│   ├── *.dll                        ← CUDA运行时
│   ├── lib/ollama/                  ← GPU后端库
│   └── models/                      ← Ollama模型 (仅qwen3:4b + embedding-0.6b)
│       ├── blobs/                   ← 模型权重文件
│       └── manifests/               ← 模型清单
│
├── manager/                         ← 服务管理器 (约88MB)
│   ├── ruyi_manager.exe
│   ├── tray_app.exe
│   └── _internal/
│
├── config/                          ← 平台配置
│   └── platform.yaml
├── scripts/                         ← 启动/停止脚本
├── icons/                           ← 图标
└── logs/                            ← 日志
```

## 二、精简效果

| 项目 | 精简前 | 精简后 | 节省 |
|------|--------|--------|------|
| AIMP `_internal/models` | 703 MB | 0 (外置) | 703 MB |
| AIMP `_internal/rapidocr` | 201 MB | 0 (外置) | 201 MB |
| RapidOCR 模型 (ppocrv4+server) | ~170 MB | ~21 MB (仅ppocrv5 mobile) | ~149 MB |
| AIMP `_internal/torchaudio` | 9.6 MB | 排除 | 9.6 MB |
| AIMP `_internal/torchvision` | 14 MB | 排除 | 14 MB |
| AIMP `_internal/matplotlib` | 11 MB | 排除 | 11 MB |
| AIMP `_internal/skimage` | 11.5 MB | 排除 | 11.5 MB |
| AIMP `_internal/sklearn` | 18 MB | 排除 | 18 MB |
| AIMP `_internal/scipy` | 100 MB | 排除 | 100 MB |
| AIMP `_internal/paddle` | 203 MB | 排除 | 203 MB |
| Ollama 模型 (bge-m3, qwen3:1.7b, 30b-a3b) | ~8 GB | 0 (仅保留4b+0.6b) | ~8 GB |
| **总计** | **~39 GB** | **~12 GB** | **~27 GB** |

## 三、打包脚本使用方法

### 3.1 完整打包（程序 + 模型）

```powershell
# 完整打包所有组件
& "G:\workshop\RUYI\packaging\jinrefine\scripts\build_all.ps1"

# 仅打包程序，跳过模型（开发调试用）
& "G:\workshop\RUYI\packaging\jinrefine\scripts\build_all.ps1" -SkipModels

# 跳过特定组件
& "G:\workshop\RUYI\packaging\jinrefine\scripts\build_all.ps1" -SkipJinRefine -SkipOllama

# 启用Virbox加密
& "G:\workshop\RUYI\packaging\jinrefine\scripts\build_all.ps1" -EnableVirbox -VirboxCloudUser "xxx" -VirboxPin "xxx"

# 自定义输出目录
& "G:\workshop\RUYI\packaging\jinrefine\scripts\build_all.ps1" -OutputDir "D:\release"
```

### 3.2 单独打包模型

```powershell
# 使用默认路径
& "G:\workshop\RUYI\packaging\jinrefine\scripts\build_models.ps1"

# 自定义路径
& "G:\workshop\RUYI\packaging\jinrefine\scripts\build_models.ps1" `
    -OutputDir "D:\release" `
    -AimpSource "G:\workshop\RUYI\ruyi-aimp" `
    -OllamaSource "D:\OllamaModels" `
    -RapidOCRSource "C:\ProgramData\Anaconda3\envs\py39ruyi\Lib\site-packages\rapidocr"
```

### 3.3 制作安装程序

```powershell
# 先执行完整打包
& "G:\workshop\RUYI\packaging\jinrefine\scripts\build_all.ps1"

# 然后用 Inno Setup 编译安装程序
ISCC "G:\workshop\RUYI\packaging\jinrefine\installer\jinrefine.iss"
```

## 四、关键代码变更说明

### 4.1 aimp.spec

移除 `models` 和 `rapidocr` 的 datas 打包，新增 `torchaudio`、`torchvision`、`matplotlib`、`skimage`、`sklearn`、`scipy`、`paddle` 到 excludes。

### 4.2 start_service.py

新增 `MODELS_DIR`、`RAPIDOCR_DIR`、`CONFIG_DIR` 变量，优先从 EXE 同级目录查找，设置 `AIMP_MODELS_DIR` 和 `AIMP_RAPIDOCR_DIR` 环境变量，将 rapidocr 目录加入 `sys.path`。

### 4.3 platform.yaml

`OLLAMA_MODELS` 改为相对路径 `ollama\models`，ruyi_manager 会自动解析为绝对路径。

### 4.4 build_all.ps1

新增 `-SkipModels`、`-EnableVirbox` 参数，默认不启用加密。

### 4.5 build_models.ps1

新建脚本，仅打包需要的模型文件：
- AIMP inspection: d0.pt, d1.pt, k0.pt, l0.pt
- AIMP seal_detection: la_model_ir10.onnx, sealdet_model.onnx
- RapidOCR: 仅 ppocrv5 mobile + cls
- Ollama: 仅 qwen3:4b + dengcao/qwen3-embedding-0.6b:f16

### 4.6 jinrefine.iss

源路径从 `release/` 改为 `deploy/jinrefine/`，明确区分 AIMP 的 exe、_internal、config、models、rapidocr 目录。

## 五、部署到新机器

1. 运行 `build_all.ps1` 完成打包
2. 将 `deploy/jinrefine/` 整个目录拷贝到目标机器
3. 运行 `scripts\start_all.bat` 或 `manager\ruyi_manager.exe start`
4. 浏览器访问 `http://localhost:3333`

## 六、模型精简策略

### 6.1 AIMP 模型

根据 `config/single_gpu_config.yaml` 配置文件，仅保留以下模型：

| 模型类别 | 文件 | 用途 |
|----------|------|------|
| inspection | d0.pt | 缺页检测 |
| inspection | d1.pt | 缺页检测 |
| inspection | k0.pt | 空白页检测 |
| inspection | l0.pt | 污损检测 |
| seal_detection | la_model_ir10.onnx | 印章定位 |
| seal_detection | sealdet_model.onnx | 印章检测 |

### 6.2 RapidOCR 模型

仅保留 ppocrv5 mobile 轻量级模型：

| 模型文件 | 用途 |
|----------|------|
| ch_PP-OCRv5_mobile_det.onnx | 文字检测 |
| ch_PP-OCRv5_rec_mobile_infer.onnx | 文字识别 |
| ch_ppocr_mobile_v2.0_cls_infer.onnx | 方向分类 |

### 6.3 Ollama 模型

仅保留两个核心模型：

| 模型 | 用途 |
|------|------|
| qwen3:4b | 主推理模型（合规审查、数据抽取） |
| dengcao/qwen3-embedding-0.6b:f16 | 文本嵌入模型（语义检索） |

已移除的模型：bge-m3、qwen3:1.7b、qwen3:30b-a3b 等。
