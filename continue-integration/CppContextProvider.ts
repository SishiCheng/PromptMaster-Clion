/**
 * PromptMaster-Clion Context Provider for Continue
 *
 * 此文件是 Continue 端的自定义 Context Provider 实现示例。
 * 它从 PromptMaster-Clion 插件的 HTTP API 获取 C/C++ 项目上下文，
 * 并将其格式化为 Continue 可消费的上下文项。
 *
 * 使用方式：
 *   在 Continue 的 config.ts 中注册此 provider
 */

import fetch from "node-fetch";

// PromptMaster-Clion API 默认端口 (CLion 内置服务器)
const BASE_URL = "http://localhost:63342/api/cpp-context";

// ----------------------------------------------------------
// 类型定义（对应 PromptMaster-Clion 的 Models.kt）
// ----------------------------------------------------------

interface FunctionInfo {
  name: string;
  qualifiedName?: string;
  returnType: string;
  parameters: { name: string; type: string; defaultValue?: string }[];
  signature: string;
  isDefinition: boolean;
  lineNumber: number;
  body?: string;
  templateParameters?: string;
  isVirtual: boolean;
  isStatic: boolean;
  isConst: boolean;
  isNoexcept: boolean;
  namespacePath: string[];
  ownerClass?: string;
}

interface StructInfo {
  name: string;
  qualifiedName?: string;
  kind: string;
  members: { name: string; type: string; accessSpecifier: string }[];
  methods: FunctionInfo[];
  baseClasses: { name: string; accessSpecifier: string }[];
  lineNumber: number;
  templateParameters?: string;
  isForwardDeclaration: boolean;
  namespacePath: string[];
}

interface FileContext {
  filePath: string;
  fileName: string;
  includes: { path: string; isSystem: boolean; resolvedPath?: string }[];
  functions: FunctionInfo[];
  structs: StructInfo[];
  enums: { name: string; isScoped: boolean; enumerators: { name: string; value?: string }[] }[];
  typedefs: { name: string; underlyingType: string }[];
  macros: { name: string; parameters: string[]; body: string; isFunctionLike: boolean }[];
  namespaces: string[];
}

interface FunctionContext {
  function: FunctionInfo;
  dependentTypes: StructInfo[];
  dependentEnums: any[];
  dependentTypedefs: any[];
  requiredIncludes: { path: string; isSystem: boolean }[];
  compileContext?: {
    isInitialized: boolean;
    projectName?: string;
    configurations: {
      name: string;
      targets: {
        name: string;
        type: string;
        compileOptions: string[];
        includeDirectories: string[];
        definitions: string[];
      }[];
    }[];
  };
}

interface ApiResponse<T> {
  success: boolean;
  data?: T;
  error?: string;
}

// ----------------------------------------------------------
// API 调用函数
// ----------------------------------------------------------

async function fetchFromPlugin<T>(endpoint: string, params?: Record<string, string>): Promise<T | null> {
  try {
    const url = new URL(`${BASE_URL}/${endpoint}`);
    if (params) {
      Object.entries(params).forEach(([key, value]) => {
        url.searchParams.append(key, value);
      });
    }

    const response = await fetch(url.toString(), {
      method: "GET",
      headers: { "Accept": "application/json" },
    });

    if (!response.ok) {
      console.error(`PromptMaster API error: ${response.status} ${response.statusText}`);
      return null;
    }

    const json = await response.json() as ApiResponse<T>;
    if (!json.success) {
      console.error(`PromptMaster API error: ${json.error}`);
      return null;
    }
    return json.data ?? null;
  } catch (error) {
    console.error("Failed to connect to PromptMaster-Clion plugin:", error);
    return null;
  }
}

// ----------------------------------------------------------
// 上下文格式化
// ----------------------------------------------------------

/**
 * 将 FunctionContext 格式化为提示词可用的文本
 */
