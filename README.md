# 表型生长轨工坊（Phenotype Growth Rail）

本地单机 Web 服务：在**完整保留原始观测**的前提下，估计植物个体生长曲线、相机换机偏移、
批次（盘位置）偏移，以及基因型对处理的差异反应（处理效应与基因型×处理互作用）。
技术栈：Kotlin + Ktor（Netty）+ SQLite（JDBC）+ 原生 Web UI（无前端构建链）。

## 运行

```bash
# 安装（打包，跳过测试）
mvn -q -DskipTests package

# 演示（测试 + 启动到 5578 端口）
mvn -q test && \
mvn -q exec:java -Dexec.mainClass=app.MainKt -Dexec.args='--port 5578'
```

打开 <http://127.0.0.1:5578>，页面标题为 **表型生长轨工坊**。

可选参数：

- `--port 5578`：监听端口（默认 5578，仅绑定 127.0.0.1）。
- `--db data/pheno.db`：SQLite 文件路径（默认 `./data/pheno.db`）。
- `--reset`：启动前删除该 DB 文件，等价于“清空后从零重新导入”。

## 试验与固定 fixture

固定试验定义在 `src/main/resources/fixture.json`（仓库副本 `fixtures/pheno_fixture.json`）。
它是**参数化、带随机种子**的固定夹具：观测值由确定性 LCG 生成，任意机器、任意次重导入
都逐位一致（见 `DeterminismTest`）。

- 两个批次：`B1`（盘位置偏移 +3）、`B2`（−2）；批次真值差约 −5。
- 一次相机更换：第 14 天起新相机，性状级加性偏移
  （叶面积 +8、株高 +2、冠幅 −1；质量标志非影像性状，无相机偏移）。
- 重叠参照植株 `REF-01`/`REF-02` 在第 10/12/14 天被新旧相机重复拍摄（每性状 6 对）。
- 基因型 `G1/G2/G3` × 处理 `CTRL/STRESS`：
  - `G1`、`G2` 四个组合均跨两批次平衡；
  - `G3 × STRESS` **只出现在单一批次 B2**（批次混淆，只作描述）；
  - `G3 × CTRL` **完全缺失**（设计空缺，不产生任何点估计）。
- 质量标志 `mass` 为稀疏破坏性采样（第 4/12/20/28 天，每 3 株取 1 株）。
- 注入两处典型问题：
  - `P-001` 第 6 天叶面积的**同时点重复影像**，重复条标记 `SUSPECT`（禁止先平均）；
  - `REF-02` 第 22 天株高一个 `SUSPECT` 离群点。

## 数据口径（重要约定）

1. **原始观测只读保留**：页面所有“质量修正”都向 `quality_corrections` 追加审计记录，
   原始值永不被覆盖删除。质量状态：
   - `OK`：权重 1；`SUSPECT`：保留但权重 0.5；`EXCLUDED`：保留记录但不参与拟合/偏移。
2. **同时点重复不平均**：同一植株同一天的重复拍摄是独立观测行（`replicate` 不同），
   拟合与配对时各自保留；重复之间的质量差异因此不会在“先平均”时被吞掉。
3. **换机偏移只认重叠参照**：相机偏移 = 重叠时段（第 10/12/14 天）内，参照植株
   新旧相机**同时点配对差**的均值（附配对清单、SE）。没有任何可用配对时
   输出**不可识别**（无数字估计，页面显示原因），不借用相邻日期或其他植株替代。
   - 页面需先点击“确认换机边界”，偏移才参与个体曲线校正；未确认时只展示估计与警示。
4. **批次偏移与换机解耦**：以 `B1` 为锚，只使用换机前（≤14 天）旧相机窗口，
   并对共有基因型×处理组合**按同一天取批次均值差**，再跨天/组合平均，避免生长率差异混入。
5. **缺失组合不造数**：处理效应是 `STRESS−CTRL` 的平均 AUC 差；基因型×处理互作用是
   双差分。任一 2×2 单元缺失即标记 `design_gap`，估计与 SE 均为空；仅单批次的组合标记
   `single_batch_confounded`，仍展示数值但明确“与批次混淆，仅作描述”。
