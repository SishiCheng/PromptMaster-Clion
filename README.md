# PromptMaster-Clion

Extracts C/C++ project code context and exposes it via a local HTTP API for AI-assisted unit test generation and code analysis.

## 🎯 核心能力点

### 1. **代码上下文提取**
- **函数签名提取**：自动识别和提取所有函数声明、返回类型、参数列表和文档注释
- **类和结构体定义**：解析C++类、结构体的成员变量、方法声明和继承关系
- **宏定义和typedef**：提取所有预处理指令和类型定义
- **命名空间识别**：正确处理命名空间嵌套和作用域

### 2. **项目级代码分析**
- **文件级上下文**：获取完整的文件内容及其依赖关系
- **函数级查询**：精确定位特定函数的完整上下文
- **项目摘要**：获取整个项目的高层概览，包括主要模块和结构
- **符号搜索**：支持全项目符号查询和交叉引用分析

### 3. **构建系统集成**
- **CMake解析**：提取CMake配置中的编译选项、链接库、目标定义
- **包含依赖追踪**：完整的#include依赖图分析
- **编译上下文**：捕获编译时的所有配置信息

### 4. **智能缓存系统**
- **多级缓存**：文件级和项目级缓存以提高性能
- **智能失效机制**：基于文件修改时间的增量更新
- **按需刷新**：提供API接口手动刷新缓存

### 5. **REST API接口**
- **本地HTTP服务**：在IntelliJ内置服务器上公开的API
- **JSON序列化**：所有响应使用标准JSON格式
- **版本兼容性**：支持CLion 2025.2及以上版本

## 🔧 技术实现

### 架构设计

```
PromptMaster-Clion
├── API层 (ContextRestService.kt)
│   └── REST端点处理和HTTP请求路由
├── 服务层 (services/)
│   ├── ContextExtractionService - 核心提取服务
│   └── ContextStartupActivity - 插件启动初始化
├── 提取器 (extraction/)
│   ├── CppContextExtractor - C/C++代码解析
│   └── CMakeContextExtractor - CMake配置解析
├── 数据模型 (models/)
│   └── 序列化对象定义
└── 缓存系统 (cache/)
    └── 多级缓存实现
```

### 核心技术栈

| 组件 | 技术 | 版本 |
|------|------|------|
| 开发语言 | Kotlin | 2.1.20 |
| IDE平台 | IntelliJ Platform | 2025.2.2 |
| 序列化 | Kotlinx Serialization | 1.7.3 |
| 构建系统 | Gradle | 8.x+ |
| JVM版本 | Java 21 |

### 代码解析实现

1. **PSI (Program Structure Index) 访问**
   - 利用IntelliJ的PSI API对C/C++代码进行抽象语法树解析
   - 支持CLion的两种引擎：Classic (CIDR) 和 Nova (Radler) 模式
   - 可选依赖处理确保在两种模式下都能运行

2. **C/C++ 语言特性支持**
   - 函数重载识别
   - 模板和泛型解析
   - 类继承和虚函数识别
   - 宏展开上下文

3. **CMake 配置解析**
   - 项目级编译标志提取
   - 链接库和依赖关系分析
   - 目标定义和属性获取

4. **缓存策略**
   - **L1缓存**：函数/结构体级别的详细信息缓存
   - **L2缓存**：文件级别的整体结构缓存
   - **LRU策略**：自动清理过期缓存条目

### HTTP服务集成

- **端口**：使用IntelliJ内置HTTP服务器的默认端口 (通常为 63342)
- **认证**：继承IntelliJ的安全机制
- **CORS**：配置为允许本地开发工具请求
- **超时处理**：异步处理长运行的代码解析任务

## 📖 用户指导书

### 安装

1. **从市场安装**
   - 在CLion中打开 Settings → Plugins
   - 搜索"PromptMaster-Clion"
   - 点击Install并重启IDE

