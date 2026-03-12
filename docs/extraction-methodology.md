# PromptMaster-Clion 提取方法详解

本文档详细解释 PromptMaster-Clion 插件的代码提取实现，包括架构设计、核心算法和关键技术决策。

---

## 1. 整体架构

### 请求处理流程

```
HTTP GET /api/cpp-context/ut-context?path=...&name=...
    │
    ▼
ContextRestService.execute()          ← Netty HTTP handler，路由分发
    │
    ▼
ContextExtractionService              ← 查缓存 → 未命中则调用提取器
    │
    ▼
╔═══════════════════════════════════╗
║  ContextCache (ConcurrentHashMap) ║  ← TTL 5 分钟，key = "ut-context:{path}:{name}"
╚═══════════════════════════════════╝
    │ 缓存未命中
    ▼
UnitTestContextExtractor.extract()    ← 编排整个提取流程
    │
    ├── TextBasedCppExtractor         ← 解析函数列表、结构体、枚举等
    │   ├── funcRegex                 ← 正则匹配函数签名
    │   └── findFunctionInText()      ← 括号深度匹配（fallback）
    │
    └── Include 解析 + 头文件扫描     ← 三级搜索策略
```

### 双模式架构

CLion 有两种 C/C++ 解析引擎：

| 引擎 | CLion 版本 | PSI API | `/file`、`/function` 策略 | `/ut-context` 策略 |
|------|-----------|---------|--------------------------|-------------------|
| **Classic (CIDR)** | 2024.2 ~ 2025.2.x | `OCFile`, `OCStruct`, `OCFunctionDeclaration` 可用 | `CppContextExtractor` PSI 提取，精度最高 | PSI 提取函数/include 基础信息 + 文件系统做 include 解析和外部函数搜索 |
| **Nova (Radler)** | 2025.3+ | CIDR PSI 被系统禁用 (`-Didea.suppressed.plugins.set.selector=radler`) | `TextBasedCppExtractor` 正则 + 深度匹配提取 | 文本模式提取函数/include 基础信息 + 文件系统做 include 解析和外部函数搜索 |

**为什么用 optional dependency？**

在 `plugin.xml` 中，`com.intellij.cidr.lang` 声明为 `optional`：

```xml
<depends optional="true" config-file="withCidrLang.xml">com.intellij.cidr.lang</depends>
```

这意味着：
- Classic 模式：`cidr.lang` 存在 → 插件加载，PSI 提取可用
- Nova 模式：`cidr.lang` 被禁用 → 插件仍然加载（因为是 optional），但 PSI 不可用

如果改成 `<depends>com.intellij.cidr.lang</depends>`（非 optional），Nova 模式下插件会**无法安装**，并提示 "Plugin requires com.intellij.cidr.lang to be enabled"。

---

## 2. PSI 模式提取（CppContextExtractor）

> 文件：`extraction/CppContextExtractor.kt`

### 什么是 PSI

PSI (Program Structure Index) 是 IntelliJ 平台的核心 API，将源代码解析为抽象语法树 (AST)。每个代码元素（函数、类、变量）都是一个 `PsiElement` 节点。

CLion 的 CIDR 插件扩展了 PSI，提供 C/C++ 专用的类：
- `OCFile` — C/C++ 文件
- `OCFunctionDeclaration` — 函数声明/定义
- `OCStruct` — 结构体/类/联合体
- `OCEnum` — 枚举
- `OCDefineDirective` — 宏定义

### 反射加载机制

由于 Nova 模式下 CIDR PSI 类不存在，直接 `import` 会导致 `ClassNotFoundException`。
因此使用**反射**在运行时动态加载：

```kotlin
companion object {
    val isPsiAvailable: Boolean by lazy {
        try {
            // 尝试多个 ClassLoader 加载 OCFile
            Class.forName("com.jetbrains.cidr.lang.psi.OCFile")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }
}
```

所有 PSI 相关操作都先检查 `isPsiAvailable`，确保 Nova 模式下不会抛异常。

### 提取流程