6. **多候选曲线并存**：每个体每性状同时拟合
   饱和生长（单分子 `A·(1−e^(−kt))`）、分段线性（单断点）、单调样条（加权 PAVA），
   报告 RMSE/BIC，推荐 BIC 最小者但三者都可查看；外推到第 32 天，>28 天区域在图上
   以阴影标出且点带 `extrapolation=true`，不确定度随外推距离放大。
7. **版本固定**：每次分析记录都钉住三个版本：
   - 设计版本：基因型×处理×批次矩阵的哈希；
   - fixture 版本：夹具文件内容哈希；
   - 预处理版本：观测质量状态、质量修正与换机边界状态的哈希。
   质量修正或边界确认都会改变预处理版本，历史运行记录不会被追溯修改。

## 页面功能

- **个体时序**：选植株/性状，SVG 绘制原始观测（颜色区分质量、重复标注）与三条候选曲线、
  换机竖线、外推阴影；表格中可把任意观测改标 OK/疑点/排除并填写依据。
- **换机与批次偏移**：每性状的可识别性、估计、SE、配对/共有组合明细；
  “移除重叠参照”按钮一键把重叠参照观测标记排除，用于复核不可识别场景（可再点恢复）。
- **处理差异/互作用**：基因型×处理设计矩阵（跨批次/单批次/设计空缺三色），
  以及每性状的处理效应与互作用表（估计、SE、状态、原因）。
- **数据覆盖**：个体×性状的总数、OK/疑点/排除、时间范围与成像天数。
- **分析运行**：命名后固化为不可变记录，可导出 JSON 或 CSV（含偏移、单元、空缺、效应）。

## HTTP 接口（自动化/复核用）

- `GET /api/state`、`GET /api/design`、`GET /api/observations?plantId=...`
- `GET /api/coverage`、`GET /api/offsets`、`GET /api/analysis/preview`
- `POST /api/quality?observationId=...`（body：`{"quality":"SUSPECT","reason":"..."}`）
- `POST /api/boundary`（`{"switchDay":14,"confirmed":true}`）
- `POST /api/reference-toggle`（`{"excludeReferencePlants":true|false}`）
- `POST /api/runs`、`GET /api/runs`、`GET /api/runs/{id}`、`GET /api/runs/{id}/export`
- `GET /api/replay`（导出 fixture + 质量修正 + 边界 + 运行索引）
- `POST /api/reseed`（清空并以同一固定 fixture 重新导入）
- `POST /api/import-fixture`（导入另一份同 schema 的参数化 fixture 后重导入）

## 清空数据库后重新导入复核

```bash
# 方式一：删库重开
mvn -q exec:java -Dexec.mainClass=app.MainKt -Dexec.args='--port 5578 --reset'

# 方式二：服务运行时点页面“清空并重导入”，或
curl -s -X POST http://127.0.0.1:5578/api/reseed
```

重导入后质量修正与运行记录被清空，观测逐位重现；重新执行相同修正与边界确认后，
预处理版本与导出结果可对照复核。重放包 `GET /api/replay` 记录当时的 fixture、
质量修正、边界与运行索引，便于留痕。

## 自动化测试

`mvn test`（共 13 个用例）覆盖：观测逐位确定性、同时点重复保留、相机偏移接近真值、
移除参照后相机偏移不可识别而批次偏移仍可识别、批次方向、设计空缺不产生互作用点估计、
单批次标记、边界确认改变预处理版本与审计、三类候选模型与外推点、运行持久化，
以及基于 Ktor testHost 的首页/接口/拒绝非法质量/参照切换 HTTP 冒烟测试。

## 目录

```
pom.xml
fixtures/pheno_fixture.json          # 固定夹具（仓库副本）
src/main/resources/fixture.json      # 打包进 JAR 的同一份夹具
src/main/resources/static/           # 单页 Web UI
src/main/kotlin/app/                 # Main / Web / Engine / Offsets / Fit / Generator / Database / Service / CsvExport / Models
src/test/kotlin/app/                 # 确定性、分析、HTTP 测试
```
