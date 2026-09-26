# API Compatibility Analyzer (Java)

在版本演进前，自动发现两份 Java 源码快照之间**公开接口的破坏性变更**，并沿引用关系
定位到**具体的调用位置（文件、行号、命中符号）**。同时提供全量分析与增量更新两条通路，
结论基于内容哈希，可复现；豁免（忽略）声明可随基线持久化。

## 能力一览

1. **新增 / 移除 / 签名变化识别**：基于“属主 + 名称 + 擦除后形参”的稳定 ID 匹配，
   区分 `ADDED` / `REMOVED` / `SIGNATURE_CHANGED`，并给出 `BREAKING` / `COMPATIBLE` 判定。
2. **破坏性变更定位到调用点**：不止接口级结论。沿 import、字段/局部变量声明类型与
   类型继承/实现层级，定位 `文件路径 + 行号 + 命中符号 + 源码片段`。
3. **继承 / 覆盖 / 重载 / 访问级别**：
   - 重载新增 = `ADDED`（兼容），不会被误判为签名变化；
   - 放宽访问级别（protected→public）不报破坏；收窄（public→package/private）报破坏；
   - 返回类型/形参/字段类型变化、方法变 final/abstract、类变 final/abstract、
     向可扩展类型新增抽象方法，均判破坏。
4. **豁免声明**：在元素上标注 `@ApiCompatExempt(reason = "...", change = "REMOVED")`
   即可抑制；豁免结果记录在报告中，并随基线 JSON 一起保存，供后续版本对比。
5. **增量更新**：仅重解析“内容变化的文件 + 其传递依赖闭包”，未变化文件不重复解析；
   增量与全量产出的快照 ID、变更、调用点、报告 ID 完全一致。
6. **并发一致**：分析（读）并发执行，更新（写）独占；报告与当前快照作为**单个不可变版本**
   原子发布，任意时刻读到的报告都是完整一致的，不出现新旧混合。
7. **时间 / 内存边界**：每次运行在独立线程上受统一预算约束。超时、文件数过多、
   单文件过大、堆内存超限时直接失败，**绝不发布半成品报告**，旧报告保持可读。

## 豁免注解

分析器按简单名识别 `ApiCompatExempt`，被分析工程无需引入本类（也提供了可直接引用的
`com.github.guoyyit.apicompatanalyzerjava.annotation.ApiCompatExempt`）。

```java
public class PaymentService {
    @ApiCompatExempt(reason = "调用方已在 v2 前迁移", change = "REMOVED")
    public void legacyCharge() { }
}
```

- `change` 可选：`ADDED` / `REMOVED` / `SIGNATURE_CHANGED` / 省略表示豁免所有变更。
- 被豁免的破坏性变更仍会出现在 `changes` 中，但 `suppressed=true`，且不会产生 `impacts`。
- 豁免记录在 `suppressions` 中，发布基线时一并落盘。

## REST 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/analysis/analyze` | 两份快照全量分析，或给 `baselineId` 用已存基线 |
| POST | `/api/analysis/baselines/{id}` | 发布基线（含豁免），返回 `snapshotId` |
| POST | `/api/analysis/incremental` | 增量更新当前快照并基于基线分析 |
| GET  | `/api/analysis/reports/latest` | 读取最近一次发布的完整报告 |
| DELETE | `/api/analysis/baselines/{id}` | 删除基线 |

全量分析请求示例：

```json
{
  "baselineFiles": [
    {"path": "com/acme/Service.java", "content": "package com.acme; public class Service { public void pay() {} }"}
  ],
  "currentFiles": [
    {"path": "com/acme/Service.java", "content": "package com.acme; public class Service {}"}
  ],
  "options": { "timeoutMillis": 10000, "maxFiles": 5000, "maxFileBytes": 2097152, "maxMemoryMb": 512 }
}
```

报告关键字段：

