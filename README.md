# api-compat-analyzer-java

源码级公开接口兼容性变更分析服务。在版本演进前自动发现公开 API 的破坏性变更，
并沿引用关系定位受影响的调用位置（文件、行号、命中符号），避免人工评审漏判。

## 能力总览

| 需求 | 实现 |
| --- | --- |
| 两份快照或基线+当前版本对比 | `POST /api/analysis/baseline` + `POST /api/analysis/full` |
| 破坏性/兼容变更分类 | `ApiDiffEngine`：REMOVED / SIGNATURE_CHANGED / ACCESS_NARROWED / ABSTRACT_METHOD_ADDED / SUPERCLASS_CHANGED 为破坏性；ADDED / ACCESS_WIDENED 等为兼容 |
| 调用位置定位 | 每个 `ApiChange.affectedCallSites` 含 file、line、column、enclosingSymbol |
| 继承/覆盖/重载/访问级别/父类变化 | `SnapshotBuilder` 做继承展开（有效 API），重载按签名区分，覆盖抑制误报，直接父类更换/移除/新增单独成类 |
| 源码豁免标注 | `@ApiCompatIgnore` 注解或 `// api-compat:ignore` 注释；可随基线持久化 |
| 增量更新 | `POST /api/analysis/incremental`，仅重解析变更文件及其依赖，结果与全量一致 |
| 确定性报告 | 报告标识由基线与当前输入完整内容的 SHA-256 决定；时间字段固定，重复执行逐字节一致 |
| 并发一致 | 快照与报告作为不可变整体单次原子发布；更新串行化，读取无锁，永不出现新旧混合 |
| 时间/内存边界 | 超时即取消且不发布任何报告；输入规模超限直接拒绝；失败后重试结果与未失败时一致 |

## 构建与测试

```bash
mvn compile     # 编译
mvn test        # 运行全部自动化测试（44 个用例）
mvn spring-boot:run   # 启动服务（默认 8080 端口）
```

验证要点（`mvn test` 中的 `ApiEvolutionTest` 等）：

- 确定性：重复执行、仅改动注释/版本号、基线落盘后重启恢复，分别断言报告
  标识与 JSON 序列化结果
- 抽象方法新增、接口方法新增（含默认方法兼容）、父类更换/移除/新增及父类
  成员传导：断言变更种类、`breaking` 与受影响文件/行号/命中符号
- 增量与全量一致：报告标识与 JSON 完全相同；用计数解析器证明无关文件不重解析
- 并发一致：相同输入并发分析返回相同报告，读写并发下报告与快照版本始终自洽
- 失败重试：解析失败与超时均不覆盖旧报告，重试结果与一次成功完全一致

## REST 使用说明

### 1. 设置基线（旧版本源码）

```bash
curl -X POST localhost:8080/api/analysis/baseline \
  -H 'Content-Type: application/json' \
  -d '{"version": "1.0", "sources": {"Service.java": "package com.example; public class Service { public int compute(int x){return x;} }"}}'
```

### 2. 全量分析（当前版本源码 vs 基线）

```bash
curl -X POST localhost:8080/api/analysis/full \
  -H 'Content-Type: application/json' \
  -d '{"version": "2.0", "sources": {"Service.java": "...", "Caller.java": "..."}}'
```

响应 `CompatReport`：

```json
{
  "reportVersion": "report-8c1a...e2f",
  "baselineVersion": "1.0",
  "currentVersion": "2.0",
  "generatedAt": "1970-01-01T00:00:00Z",
  "changes": [
    {
      "kind": "REMOVED",
      "breaking": true,
      "suppressed": false,
      "elementKey": "METHOD:com.example.Service#compute(int)",
      "description": "Method com.example.Service#compute(int) was removed",
      "affectedCallSites": [
        {"file": "Caller.java", "line": 5, "column": 20,
         "enclosingSymbol": "com.example.Caller#run()", "referencedKey": "MEMBER:com.example.Service#compute/1"}
      ]
    }
  ]
}
```

`reportVersion` 是基线输入与当前输入完整内容（版本号 + 每个文件的路径与正文）
SHA-256 组合得到的确定性标识；`generatedAt` 固定为 `1970-01-01T00:00:00Z`，
不含任何运行时刻信息。

### 3. 增量更新（只传变化的文件）

```bash
curl -X POST localhost:8080/api/analysis/incremental \
  -H 'Content-Type: application/json' \
  -d '{"version": "2.0.1", "changed": {"Service.java": "..."}, "deleted": ["Old.java"]}'
```

增量通路按文件内容哈希识别变化，沿依赖图（类型引用）扩散到受影响文件，
仅对这些文件重新解析，其余文件复用缓存结果；结论与全量分析完全一致。

### 4. 读取最新报告