```kotlin
fun extractFileContext(virtualFile: VirtualFile): FileContext? {
    if (!isPsiAvailable) return null  // Nova 模式直接返回 null

    return ReadAction.compute {
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
        // 通过反射检查是否为 OCFile
        // 遍历 PSI 树提取函数、结构体、枚举等
    }
}
```

**关键点**：所有 PSI 操作必须在 `ReadAction` 中执行（IntelliJ 的读锁机制）。

---

## 3. 文本模式提取（TextBasedCppExtractor）

> 文件：`extraction/TextBasedCppExtractor.kt`

### 为什么需要文本模式

CLion 2025.3 默认使用 Nova/Radler 引擎，CIDR PSI API 被完全禁用。
但用户仍需要代码提取功能。文本模式通过正则表达式和字符扫描算法直接操作源文件文本，
不依赖任何 IDE 特定的解析 API。

### 3.1 funcRegex —— 正则匹配函数签名

```kotlin
private val funcRegex = Regex(
    """^([ \t]*(?:template\s*<[^>]*>\s*)?""" +
    """(?:(?:static|inline|virtual|explicit|constexpr|consteval|extern|friend)\s+)*""" +
    """[\w:*&<>,\s]+?)\b(~?\w+)\s*\(([^)]*)\)\s*""" +
    """((?:const|volatile|noexcept|override|final|= *0|= *default|= *delete|->[\w:&*<>\s]+)*)\s*([{;])""",
    RegexOption.MULTILINE
)
```

**正则分组解析：**

```
^                                    ← 行首（MULTILINE 模式下匹配每行开头）
(                                    ← Group 1: 前缀（返回类型 + 限定符）
  [ \t]*                               ← 可选缩进
  (?:template\s*<[^>]*>\s*)?           ← 可选 template<...>
  (?:(?:static|inline|...)\s+)*        ← 零到多个限定词
  [\w:*&<>,\s]+?                       ← 返回类型（lazy，尽量短匹配）
)
\b                                   ← 词边界
(~?\w+)                              ← Group 2: 函数名（可选 ~ 用于析构函数）
\s*\(                                ← 开括号
([^)]*)                              ← Group 3: 参数列表
\)\s*                                ← 闭括号
(...)                                ← Group 4: 后置限定符（const, noexcept 等）
([{;])                               ← Group 5: 定义({) 还是声明(;)
```

**局限性**：`([^)]*)` 要求参数中不能包含嵌套的 `)` 字符。虽然 `[^)]*` 可以跨行匹配（因为是字符类取反，而非 `.`），但在某些边缘情况下（如参数中有默认值含 `)`）可能失败。

### 3.2 findFunctionInText() —— 括号深度匹配（核心算法）

当 `funcRegex` 无法匹配时（如多行签名的某些边缘情况），使用基于**括号深度计数**的算法：

```
算法步骤：

步骤 1：定位函数名
    使用 Regex("\b<functionName>\s*\(") 找到函数名后的左括号位置
    ┌─────────────────────────────────────────────────────────────┐
    │ uint32_t sm4_aes::encrypt_with_prefix(const uint8_t *key,  │
    │                                       ↑                    │
    │                              nameStart ↑  openParen        │
    └─────────────────────────────────────────────────────────────┘

步骤 2：括号深度计数，找到参数结束位置
    depth = 1
    遍历 openParen+1 到文本末尾：
      '(' → depth++
      ')' → depth--
      当 depth == 0 → 找到 closeParen
    ┌─────────────────────────────────────────────────────────────┐
    │ ...encrypt_with_prefix(const uint8_t *key, uint32_t key_size,
    │                                                    depth=1 │
    │                        const uint8_t *data, uint32_t data_size,
    │                                                    depth=1 │
    │                        uint32_t prefix, uint8_t *cipher,   │
    │                                                    depth=1 │
    │                        uint32_t cipher_size, uint8_t *out_mac) {
    │                                                           ↑│
    │                                               closeParen  ↑│
    │                                                   depth=0  │
    └─────────────────────────────────────────────────────────────┘

步骤 3：判断是定义还是声明
    从 closeParen+1 向前扫描（最多 600 字符）：
      遇到 '{' → 这是函数定义，记录 braceIdx
      遇到 ';' → 这是函数声明，跳过

步骤 4：向后扫描找返回类型
    从 nameStart 向左扫描到行首：
    ┌─────────────────────────────────────────────────────────────┐
    │ uint32_t sm4_aes::encrypt_with_prefix(                     │
    │ ↑                ↑                                         │
    │ lineStart        nameStart                                 │
    │ prefix = "uint32_t sm4_aes::"                              │
    └─────────────────────────────────────────────────────────────┘
    如果行首到 nameStart 之间是空白，则再往上一行查找返回类型

步骤 5：花括号深度匹配提取函数体
    从 braceIdx+1 开始：
    depth = 1
    遍历到文本末尾：
      '{' → depth++
      '}' → depth--
      当 depth == 0 → 函数体结束
    body = text[braceIdx+1 .. 结束位置-1]
```