2. **从本项目构建安装**
   ```bash
   ./gradlew buildPlugin
   ```
   插件包将生成在 `build/distributions/` 目录中
   - 在 Settings → Plugins → ⚙️ → Install plugin from disk 选择构建好的ZIP文件

### 快速开始

#### 1. 创建或打开一个C/C++项目
   - 在CLion中打开包含CMakeLists.txt的C/C++项目
   - 等待项目索引完成（通常需要几秒钟）

#### 2. 首次初始化
   - 插件在项目打开时会自动初始化
   - Check Tools → PromptMaster Console 查看初始化日志（如果需要）
   - 系统将自动扫描项目并构建代码索引

#### 3. 通过HTTP API访问代码上下文
   - 基础URL：`http://localhost:63342/api/cpp-context`

### API 端点使用

#### 健康检查
```bash
curl http://localhost:63342/api/cpp-context/health
```
**响应**：
```json
{
  "status": "ok",
  "version": "1.0.0",
  "timestamp": "2025-01-15T10:30:00Z"
}
```

#### 获取文件完整上下文
```bash
curl "http://localhost:63342/api/cpp-context/file?path=/absolute/path/to/file.cpp"
```
**参数**：
- `path`：文件的绝对路径

**响应**：
```json
{
  "file": "file.cpp",
  "path": "/absolute/path/to/file.cpp",
  "includes": ["<vector>", "\"header.h\""],
  "functions": [
    {
      "name": "functionName",
      "returnType": "void",
      "parameters": ["int x", "const string& y"],
      "startLine": 45,
      "endLine": 67
    }
  ],
  "classes": [
    {
      "name": "ClassName",
      "baseClasses": ["BaseClass"],
      "members": [...],
      "startLine": 10,
      "endLine": 40
    }
  ],
  "macros": [
    {
      "name": "MAX_SIZE",
      "value": "1024",
      "line": 5
    }
  ]
}
```

#### 获取函数级上下文
```bash
curl "http://localhost:63342/api/cpp-context/function?path=/absolute/path/to/file.cpp&name=functionName"
```
**参数**：
- `path`：文件的绝对路径
- `name`：函数名称

**响应**：
```json
{
  "name": "functionName",
  "file": "file.cpp",
  "returnType": "int",
  "parameters": [
    { "name": "x", "type": "int" },
    { "name": "y", "type": "const string&" }
  ],
  "body": "... function implementation code ...",
  "docComment": "/// Function documentation",
  "startLine": 45,
  "endLine": 67
}
```

#### 获取项目摘要
```bash
curl "http://localhost:63342/api/cpp-context/project?project=MyProject"
```
**参数**：
- `project`：（可选）项目名称；如果包含多个项目，指定具体项目

**响应**：
```json
{
  "projectName": "MyProject",
  "ROOT": "/path/to/project",
  "fileCount": 42,
  "functionCount": 128,
  "classCount": 18,
  "cmakeVersion": "3.20",
  "modules": [
    {
      "name": "core",
      "files": 10,
      "functions": 35
    }
  ]
}
```

#### 获取CMake配置信息
```bash
curl "http://localhost:63342/api/cpp-context/cmake?project=MyProject"
```
**参数**：
- `project`：（可选）项目名称

**响应**：
```json
{
  "cmakeLists": "/path/to/CMakeLists.txt",
  "compileFlags": ["-std=c++17", "-Wall", "-O2"],
  "linkLibraries": ["pthread", "boost_system"],
  "targets": [
    {
      "name": "myapp",
      "type": "executable",
      "sources": ["main.cpp", "utils.cpp"]
    }
  ],
  "variables": {
    "CMAKE_CXX_STANDARD": "17",
    "CMAKE_BUILD_TYPE": "Release"
  }
}
```

#### 符号搜索
```bash
curl "http://localhost:63342/api/cpp-context/search?q=className&type=class"
```
**参数**：
- `q`：搜索查询（函数名、类名、宏名等）
- `type`：（可选）符号类型：`function`, `class`, `macro`, `typedef`, `all`

