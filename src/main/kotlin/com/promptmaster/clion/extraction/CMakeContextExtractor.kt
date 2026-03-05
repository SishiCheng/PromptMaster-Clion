package com.promptmaster.clion.extraction

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ReadAction
import com.promptmaster.clion.models.*

/**
 * Extracts CMake workspace information using reflection to avoid
 * hard compile-time dependency on CMake-specific internal APIs.
 */
class CMakeContextExtractor(private val project: Project) {

    private val logger = Logger.getInstance(CMakeContextExtractor::class.java)

    fun extractCMakeContext(): CMakeContext? {
        return ReadAction.compute<CMakeContext?, Throwable> {
            try {
                val workspaceClass = Class.forName("com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace")
                val getInstance = workspaceClass.getMethod("getInstance", Project::class.java)
                val workspace = getInstance.invoke(null, project) ?: return@compute CMakeContext(isInitialized = false)

                val isInitialized = workspaceClass.getMethod("isInitialized").invoke(workspace) as? Boolean ?: false
                if (!isInitialized) {
                    return@compute CMakeContext(isInitialized = false)
                }

                val model = try {
                    workspaceClass.getMethod("getModel").invoke(workspace)
                } catch (_: Exception) {
                    null
                } ?: return@compute CMakeContext(isInitialized = true, projectName = project.name)

                CMakeContext(
                    isInitialized = true,
                    projectName = try {
                        model.javaClass.getMethod("getProjectName").invoke(model) as? String
                    } catch (_: Exception) {
                        project.name
                    },
                    configurations = extractConfigurations(model)
                )
            } catch (e: ClassNotFoundException) {
                logger.info("CMake workspace class not found - CMake support may not be available")
                CMakeContext(isInitialized = false)
            } catch (e: Exception) {
                logger.warn("Error extracting CMake context", e)
                CMakeContext(isInitialized = false)
            }
        }
    }

    /**
     * Get the CMake target that contains a specific source file.
     */
    fun findTargetForFile(filePath: String): CMakeTargetInfo? {
        return ReadAction.compute<CMakeTargetInfo?, Throwable> {
            try {
                val workspaceClass = Class.forName("com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace")
                val workspace = workspaceClass.getMethod("getInstance", Project::class.java).invoke(null, project)
                    ?: return@compute null

                val isInit = workspaceClass.getMethod("isInitialized").invoke(workspace) as? Boolean ?: false
                if (!isInit) return@compute null

                val model = workspaceClass.getMethod("getModel").invoke(workspace) ?: return@compute null
                val configurations = invokeListMethod(model, "getConfigurations")

                for (config in configurations) {
                    val targets = invokeListMethod(config, "getTargets")
                    for (target in targets) {
                        val sources = try {
                            invokeListMethod(target, "getSources").mapNotNull { src ->
                                tryGetPath(src)
                            }
                        } catch (_: Exception) { emptyList() }

                        if (sources.any { it == filePath || it.endsWith(filePath) }) {
                            return@compute buildTargetInfo(target)
                        }
                    }
                }
                null
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun extractConfigurations(model: Any): List<CMakeConfigInfo> {
        val result = mutableListOf<CMakeConfigInfo>()
        try {
            val configurations = invokeListMethod(model, "getConfigurations")
            for (config in configurations) {
                try {
                    val configName = tryInvokeString(config, "getConfigName") ?: "default"
                    val buildType = tryInvokeString(config, "getBuildType")
                    val targets = try {
                        invokeListMethod(config, "getTargets").mapNotNull { target ->
                            try { buildTargetInfo(target) } catch (_: Exception) { null }
                        }
                    } catch (_: Exception) { emptyList() }

                    result.add(CMakeConfigInfo(
                        name = configName,
                        buildType = buildType,
                        targets = targets
                    ))
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return result
    }

    private fun buildTargetInfo(target: Any): CMakeTargetInfo {
        return CMakeTargetInfo(
            name = tryInvokeString(target, "getName") ?: "<unknown>",
            type = try { target.javaClass.getMethod("getType").invoke(target)?.toString() ?: "UNKNOWN" } catch (_: Exception) { "UNKNOWN" },
            sources = try {
                invokeListMethod(target, "getSources").mapNotNull { tryGetPath(it) }
            } catch (_: Exception) { emptyList() },
            compileOptions = tryInvokeStringList(target, "getCompileOptions"),
            includeDirectories = try {
                invokeListMethod(target, "getIncludeDirectories").mapNotNull { tryGetPath(it) }
            } catch (_: Exception) { emptyList() },
            definitions = tryInvokeStringList(target, "getDefinitions"),
            linkLibraries = tryInvokeStringList(target, "getLinkLibraries")
        )
    }

    // ----------------------------------------------------------
    // Reflection utilities
    // ----------------------------------------------------------

    private fun invokeListMethod(obj: Any, methodName: String): List<Any> {
        return try {
            @Suppress("UNCHECKED_CAST")
            (obj.javaClass.getMethod(methodName).invoke(obj) as? List<Any>) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun tryInvokeString(obj: Any, methodName: String): String? {
        return try {
            obj.javaClass.getMethod(methodName).invoke(obj) as? String
        } catch (_: Exception) {
            null
        }
    }

    private fun tryInvokeStringList(obj: Any, methodName: String): List<String> {
        return try {
            @Suppress("UNCHECKED_CAST")
            val list = obj.javaClass.getMethod(methodName).invoke(obj) as? List<Any> ?: return emptyList()
            list.mapNotNull { it.toString() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun tryGetPath(obj: Any): String? {
        return try {
            obj.javaClass.getMethod("getPath").invoke(obj) as? String
        } catch (_: Exception) {
            try { obj.toString() } catch (_: Exception) { null }
        }
    }
}