**为什么需要两种方式？**

`funcRegex` 速度更快（一次正则扫描整个文件），但在以下情况下可能失败：
- 参数中包含 `)` 字符的默认值
- 某些复杂的模板特化
- 宏展开的函数签名

`findFunctionInText()` 通过逐字符扫描和深度计数，能正确处理任意嵌套的括号。

### 3.3 extractBraceBlock() —— 花括号深度匹配

```kotlin
private fun extractBraceBlock(text: String, openBraceIndex: Int): String? {
    var depth = 1
    var i = openBraceIndex + 1
    while (i < text.length && depth > 0) {
        when (text[i]) {
            '{' -> depth++
            '}' -> depth--
        }
        i++
    }
    if (depth != 0) return null  // 未找到匹配的 '}'
    return text.substring(openBraceIndex + 1, i - 1)
}
```

这是提取函数体、结构体体、枚举体等的通用算法。通过维护一个 `depth` 计数器，
每遇到 `{` 加一，遇到 `}` 减一，当 `depth` 回到 0 时找到匹配的闭合花括号。

**注意**：`extractBraceBlock()` 是用于 struct/enum 体提取的简化版本，不处理字符串和注释中的花括号。
函数体提取路径（`extractDefinitionText`）使用注释/字符串感知版本 `findClosingBrace()`，
能正确跳过 `// }`、`/* } */`、`"}"` 等情况。

### 3.4 parseParameters() —— 参数列表解析

```kotlin
private fun parseParameters(paramStr: String): List<ParameterInfo>
```

难点在于参数可能包含模板类型 `std::map<int, string>`，其中的逗号不应被当作参数分隔符。

解决方案 —— `splitTopLevelCommas()`：

```kotlin
private fun splitTopLevelCommas(s: String): List<String> {
    var depth = 0       // 尖括号和圆括号的嵌套深度
    for ((i, c) in s.withIndex()) {
        when (c) {
            '<', '(' -> depth++
            '>', ')' -> depth--
            ',' -> if (depth == 0) {
                // 只有在顶层时才分割
            }
        }
    }
}
```

当 `depth > 0` 时，说明逗号在 `<...>` 或 `(...)` 内部，不是参数分隔符。

### 3.5 extractNamespacePath() —— 命名空间路径回溯

```kotlin
private fun extractNamespacePath(text: String, offset: Int): List<String>
```

给定文本中的一个偏移位置，确定该位置处于哪些 namespace 的作用域内。

**算法**：从文件开头扫描到 `offset`，维护一个命名空间栈：

```
扫描过程示例：

namespace ypc {              ← 遇到 namespace ypc {，压栈 ("ypc", depth=1)
namespace crypto {           ← 遇到 namespace crypto {，压栈 ("crypto", depth=2)

uint32_t sm4_aes::encrypt... ← 当前位置 offset
                              ← 栈内容：[("ypc",1), ("crypto",2)]
                              ← 返回 ["ypc", "crypto"]

} // namespace crypto        ← depth=2 时遇到 }，弹出 "crypto"
} // namespace ypc           ← depth=1 时遇到 }，弹出 "ypc"
```

关键实现：每个 `{` 使 depth+1，每个 `}` 使 depth-1。当 depth 与栈顶命名空间的 depth 匹配时，
说明该命名空间结束，弹出栈顶。

---

## 4. 单元测试上下文提取（UnitTestContextExtractor）

> 文件：`extraction/UnitTestContextExtractor.kt`