```bash
curl localhost:8080/api/analysis/report    # 无报告时返回 404
```

### 5. 基线持久化（豁免随基线复用）

```bash
curl -X POST 'localhost:8080/api/analysis/baseline/save?path=/tmp/baseline.json'
curl -X POST 'localhost:8080/api/analysis/baseline/load?path=/tmp/baseline.json'
curl -X POST localhost:8080/api/analysis/baseline/promote   # 当前版本晋升为基线
```

## 源码豁免声明

两种方式，按简单名匹配，被豁免的变更 `suppressed=true` 且不计入 `breakingCount`：

```java
public class Legacy {
    @ApiCompatIgnore(reason = "planned removal")   // 方式一：注解
    public void oldWay() {}

    // api-compat:ignore                            // 方式二：注释标记
    public void olderWay() {}
}
```

标注在类型上时，该类型的全部成员（含嵌套类型）都被豁免。

## 变更分类规则

破坏性（breaking=true）：

- 公开/受保护的类型、方法、构造器、字段被移除（含继承而来的有效成员）
- 访问级别收窄（public → protected/package/private）
- 返回类型/字段类型变化、类型种类变化（class ↔ interface 等）
- 新增 `final`（类/方法/字段）、新增 `abstract`、移除 `static`
- 可被继承的公开类型新增抽象方法（`ABSTRACT_METHOD_ADDED`）：类新增 `abstract`
  方法，或接口新增无 `default`/`static` 的方法。所有未继承到具体实现的
  下游具体实现类型（含跨抽象中间类型的传递实现）及其使用位置列入受影响，
  给出文件、行号与命中符号；已自行实现或继承到默认实现的类型不报
- 类型的直接父类发生新增、移除或更换（`SUPERCLASS_CHANGED`）：子类自身与
  传递子类及其使用位置列入受影响；父类成员变化经继承展开传导到子类时，
  失效的调用位置同样给出文件、行号与命中符号

兼容（breaking=false）：

- 新增类型/方法/字段（含新增重载）；接口新增带 `default` 的方法不要求实现方改动
- 访问级别放宽、移除 `final`/`abstract`、新增 `static`

## 确定性报告规则

- 同一份基线输入与当前输入，无论执行多少次、是否跨进程重启，报告的每个
  字段（含 `reportVersion`、`generatedAt`、条目顺序）都完全一致，可直接
  逐字节比对
- `reportVersion = "report-" + SHA256(基线指纹, 当前指纹)`；指纹覆盖版本号
  与全部文件的路径和正文，按文件名字典序规范化，输入的任何变化（哪怕只是
  注释或空白）都会改变标识
- 基线持久化（`/baseline/save`）时一并保存原始输入指纹；服务重启后重新
  加载同一基线，报告标识保持不变。旧基线文件缺少指纹时，由持久化的 API
  要素与类型层次确定性地推导兜底指纹
- `generatedAt` 固定为 EPOCH（`1970-01-01T00:00:00Z`），报告不含时间戳、
  自增序号、进程标识或任何运行时刻状态；条目按元素键与变更种类稳定排序
- 全量通路与增量通路对同一棵树产生的报告（含标识）完全一致；增量只重解析
  内容变化文件及沿依赖图受影响的文件，未变化且无依赖关系的文件不重解析
- 并发读写时，快照与报告在同一个不可变对象中一次性原子发布，读者只能看到
  某次完整发布的版本且报告与版本信息自洽；对相同输入并发分析，所有返回与
  最终发布的报告相同
- 超时或资源不足时不发布部分结果，已发布的上一份报告保持完整可读；失败后
  以同一输入重试，得到的报告与从未失败时完全相同

## 边界与限制

- 超时（`analysis.timeout-millis`）或输入超限（`analysis.max-files` /
  `analysis.max-total-bytes`）时不发布任何报告，已发布报告保持不变。
- 解析基于源码语法树（JavaParser），不做 classpath 符号解析；调用位置的
  归属类型通过 import、同包类型、字段/参数/局部变量声明静态推断，
  未知归属的调用按方法名+参数个数保守匹配。
- 继承展开仅覆盖被分析源码集合内的类型；JDK/第三方父类成员不在 API 面内。

## 模块结构

```
parse/      JavaSourceParser：源码 → API 元素 + 调用位置 + 依赖 + 豁免
increment/  SnapshotBuilder（快照合并、继承展开、依赖图）、IncrementalAnalyzer（脏文件集）
diff/       ApiDiffEngine：快照对比、变更分类、调用位置关联
service/    AnalysisService（编排/并发/超时）、BaselineStore（基线持久化）
suppress/   SuppressionStore、ApiCompatIgnore 注解
web/        AnalysisController（REST）、异常映射
```
