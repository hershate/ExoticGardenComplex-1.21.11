# 关于粘液科技依赖的说明（第三方非官方分支）

> 本附属（ExoticGardenComplex）编译与运行所依赖的粘液科技（Slimefun）为
> **第三方非官方维护分支**，并非 Slimefun 官方版本。

## 依赖来源与坐标

- **本仓库路径**：`REF/Slimefun4.1`（只读参考，已加入 `.gitignore`，不纳入本仓库版本管理）
- **对应远程仓库**：https://github.com/hershate/Slimefun4.1 （`experimental` 分支）
- **Maven 坐标**：`com.github.slimefun:Slimefun:4.9.5`（`<name>SlimeFun4.1</name>`）
- 两者**完全同源**：pom 坐标一致、源码结构一致，都不含 xzavier0722 存储包、都 shaded dough。
  本仓库采用「本地 `mvn install REF`」方式接入。

## 性质

- 该分支基于 Slimefun 官方仓库 `Slimefun/Slimefun4` 的 `experimental` 分支衍生，
  由社区 / 个人独立维护，与 Slimefun 官方团队**无任何隶属关系**。
- 维护目标：让 Slimefun 可在 Minecraft **1.21.1 ~ 1.21.11** 上运行、补充中文本地化、
  移除联网自动更新与匿名数据上报、做安全加固。
- 这也是本附属从原汉化版（`SlimefunGuguProject/Slimefun4`，含 xzavier0722 存储、老 dough）
  迁移到该分支时，出现 `SlimefunItemStack` 不再 `extends ItemStack`、`CustomItemStack` 改为
  静态工厂等核心 API 差异的根本原因。

## 反馈与免责

- 该分支的 Bug、建议、Pull Request 请**直接提交到 `hershate/Slimefun4.1`**，
  请勿提交到官方 `Slimefun/Slimefun4` 的 Issue Tracker，以免干扰官方维护者。
- 使用该非官方分支的风险由使用者自行承担；用于生产环境前请做好数据备份。
- 本附属仅在该分支上完成**源码层编译适配**（判据：`mvn clean package -DskipTests` → BUILD SUCCESS），
  **未做实机运行回归**，建议上真实服务器后观察日志。

## 官方参考

- 官方仓库：https://github.com/Slimefun/Slimefun4
- 官方 Wiki：https://github.com/Slimefun/Slimefun4/wiki