```json
{
  "reportId": "sha256…",
  "baselineSnapshotId": "…",
  "currentSnapshotId": "…",
  "incremental": false,
  "analyzedFiles": ["com/acme/Service.java"],
  "reusedFiles": [],
  "changes": [
    { "nature": "REMOVED", "verdict": "BREAKING",
      "elementId": "com.acme.Service#pay()",
      "reason": "public API element removed; existing callers/implementations fail",
      "suppressed": false }
  ],
  "impacts": [
    { "elementId": "com.acme.Service#pay()",
      "callSites": [
        { "path": "com/acme/Client.java", "line": 4, "symbol": "pay",
          "kind": "METHOD_CALL", "snippet": "service.pay();" }
      ] }
  ],
  "suppressions": []
}
```

错误以稳定的 `{ "error": "...", "message": "..." }` 返回：
`ANALYSIS_TIMEOUT`(504)、`RESOURCE_LIMIT_EXCEEDED`(422)、`ANALYSIS_REJECTED`(400)。

## 增量通路

1. 先 `POST /api/analysis/baselines/v1` 发布基线；
2. 首次 `POST /api/analysis/incremental` 对当前快照做全量构建（`incremental=false`）；
3. 之后每次提交当前完整文件集，服务按**原始内容哈希**得到增/改/删文件，
   再求其在依赖索引上的传递反向依赖闭包，仅重解析这些文件（`incremental=true`，
   未变文件进入 `reusedFiles`，且不会被再次解析）；
4. 删除的文件自动从模型剔除，新增文件自动纳入。

依赖来源：import、类型头（extends/implements）、方法/字段/构造调用；
通配 import（`com.acme.*`）保守地依赖该包下所有已声明类型，宁可多重解析也不漏报。

## 可复现性

- 文件、快照均以 SHA-256 内容寻址；`reportId` 由“基线快照 ID + 当前快照 ID + 规范化后的
  变更/影响/豁免 JSON”计算，不包含时间戳与耗时；
- 相同输入在任意机器、全量或增量、并发或串行下都得到相同 ID 与相同结论；
- 基线以确定性 JSON 原子写入（临时文件 + move），损坏的半截文件不会被读到。

## 配置（`analyzer.*`）

| 属性 | 默认值 | 含义 |
| --- | --- | --- |
| `timeout-millis` | 60000 | 单次分析墙钟预算 |
| `max-files` | 10000 | 单快照最大文件数 |
| `max-file-bytes` | 2097152 | 单文件最大字节数 |
| `max-memory-mb` | 512 | 软堆内存预算 |
| `baseline-dir` | `./.api-compat/baselines` | 基线 JSON 目录 |

也可在每次请求的 `options` 中逐项覆盖（`0` 表示用默认/不限制）。

## 构建与测试

```bash
mvn test                 # 离线可构建（依赖已缓存时）
mvn spring-boot:run      # 启动服务，默认 8080
```

测试覆盖（39 个，全部无外部网络依赖）：

- 解析器：稳定 ID、泛型擦除、数组、调用点、豁免、非法源码拒绝；
- 差异引擎：新增/移除/重载/访问级别收窄与放宽/返回类型/豁免抑制；
- 影响定位：方法、构造、类型使用、继承链上的调用、豁免不产生 impact；
- 增量一致性：仅重解析变更与受影响依赖、删除/新增、增量 == 全量；
- 服务层：基线发布与复用、豁免入基线、增量等于全量、报告 ID 可复现；
- 边界：超时 504 且无半成品、文件数/文件大小超限拒绝、失败后旧报告仍可读；
- 并发：读者在连续更新期间只观察到内部一致的版本，并发分析不破坏状态；
- REST：全量定位、基线 + 增量 + 最新报告、400/504 错误契约。

## 设计边界（说明）

分析器不依赖 classpath 符号求解，名称依据“显式 import + java.lang + 同包 + 局部/参数
声明类型”解析。这对**同一代码库两份快照的对比**是确定性且充分的；对调用点匹配采用
“名称 + 形参元数 + 层级可达”的保守策略，宁可多列候选调用点，也不漏报真实受影响位置。
