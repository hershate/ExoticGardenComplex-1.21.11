# ExoticGardenComplex 要点笔记索引

本目录记录本仓库的关键决策、分析与说明。

- [compile-adaptation.md](compile-adaptation.md) — 编译适配 REF（Slimefun 4.9.5）的依赖调查、API 差异与完整迁移执行记录
- [non-official-slimefun.md](non-official-slimefun.md) — 声明所依赖的粘液科技为第三方非官方分支（hershate/Slimefun4.1）
- [testing.md](testing.md) — 静态测试（`test/test.sh`）的设计、九维度覆盖与结果
- [hardening.md](hardening.md) — 安全性与稳定性加固（崩溃/NPE/刷物品/领地绕过/异步线程等修复）

## 速览

- **依赖**：附属编译/运行仅依赖粘液科技一个插件 —— 本仓库 `REF/Slimefun4.1`（= `com.github.slimefun:Slimefun:4.9.5`，第三方非官方分支）。
- **构建**：先 `cd REF/Slimefun4.1 && mvn clean install -DskipTests` 装入本地仓库，再 `mvn clean package -DskipTests` 编译附属，产物 `target/ExoticGarden vUNOFFICIAL.jar`。
- **测试**：`bash test/test.sh`（9 维度 47 项静态检查，47/47 通过）。
- **当前状态**：源码层编译通过并经完整静态测试验证；**未做实机运行回归**。
