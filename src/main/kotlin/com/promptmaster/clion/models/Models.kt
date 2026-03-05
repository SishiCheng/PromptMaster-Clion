package com.promptmaster.clion.models

import kotlinx.serialization.Serializable

// ============================================================
// File-level context
// ============================================================

@Serializable
data class FileContext(
    val filePath: String,
    val fileName: String,
    val includes: List<IncludeInfo>,
    val functions: List<FunctionInfo>,
    val structs: List<StructInfo>,
    val enums: List<EnumInfo>,
    val typedefs: List<TypedefInfo>,
    val macros: List<MacroInfo>,
    val namespaces: List<String> = emptyList(),
    val globalVariables: List<VariableInfo> = emptyList()
)

// ============================================================
// Function
// ============================================================

@Serializable
data class FunctionInfo(
    val name: String,
    val qualifiedName: String? = null,
    val returnType: String,
    val parameters: List<ParameterInfo>,
    val signature: String,
    val isDefinition: Boolean,
    val lineNumber: Int,
    val body: String? = null,
    val templateParameters: String? = null,
    val isVirtual: Boolean = false,
    val isStatic: Boolean = false,
    val isConst: Boolean = false,
    val isNoexcept: Boolean = false,
    val namespacePath: List<String> = emptyList(),
    val ownerClass: String? = null
)

@Serializable
data class ParameterInfo(
    val name: String,
    val type: String,
    val defaultValue: String? = null
)

// ============================================================
// Struct / Class / Union
// ============================================================

@Serializable
data class StructInfo(
    val name: String,
    val qualifiedName: String? = null,
    val kind: String,                 // "struct", "class", "union"
    val members: List<MemberInfo>,
    val methods: List<FunctionInfo>,
    val baseClasses: List<BaseClassInfo>,
    val lineNumber: Int,
    val templateParameters: String? = null,
    val isForwardDeclaration: Boolean = false,
    val namespacePath: List<String> = emptyList()
)

@Serializable
data class MemberInfo(
    val name: String,
    val type: String,
    val accessSpecifier: String = "public",
    val isStatic: Boolean = false,
    val defaultValue: String? = null
)

@Serializable
data class BaseClassInfo(
    val name: String,
    val accessSpecifier: String = "public"
)

// ============================================================
// Enum
// ============================================================

@Serializable
data class EnumInfo(
    val name: String,
    val qualifiedName: String? = null,
    val isScoped: Boolean,
    val underlyingType: String? = null,
    val enumerators: List<EnumeratorInfo>,
    val lineNumber: Int,
    val namespacePath: List<String> = emptyList()
)

@Serializable
data class EnumeratorInfo(
    val name: String,
    val value: String? = null
)

// ============================================================
// Macro
// ============================================================

@Serializable
data class MacroInfo(
    val name: String,
    val parameters: List<String> = emptyList(),
    val body: String,
    val isFunctionLike: Boolean,
    val lineNumber: Int
)

// ============================================================
// Include
// ============================================================

@Serializable
data class IncludeInfo(
    val path: String,
    val isSystem: Boolean,
    val resolvedPath: String? = null
)

// ============================================================
// Typedef / Using
// ============================================================

@Serializable
data class TypedefInfo(
    val name: String,
    val underlyingType: String,
    val lineNumber: Int,
    val namespacePath: List<String> = emptyList()
)

// ============================================================
// Variable
// ============================================================

@Serializable
data class VariableInfo(
    val name: String,
    val type: String,
    val isConst: Boolean = false,
    val isStatic: Boolean = false,
    val lineNumber: Int
)

// ============================================================
// Function-level context (for unit test generation)
// ============================================================

@Serializable
data class FunctionContext(
    val function: FunctionInfo,
    val dependentTypes: List<StructInfo> = emptyList(),
    val dependentEnums: List<EnumInfo> = emptyList(),
    val dependentTypedefs: List<TypedefInfo> = emptyList(),
    val requiredIncludes: List<IncludeInfo> = emptyList(),
    val compileContext: CMakeContext? = null
)

// ============================================================
// CMake
// ============================================================

@Serializable
data class CMakeContext(
    val isInitialized: Boolean,
    val projectName: String? = null,
    val configurations: List<CMakeConfigInfo> = emptyList()
)

@Serializable
data class CMakeConfigInfo(
    val name: String,
    val buildType: String? = null,
    val targets: List<CMakeTargetInfo> = emptyList()
)

@Serializable
data class CMakeTargetInfo(
    val name: String,
    val type: String,
    val sources: List<String> = emptyList(),
    val compileOptions: List<String> = emptyList(),
    val includeDirectories: List<String> = emptyList(),
    val definitions: List<String> = emptyList(),
    val linkLibraries: List<String> = emptyList()
)

// ============================================================
// Project-level summary
// ============================================================

@Serializable
data class ProjectContext(
    val projectName: String,
    val projectBasePath: String,
    val files: List<FileSummary> = emptyList(),
    val cmake: CMakeContext? = null
)

@Serializable
data class FileSummary(
    val filePath: String,
    val functions: List<String> = emptyList(),
    val structs: List<String> = emptyList(),
    val enums: List<String> = emptyList()
)

// ============================================================
// API Response Wrappers
// ============================================================

@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null
)

@Serializable
data class HealthResponse(
    val status: String,
    val pluginVersion: String,
    val projectName: String? = null,
    /** Whether the classic CIDR PSI API is available (false in CLion 2025.3 Nova/Radler mode). */
    val cidrLangAvailable: Boolean = true,
    /** "classic" when full PSI extraction works; "nova" when PSI is unavailable. */
    val engineMode: String = "classic"
)
