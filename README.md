# api-compat-analyzer-java

源码级公开接口兼容性变更分析服务。在版本演进前自动发现公开 API 的破坏性变更，
并沿引用关系定位受影响的调用位置（文件、行号、命中符号），避免人工评审漏判。

## 能力总览

| 需求 | 实现 |
| --- | --- |
| 两份快照或基线+当前版本对比 | `POST /api/analysis/baseline` + `POST /api/analysis/full` |
| 破坏性/兼容变更分类 | `ApiDiffEngine`：REMOVED / SIGNATURE_CHANGED / ACCESS_NARROWED 为破坏性；ADDED / ACCESS_WIDENED 等为兼容 |
| 调用位置定位 | 每个 `ApiChange.affectedCallSites` 含 file、line、column、enclosingSymbol |
| 继承/覆盖/重载/访问级别 | `SnapshotBuilder` 做继承展开（有效 API），重载按签名区分，覆盖抑制误报 |
| 源码豁免标注 | `@ApiCompatIgnore` 注解或 `// api-compat:ignore` 注释；可随基线持久化 |
| 增量更新 | `POST /api/analysis/incremental`，仅重解析变更文件及其依赖，结果与全量一致 |
| 并发一致 | 不可变报告 + 原子发布；更新串行化，读取无锁，永不出现新旧混合 |
| 确定性报告 | 报告标识由基线与当前输入的完整内容派生；无时间/进程字段；同输入永远同报告 |
| 时间/内存边界 | 超时即取消且不发布任何报告；输入规模超限直接拒绝 |

## 构建与测试

```bash
mvn compile     # 编译
mvn test        # 运行全部自动化测试（52 个用例）
mvn spring-boot:run   # 启动服务（默认 8080 端口）
```

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
  "reportVersion": "report-3f9a…（输入内容哈希，确定性）",
  "baselineVersion": "1.0",
  "currentVersion": "2.0",
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

## 确定性报告规则

报告完全由输入内容决定，不含任何运行时刻信息：

- `reportVersion` = `report-` + SHA-256，哈希输入为基线与当前两份源码树
  全部文件的路径 → 内容哈希（按路径排序），以及两个版本标签。
- 报告不再包含生成时间字段（原 `generatedAt` 已移除），也不含序号、PID、
  主机名等任何进程状态信息。
- 变更条目按 `elementKey`、变更类型、调用位置（文件/行/列/命中符号）稳定排序。
- 同一份基线与当前输入，无论执行多少次、由哪个进程执行，报告（含标识与
  全部条目）逐字节一致；任何输入文件或版本标签变化都会改变标识。
- 基线保存到磁盘（`/baseline/save`）时连同文件哈希与继承层级一起持久化，
  服务重启后 `/baseline/load` 再分析，得到与之前完全相同的报告。

### 3. 增量更新（只传变化的文件）

```bash
curl -X POST localhost:8080/api/analysis/incremental \
  -H 'Content-Type: application/json' \
  -d '{"version": "2.0.1", "changed": {"Service.java": "..."}, "deleted": ["Old.java"]}'
```

增量通路按文件内容哈希识别变化，沿依赖图（类型引用）扩散到受影响文件，
仅对这些文件重新解析，其余文件复用缓存结果；结论（变更条目、受影响位置
与报告标识）与全量分析逐字节一致，未变化且不依赖变化文件的文件不会被重解析。

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
- **可继承公开类型新增抽象方法**：新增的抽象方法是破坏性变更。旧版本中
  继承该类型但未实现该方法的具体下游实现类型，以及实现该接口的具体类型，
  都会被列为受影响位置，给出实现类型声明处的文件与行号，以及这些类型的
  使用位置（`new`、字段/参数/局部变量上的方法调用等）。
- **公开接口新增抽象方法**：旧版本中实现该接口但未实现新方法的具体类型为
  受影响位置（文件、行号、命中符号）；通过接口类型的多态调用归属接口本身。
- **直接父类变化（`SUPERCLASS_CHANGED`）**：类型的直接父类被更换、移除或
  新增父类均为破坏性变更，受影响子类（含传递子类）的声明处与使用位置都会
  列出；父类成员经继承传导到子类的增删，调用位置同样给出文件、行号与符号。

兼容（breaking=false）：

- 新增类型/方法/字段（含新增重载）
- 接口新增带 `default` 实现的方法（实现类无需改动，绝不误报）
- 访问级别放宽、移除 `final`/`abstract`、新增 `static`

### 父类变化处理

- 解析阶段为每个类型记录“直接父类”和“直接实现/继承的接口”，以及类型是否
  可实例化（非 abstract 的 class/enum/record），随快照与基线一起保存。
- Diff 阶段对比新旧层级：仅直接 `extends` 的父类发生更换/移除/新增时产生
  一条 `SUPERCLASS_CHANGED` 破坏性变更；实现的接口列表变化不按此规则报。
- 受影响集合 = 类型自身 + 沿 `extends` 边到达的全部子类（含传递），每个类型
  给出声明位置（文件、行号、命中符号为类型 FQN）与全部使用位置。
- 父类成员的传导（例如旧父类方法在新父类不存在）由继承展开后的有效 API
  对比自动检出，子类调用位置同样带文件、行号、列与外层符号。
- 旧版持久化基线若不含层级信息，则不产生父类变更（与历史基线兼容）。

## 边界与限制

- 超时（`analysis.timeout-millis`）或输入超限（`analysis.max-files` /
  `analysis.max-total-bytes`）时不发布任何报告，已发布报告保持不变。
- 发布以“快照 + 报告 + 缓存工作状态”整体原子替换完成；并发读到的永远是
  某一次完整发布的版本，报告与版本信息自洽。相同输入并发执行多次，所有
  返回值与最终发布报告完全相同。
- 失败后重试同一输入，得到的报告与从未失败时完全相同（包括报告标识）。
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
