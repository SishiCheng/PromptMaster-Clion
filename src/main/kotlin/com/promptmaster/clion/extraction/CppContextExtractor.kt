package com.promptmaster.clion.extraction

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.application.ReadAction
import com.jetbrains.cidr.lang.psi.*
import com.promptmaster.clion.models.*

class CppContextExtractor(private val project: Project) {

    companion object {
        private const val MAX_BODY_LENGTH = 5000

        /**
         * True when com.jetbrains.cidr.lang.psi.OCFile (and the rest of the
         * CIDR PSI API) can actually be loaded at runtime.
         *
         * In CLion 2025.3+ with the Nova/Radler engine the classic
         * com.intellij.cidr.lang plugin is suppressed, so these classes are
         * not on the runtime classpath.  We detect this once via reflection so
         * that every extraction method can return null/empty instead of
         * throwing NoClassDefFoundError.
         */
        val isPsiAvailable: Boolean by lazy {
            val ocFileName = "com.jetbrains.cidr.lang.psi.OCFile"
            // Try three classloaders in order:
            //  1. Our own plugin classloader (works in Classic mode, and in Nova mode after
            //     <dependencies><module name="intellij.c.core"/></dependencies> is wired up)
            //  2. Thread context classloader (IntelliJ EDT often has a composite loader)
            //  3. cidr-clangd plugin classloader (explicit Nova-mode fallback)
            val classloaders = buildList {
                add(CppContextExtractor::class.java.classLoader)
                Thread.currentThread().contextClassLoader?.let { add(it) }
                try {
                    val pluginId = com.intellij.openapi.extensions.PluginId.getId(
                        "com.intellij.cidr.lang.clangd")
                    com.intellij.ide.plugins.PluginManagerCore.getPlugin(pluginId)
                        ?.pluginClassLoader?.let { add(it) }
                } catch (_: Throwable) { /* PluginManagerCore not yet ready */ }
            }
            classloaders.any { cl ->
                try {
                    Class.forName(ocFileName, false, cl)
                    true
                } catch (_: Throwable) {
                    false
                }
            }
        }
    }

