# PromptMaster-Clion

CLion 插件 —— 通过本地 HTTP API 提取 C/C++ 代码上下文，用于 AI 辅助单元测试生成。

支持 CLion **Classic 引擎**（PSI 提取）和 **Nova/Radler 引擎**（文本正则提取），兼容 CLion 2024.2 ~ 2025.3。

---

# 用户指南

## 系统要求

| 项目 | 要求 |
|------|------|
| IDE | CLion 2024.2 或更高版本（2024.2 ~ 2025.3 已验证）|
| JVM | Java 21+ |
| 操作系统 | macOS / Linux / Windows |

## 安装

### 方式一：从构建好的 ZIP 安装

1. 获取 `PromptMaster-Clion-x.x.x.zip`（从 Release 页面或同事提供）
2. 打开 CLion → **Settings → Plugins → ⚙️ → Install Plugin from Disk...**
3. 选择下载的 ZIP 文件
4. 重启 CLion

### 方式二：从源码构建

**macOS / Linux：**
```bash
git clone <repo-url>
cd PromptMaster-Clion
./gradlew clean buildPlugin
```

**Windows（CMD 或 PowerShell）：**
```cmd
git clone <repo-url>
cd PromptMaster-Clion
gradlew.bat clean buildPlugin
```

> **Windows 常见报错**：如果出现 `Unable to access jarfile`，说明 `gradle/wrapper/gradle-wrapper.jar` 缺失。
> 请确认项目中包含 `gradle/wrapper/gradle-wrapper.jar` 文件。如果没有，运行：
> ```cmd
> gradle wrapper --gradle-version=8.13
> ```
> 然后重新执行 `gradlew.bat clean buildPlugin`。

构建完成后，ZIP 包在 `build/distributions/` 目录中。按上述"方式一"的步骤安装到 CLion。

## 快速开始

1. 在 CLion 中打开一个 C/C++ 项目
2. 等待项目索引完成
3. 插件自动启动，无需手动配置
4. 通过 HTTP API 访问代码上下文：

```bash
# 健康检查
curl http://localhost:63342/api/cpp-context/health
```

响应示例：
```json
{
    "status": "ok",
    "pluginVersion": "1.0.1",
    "projectName": "MyProject",
    "cidrLangAvailable": true,
    "engineMode": "classic",
    "extractionMode": "psi"
}
```

## API 端点参考

基础 URL：`http://localhost:63342/api/cpp-context`

所有端点返回 JSON，格式为 `{ "success": true/false, "data": ..., "error": "..." }`。

### Windows 路径说明

Windows 路径含盘符和反斜杠（例如 `D:\project\src\file.cpp`），直接粘贴到 curl 命令里会导致两个问题：

| 问题 | 原因 | 解决方案 |
|------|------|--------|
| `&` 截断命令 | CMD/PowerShell 把 `&` 当作命令分隔符 | 用双引号 `"..."` 包住整个 URL |
| 反斜杠被误解 | 部分 shell/工具把 `\` 视为转义符 | 改用正斜杠，或直接传反斜杠（插件服务端会自动转换） |

**推荐写法**：

```cmd
REM CMD（命令提示符）—— 直接用 curl，行为与 Linux 一致
curl "http://localhost:63342/api/cpp-context/ut-context?path=D:/project/src/file.cpp&name=myFunc"
```

```powershell
# PowerShell —— 必须用 curl.exe，否则会触发 Invoke-WebRequest 别名
# （Invoke-WebRequest 会返回 StatusCode/RawContent 等额外字段，不是纯 JSON）
curl.exe "http://localhost:63342/api/cpp-context/ut-context?path=D:/project/src/file.cpp&name=myFunc"

