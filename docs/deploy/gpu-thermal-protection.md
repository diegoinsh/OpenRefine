# 托盘 GPU 高温自动降功耗 — 实现与参考

> 归属：数据金炼系统托盘程序（`tray_app.exe`）
> 代码：`packaging/jinrefine/manager/tray_app.py` → 类 `GpuThermalProtector`
> 首次落盘：2026-10-12

## 一、目标

GPU 长时间满负荷跑推理时温度会顶到硬件红线（RTX 3060 为 93℃），到达后硬件自行降频，
表现为**推理速度突然掉一半且无任何提示**。该功能在温度接近红线前主动下调功耗上限，
把温度压在安全区，避免无声降频；温度回落后再自动恢复默认上限。

**整体可休眠**：托盘的「设置 → GPU 温度保护」总开关关闭后，不做任何探测与设置，
行为与未引入该功能时完全一致。

## 二、实现位置与调用链

| 环节 | 位置 |
|---|---|
| 温控器 | `tray_app.py` → `GpuThermalProtector`（探测 / 推荐值 / 监测线程） |
| 界面 | 托盘右键 → 「设置」→「GPU 温度保护（高温自动降功耗）」 |
| 配置持久化 | `deploy/jinrefine/config/tray_settings.json` → `gpu_thermal` 段 |
| 启动入口 | `RuyiTrayApp.__init__` → `self.gpu_thermal.start()` |

调用链：`start()` 读一次 GPU 参数 → 起 daemon 线程 `_loop()` → 每 `check_interval_s` 秒
`probe()` 一次温度 → 越阈值调 `nvidia-smi -pl` 降功耗（`state=throttled`）→
低于恢复温度再调回默认上限（`state=monitoring`）。

**状态机**：`idle`（未启用）→ `monitoring`（监测中）↔ `throttled`（已降功耗）；
无 `nvidia-smi` 时置 `unavailable`。

## 三、参数与推导规则（按卡自适应，不写死机型表）

不同代次 GPU 的官方「最高工作温度」与可调功耗区间并不相同，同型号不同厂商 BIOS 也不同，
因此**全部数值在运行时从驱动读取**：

| 参数 | 来源 | 说明 |
|---|---|---|
| 温度红线 | `nvidia-smi -q -d TEMPERATURE` 的 `GPU Max Operating Temp` | 逐级回退见下 |
| 回退阶梯 | `Max Operating` → `Slowdown − 2` → `Shutdown − 5` → 兜底 90℃ | 三条统一到「最高工作温度」口径 |
| 功耗区间 | `power.default_limit / min_limit / max_limit` | 随厂商 BIOS 变化 |
| 显卡型号 | `--query-gpu=name` | 界面展示用 |

推导规则（常量见 `GpuThermalProtector`）：

```
触发温度 high_temp_c   = 温度红线 − 8℃        # 留余量提前干预
恢复温度 recover_temp_c = high_temp_c − 10℃   # 回差，避免阈值附近反复抖动
降功耗上限 target_power_w = 默认上限 × 75%     # 取整到 10W，并夹在本卡 [min, default] 内
检测间隔 check_interval_s = 5s
```

RTX 3060 代入：红线 93℃ → 触发 85℃ / 恢复 75℃ / 170W → 130W（−24%）。
设置界面每条备注的「推荐 x」与「填入推荐值」按钮均按本机实时计算。

## 四、nvidia-smi 命令速查

```powershell
# 型号 + 温度 + 功耗上限（--query-gpu 无温度阈值字段）
nvidia-smi --query-gpu=name,temperature.gpu,power.draw,power.limit,power.default_limit,power.min_limit,power.max_limit --format=csv,noheader,nounits

# 温度阈值（唯一来源：Max Operating / Slowdown / Shutdown / Target）
nvidia-smi -q -d TEMPERATURE

# 下调 / 恢复功耗上限（需管理员权限）
nvidia-smi -pl 130
nvidia-smi -pl 170
```

**踩坑记录**：`--query-gpu=temperature.gpu.tlimit` 在本机 RTX 3060 上返回 `[N/A]`，
拿不到阈值；必须在 `-q -d TEMPERATURE` 里取。设置功耗上限需管理员权限，
程序内以隐藏窗口方式调用（`_run_hidden`），成功判据为返回码 0 且输出含 `was set`。

## 五、各代 GPU 官方规格（检索结论，2026-10-12）

| 系列 | 最高 GPU 温度 | 整卡功耗 |
|---|---|---|
| RTX 50 | 5090 90℃；5080 / 5070 Ti 88℃；5070 85℃；5060 Ti 87℃；5060 89℃；5050 87℃ | 575 / 360 / 300 / 250 / 180 / 145 / 130 W |
| RTX 40 | 全系 90℃ | 450 / 320 / 320 / 285 / 285 / 220 / 200 / 160 / 115 W |
| RTX 30 | 93℃（3090 Ti 为 92℃） | 450 / 350 / 350 或 320 / 290 / 220 / 200 / 170 / 130 / 70 W |
| 其他 | L40S 83℃（结温） | L40S 350 W |

