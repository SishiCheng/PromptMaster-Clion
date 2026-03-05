# 单测生成提示词模板

下面是一个可用于提示词市场的模板示例。模板中的 `{{变量名}}` 由 Continue 在调用时自动替换为
PromptMaster-Clion 插件提取的真实上下文。

---

## 模板内容

你是一位 C++ 单元测试专家。请为以下函数生成 Google Test 单元测试代码。

### 待测函数信息

**函数签名:**
```cpp
{{function_signature}}
```

**函数实现:**
```cpp
{{function_body}}
```

**所在命名空间:** `{{namespace_path}}`

**所属类:** `{{owner_class}}`（如果有）

### 依赖的类型定义

以下是该函数使用的结构体/类/枚举/类型别名定义，生成测试时必须正确引用：

```cpp
{{dependent_types}}
```

### 需要的 include

```cpp
{{required_includes}}
```

### 编译环境

- **CMake Target:** `{{cmake_target}}`
- **编译选项:** `{{compile_options}}`
- **Include 目录:** `{{include_directories}}`

### 要求

1. 使用 Google Test (GTest) 框架
2. 生成的代码必须可以直接编译通过
3. 包含所有必要的 `#include` 指令
4. 测试用例需覆盖：
   - 正常输入
   - 边界条件
   - 异常/错误情况（如果适用）
5. 使用正确的命名空间
6. 如果函数是类的方法，需要正确构造对象

### 输出格式

请只输出完整的 `.cpp` 测试文件代码，包含所有 include 和 namespace。

---

## 上下文变量映射表

在 Continue 的提示词市场中创建模板时，使用以下变量名对应 PromptMaster-Clion API 返回的字段：

| 模板变量 | API 字段路径 | 说明 |
|----------|-------------|------|
| `{{function_signature}}` | `data.function.signature` | 函数签名 |
| `{{function_body}}` | `data.function.body` | 函数体 |
| `{{namespace_path}}` | `data.function.namespacePath` (join "::") | 命名空间链 |
| `{{owner_class}}` | `data.function.ownerClass` | 所属类名 |
| `{{dependent_types}}` | `data.dependentTypes[*]` 序列化 | 依赖的类型定义 |
| `{{required_includes}}` | `data.requiredIncludes[*].path` | include 路径列表 |
| `{{cmake_target}}` | `data.compileContext.configurations[0].targets[0].name` | CMake target |
| `{{compile_options}}` | `data.compileContext.configurations[0].targets[0].compileOptions` | 编译选项 |
| `{{include_directories}}` | `data.compileContext.configurations[0].targets[0].includeDirectories` | include 目录 |