这是 `/ut-context` 端点的核心实现，编排整个提取流水线。

### 光标自动检测（v1.1.0 新增）

`/ut-context` 端点的 `path` 和 `name` 参数现在是可选的。不传参数时，插件通过
`ContextExtractionService.getUnitTestContextFromCursor()` 自动检测：

```
GET /api/cpp-context/ut-context  （无参数）
    │
    ▼
getCurrentCursorInfo()                  ← 在 EDT 上访问编辑器状态
    │
    ├── FileEditorManager.selectedTextEditor    ← 获取当前活跃编辑器
    ├── selectedFiles[0].path                   ← 获取文件绝对路径
    ├── editor.caretModel.logicalPosition.line  ← 获取光标行号（0-based → 1-based）
    │
    ▼
findFunctionAtLine(fileText, caretLine)  ← 委托给 TextBasedCppExtractor.findFunctionAtLine()
    │                                       验证光标行在函数体的 { ... } 范围内
    │                                       （不只是"函数开始前"，避免函数体内的调用误匹配）
    ▼
getUnitTestContext(filePath, funcName)   ← 复用已有的提取流水线
```

Continue 等外部调用方只需 `GET /api/cpp-context/ut-context`，无需传任何参数。

### 提取流水线（9 个字段）

```
doExtract(filePath, functionName)
    │
    ├── 1. 读取文件文本
    │
    ├── 2. 获取 FileContext（函数列表、include 列表等）
    │      Classic 模式：CppContextExtractor.extractFileContext() [PSI，精度更高]
    │      Nova 模式：TextBasedCppExtractor.extractFileContext() [文本正则，自动降级]
    │
    ├── 3. 定位目标函数
    │      ├── 首选：fileCtx.functions.find { it.name == functionName }
    │      └── fallback：findFunctionInText(fileText, functionName)
    │
    ├── 4. extractDefinitionText()
    │      从 lineNumber 开始，找到 {，brace-match 找到 }，
    │      截取完整的函数定义原文
    │      → definition 字段
    │
    ├── 5. 解析 #include
    │      对每个 include，三级搜索找到绝对路径
    │      → headFiles 字段
    │
    ├── 6. extractAllIdentifiers()
    │      从 signature + body 中提取所有标识符（\b[A-Za-z_]\w*\b）
    │      用于过滤后续步骤中不相关的定义
    │
    ├── 7. collectRawTypeDefinitions()
    │      从文件和头文件中收集 struct/class/enum/typedef 原文
    │      只保留标识符出现在函数 body/signature 中的定义
    │      → structDefinitions 字段
    │
    ├── 8. collectRawMacros()
    │      从文件和头文件中收集 #define 宏原文
    │      支持多行续行（行尾 \）
    │      → macroDefinitions 字段
    │
    ├── 9. findExternalFunctions()
    │      a) 从 body 中提取 identifier( 模式的函数调用
    │         body 来自 FunctionInfo.body；若为 null，从源文本 + 行号重新提取
    │      b) 减去本地函数名 + C++ 关键字 + 标准库函数
    │         （本地函数名来自 fileCtx.functions 或 extractFunctionNamesFromText 回退）
    │      c) 先搜头文件，再用 java.io.File 搜项目
    │      → externalFunctions 字段
    │
    └── 10. function.namespacePath.joinToString("::")
           → namespacePath 字段
```

### Include 解析的三级搜索策略

> **重要变更**：所有文件系统操作使用 `java.io.File` 而非 IntelliJ VFS，
> 确保 Nova 模式下即使 VFS 未被 Classic 引擎预填充也能正常工作。

```kotlin
private fun doResolveInclude(includePath: String, isSystem: Boolean, sourceFile: String): String? {
    // 级别 1：相对于源文件目录（仅 quoted include "..."）
    if (!isSystem) {
        val srcDir = IoFile(sourceFile).parentFile
        val candidate = IoFile(srcDir, includePath)
        if (candidate.isFile) return normalizeFsPath(candidate)
    }

    // 级别 2：相对于项目 content root + basePath
    for (rootPath in getSearchRoots()) {
        val candidate = IoFile(rootPath, includePath)
        if (candidate.isFile) return normalizeFsPath(candidate)
    }

    // 级别 3：按文件名递归搜索整个项目（使用 java.io.File.listFiles）
    val fileName = includePath.substringAfterLast('/')
    for (rootPath in getSearchRoots()) {
        val found = searchFileOnDisk(IoFile(rootPath), includePath, fileName)
        if (found != null) return normalizeFsPath(found)
    }

    return null
}
```

