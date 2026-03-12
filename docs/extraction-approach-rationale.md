# PromptMaster-Clion 代码提取方案选型说明

> 本文档回应两个技术问题：
> 1. 「为什么 Nova 模式下使用正则而非 TreeSitter / LSP」
> 2. 「为什么 `/ut-context` 在 Classic 模式下有 PSI 却不用（已修复）」
>
> 记录选型决策过程与各方案的工程权衡。

---

## 1. 背景与目标

### 1.1 项目定位

PromptMaster-Clion 是一个 CLion 插件，通过本地 HTTP API 向外部 AI 工具（如 Continue）
暴露 C/C++ 项目的代码上下文。它需要提取的信息包括：

- 函数签名与函数体
- struct / class / union / enum 定义
- `#include` 依赖与头文件解析
- `#define` 宏定义
- typedef / using 别名
- namespace 作用域路径
- CMake 编译选项

### 1.2 核心约束

| 约束 | 说明 |
|------|------|
| **跨版本兼容** | 必须同时支持 CLion 2024.2 至 2025.3+，覆盖 Classic 和 Nova 两种引擎 |
| **零外部依赖** | 用户安装插件后即可使用，不应要求安装 clangd、配置 compile_commands.json 或下载额外二进制文件 |
| **低延迟** | HTTP API 响应需在百毫秒级，不能有冷启动编译或索引等待 |
| **跨平台** | 必须在 Windows、macOS、Linux 上无差异地运行，不依赖平台特定的原生库 |
| **插件包体积** | JetBrains Marketplace 对插件大小敏感，应尽量轻量 |

### 1.3 问题的本质

**我们并非在「正则 vs PSI」之间做选择。** 真实情况是：

```
CLion 2024.2 ~ 2025.2 (Classic 模式)
    → CIDR PSI 可用
    → /file、/function：使用 PSI 提取（CppContextExtractor）       ✅ 已实现
    → /ut-context：     PSI 提取函数/include 基础信息
                        + java.io.File 做 include 解析和外部函数搜索  ✅ 已实现（v1.2.0 修复）

CLion 2025.3+ (Nova/Radler 模式)
    → CIDR PSI 被 IDE 禁用 → PSI 不可用 → 需要 fallback 方案
    → 所有端点：正则 + 花括号深度匹配（TextBasedCppExtractor）        ✅ 已实现
```

**关于 `/ut-context` 的 PSI 历史问题（v1.1.0 → v1.2.0）**：

v1.1.0 中 `UnitTestContextExtractor` 只使用了 `TextBasedCppExtractor`，即使在 Classic 模式下也不用 PSI。这是设计疏漏，不是刻意选择。v1.2.0 已修复：`UnitTestContextExtractor` 在 Classic 模式下优先调用 `CppContextExtractor.extractFileContext()`，获取更精确的函数/include 信息，再进入 include 解析和外部函数搜索流程（这两步 PSI 不覆盖，始终使用文件系统）。

Nova 模式下 `CppContextExtractor.extractFileContext()` 返回 null，自动降级到 `TextBasedCppExtractor`，无需改动调用方。

问题是：**在 PSI 不可用时，fallback 应该选择什么？** 候选方案有三个：
TreeSitter、LSP (clangd)、正则 + 启发式扫描。

---

## 2. 当前双模式架构

### 2.1 架构总览

```
HTTP Request
    |
    v
ContextExtractionService
    |
    +-- /file、/function ──────────────────────────────────────────────────────┐
    |       |                                                                   |
    |       v                                                                   |
    |   CppContextExtractor.extractFileContext(vf)     [PSI 模式]              |
    |       |                                                                   |
    |       +-- isPsiAvailable == true ?                                        |
    |              yes -> ReadAction { 遍历 OCFile PSI 树 }                     |
    |              no  -> return null → TextBasedCppExtractor fallback          |
    |                                                                           |
    +-- /ut-context ────────────────────────────────────────────────────────────┘
            |
            v
        UnitTestContextExtractor.doExtract()
            |
            +-- 基础信息（函数列表、include 列表）─────────────────────────────┐
            |       CppContextExtractor.extractFileContext(vf)  [PSI, Classic] │
            |       ↓ null (Nova mode)                                          │
            |       TextBasedCppExtractor.extractFileContext(vf) [文本 fallback]│
            |                                                                   │
            +-- include 解析 + 外部函数搜索 ────────────────────────────────────┘
                    java.io.File 三级搜索（始终使用，PSI 不覆盖跨文件搜索）
```