    /**
     * Extract all context from a single file.
     * Returns null when CIDR PSI is not available (CLion Nova/Radler mode).
     */
    fun extractFileContext(virtualFile: VirtualFile): FileContext? {
        if (!isPsiAvailable) return null
        return try {
            ReadAction.compute<FileContext?, Throwable> {
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                if (psiFile !is OCFile) return@compute null

                FileContext(
                    filePath = virtualFile.path,
                    fileName = virtualFile.name,
                    includes = extractIncludes(psiFile),
                    functions = extractFunctions(psiFile),
                    structs = extractStructs(psiFile),
                    enums = extractEnums(psiFile),
                    typedefs = extractTypedefs(psiFile),
                    macros = extractMacros(psiFile),
                    namespaces = extractNamespaces(psiFile),
                    globalVariables = extractGlobalVariables(psiFile)
                )
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Extract context for a specific function by name within a file.
     * Returns null when CIDR PSI is not available (CLion Nova/Radler mode).
     */
    fun extractFunctionByName(virtualFile: VirtualFile, functionName: String): FunctionInfo? {
        if (!isPsiAvailable) return null
        return try {
            ReadAction.compute<FunctionInfo?, Throwable> {
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                if (psiFile !is OCFile) return@compute null
                val allFunctions = extractFunctions(psiFile)
                allFunctions.find { it.name == functionName }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Extract the function at a specific line number within a file.
     * Returns null when CIDR PSI is not available (CLion Nova/Radler mode).
     */
    fun extractFunctionAtLine(virtualFile: VirtualFile, targetLine: Int): FunctionInfo? {
        if (!isPsiAvailable) return null
        return try {
            ReadAction.compute<FunctionInfo?, Throwable> {
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                if (psiFile !is OCFile) return@compute null
                val document = psiFile.viewProvider.document ?: return@compute null
                val targetOffset = document.getLineStartOffset(targetLine - 1)
                val function = findFunctionContainingOffset(psiFile, targetOffset)
                function?.let { buildFunctionInfo(it, it is OCFunctionDefinition) }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Find the function definition/declaration that contains the given offset.
     * Returns both top-level functions and methods inside structs/classes.
     */
    private fun findFunctionContainingOffset(ocFile: OCFile, offset: Int): OCFunctionDeclaration? {
        val leafElement = ocFile.findElementAt(offset) ?: return null
        
        var current: PsiElement? = leafElement
        while (current != null) {
            if (current is OCFunctionDeclaration) {
                // Return the first function declaration we find
                // This works for both top-level functions and class methods
                return current
            }
            current = current.parent
        }
        return null
    }
    

    /**
     * Resolve a file path to a VirtualFile.
     */
    fun resolveFile(filePath: String): VirtualFile? {
        return LocalFileSystem.getInstance().findFileByPath(filePath)
    }

    // ----------------------------------------------------------
    // Include extraction
    // ----------------------------------------------------------

    private fun extractIncludes(ocFile: OCFile): List<IncludeInfo> {
        val directives = PsiTreeUtil.collectElementsOfType(ocFile, OCIncludeDirective::class.java)
        return directives.map { directive ->
            IncludeInfo(
                path = directive.referenceText ?: "",
                isSystem = directive.isAngleBrackets,
                resolvedPath = try {
                    directive.includedFile?.virtualFile?.path
                } catch (_: Exception) {
                    null
                }
            )
        }
    }

    // ----------------------------------------------------------
    // Function extraction
    // ----------------------------------------------------------

    private fun extractFunctions(ocFile: OCFile): List<FunctionInfo> {
        val results = mutableListOf<FunctionInfo>()

        // Collect definitions (with body) — OCFunctionDefinition extends OCFunctionDeclaration
        val definitions = PsiTreeUtil.collectElementsOfType(ocFile, OCFunctionDefinition::class.java)
        val definitionSet = definitions.toSet()

        for (defn in definitions) {
            if (PsiTreeUtil.getParentOfType(defn, OCStruct::class.java) != null) continue
            results.add(buildFunctionInfo(defn, isDefinition = true))
        }

        // Collect declarations (prototypes only, skip definitions)
        val declarations = PsiTreeUtil.collectElementsOfType(ocFile, OCFunctionDeclaration::class.java)
        for (decl in declarations) {
            if (decl in definitionSet) continue
            if (PsiTreeUtil.getParentOfType(decl, OCStruct::class.java) != null) continue
            results.add(buildFunctionInfo(decl, isDefinition = false))
        }

        return results
    }

    private fun buildFunctionInfo(funcDecl: OCFunctionDeclaration, isDefinition: Boolean): FunctionInfo {
        val namespacePath = resolveNamespacePath(funcDecl)
        val ownerClass = resolveOwnerClass(funcDecl)

        val signatureText = funcDecl.text
            .substringBefore('{')
            .trim()
            .let { if (!isDefinition) it.removeSuffix(";").trim() else it }

        val returnType = try {
            funcDecl.returnType?.toString() ?: "void"
        } catch (_: Exception) {
            "void"
        }

        val parameters = try {
            funcDecl.parameters?.map { declarator ->
                ParameterInfo(
                    name = declarator.name ?: "",
                    type = try { declarator.type?.toString() ?: "" } catch (_: Exception) { "" },
                    defaultValue = try { declarator.initializer?.text } catch (_: Exception) { null }
                )
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        val body = if (isDefinition) {
            try { funcDecl.body?.text?.take(MAX_BODY_LENGTH) } catch (_: Exception) { null }
        } else null

        val headerText = signatureText.substringBefore('(')
        val funcName = funcDecl.name ?: "<anonymous>"

        return FunctionInfo(
            name = funcName,
            qualifiedName = buildQualifiedName(namespacePath, ownerClass, funcName),
            returnType = returnType,
            parameters = parameters,
            signature = signatureText,
            isDefinition = isDefinition,
            lineNumber = getLineNumber(funcDecl),
            body = body,
            templateParameters = extractTemplateParams(funcDecl),
            isVirtual = headerText.contains("virtual"),
            isStatic = try { funcDecl.isStatic } catch (_: Exception) { headerText.contains("static") },
            isConst = funcDecl.constQualifier != null,
            isNoexcept = funcDecl.noexceptSpecifier != null,
            namespacePath = namespacePath,
            ownerClass = ownerClass
        )
    }

    // ----------------------------------------------------------
    // Struct / Class extraction
    // ----------------------------------------------------------

    private fun extractStructs(ocFile: OCFile): List<StructInfo> {
        val structs = PsiTreeUtil.collectElementsOfType(ocFile, OCStruct::class.java)

        return structs.map { struct ->
            val namespacePath = resolveNamespacePath(struct)
            val structName = struct.name ?: "<anonymous>"

            StructInfo(
                name = structName,
                qualifiedName = buildQualifiedName(namespacePath, null, structName),
                kind = try {
                    struct.kind?.toString()?.lowercase() ?: "struct"
                } catch (_: Exception) {
                    val trimmedText = struct.text.trimStart()
                    when {
                        trimmedText.startsWith("class") -> "class"
                        trimmedText.startsWith("union") -> "union"
                        else -> "struct"
                    }
                },
                members = extractStructMembers(struct),
                methods = extractStructMethods(struct),
                baseClasses = extractBaseClasses(struct),
                lineNumber = getLineNumber(struct),
                templateParameters = extractTemplateParams(struct),
                isForwardDeclaration = struct.isDeclaration,
                namespacePath = namespacePath
            )
        }
    }

    private fun extractStructMembers(struct: OCStruct): List<MemberInfo> {
        return try {
            val fields = struct.fields ?: return emptyList()
            fields.mapNotNull { decl ->
                val declarators = decl.declarators
                if (declarators.isNullOrEmpty()) return@mapNotNull null
                val d = declarators.firstOrNull() ?: return@mapNotNull null
                val name = d.name ?: return@mapNotNull null

                MemberInfo(
                    name = name,
                    type = try { d.type?.toString() ?: "" } catch (_: Exception) { "" },
                    accessSpecifier = getAccessSpecifier(decl),
                    isStatic = decl.isStatic,
                    defaultValue = try { d.initializer?.text } catch (_: Exception) { null }
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractStructMethods(struct: OCStruct): List<FunctionInfo> {
        val results = mutableListOf<FunctionInfo>()
        try {
            val members = struct.members ?: return results
            for (member in members) {
                if (member is OCFunctionDefinition) {
                    results.add(buildFunctionInfo(member, isDefinition = true))
                } else if (member is OCFunctionDeclaration) {
                    results.add(buildFunctionInfo(member, isDefinition = false))
                }
            }
        } catch (_: Exception) {}
        return results
    }

    private fun extractBaseClasses(struct: OCStruct): List<BaseClassInfo> {
        return try {
            val clauseList = struct.baseClausesList ?: return emptyList()
            clauseList.baseClauses.map { clause ->
                val text = clause.text.trim()
                val accessSpec = when {
                    text.startsWith("public") -> "public"
                    text.startsWith("protected") -> "protected"
                    text.startsWith("private") -> "private"
                    else -> try {
                        struct.defaultVisibility?.toString()?.lowercase() ?: "public"
                    } catch (_: Exception) { "public" }
                }
                BaseClassInfo(
                    name = clause.referenceElement?.text?.trim() ?: text,
                    accessSpecifier = accessSpec
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ----------------------------------------------------------
    // Enum extraction (OCEnum, not OCEnumDeclaration)
    // ----------------------------------------------------------

    private fun extractEnums(ocFile: OCFile): List<EnumInfo> {
        val enums = PsiTreeUtil.collectElementsOfType(ocFile, OCEnum::class.java)

        return enums.map { enum ->
            val namespacePath = resolveNamespacePath(enum)
            val enumName = enum.name ?: "<anonymous>"

            val enumerators = mutableListOf<EnumeratorInfo>()
            try {
                enum.processEnumConsts { symbol ->
                    enumerators.add(EnumeratorInfo(
                        name = symbol.name ?: "",
                        value = null
                    ))
                    true
                }
            } catch (_: Exception) {}

            EnumInfo(
                name = enumName,
                qualifiedName = buildQualifiedName(namespacePath, null, enumName),
                isScoped = enum.isEnumClass,
                underlyingType = null,
                enumerators = enumerators,
                lineNumber = getLineNumber(enum),
                namespacePath = namespacePath
            )
        }
    }

    // ----------------------------------------------------------
    // Macro extraction (OCDefineDirective, not OCMacroDefinition)
    // ----------------------------------------------------------

    private fun extractMacros(ocFile: OCFile): List<MacroInfo> {
        val macros = PsiTreeUtil.collectElementsOfType(ocFile, OCDefineDirective::class.java)

        return macros.map { macro ->
            val macroParams = try {
                macro.macroParameters?.parameters?.map { it.name ?: "" } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }

            val isFunctionLike = macro.macroParameters != null

            // Extract body: text after the macro name/params
            val bodyText = try {
                val fullText = macro.text
                val afterDefine = fullText.removePrefix("#define").trim()
                if (isFunctionLike) {
                    val closeParen = afterDefine.indexOf(')')
                    if (closeParen >= 0) afterDefine.substring(closeParen + 1).trim() else ""
                } else {
                    val spaceIdx = afterDefine.indexOf(' ')
                    if (spaceIdx >= 0) afterDefine.substring(spaceIdx + 1).trim() else ""
                }
            } catch (_: Exception) {
                ""
            }

            MacroInfo(
                name = macro.name ?: "",
                parameters = macroParams,
                body = bodyText,
                isFunctionLike = isFunctionLike,
                lineNumber = getLineNumber(macro)
            )
        }
    }

    // ----------------------------------------------------------
    // Typedef extraction (OCDeclaration where isTypedef() == true)
    // ----------------------------------------------------------

    private fun extractTypedefs(ocFile: OCFile): List<TypedefInfo> {
        val allDecls = PsiTreeUtil.collectElementsOfType(ocFile, OCDeclaration::class.java)

        return allDecls
            .filter { try { it.isTypedef } catch (_: Exception) { false } }
            .mapNotNull { decl ->
                val namespacePath = resolveNamespacePath(decl)
                val declarators = decl.declarators
                val firstDecl = declarators?.firstOrNull() ?: return@mapNotNull null
                val name = firstDecl.name ?: return@mapNotNull null

                TypedefInfo(
                    name = name,
                    underlyingType = try { decl.type?.toString() ?: "" } catch (_: Exception) { "" },
                    lineNumber = getLineNumber(decl),
                    namespacePath = namespacePath
                )
            }
    }

    // ----------------------------------------------------------
    // Namespace extraction (OCCppNamespace, not OCNamespace)
    // ----------------------------------------------------------

    private fun extractNamespaces(ocFile: OCFile): List<String> {
        val namespaces = mutableSetOf<String>()
        collectNamespaces(ocFile, mutableListOf(), namespaces)
        return namespaces.toList()
    }

    private fun collectNamespaces(element: PsiElement, currentPath: MutableList<String>, result: MutableSet<String>) {
        for (child in element.children) {
            if (child is OCCppNamespace) {
                val name = child.name ?: continue
                currentPath.add(name)
                result.add(currentPath.joinToString("::"))
                collectNamespaces(child, currentPath, result)
                currentPath.removeAt(currentPath.lastIndex)
            } else {
                collectNamespaces(child, currentPath, result)
            }
        }
    }

    // ----------------------------------------------------------
    // Global variable extraction
    // ----------------------------------------------------------

    private fun extractGlobalVariables(ocFile: OCFile): List<VariableInfo> {
        val declarations = PsiTreeUtil.getChildrenOfType(ocFile, OCDeclaration::class.java)
            ?: return emptyList()

        return declarations
            .filter {
                it !is OCFunctionDeclaration &&
                it !is OCStruct &&
                it !is OCEnum &&
                !(try { it.isTypedef } catch (_: Exception) { false })
            }
            .flatMap { decl ->
                val declarators = decl.declarators ?: return@flatMap emptyList()
                declarators.mapNotNull { d ->
                    val name = d.name ?: return@mapNotNull null
                    VariableInfo(
                        name = name,
                        type = try { d.type?.toString() ?: "" } catch (_: Exception) { "" },
                        isConst = decl.text.contains("const"),
                        isStatic = decl.isStatic,
                        lineNumber = getLineNumber(decl)
                    )
                }
            }
    }

    // ----------------------------------------------------------
    // Utility methods
    // ----------------------------------------------------------

    private fun resolveNamespacePath(element: PsiElement): List<String> {
        val path = mutableListOf<String>()
        var current = element.parent
        while (current != null) {
            if (current is OCCppNamespace) {
                val name = current.name
                if (name != null) {
                    path.add(0, name)
                }
            }
            current = current.parent
        }
        return path
    }

    private fun resolveOwnerClass(element: PsiElement): String? {
        val parentStruct = PsiTreeUtil.getParentOfType(element, OCStruct::class.java)
        if (parentStruct != null) return parentStruct.name

        if (element is OCFunctionDeclaration) {
            val declarator = element.declarator
            val nsQualifier = declarator?.namespaceQualifier
            if (nsQualifier != null) {
                val qualText = nsQualifier.text.removeSuffix("::")
                val parts = qualText.split("::")
                return parts.lastOrNull()
            }
        }
        return null
    }

    private fun buildQualifiedName(namespacePath: List<String>, ownerClass: String?, name: String): String {
        val parts = mutableListOf<String>()
        parts.addAll(namespacePath)
        if (ownerClass != null) parts.add(ownerClass)
        parts.add(name)
        return parts.joinToString("::")
    }

    private fun extractTemplateParams(element: PsiElement): String? {
        if (element is OCDeclaration) {
            val templateParamList = element.templateParameterList
            if (templateParamList != null) {
                return templateParamList.text
                    .removePrefix("<").removeSuffix(">").trim()
            }
        }
        return null
    }

    private fun getAccessSpecifier(element: PsiElement): String {
        var prev = element.prevSibling
        while (prev != null) {
            val text = prev.text.trim()
            when {
                text.startsWith("public") -> return "public"
                text.startsWith("protected") -> return "protected"
                text.startsWith("private") -> return "private"
            }
            prev = prev.prevSibling
        }
        return "public"
    }

    private fun getLineNumber(element: PsiElement): Int {
        return try {
            val document = element.containingFile?.viewProvider?.document
            if (document != null) {
                document.getLineNumber(element.textOffset) + 1
            } else {
                0
            }
        } catch (_: Exception) {
            0
        }
    }
}