**为什么需要级别 3？** 因为很多项目的 include 路径不完全对应目录结构。
例如 `#include "ypc/stbox/gmssl/sm4.h"` 的实际文件可能在 `vendor/gmssl/include/sm4.h`。
通过递归搜索项目中所有同名文件，找到匹配的路径。

**为什么用 `java.io.File` 而非 VFS？** IntelliJ 的 VFS 是懒加载的。在 Nova 模式下，
如果用户从未使用过 Classic 引擎，VFS 可能没有索引整个项目的文件树。`java.io.File` 直接
操作操作系统文件系统，不受 VFS 状态影响。读取文件时先尝试 VFS（速度更快），失败则回退到
`java.io.File.readText()`。

结果缓存在 `ConcurrentHashMap` 中，避免重复搜索。

### 标识符过滤

`collectRawTypeDefinitions()` 和 `collectRawMacros()` 会从文件和所有头文件中收集定义，
但不是全部返回。通过 `extractAllIdentifiers()` 提取函数签名和函数体中出现的所有标识符，
只返回**函数实际引用到的**定义，减少输出噪声。

例如，如果函数体中使用了 `SM4_KEY` 类型和 `AAD_MAC_TEXT_LEN` 宏，
那么只有这两个的定义会出现在 `structDefinitions` 和 `macroDefinitions` 中。

### 外部函数发现

```
函数体: encrypt_with_prefix() {
    ...
    memcpy(mac_text, aad_mac_text, AAD_MAC_TEXT_LEN);    ← memcpy 是标准库，排除
    sm4_set_encrypt_key(&sm4_key, key);                   ← 本地文件没有 → 外部函数
    sm4_gcm_encrypt(&sm4_key, ...);                       ← 本地文件没有 → 外部函数
    RAND_bytes(p_iv_text, INITIALIZATION_VECTOR_SIZE);    ← 本地文件没有 → 外部函数
    ...
}

排除规则：
  - 本地文件中的函数（fileCtx.functions 或文件文本 fallback 提取）
  - C++ 关键字（if, for, return, sizeof, ...）
  - 标准库函数（printf, malloc, memcpy, make_shared, ...）

搜索策略：
  1. 先在已解析的头文件中搜索（使用 readTextByPath：VFS → java.io.File 回退）
  2. 如果仍有未找到的函数，使用 java.io.File 遍历项目目录全局搜索
```

---

## 5. 缓存系统

> 文件：`cache/ContextCache.kt`、`cache/CacheInvalidator.kt`

### TTL 缓存

```kotlin
class ContextCache(private val ttlMs: Long = TimeUnit.MINUTES.toMillis(5)) {
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    fun <T> getOrCompute(key: String, compute: () -> T): T {
        val existing = cache[key]
        if (existing != null && !isExpired(existing)) {
            return existing.value as T
        }
        val value = compute()
        cache[key] = CacheEntry(value)
        return value
    }
}
```

缓存键格式：
- `"file:/abs/path/to/file.cpp"` — 文件级上下文
- `"ut-context:/abs/path/to/file.cpp:functionName"` — UT 上下文
- `"project"` — 项目摘要
- `"cmake"` — CMake 配置

### ConcurrentHashMap 注意事项

`ConcurrentHashMap` **不允许 null key 或 null value**。如果 `compute()` 返回 null
并尝试存入 cache，会抛出 `NullPointerException`。

在 `UnitTestContextExtractor` 的 include 缓存中，使用 **sentinel 值**替代 null：

```kotlin
private val UNRESOLVED_SENTINEL = "\u0000UNRESOLVED"

private fun resolveInclude(...): String? {
    val cached = includeCache.getOrPut(key) {
        doResolveInclude(...) ?: UNRESOLVED_SENTINEL  // null → sentinel
    }
    return if (cached === UNRESOLVED_SENTINEL) null else cached
}
```