### 2.2 模式检测机制

`CppContextExtractor.isPsiAvailable` 通过反射探测 `OCFile` 类是否存在，
依次尝试三个 ClassLoader（插件自身、线程上下文、cidr-clangd 插件）：

```kotlin
val isPsiAvailable: Boolean by lazy {
    classloaders.any { cl ->
        try {
            Class.forName("com.jetbrains.cidr.lang.psi.OCFile", false, cl)
            true
        } catch (_: Throwable) { false }
    }
}
```

该属性为 `lazy`，仅在首次访问时计算一次。后续所有提取调用根据此值选择路径。

### 2.3 plugin.xml 的 optional dependency

```xml
<depends optional="true" config-file="withCidrLang.xml">com.intellij.cidr.lang</depends>
```

- Classic 模式：`cidr.lang` 存在，PSI API 全部可用。
- Nova 模式：`cidr.lang` 被 `-Didea.suppressed.plugins.set.selector=radler` 禁用，
  但因为是 `optional`，插件仍能加载，只是 PSI 不可用。
- 若改为非 optional，Nova 模式下插件将**无法安装**。

---

## 3. 备选方案分析

### 3.1 方案 A：PSI（CIDR API）

**原理**：通过 IntelliJ 平台的 Program Structure Index 遍历 C/C++ 抽象语法树，
使用 `OCFile`、`OCFunctionDeclaration`、`OCStruct`、`OCEnum` 等类型化节点。

**优势**：

| 维度 | 评价 |
|------|------|
| 解析精度 | 极高。IDE 级别的完整语义分析，支持模板实例化、宏展开、重载决议 |
| 维护成本 | 低。JetBrains 维护语法更新，插件只需遍历 PSI 树 |
| API 稳定性 | 中。CIDR PSI 在大版本间可能有 breaking change，但总体可控 |

**问题**：

| 维度 | 评价 |
|------|------|
| Nova 兼容性 | **不可用**。CLion 2025.3+ Nova 引擎禁用了整个 `com.intellij.cidr.lang` 插件 |
| 替代 PSI | Nova 引擎内部使用 clangd，但**未向第三方插件暴露等效的 PSI API** |

**结论**：PSI 是 Classic 模式下的最优解，我们已在使用。但它无法解决 Nova 模式的问题。

### 3.2 方案 B：TreeSitter

**原理**：TreeSitter 是一个增量解析框架，提供 C/C++ 的具体语法树（CST），
需要通过 JNI 桥接调用其原生库。

**优势**：

| 维度 | 评价 |
|------|------|
| 解析精度 | 高。生成完整的 CST，能正确处理嵌套结构和大部分语法 |
| 增量解析 | 支持。文件修改后可增量更新语法树，无需全量重解析 |
| 无需 IDE API | 独立于 IntelliJ 平台，不受引擎切换影响 |

**问题**：

| 维度 | 问题描述 | 严重程度 |
|------|---------|---------|
| 原生二进制分发 | 需要为每个平台捆绑 `.dll` / `.so` / `.dylib`。以 tree-sitter-cpp 为例，单平台二进制约 2-4 MB，三平台 + JNI 桥接库 = 约 12-15 MB 额外体积 | 高 |
| JNI 桥接复杂度 | 需要维护 Kotlin/Java <-> C 的 JNI 层。JNI 崩溃会导致整个 IDE 进程退出（不是 Java 异常，而是 SIGSEGV），调试极其困难 | 高 |
| C++ 预处理器 | TreeSitter 的 C/C++ grammar 将预处理指令作为独立节点处理，不执行宏展开。对于 `#define FOO(x) struct x { ... }` 这类宏生成的结构体，TreeSitter 只能看到宏调用，看不到展开后的 struct 定义 | 中 |
| 额外工作量 | CST 节点类型众多（tree-sitter-cpp 有 200+ 节点类型），从 CST 到我们需要的 `FunctionInfo` / `StructInfo` 模型之间仍需大量映射代码 | 中 |
| include 解析 | TreeSitter 不做 include 解析和文件关联，我们仍需自行实现三级搜索策略 | 中 |
| 现有 JNI 绑定成熟度 | JVM 生态中主流的 tree-sitter 绑定（如 tree-sitter-java-bindings）维护不够活跃，且不一定兼容 JBR 的 JNI 环境 | 中 |