# 或者用 PowerShell 原生方式，自动解析 JSON：
Invoke-RestMethod "http://localhost:63342/api/cpp-context/ut-context?path=D:/project/src/file.cpp&name=myFunc"
```

> **关键点**：
> 1. 把路径里的 `\` 换成 `/`（`D:\project\src\file.cpp` → `D:/project/src/file.cpp`）
> 2. 用双引号包裹整个 URL（防止 `&` 被 shell 截断）
> 3. PowerShell 必须用 `curl.exe`，不能直接用 `curl`（后者是 `Invoke-WebRequest` 别名）

### GET /health

健康检查，返回插件版本和引擎模式。

```bash
curl http://localhost:63342/api/cpp-context/health
```

### GET /file?path=\<abs_path\>

获取文件的完整上下文（函数、结构体、枚举、宏、include 等）。

```bash
curl "http://localhost:63342/api/cpp-context/file?path=/home/user/project/src/main.cpp"
```

### GET /function?path=\<p\>&name=\<n\>

获取指定函数的上下文，包含函数签名、参数、函数体，以及相关的类型定义和 CMake 编译上下文。

```bash
curl "http://localhost:63342/api/cpp-context/function?path=/home/user/project/src/main.cpp&name=processData"
```

### GET /ut-context?path=\<p\>&name=\<n\>

**核心端点** —— 获取单元测试生成所需的上下文，输出扁平 string/string[] 格式（与 VS Code CodeArtsX-IDE 插件兼容）。

```bash
curl "http://localhost:63342/api/cpp-context/ut-context?path=/home/user/project/src/crypto/sm4_aes.cpp&name=encrypt_with_prefix"
```

响应示例：
```json
{
    "success": true,
    "data": {
        "modulePath": "/home/user/project/src/crypto/sm4_aes.cpp",
        "filenameWithoutExt": "sm4_aes",
        "signature": "uint32_t sm4_aes::encrypt_with_prefix(const uint8_t *key, uint32_t key_size, ...)",
        "definition": ["uint32_t sm4_aes::encrypt_with_prefix(...) {\n  ...\n}"],
        "structDefinitions": ["struct SM4_KEY {\n  ...\n};"],
        "headFiles": ["src/crypto/sm4_aes.h", "include/stbox/gmssl/sm4.h"],
        "externalFunctions": ["void sm4_set_encrypt_key(SM4_KEY *key, const uint8_t *user_key) @ include/stbox/gmssl/sm4.h"],
        "macroDefinitions": ["#define AAD_MAC_TEXT_LEN 64"],
        "namespacePath": "ypc::crypto"
    }
}
```

**字段说明：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `modulePath` | string | 源文件绝对路径 |
| `filenameWithoutExt` | string | 无后缀文件名 |
| `signature` | string | 函数签名（不含函数体） |
| `definition` | string[] | 完整函数定义含函数体（单元素数组） |
| `structDefinitions` | string[] | 相关的 struct/class/enum/typedef 原文（从文件和头文件中提取） |
| `headFiles` | string[] | 解析后的 #include 项目相对路径 |
| `externalFunctions` | string[] | 外部函数调用，格式：`"签名 @ 相对路径"` |
| `macroDefinitions` | string[] | 相关的 #define 宏原文 |
| `namespacePath` | string | 函数所在的命名空间路径（如 `"ypc::crypto"`），全局作用域为空字符串 |

### GET /project

获取项目摘要（文件列表、函数名、结构体名等）。

```bash
curl http://localhost:63342/api/cpp-context/project
```

### GET /cmake

获取 CMake 配置信息（targets、编译选项、链接库等）。

```bash
curl http://localhost:63342/api/cpp-context/cmake
```

### GET /search?q=\<query\>

全项目符号搜索。

```bash
curl "http://localhost:63342/api/cpp-context/search?q=encrypt"
```

### GET /invalidate

清空所有缓存，强制下次请求重新解析。

```bash
curl http://localhost:63342/api/cpp-context/invalidate
```

## CLion 引擎模式

| 模式 | CLion 版本 | 提取方式 | 说明 |
|------|-----------|---------|------|
| **Classic** | 2024.2 ~ 2025.2.x | PSI（语法树）| 通过 CIDR PSI API 提取，精度最高 |
| **Nova/Radler** | 2025.3+ | 文本正则 | CIDR PSI 被禁用，使用正则 + 括号深度匹配 + 文件系统搜索 |

插件会自动检测引擎模式，通过 `/health` 端点的 `engineMode` 和 `extractionMode` 字段可以确认。

## 故障排查

| 问题 | 原因 | 解决方案 |
|------|------|--------|
| 连接被拒绝 | IntelliJ HTTP 服务器未启动 | 打开任意项目，服务器自动启动 |
| 404 错误 | 端点路径错误 | 确认 URL 以 `/api/cpp-context/` 开头 |
| "Function not found" | 函数提取失败 | 检查 CLion 日志（Help → Show Log），查看详细错误 |
| 缓存数据过期 | 文件修改后未更新 | 调用 `/invalidate` 刷新缓存 |
| Windows curl 命令被截断 | `&` 被 CMD/PowerShell 解释为命令分隔符 | 用双引号包住整个 URL：`curl "http://...?path=D:/...&name=func"` |
| Windows 路径 "File not found" | 路径使用了反斜杠 | 将 `\` 换为 `/`，例如 `D:\src\file.cpp` → `D:/src/file.cpp` |
| PowerShell curl 返回 StatusCode/RawContent 等字段 | PowerShell 的 `curl` 是 `Invoke-WebRequest` 的别名，不是真正的 curl | 改用 `curl.exe "..."` 或 `Invoke-RestMethod "..."` |
| Windows 构建 "unable to access jarfile" | `gradle-wrapper.jar` 缺失 | 运行 `gradle wrapper --gradle-version=8.13`，或从仓库重新 clone |

---

# 开发者指南

## 项目结构

```
PromptMaster-Clion/
├── src/main/kotlin/com/promptmaster/clion/
│   ├── api/
│   │   └── ContextRestService.kt          # REST 端点（Netty HTTP handler）
│   ├── services/
│   │   ├── ContextExtractionService.kt    # 核心服务（缓存 + 调度）
│   │   └── ContextStartupActivity.kt     # 项目启动初始化
│   ├── extraction/
│   │   ├── CppContextExtractor.kt        # PSI 模式提取（Classic 引擎）
│   │   ├── TextBasedCppExtractor.kt      # 文本模式提取（Nova 引擎）
│   │   ├── UnitTestContextExtractor.kt   # ut-context 提取器
│   │   └── CMakeContextExtractor.kt      # CMake 配置提取
│   ├── cache/
│   │   ├── ContextCache.kt              # TTL 内存缓存
│   │   └── CacheInvalidator.kt          # PSI 变更监听器
│   └── models/
│       └── Models.kt                    # 所有数据类
├── src/main/resources/META-INF/
│   ├── plugin.xml                       # 插件描述符
│   └── withCidrLang.xml                 # 可选依赖描述符
├── build.gradle.kts
├── gradle.properties
└── docs/
    └── extraction-methodology.md        # 提取方法详解文档
