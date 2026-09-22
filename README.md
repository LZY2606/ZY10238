# 表型生长轨工坊

一个本地运行的 Kotlin/Ktor/SQLite 表型时序分析工具。页面和测试固定使用仓库内 fixture，设计包含两个批次、一次相机更换，以及一个只在单批次出现的基因型×处理组合；另有一个完全缺失的基因型×处理组合用于验证“设计空缺”。

## 构建与演示

```bash
mvn -q -DskipTests package
mvn -q test && mvn -q exec:java -Dexec.mainClass=app.MainKt -Dexec.args='--port 5578'
```

访问 <http://127.0.0.1:5578>，页面标题为“表型生长轨工坊”。

默认 SQLite 文件为 `data/phenotype.sqlite`，不存在时自动初始化；也可用 `--db path/to/file.sqlite` 指定独立数据库。

## 固定数据口径

- 2 个批次：`B1`、`B2`；每批有 2 株对照参照植株。
- 2 个基因型：`G1`、`G2`；3 个处理：`C` 对照、`D` 干旱、`W` 高水。
- `G1×W` 只出现在 `B1`，仍给点估计但标记为“单批次，批次不可差分”。
- `G2×W` 完全没有植株，不产生均值或互作用点估计，只显示 `DESIGN_GAP`。
- 性状包括叶面积、株高、冠幅；每株固定 8 个常规成像日。
- 第 4 天保留同日重复观测；其中 `B1-G1-C-01` 的重复之一为 `bad`，重复行不先平均。
- 相机更换日为第 8 天；每批参照植株在第 7 天同时有 old/new 两条有效观测，形成同日重叠。
- new 相机偏移以叶面积 +12、株高 +6、冠幅 +5 为中心；重叠参照另含对称测量误差，因此有非零 SE，两株均值回到固定中心。
- 默认目标日为第 16 天，晚于第 14 天观测窗口，页面橙色虚线段即外推区间；也可改回第 14 天仅查看观测支撑区间。

原始观测永不被页面操作覆盖。质量修正只写入 `quality_overrides`，并立即生成新的 `preprocessing_versions`。分析请求固定设计版本与预处理版本；保存的运行会把完整结果写入 `analysis_runs`。

## 统计规则

- 偏移只在边界已确认，并且某批次至少有 2 株参照植株在边界前同一天有 old/new 有效观测时识别。
- 参照植株被排除到少于 2 株、或没有同日重叠时，状态为 `NOT_IDENTIFIED`；边界未确认时为 `UNCONFIRMED`，均不输出偏移点估计。
- 每条同日重复作为独立点进入 SSE/AIC；bad 观测不纳入，good 与 questionable 保留其原始行和质量差异。
- 每株、每性状保留三类候选：饱和 monomolecular 曲线、两段线性、单调 PCHIP 样条；按 AIC 标记选中模型。
- 目标日晚于该株最后观测日时，橙色虚线段显示外推。
- 处理反应为四格 difference-in-differences；任一组合缺失即显示设计空缺，不输出虚假交互作用。

## HTTP 接口

- `GET /api/bootstrap`：植株、原始观测、有效观测、边界、版本和运行。
- `POST /api/analysis`：运行分析；`persist:false` 可只试算。
- `POST /api/observations/{id}/quality`：修正单条观测质量并生成预处理版本。
- `POST /api/batches/{batch}/boundary`：确认或修改换机边界。
- `GET /api/runs/{id}/export`：导出单次分析 JSON。
- `GET /api/logs/export`：导出全部操作记录。
- `POST /api/admin/reimport`：清空业务表并从同一 fixture 重新导入，用 checksum 复核。

## 清空后复核

页面“清空重导入”或调用 `/api/admin/reimport` 会删除运行、质量修正、边界确认、观测和植株，再按确定性 fixture 重建。导入前后可比较设计与原始观测 SHA-256；测试 `reseedClearsOverridesAndRestoresFixedChecksums` 自动验证该重放过程。