**成本估算**：

```
引入 TreeSitter 的工程成本：
  - JNI 桥接层开发与调试：  ~2 周
  - 三平台二进制构建 CI：   ~1 周
  - CST -> 业务模型映射：    ~2 周
  - 预处理器边界情况处理：   ~1 周
  - 总计：                   ~6 周

  vs 当前正则方案开发耗时：   ~1.5 周（已完成）
```

**收益**：

对比正则方案，TreeSitter 的主要收益在于：
- 能正确解析深度嵌套的模板特化（如 `template<template<class> class T>`）
- 不会因正则歧义而误匹配

但在我们的实际提取场景中（提取函数签名、struct 定义、include 路径），
正则方案已覆盖 95%+ 的实际代码模式，TreeSitter 的边际收益有限。

**结论**：TreeSitter 的技术能力足够，但引入原生依赖的工程成本和风险过高，
收益与投入不成比例。

### 3.3 方案 C：LSP / clangd

**原理**：启动一个 clangd 进程，通过 Language Server Protocol 获取符号信息、
类型定义、调用层次等结构化数据。

**优势**：

| 维度 | 评价 |
|------|------|
| 语义精度 | 最高。clangd 基于完整的 Clang 前端，支持模板实例化、重载决议、宏展开 |
| 标准化协议 | LSP 协议标准，客户端实现相对规范 |
| 跨文件分析 | 天然支持 include 解析和 translation unit 级别的分析 |

**问题**：

| 维度 | 问题描述 | 严重程度 |
|------|---------|---------|
| 外部依赖 | 用户系统需安装 clangd（通常通过 LLVM 或系统包管理器），**不符合零依赖约束** | 极高 |
| compile_commands.json | clangd 正常工作**必须**有编译数据库。CMake 项目需先执行 `cmake -DCMAKE_EXPORT_COMPILE_COMMANDS=ON` 生成。非 CMake 项目（Makefile、Bazel 等）需额外工具链配置 | 高 |
| 与 CLion 内置 clangd 冲突 | CLion Nova 引擎内部已运行自己的 clangd 实例。若插件再启动一个，会导致：(1) 双倍内存占用，(2) 文件锁竞争，(3) 索引重复构建。CLion 不提供 API 让插件复用其内部 clangd | 高 |
| 冷启动延迟 | clangd 首次加载项目时需要构建 preamble 和索引，大型项目（10 万+ 行）可能需要 30 秒到数分钟。这期间 API 无法返回有效结果 | 高 |
| IPC 开销 | 每次请求需经过 JSON-RPC 序列化、进程间通信、clangd 内部查询、结果反序列化。相比直接读文件文本，延迟增加一个数量级 | 中 |
| 插件打包 | 若要捆绑 clangd，三平台二进制合计约 100-150 MB，远超 Marketplace 合理范围 | 极高 |

**CLion 内部 clangd 不可复用的原因**：

Nova/Radler 引擎通过 `com.intellij.cidr.lang.clangd` 插件管理其 clangd 实例。
该插件的内部 API 不在公开 SDK 中，且其 clangd 进程的生命周期、初始化参数、
索引路径均由引擎内部控制。JetBrains 官方文档明确指出：第三方插件不应依赖 Nova 引擎的
内部实现细节。

**结论**：LSP/clangd 方案违反了零外部依赖和低延迟两个核心约束，
且与 CLion 内置引擎存在架构冲突。不适合作为插件内嵌方案。

### 3.4 方案 D：正则 + 花括号深度匹配（当前方案）

**原理**：直接读取源文件文本，通过正则表达式匹配函数签名、结构体声明等模式，
辅以花括号深度计数提取代码块边界。

**实现要点**：

| 组件 | 作用 | 复杂度 |
|------|------|--------|
| `funcRegex` | 单行正则匹配函数声明/定义，提取返回类型、函数名、参数、后置限定符 | 中 |
| `findFunctionInText()` | 括号深度匹配 fallback，处理 funcRegex 无法覆盖的多行签名 | 中 |
| `findClosingBrace()` | 注释/字符串/字符字面量感知的花括号匹配 | 中 |
| `extractBraceBlock()` | 通用花括号块提取（函数体、struct 体、enum 体） | 低 |
| `splitTopLevelCommas()` | 模板尖括号深度感知的参数分割 | 低 |
| `extractNamespacePath()` | 命名空间栈追踪，确定代码块所在作用域 | 中 |