```

## 技术栈

| 组件 | 技术 | 版本 |
|------|------|------|
| 语言 | Kotlin | 1.9.25 |
| IDE 平台 | IntelliJ Platform SDK | 2024.2.5（构建基准，兼容至 2025.3）|
| 序列化 | Kotlinx Serialization | 1.6.3 |
| 构建 | Gradle + IntelliJ Platform Plugin | 8.13 / 2.11.0 |
| JVM | Java | 21 |

## 架构概览

```
HTTP 请求 (port 63342)
    │
    ▼
ContextRestService          ← REST 路由分发
    │
    ▼
ContextExtractionService    ← 缓存层 + 提取调度
    │
    ├── CppContextExtractor        ← PSI 模式（反射加载 OCFile）
    ├── TextBasedCppExtractor      ← 文本模式（正则 + 深度匹配）
    ├── UnitTestContextExtractor   ← ut-context 编排
    └── CMakeContextExtractor      ← CMake 反射提取
    │
    ▼
ContextCache (5 min TTL)    ← ConcurrentHashMap + PSI 变更监听失效
```

**关键设计决策**：
- `com.intellij.cidr.lang` 声明为 **optional dependency**，确保 Nova 模式下插件仍能加载
- `CppContextExtractor` 使用**反射**加载 `OCFile` 等 CIDR 类，避免编译时硬依赖
- `TextBasedCppExtractor` 使用**括号深度匹配**而非纯正则，处理多行函数签名
- `UnitTestContextExtractor` 使用 **java.io.File** 做文件发现，不依赖 VFS 预填充
- `ConcurrentHashMap` 不能存 `null`，用 sentinel 值替代

## 构建与调试

### 构建

```bash
# macOS / Linux
./gradlew clean buildPlugin

# Windows
gradlew.bat clean buildPlugin
```

输出：`build/distributions/PromptMaster-Clion-x.x.x.zip`

### 在 IDE 中调试

1. 用 IntelliJ IDEA 打开本项目
2. 运行 `Run Plugin`（Gradle task: `runIde`），会启动一个带插件的 CLion 沙箱实例
3. 在沙箱 CLion 中打开一个 C/C++ 项目
4. 用 `curl` 测试 API

### 常见构建问题

| 问题 | 解决方案 |
|------|--------|
| `Unable to access jarfile` (Windows) | 确认 `gradle/wrapper/gradle-wrapper.jar` 存在；或运行 `gradle wrapper --gradle-version=8.13` |
| Kotlin 版本不兼容 | 确认 `build.gradle.kts` 中 Kotlin 版本为 2.1.20 |
| CLion SDK 下载磁盘不足 | 清理 `~/.gradle/caches/` 下的旧 SDK 缓存 |
| `plugin.xml` 修改不生效 | 执行 `clean` 再 `buildPlugin`（Gradle 增量构建可能不更新资源） |

## 提取方法详解

详见 [docs/extraction-methodology.md](docs/extraction-methodology.md)。

---

**版本**: 1.1.0 | **兼容**: CLion 2024.2 ~ 2025.3
