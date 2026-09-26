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
| 时间/内存边界 | 超时即取消且不发布任何报告；输入规模超限直接拒绝 |

## 构建与测试

```bash
mvn compile     # 编译
mvn test        # 运行全部自动化测试（29 个用例）
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
  "reportVersion": "report-2",
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

兼容（breaking=false）：

- 新增类型/方法/字段（含新增重载）
- 访问级别放宽、移除 `final`/`abstract`、新增 `static`

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