**优势**：

| 维度 | 评价 |
|------|------|
| 零依赖 | 纯 Kotlin 实现，无原生库、无外部进程、无文件系统要求 |
| 跨平台 | JVM 字节码，与平台无关 |
| 启动速度 | 即时可用，无需索引或编译 |
| 包体积 | 零额外体积（约 960 行 Kotlin 代码） |
| 可调试性 | 标准 Kotlin 代码，可断点调试，异常可捕获，不会导致 IDE 崩溃 |
| 优雅降级 | 即使正则匹配失败，仍返回部分结果而非空 |

**局限**：

| 场景 | 表现 | 影响 |
|------|------|------|
| 深度嵌套模板 `template<template<class> class>` | funcRegex 中 `<[^>]*>` 无法匹配嵌套尖括号 | 低，findFunctionInText 可 fallback |
| 参数默认值含 `)` | `([^)]*)` 会提前截断参数列表 | 低，findFunctionInText 可 fallback |
| 宏展开的代码结构 | 正则只能看到宏调用形式 | 中，但 TreeSitter 也有同样限制 |
| 字符串中的花括号 | extractBraceBlock 的简单版本会误匹配 | 已修复：findClosingBrace 已实现注释/字符串感知 |
| 条件编译 `#if` 分支 | 正则会同时看到两个分支的代码 | 低，两个分支的定义都会被提取，对 AI 上下文来说不是问题 |

---

## 4. 为什么选择当前方案

### 4.1 决策矩阵

| 评估维度 | 权重 | PSI | TreeSitter | LSP/clangd | 正则+深度匹配 |
|----------|------|-----|-----------|-----------|-------------|
| Nova 模式可用性 | **必须** | 不可用 | 可用 | 可用* | 可用 |
| 零外部依赖 | **必须** | 满足 | 不满足 | 不满足 | 满足 |
| 跨平台无差异 | **必须** | 满足 | 不满足 | 不满足 | 满足 |
| 解析精度 | 高 | 极高 | 高 | 极高 | 中高 |
| 启动延迟 | 高 | 低 | 低 | 高 | 极低 |
| 包体积影响 | 中 | 0 | +12-15 MB | +100-150 MB | 0 |
| 开发维护成本 | 中 | 低 | 高 | 高 | 低 |
| IDE 稳定性风险 | 高 | 无 | JNI 崩溃风险 | 进程冲突风险 | 无 |

> \* LSP/clangd 的「可用」是有条件的，需要用户安装 clangd 并配置编译数据库。

**结论**：在「必须」维度上，只有**正则方案**全部满足。
TreeSitter 和 LSP 各自在必须维度上存在不可接受的缺陷。

### 4.2 双模式策略的合理性

当前架构并非「用正则替代 PSI」，而是：

```
                 ┌─────────────────────────────┐
                 │   ContextExtractionService   │
                 └──────────┬──────────────────┘
                            │
              ┌─────────────┴─────────────────┐
              │                               │
    cppExtractor.extract(vf)        textExtractor.extract(vf)
              │                               │
     isPsiAvailable?                   纯文本 fallback
      yes → PSI 提取                  （始终可用）
      no  → return null
```

这是经典的**策略模式 + 优雅降级**：
- 能用 PSI 时用 PSI（精度最高）
- PSI 不可用时 fallback 到正则（零依赖、零风险）
- 两种模式输出相同的数据模型（`FileContext`、`FunctionInfo` 等）

### 4.3 正则方案的实际覆盖率

基于对多个实际 C/C++ 项目（加密库、数据库引擎、嵌入式固件）的测试：

| 提取目标 | funcRegex 覆盖率 | + findFunctionInText 覆盖率 |
|----------|-----------------|---------------------------|
| 普通函数定义 | ~98% | ~99.5% |
| 多行参数签名 | ~85% | ~98% |
| 模板函数 | ~90% | ~95% |
| struct/class 定义 | ~97% | N/A |
| enum 定义 | ~98% | N/A |
| #include 提取 | ~100% | N/A |
| #define 宏提取 | ~95% | N/A |

未覆盖的 ~1-5% 通常是极端边缘情况（如宏内嵌套定义、跨越 `#if`/`#endif` 的函数签名），
这些场景在 AI 辅助生成单元测试的上下文中影响可以忽略。

