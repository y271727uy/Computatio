# Computatio / FRMC 算法库审计报告

审计对象：`Computatio` 当前源码、`FarmersRanchMixinCore`（Forge 1.20.1 / Java 17）源码，以及指定整合包 `mods` 目录的文件名。此报告是静态审计，不等同于游戏性能采样；所列热点是源码中出现的高频/大范围工作形态，是否是整合包实际瓶颈仍须用 spark/JFR 与基准确认。

## 总结

Computatio 的定位应是**可嵌入 Minecraft 模组的纯 Java、低分配、可复用算法原语**，而不是把通用算法库堆到 FRMC。最直接且已经成立的用例，是 FRMC 的配方多重集索引：`RecipeDB` 为配方构建候选索引，按容器输入快速缩小候选，再由原始 `Recipe.matches` 校验。因此首要工作不是再做 Lucene、图论、机器学习，而是把现有索引的正确性、查询分配、更新生命周期、基准和可增量空间索引做好。

推荐顺序：

1. **P0：配方多重集索引的基准与边界完善。** 独立于 Minecraft 的 JMH 基准覆盖配方数、配方输入特征数、tag 展开度、容器槽位数、重复物品、无可索引配方比例和查询命中率；与线性扫描、FRMC 当前实现、Fast Recipe Search 做相同语义对照。再针对测出的分配热点改进查询临时对象/键数组/排序。
2. **P1：可增量维护的区块兴趣点索引（BlockPos -> 类型/属性 -> 坐标）。** 服务巢穴、可采集物、特定方块实体、农场/采集 AI 等“每次在附近区块找少量匹配目标”的查询。必须按方块实体/区块加载卸载/区块重载事件维护，限制为已加载区块，不得为了查找而加载区块；改变后保持 vanilla/mod 行为语义。先为 FRMC 某一个有实测收益的查询做适配，不做全局替换 Minecraft 空间查询。
3. **P1：候选索引与评分/过滤器组合原语。** 针对 modded AI 的重复实体/方块候选查询，提供可复用的快速过滤和距离 Top-K/最近目标选择，避免临时集合、全量排序和重复能力检查。Minecraft 的实体空间树仍应由 MC 自己负责；本库不应复制实体世界索引。
4. **P2：位图/稀疏集合交并、压缩整数列表仅在真实集合/日志/快照工作流出现后实施。** FRMC 有区块压力快照、实体活动统计等数据，但目前没有证据表明它们的存储或集合代数是主耗时。

## 已有代码及其含义

- Computatio 目前主要代码为 `algorithm.multisetindex`，并依赖 fastutil 8.5.12；Java 编译目标为 17。
- FRMC 已在 `RecipeDB` 直接使用 `IntLongMultiset`、`FeatureCountTable`、`AbstractMultisetIndex`。这是已有生产集成，不是未来假想用例。
- FRMC 对 recipe search 有配置限制：默认仅优化 vanilla recipe type，且默认只为原版 recipe 类构建索引；有自定义 ingredient 适配、Polymorph 路径及线性回退。因此任何新索引要保留自定义 recipe/ingredient 的保守回退和语义校验，不应假设所有配方都可按“物品集合包含”判定。
- FRMC 已有 `RecipeSearchCoreTest`，覆盖重复计数、fallback、完整结果、深树等基本性质；但没有看到独立的 JMH/吞吐-分配基准。`RecipeDB.getAll` 还会排序结果，调用方是否需要稳定排序及其成本需要基准验证。
- 项目有用于游戏世界对象的空间查询和降频代码。直接替代/复制 MC 的实体空间索引风险高；增量**方块兴趣点索引**更可能是库侧有价值的边界。

## 推荐实现及理由

