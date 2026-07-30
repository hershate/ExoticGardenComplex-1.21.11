# ExoticGardenComplex 要点笔记索引

本目录记录本仓库的关键决策、分析与说明。

- [compile-adaptation.md](compile-adaptation.md) — 编译适配 REF（Slimefun 4.9.5）的依赖调查、API 差异与完整迁移执行记录
- [non-official-slimefun.md](non-official-slimefun.md) — 声明所依赖的粘液科技为第三方非官方分支（hershate/Slimefun4.1）
- [testing.md](testing.md) — 静态测试（`test/test.sh`）的设计、维度覆盖与结果
- [hardening.md](hardening.md) — 安全性与稳定性加固（崩溃/NPE/刷物品/领地绕过/异步线程等修复）
- [perf-optimization.md](perf-optimization.md) — 性能优化（机器 ticker/采集/监听器热点 + 离线基准量化）
- [release/](release/) — 各版本改动（[1.0.0](release/1.0.0.md) / [1.1.0](release/1.1.0.md)）
- [report/perf/](report/perf/) — 性能对比报告

## 速览

- **依赖**：附属编译/运行仅依赖粘液科技一个插件 —— 本仓库 `REF/Slimefun4.1`（= `com.github.slimefun:Slimefun:4.9.5`，第三方非官方分支）。
- **构建**：先 `cd REF/Slimefun4.1 && mvn clean install -DskipTests` 装入本地仓库，再 `mvn clean package -DskipTests` 编译附属，产物 `target/ExoticGardenComplex-1.21.11-1.1.0.jar`。
- **测试**：`bash test/test.sh`（10 维度 51 项静态检查，含基准正确性，51/51 通过）。
- **基准**：`bash benchmark/run.sh [save]`（算法层离线基准，新旧算法等价性 + 计时）。
- **当前版本**：1.1.0（性能优化版）。源码层编译通过、静态测试与离线基准等价性断言全绿；**未做实机运行回归**。
