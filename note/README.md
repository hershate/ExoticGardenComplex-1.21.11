# ExoticGardenComplex 要点笔记索引

本目录记录本仓库的关键决策、分析与说明。

- [compile-adaptation.md](compile-adaptation.md) — 编译适配 REF（Slimefun 4.9.5）的依赖调查、API 差异与完整迁移执行记录
- [non-official-slimefun.md](non-official-slimefun.md) — 声明所依赖的粘液科技为第三方非官方分支（hershate/Slimefun4.1）
- [testing.md](testing.md) — 静态测试（`test/test.sh`）的设计、维度覆盖与结果
- [hardening.md](hardening.md) — 安全性与稳定性加固（崩溃/NPE/刷物品/领地绕过/异步线程/能量消耗 Bug 等修复）
- [perf-optimization.md](perf-optimization.md) — 性能优化（机器 ticker/采集/监听器热点 + 离线基准量化）
- [release/](release/) — 各版本改动（[1.0.0](release/1.0.0.md) / [1.1.0](release/1.1.0.md) / [1.2.0](release/1.2.0.md) / [1.2.1](release/1.2.1.md) / [1.3.0](release/1.3.0.md) / [1.3.1](release/1.3.1.md)）
- [report/perf/](report/perf/) — 性能对比报告（[1.1.0 总览](report/perf/2026-07-31-perf-optimization.md) / [1.2.0 tick 门控](report/perf/2026-07-31-machine-tick-viewer-gating.md) / [1.3.0 事件路径+能量](report/perf/2026-08-01-perf-optimization-2.md)）

## 速览

- **依赖**：附属编译/运行仅依赖粘液科技一个插件 —— 本仓库 `REF/Slimefun4.1`（= `com.github.slimefun:Slimefun:4.9.5`，第三方非官方分支）。
- **构建**：先 `cd REF/Slimefun4.1 && mvn clean install -DskipTests` 装入本地仓库，再 `mvn clean package -DskipTests` 编译附属，产物 `target/ExoticGardenComplex-1.21.11-1.3.0.jar`。
- **测试**：`bash test/test.sh`（10 维度 51 项静态检查，含基准正确性，51/51 通过）。
- **基准**：`bash benchmark/run.sh [save]`（算法层离线基准，新旧算法等价性 10 项断言 + 计时）。
- **当前版本**：1.3.1（1.3.0 之后：实机发现并修复“受保护领地内破坏草丛掉一颗种子即致服务端延时极高”——草种子掉落改为延迟到下一 tick 并校验方块确为 air 才掉落，使还原式领地保护下不再掉种子，消除卡顿触发条件）。源码层编译通过、静态测试 51/51、离线基准等价性断言全绿；**未做实机运行回归**（建议复测）。