function formatFunctionContext(ctx: FunctionContext): string {
  const parts: string[] = [];

  // 1. 函数签名与命名空间
  const ns = ctx.function.namespacePath.join("::");
  if (ns) {
    parts.push(`// Namespace: ${ns}`);
  }
  if (ctx.function.ownerClass) {
    parts.push(`// Class: ${ctx.function.ownerClass}`);
  }
  parts.push(`// Function signature:`);
  parts.push(ctx.function.signature);
  parts.push("");

  // 2. 函数体
  if (ctx.function.body) {
    parts.push("// Function implementation:");
    parts.push(ctx.function.body);
    parts.push("");
  }

  // 3. 依赖类型
  if (ctx.dependentTypes.length > 0) {
    parts.push("// Dependent type definitions:");
    for (const struct of ctx.dependentTypes) {
      const structNs = struct.namespacePath.join("::");
      if (structNs) parts.push(`namespace ${structNs} {`);

      const keyword = struct.kind;
      let decl = `${keyword} ${struct.name}`;

      if (struct.baseClasses.length > 0) {
        decl += " : " + struct.baseClasses.map(b => `${b.accessSpecifier} ${b.name}`).join(", ");
      }
      decl += " {";
      parts.push(decl);

      for (const member of struct.members) {
        parts.push(`  ${member.type} ${member.name};  // ${member.accessSpecifier}`);
      }
      for (const method of struct.methods) {
        parts.push(`  ${method.signature};`);
      }
      parts.push("};");
      if (structNs) parts.push("}");
      parts.push("");
    }
  }

  // 4. Include
  if (ctx.requiredIncludes.length > 0) {
    parts.push("// Required includes:");
    for (const inc of ctx.requiredIncludes) {
      if (inc.isSystem) {
        parts.push(`#include <${inc.path}>`);
      } else {
        parts.push(`#include "${inc.path}"`);
      }
    }
    parts.push("");
  }

  // 5. 编译上下文
  if (ctx.compileContext?.configurations?.length) {
    const config = ctx.compileContext.configurations[0];
    if (config.targets?.length) {
      const target = config.targets[0];
      parts.push("// Build context:");
      parts.push(`//   CMake target: ${target.name}`);
      if (target.compileOptions.length > 0) {
        parts.push(`//   Compile options: ${target.compileOptions.join(" ")}`);
      }
      if (target.definitions.length > 0) {
        parts.push(`//   Definitions: ${target.definitions.join(" ")}`);
      }
    }
  }

  return parts.join("\n");
}

/**
 * 将 FileContext 格式化为提示词可用的文本
 */
function formatFileContext(ctx: FileContext): string {
  const parts: string[] = [];

  parts.push(`// File: ${ctx.filePath}`);
  parts.push(`// Namespaces: ${ctx.namespaces.join(", ") || "(global)"}`);
  parts.push("");

  // Includes
  if (ctx.includes.length > 0) {
    for (const inc of ctx.includes) {
      parts.push(inc.isSystem ? `#include <${inc.path}>` : `#include "${inc.path}"`);
    }
    parts.push("");
  }

  // Macros
  for (const macro of ctx.macros) {
    if (macro.isFunctionLike) {
      parts.push(`#define ${macro.name}(${macro.parameters.join(", ")}) ${macro.body}`);
    } else {
      parts.push(`#define ${macro.name} ${macro.body}`);
    }
  }
  if (ctx.macros.length > 0) parts.push("");

  // Structs
  for (const struct of ctx.structs) {
    let header = `${struct.kind} ${struct.name}`;
    if (struct.baseClasses.length > 0) {
      header += " : " + struct.baseClasses.map(b => `${b.accessSpecifier} ${b.name}`).join(", ");
    }
    parts.push(`${header} { /* ${struct.members.length} members, ${struct.methods.length} methods */ };`);
  }
  if (ctx.structs.length > 0) parts.push("");

  // Functions
  for (const func of ctx.functions) {
    parts.push(`${func.signature};`);
  }

  return parts.join("\n");
}

// ----------------------------------------------------------
// Continue Context Provider 导出
// ----------------------------------------------------------

/**
 * 用法: 在 Continue 的 config.ts 中如下注册:
 *
 * ```typescript
 * import { getCppUnitTestContext } from "./CppContextProvider";
 *
 * export default {
 *   contextProviders: [
 *     {
 *       name: "cpp-unittest-context",
 *       description: "C++ 单测生成上下文 (PromptMaster-Clion)",
 *       getContextItems: async (query) => {
 *         return await getCppUnitTestContext(query.filePath, query.functionName);
 *       }
 *     }
 *   ]
 * };
 * ```
 */

export async function getCppFileContext(filePath: string): Promise<string | null> {
  const ctx = await fetchFromPlugin<FileContext>("file", { path: filePath });
  if (!ctx) return null;
  return formatFileContext(ctx);
}

export async function getCppFunctionContext(filePath: string, functionName: string): Promise<string | null> {
  const ctx = await fetchFromPlugin<FunctionContext>("function", { path: filePath, name: functionName });
  if (!ctx) return null;
  return formatFunctionContext(ctx);
}

export async function getCppUnitTestContext(filePath: string, functionName: string): Promise<{
  name: string;
  description: string;
  content: string;
}[]> {
  const funcCtx = await fetchFromPlugin<FunctionContext>("function", { path: filePath, name: functionName });
  if (!funcCtx) return [];

  const contextItems = [];

  // Main function context
  contextItems.push({
    name: `Function: ${funcCtx.function.name}`,
    description: `Unit test context for ${funcCtx.function.qualifiedName || funcCtx.function.name}`,
    content: formatFunctionContext(funcCtx),
  });

  return contextItems;
}

export async function isPluginAvailable(): Promise<boolean> {
  try {
    const response = await fetch(`${BASE_URL}/health`);
    return response.ok;
  } catch {
    return false;
  }
}