| 优先级 | 算法/结构 | 用途与理由 | 前置条件/风险 |
|---|---|---|---|
| P0 | 可基准的多重集倒排/前缀候选索引 | 配方输入可抽象为 feature 多重集；稀有特征先检索可压缩候选，最后原版 `matches` 保证语义。它已经直接服务 FRMC。 | 先建立与线性实现同语义基线。索引不可代替 recipe `matches`；tag/自定义 Ingredient/容器数量语义要覆盖。 |
| P0 | 查询 scratch/context（复用但线程隔离） | 查询当前构造临时 `IntLongMultiset`、键数组和结果集合。若 JFR 显示 recipe lookup 是高频且分配突出，可用调用方 scratch 或安全的线程本地缓冲降低 GC。 | 不共享可变缓存给并发线程；不要跨世界/重载保留旧 registry 数据；先量分配率。 |
| P1 | 可增量区块兴趣点索引 | 以 chunk 为作用域，按 POI 类型分桶；为巢穴、作物、特定 BlockEntity 目标查询减少扫描整个 chunk 的 BE map。FRMC `NestSearchHelper` 已在 3x3 左右 chunks 遍历 block entities 后筛 nest，说明形态确实存在。 | 加载状态、chunk unload、方块替换、BE 生命周期、世界卸载都需完整维护。只读已加载区块；未验证事件覆盖前不可上生产。 |
| P1 | nearest/top-k 候选筛选器 | 一些 AI/程序在邻域筛实体或目标，按 squared distance 逐步保留最优项通常比收集全部再排序节省内存和排序工作。 | 只有“使用全排序/临时列表”的调用点才有收益；MC `getEntitiesOfClass` 仍通常负责空间粗筛。过滤、距离平局、实体有效性必须保持语义。 |
| P2 | Long/BitSet 稀疏集合运算、Roaring 风格容器 | 区块/实体/特征 ID 的批量交并差可能有潜力，尤其是多个过滤条件复用同一候选集合时。 | 目前没有调用证据证明是瓶颈；若集合规模小或更新频繁，压缩/容器切换会更慢、更复杂。先拿真实分布测。 |
| P2 | 基于固定整数编码的差分/位打包 | 只适合大量可序列化整数（分析快照、离线统计、缓存文件）。 | 不能因为 JavaFastPFOR 的数据库吞吐数据就推断适用于主线程 MC 查找；游戏当前未见海量整数流编解码热路径。 |

### 配方索引需要特别审计的正确性点

- 特征 key 使用 registry id 的 `hashCode` 会有碰撞可能；哈希碰撞不得导致漏配方或错误拒绝。索引只能做无假阴性的候选过滤，最终由 recipe 实际匹配确定。
- `RecipeDB.extractIntMap` 仅对特定 vanilla Ingredient 形态建立精确 key，对复杂 ingredient 回退是正确方向。任何扩展都要证明候选过滤不会漏掉可匹配 recipe。
- `maxInputAmount > 1` 时才做输入多重集匹配；槽位数、堆叠计数、shaped/shapeless、重复 ingredient、锅等非普通容器需要单独验证。
- 热重载/`replaceRecipes` 后所有派生索引、scratch 快照和缓存必须失效；不能持有旧 registry/recipe 引用。
- `search` 返回完整结果时的排序合同要写清。结果顺序若是 API 行为必须保留；若仅为确定性/兼容性需要，要测排序成本，不能直接删。

## 不建议写入 Computatio 的算法/库

