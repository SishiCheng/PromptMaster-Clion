package com.promptmaster.clion.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.application.ReadAction
import com.promptmaster.clion.cache.ContextCache
import com.promptmaster.clion.extraction.CMakeContextExtractor
import com.promptmaster.clion.extraction.CppContextExtractor
import com.promptmaster.clion.models.*

@Service(Service.Level.PROJECT)
class ContextExtractionService(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(ContextExtractionService::class.java)
    private val cppExtractor = CppContextExtractor(project)
    private val cmakeExtractor = CMakeContextExtractor(project)
    private val cache = ContextCache()

    companion object {
        const val PLUGIN_VERSION = "1.0.0"

        fun getInstance(project: Project): ContextExtractionService {
            return project.getService(ContextExtractionService::class.java)
        }
    }

    // ----------------------------------------------------------
    // File-level context
    // ----------------------------------------------------------

    fun getFileContext(filePath: String): FileContext? {
        return cache.getOrCompute("file:$filePath") {
            val vf = resolveFile(filePath) ?: return@getOrCompute null
            cppExtractor.extractFileContext(vf)
        }
    }

    // ----------------------------------------------------------
    // Function-level context (primary endpoint for unit test gen)
    // ----------------------------------------------------------

    fun getFunctionContext(filePath: String, functionName: String): FunctionContext? {
        val fileCtx = getFileContext(filePath) ?: return null

        val function = fileCtx.functions.find { it.name == functionName }
            ?: return null

        // Gather dependent types from the same file
        val depTypes = resolveDependentTypes(function, fileCtx)
        val depEnums = resolveDependentEnums(function, fileCtx)
        val depTypedefs = resolveDependentTypedefs(function, fileCtx)

        return FunctionContext(
            function = function,
            dependentTypes = depTypes,
            dependentEnums = depEnums,
            dependentTypedefs = depTypedefs,
            requiredIncludes = fileCtx.includes,
            compileContext = getCMakeContext()
        )
    }

    // ----------------------------------------------------------
    // Project-level context
    // ----------------------------------------------------------

    fun getProjectContext(): ProjectContext {
        return cache.getOrCompute("project") {
            val basePath = project.basePath ?: ""
            val fileSummaries = mutableListOf<FileSummary>()

            ReadAction.compute<Unit, Throwable> {
                val contentRoots = ProjectRootManager.getInstance(project).contentRoots
                for (root in contentRoots) {
                    collectCppFiles(root, fileSummaries)
                }
            }

            ProjectContext(
                projectName = project.name,
                projectBasePath = basePath,
                files = fileSummaries,
                cmake = getCMakeContext()
            )
        }
    }

    // ----------------------------------------------------------
    // CMake context
    // ----------------------------------------------------------

    fun getCMakeContext(): CMakeContext? {
        return cache.getOrCompute("cmake") {
            cmakeExtractor.extractCMakeContext()
        }
    }

    /**
     * Find the CMake target containing a specific source file.
     */
    fun getCMakeTargetForFile(filePath: String): CMakeTargetInfo? {
        return cmakeExtractor.findTargetForFile(filePath)
    }

    // ----------------------------------------------------------
    // Symbol search
    // ----------------------------------------------------------

    fun searchSymbols(query: String): List<FileSummary> {
        val projectCtx = getProjectContext()
        val lowerQuery = query.lowercase()

        return projectCtx.files.filter { file ->
            file.functions.any { it.lowercase().contains(lowerQuery) } ||
            file.structs.any { it.lowercase().contains(lowerQuery) } ||
            file.enums.any { it.lowercase().contains(lowerQuery) }
        }
    }

    // ----------------------------------------------------------
    // Cache management
    // ----------------------------------------------------------

    fun invalidateCacheForFile(filePath: String) {
        cache.invalidate("file:$filePath")
        // Also invalidate project summary since it depends on file contents
        cache.invalidate("project")
        logger.debug("Cache invalidated for file: $filePath")
    }

    fun invalidateAllCache() {
        cache.clear()
        logger.info("All caches invalidated")
    }

    // ----------------------------------------------------------
    // Health
    // ----------------------------------------------------------

    fun getHealth(): HealthResponse {
        val cidrAvailable = CppContextExtractor.isPsiAvailable
        return HealthResponse(
            status = "ok",
            pluginVersion = PLUGIN_VERSION,
            projectName = project.name,
            cidrLangAvailable = cidrAvailable,
            engineMode = if (cidrAvailable) "classic" else "nova"
        )
    }

    // ----------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------

    private fun resolveFile(filePath: String): VirtualFile? {
        return LocalFileSystem.getInstance().findFileByPath(filePath)
    }

    private fun collectCppFiles(dir: VirtualFile, result: MutableList<FileSummary>) {
        if (!dir.isDirectory) {
            if (isCppFile(dir)) {
                val fileCtx = cppExtractor.extractFileContext(dir)
                if (fileCtx != null) {
                    result.add(FileSummary(
                        filePath = dir.path,
                        functions = fileCtx.functions.mapNotNull { it.name.takeIf { n -> n != "<anonymous>" } },
                        structs = fileCtx.structs.mapNotNull { it.name.takeIf { n -> n != "<anonymous>" } },
                        enums = fileCtx.enums.mapNotNull { it.name.takeIf { n -> n != "<anonymous>" } }
                    ))
                }
            }
            return
        }

        // Skip build directories and hidden directories
        val name = dir.name
        if (name.startsWith(".") || name == "build" || name == "cmake-build-debug" ||
            name == "cmake-build-release" || name == "node_modules") {
            return
        }

        for (child in dir.children) {
            collectCppFiles(child, result)
        }
    }

    private fun isCppFile(file: VirtualFile): Boolean {
        val ext = file.extension?.lowercase() ?: return false
        return ext in setOf("c", "cpp", "cc", "cxx", "h", "hpp", "hxx", "hh")
    }

    /**
     * Find struct/class definitions that the function's parameter types or return type reference.
     */
    private fun resolveDependentTypes(function: FunctionInfo, fileCtx: FileContext): List<StructInfo> {
        val referencedTypeNames = mutableSetOf<String>()

        // Collect type names from return type
        referencedTypeNames.addAll(extractTypeNames(function.returnType))

        // Collect type names from parameters
        for (param in function.parameters) {
            referencedTypeNames.addAll(extractTypeNames(param.type))
        }

        return fileCtx.structs.filter { struct ->
            struct.name in referencedTypeNames ||
            struct.qualifiedName?.split("::")?.lastOrNull() in referencedTypeNames
        }
    }

    private fun resolveDependentEnums(function: FunctionInfo, fileCtx: FileContext): List<EnumInfo> {
        val referencedTypeNames = mutableSetOf<String>()
        referencedTypeNames.addAll(extractTypeNames(function.returnType))
        for (param in function.parameters) {
            referencedTypeNames.addAll(extractTypeNames(param.type))
        }

        return fileCtx.enums.filter { enum ->
            enum.name in referencedTypeNames ||
            enum.qualifiedName?.split("::")?.lastOrNull() in referencedTypeNames
        }
    }

    private fun resolveDependentTypedefs(function: FunctionInfo, fileCtx: FileContext): List<TypedefInfo> {
        val referencedTypeNames = mutableSetOf<String>()
        referencedTypeNames.addAll(extractTypeNames(function.returnType))
        for (param in function.parameters) {
            referencedTypeNames.addAll(extractTypeNames(param.type))
        }

        return fileCtx.typedefs.filter { typedef ->
            typedef.name in referencedTypeNames
        }
    }

    /**
     * Extract bare type names from a type expression like "const std::vector<MyStruct>&".
     */
    private fun extractTypeNames(typeExpr: String): Set<String> {
        val cleaned = typeExpr
            .replace(Regex("[*&]"), "")
            .replace("const", "")
            .replace("volatile", "")
            .replace("static", "")
            .replace("inline", "")
            .trim()

        val names = mutableSetOf<String>()
        // Split by common delimiters: <, >, ,, ::, space
        val tokens = cleaned.split(Regex("[<>,\\s:]+")).filter { it.isNotBlank() }
        for (token in tokens) {
            // Skip common STL / primitive type names
            if (token !in PRIMITIVE_TYPES) {
                names.add(token)
            }
        }
        return names
    }

    override fun dispose() {
        cache.clear()
    }
}

private val PRIMITIVE_TYPES = setOf(
    "void", "bool", "char", "short", "int", "long", "float", "double",
    "unsigned", "signed", "size_t", "int8_t", "int16_t", "int32_t", "int64_t",
    "uint8_t", "uint16_t", "uint32_t", "uint64_t",
    "string", "wstring", "vector", "map", "set", "list", "deque",
    "unordered_map", "unordered_set", "pair", "tuple", "optional",
    "shared_ptr", "unique_ptr", "weak_ptr",
    "std", "auto"
)
