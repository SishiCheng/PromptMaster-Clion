package com.promptmaster.clion.extraction

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.promptmaster.clion.models.*

/**
 * Text-based (regex) C/C++ context extractor.
 *
 * Used as a fallback in CLion 2025.3+ Nova/Radler mode where the classic
 * CIDR PSI API (OCFile, OCStruct, etc.) is not available.
 *
 * It reads the raw file text and uses regex patterns to extract:
 *   - #include directives
 *   - function declarations & definitions
 *   - struct / class / union definitions
 *   - enum definitions
 *   - #define macros
 *   - typedef / using declarations
 *   - namespace blocks
 *   - global variables (best-effort)
 */
class TextBasedCppExtractor(@Suppress("unused") private val project: Project) {

    companion object {
        private const val MAX_BODY_LENGTH = 5000
    }

    // ==========================================================
    // Public API  (mirrors CppContextExtractor)
    // ==========================================================

    fun extractFileContext(virtualFile: VirtualFile): FileContext? {
        return try {
            val text = String(virtualFile.contentsToByteArray(), Charsets.UTF_8)
            val lines = text.lines()

            FileContext(
                filePath = virtualFile.path,
                fileName = virtualFile.name,
                includes = extractIncludes(lines),
                functions = extractFunctions(text, lines),
                structs = extractStructs(text, lines),
                enums = extractEnums(text, lines),
                typedefs = extractTypedefs(lines),
                macros = extractMacros(lines),
                namespaces = extractNamespaces(text),
                globalVariables = emptyList() // too error-prone via regex
            )
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Overload that accepts raw text instead of VirtualFile.
     * Used when VFS has not been populated (e.g. pure Nova mode without prior Classic indexing).
     */
    fun extractFileContext(text: String, filePath: String, fileName: String): FileContext? {
        return try {
            val lines = text.lines()
            FileContext(
                filePath = filePath,
                fileName = fileName,
                includes = extractIncludes(lines),
                functions = extractFunctions(text, lines),
                structs = extractStructs(text, lines),
                enums = extractEnums(text, lines),
                typedefs = extractTypedefs(lines),
                macros = extractMacros(lines),
                namespaces = extractNamespaces(text),
                globalVariables = emptyList()
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun extractFunctionByName(virtualFile: VirtualFile, functionName: String): FunctionInfo? {
        // Try the general extractor first (funcRegex)
        val ctx = extractFileContext(virtualFile)
        val found = ctx?.functions?.find { it.name == functionName }
        if (found != null) return found

        // Fallback: direct paren-depth scan (handles multi-line signatures)
        val text = try {
            String(virtualFile.contentsToByteArray(), Charsets.UTF_8)
        } catch (_: Throwable) { return null }
        return findFunctionInText(text, functionName)
    }

    /**
     * Find a specific function DEFINITION in raw C/C++ source text using paren-depth
     * matching. This handles multi-line signatures that the general funcRegex may miss.
     */
    fun findFunctionInText(text: String, functionName: String): FunctionInfo? {
        val namePattern = Regex("""\b${Regex.escape(functionName)}\s*\(""")

        for (match in namePattern.findAll(text)) {
            val nameStart = match.range.first
            // match.range.last is the index of '('
            val openParen = match.range.last

            // Paren-depth match to find closing ')'
            var depth = 1
            var i = openParen + 1
            while (i < text.length && depth > 0) {
                when (text[i]) { '(' -> depth++; ')' -> depth-- }
                i++
            }
            if (depth != 0) continue
            val closeParen = i - 1

            // Scan forward (max 600 chars) for '{' or ';'
            var braceIdx = -1
            var j = closeParen + 1
            val limit = (j + 600).coerceAtMost(text.length)
            while (j < limit) {
                when (text[j]) { '{' -> { braceIdx = j; break }; ';' -> break }
                j++
            }
            if (braceIdx < 0) continue   // declaration only, not a definition

            // Find start of this declaration line
            var lineStart = nameStart
            while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--

            // If the line before the name is blank/whitespace, the return type may be
            // on a previous line — walk back one more line
            val prefixOnLine = text.substring(lineStart, nameStart)
            val declStart = if (prefixOnLine.isBlank() && lineStart > 0) {
                var prev = lineStart - 1   // skip the '\n'
                while (prev > 0 && text[prev - 1] != '\n') prev--
                prev
            } else {
                lineStart
            }

            val prefix   = text.substring(declStart, nameStart).trim()
            val lineNumber = text.substring(0, declStart).count { it == '\n' } + 1

            // Normalise params to a single line for the signature field
            val paramsText        = text.substring(openParen + 1, closeParen)
            val normalizedParams  = paramsText.replace(Regex("""\s+"""), " ").trim()
            val qualText          = text.substring(closeParen + 1, braceIdx).trim()

            val signature = buildString {
                if (prefix.isNotEmpty()) { append(prefix); append(" ") }
                append(functionName).append("(").append(normalizedParams).append(")")
                if (qualText.isNotEmpty()) { append(" ").append(qualText) }
            }.trim()

            // Brace-depth match for body
            var bodyDepth = 1; var k = braceIdx + 1
            while (k < text.length && bodyDepth > 0) {
                when (text[k]) { '{' -> bodyDepth++; '}' -> bodyDepth-- }
                k++
            }
            val body = if (bodyDepth == 0) text.substring(braceIdx + 1, k - 1) else null

            // Owner class: look for "Word::" at end of prefix
            val ownerClass = Regex("""(\w+)::\s*$""").find(prefix)?.groupValues?.get(1)

            return FunctionInfo(
                name               = functionName,
                qualifiedName      = null,
                returnType         = extractReturnType(prefix),
                parameters         = parseParameters(paramsText),
                signature          = signature,
                isDefinition       = true,
                lineNumber         = lineNumber,
                body               = body?.take(MAX_BODY_LENGTH),
                templateParameters = extractTemplateParamsFromPrefix(prefix),
                isVirtual          = prefix.contains("virtual"),
                isStatic           = prefix.contains("static"),
                isConst            = qualText.contains("const"),
                isNoexcept         = qualText.contains("noexcept"),
                namespacePath      = extractNamespacePath(text, declStart),
                ownerClass         = ownerClass
            )
        }
        return null
    }

    // ==========================================================
    // #include
    // ==========================================================

    private val includeRegex = Regex("""^\s*#\s*include\s+([<"])(.+?)[>"]""")

    private fun extractIncludes(lines: List<String>): List<IncludeInfo> {
        return lines.mapNotNull { line ->
            val m = includeRegex.find(line) ?: return@mapNotNull null
            val bracket = m.groupValues[1]
            val path = m.groupValues[2]
            IncludeInfo(
                path = path,
                isSystem = bracket == "<",
                resolvedPath = null  // no PSI resolution in text mode
            )
        }
    }

    // ==========================================================
    // Functions
    // ==========================================================

    // Matches: [template<...>] [qualifiers] return_type [Class::]funcName(params) [const] [noexcept] [= 0|default|delete] { | ;
    // This is intentionally broad; we filter false positives afterwards.
    private val funcRegex = Regex(
        """^([ \t]*(?:template\s*<[^>]*>\s*)?(?:(?:static|inline|virtual|explicit|constexpr|consteval|extern|friend)\s+)*""" +
        """[\w:*&<>,\s]+?)\b(~?\w+)\s*\(([^)]*)\)\s*((?:const|volatile|noexcept|override|final|= *0|= *default|= *delete|->[\w:&*<>\s]+)*)\s*([{;])""",
        RegexOption.MULTILINE
    )

    // Keywords that look like function names but aren't
    private val nonFuncKeywords = setOf(
        "if", "else", "for", "while", "do", "switch", "case", "return",
        "catch", "throw", "try", "sizeof", "alignof", "decltype", "typeid",
        "new", "delete", "static_assert", "typedef", "using", "namespace"
    )

    private fun extractFunctions(text: String, lines: List<String>): List<FunctionInfo> {
        val results = mutableListOf<FunctionInfo>()
        // We need to exclude functions that are inside struct/class bodies.
        // Strategy: find all top-level and class-member functions, tag them.

        for (m in funcRegex.findAll(text)) {
            val prefix = m.groupValues[1].trim()
            val funcName = m.groupValues[2]
            val params = m.groupValues[3]
            val qualifiers = m.groupValues[4].trim()
            val terminator = m.groupValues[5]

            if (funcName in nonFuncKeywords) continue
            // Skip preprocessor macros that look like functions
            if (prefix.contains("#")) continue
            // Skip struct/class/enum declarations
            if (prefix.matches(Regex(""".*\b(struct|class|union|enum)\b.*"""))) continue

            val isDefinition = terminator == "{"
            val lineNumber = text.substring(0, m.range.first).count { it == '\n' } + 1

            val signature = buildString {
                append(prefix)
                if (!prefix.endsWith(" ") && !prefix.endsWith("*") && !prefix.endsWith("&")) append(" ")
                append(funcName)
                append("(")
                append(params)
                append(")")
                if (qualifiers.isNotEmpty()) {
                    append(" ")
                    append(qualifiers)
                }
            }.trim()

            // Try to extract return type
            val returnType = extractReturnType(prefix)

            // Parse parameters
            val paramList = parseParameters(params)

            // Extract body if definition
            val body = if (isDefinition) {
                extractBraceBlock(text, m.range.last)?.take(MAX_BODY_LENGTH)
            } else null

            // Detect namespace from qualified name (e.g., WriteBatch::Clear)
            val ownerClass = extractOwnerClass(prefix, funcName)
            val namespacePath = extractNamespacePath(text, m.range.first)

            results.add(FunctionInfo(
                name = funcName,
                qualifiedName = buildQualifiedName(namespacePath, ownerClass, funcName),
                returnType = returnType,
                parameters = paramList,
                signature = signature,
                isDefinition = isDefinition,
                lineNumber = lineNumber,
                body = body,
                templateParameters = extractTemplateParamsFromPrefix(prefix),
                isVirtual = prefix.contains("virtual"),
                isStatic = prefix.contains("static"),
                isConst = qualifiers.contains("const"),
                isNoexcept = qualifiers.contains("noexcept"),
                namespacePath = namespacePath,
                ownerClass = ownerClass
            ))
        }
        return results
    }

    // ==========================================================
    // Struct / Class / Union
    // ==========================================================

    private val structRegex = Regex(
        """(?:template\s*<[^>]*>\s*)?(struct|class|union)\s+(?:\[\[[^\]]*\]\]\s*)?(\w+)([^;{]*)\{""",
        RegexOption.MULTILINE
    )

    private fun extractStructs(text: String, lines: List<String>): List<StructInfo> {
        val results = mutableListOf<StructInfo>()

        for (m in structRegex.findAll(text)) {
            val kind = m.groupValues[1]
            val name = m.groupValues[2]
            val afterName = m.groupValues[3].trim()
            val lineNumber = text.substring(0, m.range.first).count { it == '\n' } + 1

            // Extract base classes from "... : public Base1, private Base2"
            val baseClasses = extractBaseClassesFromText(afterName)

            // Extract body
            val bodyText = extractBraceBlock(text, m.range.last) ?: ""

            // Parse members and methods from body
            val members = parseStructMembers(bodyText)
            val methods = parseStructMethods(bodyText, lineNumber)

            // Template parameters
            val fullMatch = m.value
            val templateParams = if (fullMatch.trimStart().startsWith("template")) {
                extractTemplateParamsFromPrefix(fullMatch)
            } else null

            val namespacePath = extractNamespacePath(text, m.range.first)

            results.add(StructInfo(
                name = name,
                qualifiedName = buildQualifiedName(namespacePath, null, name),
                kind = kind,
                members = members,
                methods = methods,
                baseClasses = baseClasses,
                lineNumber = lineNumber,
                templateParameters = templateParams,
                isForwardDeclaration = false,
                namespacePath = namespacePath
            ))
        }
        return results
    }

    // ==========================================================
    // Enum
    // ==========================================================

    private val enumRegex = Regex(
        """(enum)\s+(class\s+|struct\s+)?(\w+)(?:\s*:\s*(\w+))?\s*\{([^}]*)\}""",
        RegexOption.MULTILINE
    )

    private fun extractEnums(text: String, lines: List<String>): List<EnumInfo> {
        val results = mutableListOf<EnumInfo>()

        for (m in enumRegex.findAll(text)) {
            val isScoped = m.groupValues[2].isNotBlank()
            val name = m.groupValues[3]
            val underlyingType = m.groupValues[4].ifEmpty { null }
            val body = m.groupValues[5]
            val lineNumber = text.substring(0, m.range.first).count { it == '\n' } + 1

            val enumerators = body.split(",").mapNotNull { entry ->
                val trimmed = entry.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val parts = trimmed.split("=", limit = 2)
                EnumeratorInfo(
                    name = parts[0].trim().split("//")[0].trim(), // strip inline comments
                    value = parts.getOrNull(1)?.trim()?.split("//")?.get(0)?.trim()
                )
            }.filter { it.name.isNotEmpty() }

            val namespacePath = extractNamespacePath(text, m.range.first)

            results.add(EnumInfo(
                name = name,
                qualifiedName = buildQualifiedName(namespacePath, null, name),
                isScoped = isScoped,
                underlyingType = underlyingType,
                enumerators = enumerators,
                lineNumber = lineNumber,
                namespacePath = namespacePath
            ))
        }
        return results
    }

    // ==========================================================
    // Macros (#define)
    // ==========================================================

    private val defineRegex = Regex("""^\s*#\s*define\s+(\w+)(?:\(([^)]*)\))?\s*(.*)""")

    private fun extractMacros(lines: List<String>): List<MacroInfo> {
        val results = mutableListOf<MacroInfo>()
        var lineNum = 0

        for (line in lines) {
            lineNum++
            val m = defineRegex.find(line) ?: continue
            val name = m.groupValues[1]
            val paramsStr = m.groupValues[2]
            val body = m.groupValues[3].trimEnd().removeSuffix("\\").trim()

            val isFunctionLike = m.groupValues[2].isNotEmpty() || line.contains("$name(")
            val params = if (paramsStr.isNotBlank()) {
                paramsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            } else emptyList()

            results.add(MacroInfo(
                name = name,
                parameters = params,
                body = body,
                isFunctionLike = isFunctionLike && params.isNotEmpty(),
                lineNumber = lineNum
            ))
        }
        return results
    }

    // ==========================================================
    // Typedef / using
    // ==========================================================

    private val typedefRegex = Regex("""^\s*typedef\s+(.+?)\s+(\w+)\s*;""")
    private val usingRegex = Regex("""^\s*using\s+(\w+)\s*=\s*(.+?)\s*;""")

    private fun extractTypedefs(lines: List<String>): List<TypedefInfo> {
        val results = mutableListOf<TypedefInfo>()
        var lineNum = 0

        for (line in lines) {
            lineNum++
            typedefRegex.find(line)?.let { m ->
                results.add(TypedefInfo(
                    name = m.groupValues[2],
                    underlyingType = m.groupValues[1],
                    lineNumber = lineNum,
                    namespacePath = emptyList()
                ))
            }
            usingRegex.find(line)?.let { m ->
                results.add(TypedefInfo(
                    name = m.groupValues[1],
                    underlyingType = m.groupValues[2],
                    lineNumber = lineNum,
                    namespacePath = emptyList()
                ))
            }
        }
        return results
    }

    // ==========================================================
    // Namespaces
    // ==========================================================

    private val namespaceRegex = Regex("""namespace\s+(\w+(?:::\w+)*)\s*\{""")

    private fun extractNamespaces(text: String): List<String> {
        return namespaceRegex.findAll(text).map { it.groupValues[1] }.distinct().toList()
    }

    // ==========================================================
    // Helpers
    // ==========================================================

    /**
     * Extract the content of a brace-delimited block starting just after the
     * opening '{' whose index is [openBraceIndex] (inclusive).
     */
    private fun extractBraceBlock(text: String, openBraceIndex: Int): String? {
        // openBraceIndex is the index of '{' itself
        var depth = 1
        var i = openBraceIndex + 1
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        if (depth != 0) return null
        // The block content is between openBraceIndex+1 and i-1 (the closing '}')
        return text.substring(openBraceIndex + 1, i - 1).trimIndent()
    }

    /**
     * Given the text before the function name, extract the return type.
     * e.g. "static inline int*" -> "int*"
     */
    private fun extractReturnType(prefix: String): String {
        var cleaned = prefix
            .replace(Regex("""template\s*<[^>]*>\s*"""), "")
            .replace("static", "").replace("inline", "")
            .replace("virtual", "").replace("explicit", "")
            .replace("constexpr", "").replace("consteval", "")
            .replace("extern", "").replace("friend", "")
            .trim()
        // Remove leading qualifiers in case of "const int"
        if (cleaned.isEmpty()) cleaned = "void"
        return cleaned
    }

    /**
     * Parse a comma-separated parameter list like "int x, const std::string& y = \"default\""
     */
    private fun parseParameters(paramStr: String): List<ParameterInfo> {
        val trimmed = paramStr.trim()
        if (trimmed.isEmpty() || trimmed == "void") return emptyList()

        // Simple split by top-level commas (ignore commas inside angle brackets)
        val params = splitTopLevelCommas(trimmed)

        return params.mapNotNull { param ->
            val p = param.trim()
            if (p.isEmpty() || p == "...") return@mapNotNull ParameterInfo(name = p, type = p)

            // Handle default value
            val (beforeDefault, defaultVal) = splitDefault(p)

            // Last token is the name (if it's an identifier), everything before is the type
            val tokens = beforeDefault.trim().split(Regex("""\s+"""))
            if (tokens.isEmpty()) return@mapNotNull null

            if (tokens.size == 1) {
                // Just a type with no name (e.g., "int")
                ParameterInfo(name = "", type = tokens[0], defaultValue = defaultVal)
            } else {
                val lastName = tokens.last().trimStart('*').trimStart('&')
                if (lastName.matches(Regex("""\w+"""))) {
                    ParameterInfo(
                        name = lastName,
                        type = tokens.dropLast(1).joinToString(" "),
                        defaultValue = defaultVal
                    )
                } else {
                    ParameterInfo(name = "", type = beforeDefault.trim(), defaultValue = defaultVal)
                }
            }
        }
    }

    /**
     * Split parameter string by commas, respecting < > depth.
     */
    private fun splitTopLevelCommas(s: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var start = 0
        for ((i, c) in s.withIndex()) {
            when (c) {
                '<', '(' -> depth++
                '>', ')' -> depth--
                ',' -> if (depth == 0) {
                    result.add(s.substring(start, i))
                    start = i + 1
                }
            }
        }
        result.add(s.substring(start))
        return result
    }

    /**
     * Split "const int x = 42" into ("const int x", "42")
     */
    private fun splitDefault(param: String): Pair<String, String?> {
        var depth = 0
        for ((i, c) in param.withIndex()) {
            when (c) {
                '<', '(' -> depth++
                '>', ')' -> depth--
                '=' -> if (depth == 0) {
                    return Pair(param.substring(0, i).trim(), param.substring(i + 1).trim())
                }
            }
        }
        return Pair(param, null)
    }

    /**
     * Extract owner class from qualified function definition prefix.
     * e.g. "void WriteBatch::Clear" -> ownerClass = "WriteBatch"
     */
    private fun extractOwnerClass(prefix: String, funcName: String): String? {
        val qualifiedPattern = Regex("""(\w+)::${Regex.escape(funcName)}\s*$""")
        val m = qualifiedPattern.find(prefix) ?: return null
        return m.groupValues[1]
    }

    /**
     * Determine the namespace path at a given offset by scanning backwards
     * for `namespace X {` blocks and tracking brace depth.
     */
    private fun extractNamespacePath(text: String, offset: Int): List<String> {
        val path = mutableListOf<String>()
        // Stack: each entry is (namespaceName, braceDepthAtOpen)
        var depth = 0
        val nsStack = mutableListOf<Pair<String, Int>>()

        var i = 0
        while (i < offset && i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    if (nsStack.isNotEmpty() && nsStack.last().second == depth) {
                        nsStack.removeAt(nsStack.lastIndex)
                    }
                    depth--
                }
                'n' -> {
                    // Check for "namespace"
                    if (text.startsWith("namespace", i)) {
                        val nsMatch = namespaceRegex.find(text, i)
                        if (nsMatch != null && nsMatch.range.first == i) {
                            val nsName = nsMatch.groupValues[1]
                            // Jump past the opening brace
                            i = nsMatch.range.last
                            depth++
                            nsStack.add(Pair(nsName, depth))
                            continue
                        }
                    }
                }
            }
            i++
        }

        return nsStack.map { it.first }
    }

    /**
     * Extract template parameters from a prefix like "template<typename T, int N> void"
     */
    private fun extractTemplateParamsFromPrefix(prefix: String): String? {
        val m = Regex("""template\s*<([^>]*)>""").find(prefix) ?: return null
        return m.groupValues[1].trim()
    }

    /**
     * Extract base classes from text after struct/class name.
     * e.g. ": public Base1, private Base2"
     */
    private fun extractBaseClassesFromText(afterName: String): List<BaseClassInfo> {
        val colonIdx = afterName.indexOf(':')
        if (colonIdx < 0) return emptyList()

        val inheritance = afterName.substring(colonIdx + 1).trim()
        return splitTopLevelCommas(inheritance).mapNotNull { clause ->
            val parts = clause.trim().split(Regex("""\s+"""), limit = 2)
            if (parts.isEmpty()) return@mapNotNull null

            when {
                parts.size >= 2 && parts[0] in listOf("public", "protected", "private") ->
                    BaseClassInfo(name = parts[1].trim(), accessSpecifier = parts[0])
                parts.size == 1 ->
                    BaseClassInfo(name = parts[0].trim(), accessSpecifier = "public")
                else ->
                    BaseClassInfo(name = clause.trim(), accessSpecifier = "public")
            }
        }
    }

    /**
     * Parse struct members (fields) from body text.
     * Very best-effort — looks for simple `type name;` patterns.
     */
    private fun parseStructMembers(bodyText: String): List<MemberInfo> {
        val members = mutableListOf<MemberInfo>()
        var currentAccess = "public"

        for (line in bodyText.lines()) {
            val trimmed = line.trim()

            // Track access specifier changes
            when {
                trimmed.startsWith("public:") -> { currentAccess = "public"; continue }
                trimmed.startsWith("protected:") -> { currentAccess = "protected"; continue }
                trimmed.startsWith("private:") -> { currentAccess = "private"; continue }
            }

            // Skip methods, macros, comments, empty lines
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("/*") ||
                trimmed.startsWith("#") || trimmed.contains("(")) continue

            // Simple pattern: [static] [const] type name [= value] ;
            val memberRegex = Regex("""^((?:static\s+)?(?:const\s+)?[\w:*&<>,\s]+?)\s+(\w+)\s*(?:=\s*(.+))?\s*;$""")
            memberRegex.find(trimmed)?.let { m ->
                val type = m.groupValues[1].trim()
                val name = m.groupValues[2]
                val default = m.groupValues[3].ifEmpty { null }

                members.add(MemberInfo(
                    name = name,
                    type = type.replace("static ", "").trim(),
                    accessSpecifier = currentAccess,
                    isStatic = type.contains("static"),
                    defaultValue = default
                ))
            }
        }
        return members
    }

    /**
     * Parse methods inside a struct body text.
     * Looks for function-like patterns.
     */
    private fun parseStructMethods(bodyText: String, baseLineNumber: Int): List<FunctionInfo> {
        val results = mutableListOf<FunctionInfo>()

        for (m in funcRegex.findAll(bodyText)) {
            val prefix = m.groupValues[1].trim()
            val funcName = m.groupValues[2]
            val params = m.groupValues[3]
            val qualifiers = m.groupValues[4].trim()
            val terminator = m.groupValues[5]

            if (funcName in nonFuncKeywords) continue
            if (prefix.contains("#")) continue

            val isDefinition = terminator == "{"
            val localLine = bodyText.substring(0, m.range.first).count { it == '\n' }

            val signature = buildString {
                append(prefix)
                if (!prefix.endsWith(" ") && !prefix.endsWith("*") && !prefix.endsWith("&")) append(" ")
                append(funcName)
                append("(").append(params).append(")")
                if (qualifiers.isNotEmpty()) append(" ").append(qualifiers)
            }.trim()

            val body = if (isDefinition) {
                extractBraceBlock(bodyText, m.range.last)?.take(MAX_BODY_LENGTH)
            } else null

            results.add(FunctionInfo(
                name = funcName,
                qualifiedName = null,
                returnType = extractReturnType(prefix),
                parameters = parseParameters(params),
                signature = signature,
                isDefinition = isDefinition,
                lineNumber = baseLineNumber + localLine,
                body = body,
                templateParameters = extractTemplateParamsFromPrefix(prefix),
                isVirtual = prefix.contains("virtual"),
                isStatic = prefix.contains("static"),
                isConst = qualifiers.contains("const"),
                isNoexcept = qualifiers.contains("noexcept"),
                namespacePath = emptyList(),
                ownerClass = null
            ))
        }
        return results
    }

    private fun buildQualifiedName(namespacePath: List<String>, ownerClass: String?, name: String): String {
        val parts = mutableListOf<String>()
        parts.addAll(namespacePath)
        if (ownerClass != null) parts.add(ownerClass)
        parts.add(name)
        return parts.joinToString("::")
    }
}