| 项目 | 结论 | 原因 |
|---|---|---|
| JGraphT（最短路、最大流、匹配、TSP） | 不做通用依赖 | FRMC 当前源码没有游戏拓扑图、最短路或网络流热点。MC 已有导航/寻路系统，替换会破坏实体 AI 语义并与其实现重复。仅在将来出现独立的物流/路网优化问题时评估窄算法。 |
| ojAlgo、Choco、LogicNG、Timefold、Jenetics | 不做 | 线性/整数优化、SAT/约束求解、调度和进化计算不是当前每 tick 热路径；体积、启动/分配成本和调试复杂度不匹配。适合离线配方规划器/自动工厂规划器，若做成用户功能应独立模块而非核心性能依赖。 |
| Smile、Tribuo、DJL、自动微分 | 不做 | 没有训练/推理需求；模型/矩阵库增加体积、初始化成本和不可控分配，推理也不应进入 server tick。 |
| Apache Lucene | 不做 | 配方查询不是全文检索；FRMC 的多重集索引更贴近问题。JEI/Searchables/Fast Recipe Search 已覆盖用户侧检索生态，Lucene 会增加依赖与索引同步成本。 |
| JTS、Tinfour、计算几何 | 不做 | 游戏世界使用离散方块、AABB、现有导航与区块空间结构；通用 GIS 拓扑不是此项目的热点。 |
| Aho-Corasick、JavaBDD、密码学、FFT/信号处理 | 不做 | 未发现运行时对应问题；仅因算法复杂或“值得读源码”而加入不构成产品收益。字符串/加密/频谱没有当前主线程数据流。 |
| JavaFastPFOR、Stream VByte、Aircompressor、XZ | 暂不实现 | 只在大量整数或字节压缩确实占用存储/IO/网络时值得。Minecraft 网络协议、区块压缩和存档压缩由 MC/网络层负责，改写会有兼容性和数据损坏风险。 |
| JCTools、Agrona、Chronicle、off-heap/Unsafe | 不作为默认依赖 | 当前库没有跨线程高吞吐队列或持久化堆外数据。MC 世界状态通常受主线程约束；异步访问世界会引入线程安全/生命周期问题。Java 17 下 Unsafe/off-heap 也提高 JDK 与崩溃风险。 |
| fastutil 再造轮子 / Eclipse Collections / HPPC / Koloboke | 不做新通用集合 | Computatio 已直接依赖 fastutil，FRMC 也使用 fastutil。更换集合库难以证明胜过现有实现，容易造成重复依赖和映射/维护成本。只在 JMH 证明某个专用结构收益明显时写窄型结构。 |
| RoaringBitmap 通用移植 | 先不做 | 当前无成熟 bitmap 批量集合场景；Java `BitSet` 或 fastutil primitive set 可能已足够。只在稀疏/稠密分布实测支持容器切换后实现，不要仅凭 Spark/数据库案例决策。 |

## 已启用优化模组的边界与冲突风险

指定目录中实际存在（按 `.jar` 文件名判断，未核验每个 jar 在启动时是否成功加载）：

- **世界生成/噪声**：FastChunkGen、Noisium、Structure Layout Optimizer。Computatio 不应再做 chunk generation、噪声采样或结构布局优化。
- **区块/方块 tick 与服务端常规优化**：ModernFix、Radium、Immersive Optimization、AI Improvements、Starlight。对全局 tick 调度、光照、通用 mob AI 降频等改动高度可能重叠；仅做 FRMC 针对特定 mod 的窄适配，并检查配置/禁用机制。
- **渲染与剔除**：Embeddium、Oculus、EntityCulling、ImmediatelyFast、Chloride、Accelerated Rendering。不要在 Computatio 做渲染剔除、模型/顶点缓冲、显卡路径、光照传播优化。
- **内存/数据结构**：FerriteCore。避免重复做全局状态/模型/注册表内存压缩；算法库只处理明确的查询热点。
- **配方与搜索**：JEI、Searchables、Polymorph，以及 `Fast-Recipe-Search-forge-1.20` 参考项目。整合包目录列表中没有明显启用 Fast Recipe Search jar，但 FRMC build.gradle 注明了 third-party 参考来源且项目存在对应 jar 资源；是否真实运行以启动日志/mod 列表为准。FRMC 自身配方搜索默认限制为 vanilla type/class，避免无条件接管所有 modded recipe。新增方案须与 JEI 的展示搜索、Polymorph 的配方选择和 RecipeManager 查找语义区分。

特别提醒：列出的性能 mod 中存在跨版本名（如 ImmediatelyFast 文件名含 1.20.4），仅凭文件名不能判断运行兼容/实际加载。冲突结论应最终以启动日志中的 mod 加载状态、Mixin apply 日志和配置为准。