**响应**：
```json
{
  "query": "className",
  "type": "class",
  "results": [
    {
      "name": "className",
      "type": "class",
      "file": "definitions.h",
      "line": 42,
      "context": "Full class definition..."
    }
  ]
}
```

#### 刷新缓存
```bash
curl -X POST "http://localhost:63342/api/cpp-context/invalidate"
```
**参数**：
- `scope`：（可选）`file` | `project` | `full`（默认为 `full`）
- `path`：（可选）特定文件路径（仅当 scope=file 时使用）

**响应**：
```json
{
  "status": "success",
  "message": "Cache invalidated",
  "scope": "full",
  "affectedItems": 150
}
```

### 常见使用场景

#### 场景1：为特定函数生成单元测试
```bash
# 1. 查询函数上下文
curl "http://localhost:63342/api/cpp-context/function?path=/app/src/math.cpp&name=calculateSum"

# 2. 将响应传给AI模型进行测试生成
# AI成功理解函数参数、返回类型和实现细节后，生成对应的测试用例
```

#### 场景2：获取类的完整定义用于重构
```bash
# 1. 获取类所在文件的所有上下文
curl "http://localhost:63342/api/cpp-context/file?path=/app/include/DataManager.h"

# 2. 根据返回的类定义和相关方法，进行重构分析
```

#### 场景3：分析项目的整体结构
```bash
# 获取项目摘要和模块信息
curl "http://localhost:63342/api/cpp-context/project?project=MyProject"

# 获取编译配置信息
curl "http://localhost:63342/api/cpp-context/cmake?project=MyProject"
```

#### 场景4：快速搜索符号定义
```bash
# 搜索类定义
curl "http://localhost:63342/api/cpp-context/search?q=DataManager&type=class"

# 搜索函数
curl "http://localhost:63342/api/cpp-context/search?q=processData&type=function"
```

### 性能优化建议

1. **批量查询**
   - 使用项目级API而非逐个查询单个文件
   - 利用缓存机制，避免频繁查询相同数据

2. **缓存管理**
   - 定期刷新缓存以确保数据最新：在大规模代码修改后调用 `/invalidate`

3. **大型项目处理**
   - 对于文件数超过500的项目，首次索引可能需要较长时间
   - 可在IDE的后台任务中等待首次索引完成

### 故障排查

| 问题 | 原因 | 解决方案 |
|------|------|--------|
| 连接拒绝 | IntelliJ HTTP服务器未启动 | 打开任意项目，服务器会自动启动 |
| 404错误 | API端点路径错误 | 检查URL格式，确保使用正确的基础路径 |
| 空响应 | 项目索引未完成 | 等待IDE右下角的进度条完成 |
| "CIDR PSI not available" | CLion使用Nova模式 | 功能在Nova模式中受限，某些功能可能不可用 |
| 缓存过期数据 | 文件已修改但缓存未更新 | 手动调用 `/invalidate` 刷新缓存 |

## 📋 系统要求

- **IDE版本**：CLion 2025.2 或更高版本
- **JVM版本**：Java 21+
- **操作系统**：macOS, Linux, Windows
- **内存**：最小2GB（推荐4GB+，用于大型项目）
- **磁盘空间**：缓存需要约50-500MB，取决于项目大小

## 🔗 相关配置

### CMakeLists.txt 支持
确保你的项目包含有效的 CMakeLists.txt 文件，此插件可以正确解析编译配置。

### 支持的C++标准
- C++11, C++14, C++17, C++20, C++23

### IDE插件配置
插件会在以下位置保存临时缓存和配置：
- macOS: `~/Library/Caches/JetBrains/CLion2025.x/`
- Linux: `~/.cache/JetBrains/CLion2025.x/`
- Windows: `%LOCALAPPDATA%\JetBrains\CLion2025.x\cache`

## 📝 许可证

请参考项目的LICENSE文件。

## 🤝 反馈

遇到问题或有功能建议？欢迎提交Issues。

---

**最后更新**: 2025年3月6日 | **版本**: 1.0.0
