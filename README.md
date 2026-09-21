# 谱峰案卷（Spectral Peak Dossier）

面向分析化学实验室的 centroid 质谱峰管理与候选裁定系统：导入 m/z、强度、扫描时间、极性，
围绕目标峰生成同位素簇与加合物候选，所有质量校准、基线参数与候选规则均为**有版本的分析配置**，
所有编辑动作走**事件溯源 + 分支**模型。

## 运行

```bash
./gradlew build -x test          # 准备环境
./gradlew test                    # 16 个单元/集成测试
./gradlew run --args='--host 127.0.0.1 --port 5236'
# 浏览器打开 http://127.0.0.1:5236 ，页面标题为“谱峰案卷”
```

所有文件与 SQLite 数据库（含 SQLite JDBC 原生库）都位于项目目录 `data/`。

## 能力

- 批量导入 `m/z,强度,扫描时间秒,极性(pos/neg)[,扫描号]`；损坏扫描**逐行隔离**，其余照常进入，
  返回内容摘要（SHA-256）、逐行错误、新峰/合并峰计数。
- 相同极性、相对差在 `mzIdentityBinPpm`（默认 2ppm）内的观测行归并为**稳定峰身份**，
  跨导入保持同一 `pk-*` ID。
- 候选生成：加合物（H/Na/K/NH4、2+、负模式 Cl/甲酸/乙酸）、M0 双容差
  （ppm 与绝对 Da **必须同时满足**）、同位素簇间距（1.003355/z Da）、泊松式碳同位素丰度、
  保留时间窗、基线噪声。每条解释包含：使用的观测峰、ppm/Da 误差、每个同位素的观测/期望间距、
  应用规则列表与配置版本——而不只是化合物名+总分。
- **缺失的低强度同位素**记录为 `present=false` 且不占峰，不与真正冲突混同。
- 裁定校验：基础版本（乐观并发，两个编辑者基于旧版本裁定时后者收到 409 `STALE_VERSION`）、
  峰存在/污染、极性、电荷、RT 窗漂移，以及已接受候选间的峰占用；冲突返回**最小共享峰集合**。
- 峰操作：标记/取消污染、拆分过饱和峰、修订校准点、校准回滚到任意历史配置版本；
  这些动作只追加到当前分支，其它分支不受影响。
- 规则升级产生新配置版本并生成新 run；旧 run/旧解释按其 `configVersion` 保留可查看。
- 版本比较区分 `OBSERVATION`（导入/拆分/污染）、`CALIBRATION`（仅校准 m/z 变化）、
  `RULES`（配置版本变化），并列出各侧独有与发生变化的候选。
- 前端：Canvas 谱图（滚轮缩放、拖拽平移）、候选 M0/同位素叠加（虚线=缺失同位素）、
  冲突队列、裁定历史、分析分支与版本时间线、版本比较。
- 每个业务动作（导入/拆分/校准/run/裁定/配置升级）与其所有写入在**同一个 SQLite 事务**提交，
  中断时不会留下半条峰版本或裁定。

## 主要代码

- `src/main/kotlin/dossier/chem/Chemistry.kt`：容差、质量校准最小二乘、加合物/同位素化学
- `src/main/kotlin/dossier/engine/Store.kt`：版本事件、分支、沿 parent 链回放物化
- `src/main/kotlin/dossier/engine/Importer.kt`：CSV 解析与隔离、稳定峰身份
- `src/main/kotlin/dossier/engine/CandidateGenerator.kt`：候选与解释轨迹
- `src/main/kotlin/dossier/engine/Adjudicator.kt`：裁定校验与最小共享峰集合
- `src/main/kotlin/dossier/engine/PeakOps.kt`、`Comparator.kt`：峰操作/校准与版本比较
- `src/main/kotlin/dossier/api/Server.kt`：HTTP API 与静态页面
- `src/test/kotlin/dossier/`：ppm/Da 边界、稳定身份、共享峰冲突、缺失同位素、
  校准回滚、批量部分失败、双编辑者旧版本冲突、事务原子性、HTTP 409 测试
