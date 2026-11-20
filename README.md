# AutoHyb 6D MFEA

AutoHyb 用于多颗敏捷对地观测卫星的成像任务调度。外层多因子进化算法（MFEA）搜索由父算法、更新策略、执行顺序和生存策略组成的 6 维混合配置。内层 HybCec 优化单星任务序列，协调层在卫星之间动态重分配任务。

```text
{parent1, parent2, update1, update2, operationOrder, survivor}
```

## 构建与测试

需要 JDK 21 和 Maven。在仓库根目录执行以下命令。`.` 为测试提供场景路径，单元测试无需实际场景数据。

```sh
mvn -q "-Dautohyb.sceneRoot=." test
```

## 运行调度

调度需要单独准备 `Scene` 数据。每个场景子目录包含 `tasklist.txt`、`outputtimewindow_X.txt` 和 `outputattitude_X.txt`，其中 `X` 是从 1 开始的卫星编号。可通过环境变量 `AUTOHYB_SCENE_ROOT` 或 JVM 参数 `-Dautohyb.sceneRoot` 指定数据根目录。程序也会查找仓库内或仓库同级的 `Scene` 目录。

从仓库根目录运行 `mvn -q compile exec:java` 将启动 `main.MainMFEAMOPHOA`，搜索 6 维混合配置。固定配置的运行入口如下，两者均支持 `--help`。

- [`main.RunSingleHybridConfig`](src/main/java/main/RunSingleHybridConfig.java) 运行指定的 6 维混合配置。
- [`main.RunSingleParentConfig`](src/main/java/main/RunSingleParentConfig.java) 运行由 `algorithmIndex` 指定的单算法。

运行参数见 [`tuning.properties`](tuning.properties)。Hybrid 默认按场景目录名从 `scene-seeds-native290002.properties` 读取种子。使用 `-Devaluation.perRunStart=N` 选择从 0 到 19 的起始索引。使用 `-Devaluation.seedTable.source` 切换到 `native270342`、`native200042` 或 `native410000`，也可通过 `-Devaluation.seedTable.path` 指定自定义表。

[`scripts/`](scripts/) 提供可选的 PowerShell 实验驱动及结果分析脚本。