## FRMC 源码指向的真实热点候选与遗漏算法

以下是代码形态证据，不是已测定的 TPS 排名：

1. **Modded AI 的重复邻域搜索**：FRMC 已对多种 Untamed Wilds/Alex's Mobs/Forager's Insight 行为做搜索降频；说明“行为树/Goal 高频执行邻域扫描”是项目已遇到的类别。尚未统一解决的方向不是全局替换 vanilla AI，而是对同一个 mob/goal 在状态未变时复用目标候选，并以 chunk/实体变化失效；缓存必须含世界、目标类型、查询边界和配置版本。
2. **巢穴/作物/采集目标的方块实体或方块扫描**：`NestSearchHelper` 遍历附近 loaded chunks 的全部 BlockEntity 后才筛巢穴；可作为首个兴趣点索引试点。类似地，`ForagersInsight` 与 Vietnam Delight 的扫描类调用已有局部优化，注意别重复覆盖同一调用点。
3. **Create 机械网络/物流**：整合包装有 Create 6.0.8，但 FRMC 当前 dependency 仅 compileOnly，未见可复用的 Create 网络图算法接入。Create kinetic network 的拓扑更新和旋转传播可能昂贵，但其核心实现由 Create 管理，跨版本兼容/同步风险高。若 spark 确认大规模轴系/应力计算占用，应单独针对 Create 版本源码审计与 benchmark，不要把通用图算法库直接套到网络上。
4. **配方查询调用频率与候选成本**：已有索引可能让单次查询更快，但 Create/Farmer's Delight 等机器的高频调用、recipe reload 重建、`getRecipesFor` 全量结果排序、hash collision 和复杂配方回退都可能成为残余热点。需要记录每类型调用计数、候选数、matches 次数、构建耗时和分配，不要只看单次搜索耗时。
5. **大规模实体/方块实体区块压力统计**：FRMC 已维护 chunk pressure/overlay 一类数据，但扫描器读取 `getAllEntities()` 的形态存在潜在 O(全世界实体数) 风险。若其按周期执行，专用按 chunk 增量聚合/快照可能比引入通用压缩库更有意义；先核对执行周期、采样耗时及实体归属变化事件。
6. **整合包农场/动物密度与繁殖逻辑**：此包包含大量农作物、动物、食物与村庄/生物模组，mob 数、作物随机刻、BlockEntity 数可能成为真实热点。但已启用 AI Improvements、Radium、Immersive Optimization 与 Noisium 等；只有 profile 显示特定 mod 的目标选择/每刻逻辑仍占显著时间，才新增 FRMC 定向优化。

## 建议基准及准入标准

- 算法单测：结果与基线穷举一致；包含碰撞模拟、重复元素、空输入、未知 key、超大 key 数、reload 后失效、多线程独立查询、随机生成 property test。
- JMH：分别测 build 与 query；报告 ops/s、平均/p95/p99（若 harness 支持）、`gc.alloc.rate.norm`、索引内存；按 10/100/1k/10k 配方以及不同稀疏度/命中率分组，至少 fork/warmup/measurement 合理配置。
- 游戏验证：Spark profiler 采集同一世界/同一场景优化前后服务端 tick 火焰图；记录 MSPT、GC pause、实体/BE 数、配方调用量。测试整合包全 mod 与主要冲突模组开启，确认逻辑结果不变。
- 只有在代表性场景有稳定收益且没有明显 p99/内存/加载回退时，算法才进入默认启用路径。若收益局限于特定模组，按 mod 存在性隔离并提供配置开关。

## 结论

Computatio 的方向应收敛为：**已用的配方多重集索引（可靠性和基准） + 可增量的 Minecraft 领域无关 primitive 索引原语（首先是区块兴趣点查询） + 少量可证明减少分配的候选筛选结构**。不建议为了覆盖“图论、求解器、机器学习、压缩、并发”而引入库或开发算法；它们与当前 FRMC/整合包的热点缺乏因果证据，且会增大依赖、兼容和语义风险。