按本方案自动推导的推荐参数：

| 显卡 | 温度红线 | 触发 / 恢复 | 默认上限 → 降功耗上限 |
|---|---|---|---|
| RTX 3060 | 93℃ | 85 / 75℃ | 170 → 130 W |
| RTX 3090 | 93℃ | 85 / 75℃ | 350 → 260 W |
| RTX 4090 | 90℃ | 82 / 72℃ | 450 → 340 W |
| RTX 4060 | 90℃ | 82 / 72℃ | 115 → 90 W |
| L40S | 83℃ | 75 / 65℃ | 350 → 260 W |

参考资料：

- [Compare GeForce Graphics Cards Specs | NVIDIA](https://www.nvidia.com/en-gb/geforce/graphics-cards/compare/)（50 / 40 系列）
- [GeForce RTX 30 Series Graphics Cards | NVIDIA](https://www.nvidia.com/en-my/geforce/graphics-cards/30-series/)（30 系列）
- [GeForce RTX 4070 Ti & 4070 | NVIDIA](https://www.nvidia.com/en-gb/geforce/graphics-cards/40-series/rtx-4070-4070ti/)
- [GeForce RTX 4060 Family | NVIDIA](https://www.nvidia.com/en-gb/geforce/graphics-cards/40-series/rtx-4060-4060ti/)
- [NVIDIA L40S GPU Accelerator Product Brief | PNY](https://www.pny.com/File%20Library/Company/Support/product-briefs/data-center-gpus/NVIDIA-L40S-Product-Brief.pdf)
- [nvidia-smi 命令行参考 | NVIDIA](https://docs.nvidia.com/deploy/nvidia-smi/index.html)

## 六、实测记录

**本机 RTX 3060（2026-10-12）**

```
nvidia-smi -q -d TEMPERATURE
    GPU Current Temp         : 55 C
    GPU Shutdown Temp        : 98 C
    GPU Slowdown Temp        : 95 C
    GPU Max Operating Temp   : 93 C     ← 取此值作红线
    GPU Target Temperature   : 83 C
功耗：min 100W / default 170W / max 187W
```

**触发链路已验证通过**：把触发温度设为 51℃（低于当时 55℃）保存后，下一监测周期
（`check_interval_s=6`）内 `power.limit` 从 `170.00W` 变为 `131.00W`，与目标值一致；
新构建的托盘进程（管理员权限）执行 `nvidia-smi -pl` 成功。推荐值回归后为 85 / 75 / 130 / 5。

**历史缺陷**：保存设置路径为 `stop() → apply_settings() → start()`，而旧 `stop()`
只置停止标志、既不 `join()` 也不清理 `_thread`，`start()` 开头的
`if self._thread.is_alive(): return` 会静默早退 → **保存后监测静默停摆，降功耗不再触发**。
现 `stop()` 用 `threading.Event` + `join(timeout=3)` 彻底回收线程，`start()` 先 `stop()`
再重建，已修复。

## 七、已知边界

1. **重启不复原**：托盘在 `throttled` 状态下被强杀或重启时，新会话是 `monitoring` 而非
   `throttled`，不会主动把功耗上限恢复成默认值，需等温度先越过触发阈值再回落才恢复。
   如需消除，在 `start()` 探测后加一句「当前上限 ≠ 默认上限则先恢复」即可（约 3 行）。
2. **多卡机器**：温度与功耗区间只取第一张卡（`--query-gpu` 输出的第一行）；
   而 `nvidia-smi -pl` 未带 `-i`，作用范围是全部 GPU。多卡场景需另行确认。
3. **需管理员权限**：非管理员运行时 `nvidia-smi -pl` 会失败并记日志，监测本身不受影响。
4. **取证**：托盘为窗口模式（`console=False`），日志走 `print` 不可见；排查时直接读
   `config/tray_settings.json` 的 `enabled` 与阈值，再用 `nvidia-smi` 看 `power.limit`
   是否等于目标值。

## 八、验证步骤

1. 打开托盘「设置」，确认「GPU 温度保护」区显示本机显卡型号、温度红线、可调功耗区间。
2. 点「填入推荐值」→「保存设置」，检查 `config/tray_settings.json` 的 `gpu_thermal` 段。
3. 触发验证：把「触发温度」临时改到低于当前温度（如 50℃）保存，等一个监测周期后执行
   `nvidia-smi --query-gpu=power.limit --format=csv,noheader`，应显示目标功耗值。
4. 恢复验证：把「恢复温度」临时改到高于当前温度（如 60℃）保存，上限应回到默认值。