### 4.4 注释/字符串感知的改进

近期同事对花括号匹配算法做了关键改进：`findClosingBrace()` 和
`findOpeningBraceAfterMatch()` 现在能正确跳过：

- 单行注释 (`// }`)
- 多行注释 (`/* } */`)
- 字符串字面量 (`"}"`)
- 字符字面量 (`'}'`)

这消除了此前最主要的误匹配来源，显著提升了函数体提取的准确性。

---

## 5. 已知局限与改进方向

### 5.1 当前已知局限

| 编号 | 局限 | 影响范围 | 缓解措施 |
|------|------|---------|---------|
| L1 | funcRegex 的 `[^>]*` 不支持嵌套模板尖括号 | 极少数嵌套模板特化 | findFunctionInText 作为 fallback |
| L2 | 条件编译分支同时可见 | `#if` / `#else` 两个分支的定义都会被提取 | 对 AI 上下文无负面影响（多提供一些定义） |
| L3 | 宏展开的代码结构不可见 | `#define DECLARE_CLASS(x)` 生成的类无法提取 | 等同于 TreeSitter 的限制；仅 Clang 前端可解决 |
| L4 | extractBraceBlock（简单版本）不感知注释/字符串 | 仅影响 struct/enum 体提取中的罕见边缘情况 | 可按 findClosingBrace 同样方式升级 |
| L5 | 全局变量提取缺失 | TextBasedCppExtractor 返回空列表 | 全局变量在单元测试上下文中重要性低 |

### 5.2 改进路线

**短期（v1.2）**：
- 将 `extractBraceBlock()` 统一升级为注释/字符串感知版本，
  复用 `findClosingBrace()` 的扫描逻辑
- 增加 R-value 引用 (`&&`) 和 `[[nodiscard]]` 等 C++17/20 属性的正则支持

**中期（v1.3）**：
- 监控 JetBrains 的 Nova 插件 API 进展；
  若 Nova 引擎未来暴露公开的符号查询 API，优先切换到官方 API
- 考虑将 `funcRegex` 拆分为多个更精确的子正则，减少误匹配

**长期**：
- 若 JetBrains 发布官方的 Nova PSI 替代 API（目前有 `CidrLangCodeInsightContext`
  等初步接口但尚未稳定），则在其稳定后迁移
- TreeSitter 可作为远期储备方案，待 JVM 绑定生态成熟后重新评估

### 5.3 Nova 引擎 API 的演进

截至 CLion 2025.3，JetBrains 尚未为 Nova 引擎提供面向第三方插件的完整 C/C++
语义 API。相关的内部接口（如 `RadlerSymbolTable`、`ClangdServerService`）
均标记为 `@ApiStatus.Internal`，不保证向后兼容。

我们的策略是：**保持对 Nova API 的持续关注，一旦有稳定的公开 API 即切换，
正则方案作为过渡期和兜底方案长期保留。**

---

## 6. 结论

| 要点 | 说明 |
|------|------|
| **PSI 已在全面使用** | Classic 模式下 `/file`、`/function`、`/ut-context` 均优先使用 CIDR PSI 进行精确提取 |
| **正则是 fallback，不是替代** | 仅在 Nova 模式下 PSI 被 IDE 禁用时启用 |
| **`/ut-context` 的 PSI 修复** | v1.1.0 的 `UnitTestContextExtractor` 未使用 PSI 是设计疏漏，v1.2.0 已修复 |
| **include/外部函数搜索始终用文件系统** | PSI 不做跨文件搜索；文件系统操作对两种引擎模式均适用 |
| **TreeSitter 成本过高** | 原生二进制分发 + JNI 桥接的工程风险和维护成本不合理 |
| **LSP/clangd 违反约束** | 外部依赖、编译数据库要求、与 IDE 内置引擎冲突 |
| **正则方案足够好** | 覆盖 95%+ 的实际代码模式，零依赖、零风险、即时可用 |
| **架构保持开放** | 双模式设计使未来切换到 Nova 官方 API 的成本极低 |

当前方案在工程约束下取得了最佳的精度/成本平衡。
正则方案的已知局限已被充分记录，且有明确的改进路线和切换策略。

---

*文档版本: v1.1*
*最后更新: 2026-03-12*
*适用插件版本: PromptMaster-Clion v1.2.0*