### PSI 变更监听器

```kotlin
class CacheInvalidator : PsiTreeChangeAdapter() {
    override fun childrenChanged(event: PsiTreeChangeEvent) {
        val file = event.file ?: return
        if (isOCFile(file)) {  // 通过反射检查，Nova 模式下安全
            service.invalidateCacheForFile(file.virtualFile.path)
        }
    }
}
```

当用户修改 C/C++ 文件时，自动清除该文件的缓存。`invalidateCacheForFile()` 同时清除
`"file:..."` 和 `"ut-context:..."` 前缀的缓存条目。

---

## 6. CMake 提取

> 文件：`extraction/CMakeContextExtractor.kt`

### 纯反射方式

CMake 工作区 API 属于 CLion 的 CMake 插件，不在 IntelliJ Platform SDK 中。
为了避免编译时依赖，使用**纯反射**访问：

```kotlin
// 通过反射获取 CMakeWorkspace 实例
val wsClass = Class.forName("com.jetbrains.cmake.CMakeWorkspace")
val getInstance = wsClass.getMethod("getInstance", Project::class.java)
val workspace = getInstance.invoke(null, project)

// 调用 workspace.getConfigurations() 等方法
val configs = invokeListMethod(workspace, "getConfigurations")
```

**提取的信息：**
- 项目名称
- 构建配置（Debug / Release / RelWithDebInfo / MinSizeRel）
- 每个配置下的 targets：
  - target 名称和类型（executable / library）
  - 源文件列表
  - 编译选项（`-Wall`, `-std=c++17` 等）
  - include 目录
  - 预处理器定义
  - 链接库

---

## 7. 常见问题与调试

### 多行函数签名为什么需要 findFunctionInText

C++ 函数签名可以跨多行：

```cpp
uint32_t sm4_aes::encrypt_with_prefix(const uint8_t *key, uint32_t key_size,
                                      const uint8_t *data, uint32_t data_size,
                                      uint32_t prefix, uint8_t *cipher,
                                      uint32_t cipher_size, uint8_t *out_mac) {
```

`funcRegex` 的参数部分使用 `([^)]*)` 匹配。虽然 `[^)]*` 理论上可以跨行
（因为是字符类取反，不受 DOTALL 影响），但与 lazy 前缀量词 `[\w:*&<>,\s]+?`
和 `MULTILINE` 模式的 `^` 锚点组合时，在某些正则引擎实现中可能出现意外行为。

`findFunctionInText()` 通过逐字符的括号深度计数完全避免了正则匹配的不确定性，
是最稳健的方案。

### ConcurrentHashMap null value 陷阱

Java 的 `ConcurrentHashMap` 不允许 null key 或 value。
Kotlin 的 `getOrPut()` 扩展函数在 lambda 返回 null 时会调用 `putIfAbsent(key, null)`，
导致 `NullPointerException`。

**症状**：API 返回 "Function not found"，但函数确实存在。错误被外层 `catch(Throwable)` 吞掉。

**解决**：使用 sentinel 值替代 null 存入 ConcurrentHashMap。

### Nova 模式下 PSI 不可用的检测

```kotlin
val isPsiAvailable: Boolean by lazy {
    try {
        Class.forName("com.jetbrains.cidr.lang.psi.OCFile")
        true
    } catch (_: ClassNotFoundException) {
        false
    }
}
```

这是一个 `lazy` 属性，只在首次访问时计算一次。如果 `OCFile` 类不存在
（Nova 模式下 `com.intellij.cidr.lang` 被禁用），捕获 `ClassNotFoundException`
并返回 false。后续所有 PSI 提取方法直接返回 null，由文本模式接管。

### 如何查看插件日志

1. CLion 菜单 → **Help → Show Log in Finder/Explorer**
2. 打开 `idea.log`
3. 搜索 `com.promptmaster.clion` 或 `ut-context` 查看插件日志

日志级别：
- `INFO` — 正常操作日志（如 "extractFileContext returned null, using direct scan"）
- `WARN` — 提取失败的详细原因
- `ERROR` — 内部错误
