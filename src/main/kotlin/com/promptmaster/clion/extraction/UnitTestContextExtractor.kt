package com.promptmaster.clion.extraction

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.application.ReadAction
import com.promptmaster.clion.models.*
import java.util.concurrent.ConcurrentHashMap
import java.io.File as IoFile

/**
 * Extracts unit-test context in the flat string / string-array format
 * expected by the Continue prompt templates (aligned with the VS Code
 * CodeArtsX-IDE plugin output).
 *
 * File discovery uses java.io.File (filesystem) rather than IntelliJ VFS
 * so that it works in CLion Nova/Radler mode even when the VFS has not
 * been fully populated by classic CIDR PSI indexing.
 *
 * Extraction strategy (mirrors ContextExtractionService):
 *   Classic mode (PSI available): CppContextExtractor → accurate AST-based
 *                                 function/include info, no regex false positives
 *   Nova/Radler mode (PSI absent): TextBasedCppExtractor → regex + brace-depth fallback
 *
 * Include resolution and external-function search always use java.io.File
 * regardless of mode, since PSI does not traverse the filesystem.
 */
class UnitTestContextExtractor(private val project: Project) {

    private val logger = Logger.getInstance(UnitTestContextExtractor::class.java)

    /**
     * PSI-based extractor — used when CIDR PSI is available (Classic mode).
     * extractFileContext() returns null in Nova mode, so we always create
     * the instance and let it self-disable via isPsiAvailable.
     */
    private val psiExtractor = CppContextExtractor(project)

    /** Text/regex-based extractor — fallback when PSI is unavailable (Nova mode). */
    private val textExtractor = TextBasedCppExtractor(project)

    /** Cache: absolute include path → resolved absolute path (UNRESOLVED_SENTINEL if not found). */
    private val includeCache = ConcurrentHashMap<String, String>()

    /** Sentinel value stored in includeCache when an include could not be resolved. */
    private val UNRESOLVED_SENTINEL = "\u0000UNRESOLVED"

    // ==========================================================
    //  Public API
    // ==========================================================

    fun extract(filePath: String, functionName: String): UnitTestContext? {
        return try {
            doExtract(filePath, functionName)
        } catch (e: Throwable) {
            logger.warn("ut-context extraction failed for $functionName in $filePath", e)
            null
        }
    }

    // ==========================================================
    //  Main orchestrator
    // ==========================================================

    private fun doExtract(filePath: String, functionName: String): UnitTestContext? {
        val vf = LocalFileSystem.getInstance().findFileByPath(filePath) ?: run {
            logger.warn("ut-context: file not found in VFS: $filePath")
            return null
        }
        val fileText = readText(vf)

        // Prefer PSI-based extraction (Classic mode) for accuracy.
        // PSI gives us AST-level body/lineNumber without regex false positives.
        // Falls back to text-based extraction in Nova/Radler mode (PSI unavailable).
        val fileCtx = psiExtractor.extractFileContext(vf)
            ?: textExtractor.extractFileContext(vf)
        if (fileCtx == null) {
            logger.info("ut-context: extractFileContext returned null for $filePath (psi=${CppContextExtractor.isPsiAvailable}), using direct scan")
        }

        // 1. Locate the target function.
        //    Primary:  structured parser (funcRegex).
        //    Fallback: paren-depth scan (handles multi-line / unusual signatures).
        val function = fileCtx?.functions?.find { it.name == functionName }
            ?: textExtractor.findFunctionInText(fileText, functionName)
            ?: run {
                logger.warn("ut-context: function '$functionName' not found in $filePath " +
                        "(fileCtx=${if (fileCtx != null) "${fileCtx.functions.size} funcs" else "null"})")
                return null
            }

        // 2. Full definition text (raw source)
        val definitionText = extractDefinitionText(fileText, function)

        // 3. Resolve all #include paths (use fileCtx includes when available,
        //    otherwise parse includes directly from the file text)
        val includes = fileCtx?.includes ?: extractIncludesFromText(fileText)
        val resolvedIncludes = mutableMapOf<String, String>()   // include text → abs path
        for (inc in includes) {
            val abs = resolveInclude(inc.path, inc.isSystem, filePath)
            if (abs != null) resolvedIncludes[inc.path] = abs
        }

        // 4. Identifiers used in signature + body — used to filter relevant defs
        val usedIds = extractAllIdentifiers(function.signature + " " + (function.body ?: ""))

        // 5. headFiles
        val basePath = project.basePath ?: ""
        val headFilesList = resolvedIncludes.values.map { relativize(it, basePath) }.toMutableList()
        // If the source file itself is a header, include it so the generated test
        // can #include it (otherwise the definition under test would be missing).
        val sourceExt = vf.extension?.lowercase() ?: ""
        if (sourceExt in HEADER_EXTENSIONS) {
            val selfRelative = relativize(filePath, basePath)
            if (selfRelative !in headFilesList) {
                headFilesList.add(0, selfRelative)
            }
        }
        val headFiles = headFilesList.toList()

        // 6. structDefinitions (file + headers, filtered)
        //    Uses readTextByPath so headers are readable even when VFS is unpopulated.
        val structDefs = mutableListOf<String>()
        structDefs.addAll(collectRawTypeDefinitions(fileText, usedIds))
        for ((_, absPath) in resolvedIncludes) {
            val hdrText = readTextByPath(absPath) ?: continue
            structDefs.addAll(collectRawTypeDefinitions(hdrText, usedIds))
        }

        // 7. macroDefinitions (file + headers, filtered)
        val macroDefs = mutableListOf<String>()
        macroDefs.addAll(collectRawMacros(fileText, usedIds))
        for ((_, absPath) in resolvedIncludes) {
            val hdrText = readTextByPath(absPath) ?: continue
            macroDefs.addAll(collectRawMacros(hdrText, usedIds))
        }

        // 8. externalFunctions — works even when fileCtx is null by extracting
        //    local function names from file text as a fallback.
        val localFuncNames = fileCtx?.functions?.map { it.name }?.toSet()
            ?: extractFunctionNamesFromText(fileText)
        val externalFuncs = findExternalFunctions(
            function, localFuncNames, filePath, resolvedIncludes, basePath, fileText
        )

        return UnitTestContext(
            modulePath = filePath,
            filenameWithoutExt = vf.nameWithoutExtension,
            signature = function.signature,
            definition = listOf(definitionText),
            structDefinitions = structDefs.distinct(),
            headFiles = headFiles,
            externalFunctions = externalFuncs,
            macroDefinitions = macroDefs.distinct(),
            namespacePath = function.namespacePath.joinToString("::")
        )
    }

    // ==========================================================
    //  1. Full definition text
    // ==========================================================

    /**
     * Extract the verbatim source text of a function definition
     * (from the return type through the closing brace).
     *
     * This method uses improved brace-matching logic that correctly handles
     * comments and string literals, ensuring we only extract the function body
     * and not accidentally include outer structures like namespaces.
     */
    private fun extractDefinitionText(fileText: String, func: FunctionInfo): String {
        // Fallback: use parsed body if available
        if (func.body != null) {
            val bodySnippet = if (func.body.length > 5000) {
                func.body.take(5000) + "\n// ... (truncated)"
            } else {
                func.body
            }
            return "${func.signature} {\n$bodySnippet\n}"
        }

        // If no line number info, reconstruct from signature only
        if (func.lineNumber <= 0) {
            logger.warn("extractDefinitionText: no line number info for ${func.name}, reconstructing from signature")
            return func.signature
        }

        val lines = fileText.lines()
        val startIdx = (func.lineNumber - 1).coerceIn(0, lines.lastIndex)
        val startOffset = lines.take(startIdx).sumOf { it.length + 1 }

        // Find the opening brace '{' using helper that handles comments/strings
        val openBrace = findOpeningBrace(fileText, startOffset)
        if (openBrace == null) {
            logger.warn("extractDefinitionText: could not find opening brace for ${func.name}")
            return func.signature
        }

        // Find the matching closing brace '}' using helper that handles comments/strings
        val closeBrace = findClosingBrace(fileText, openBrace)
        if (closeBrace == null) {
            logger.warn("extractDefinitionText: could not find closing brace for ${func.name}")
            return func.signature
        }

        // Extract the complete function definition text
        val definition = fileText.substring(startOffset, closeBrace + 1).trim()
        
        logger.debug("extractDefinitionText: extracted ${func.name} (${definition.length} chars)")
        return definition
    }

    // ==========================================================
    //  2. Include resolution  (filesystem-based, VFS-independent)
    // ==========================================================

    private fun resolveInclude(includePath: String, isSystem: Boolean, sourceFile: String): String? {
        val key = "$sourceFile|$includePath"
        val cached = includeCache.getOrPut(key) {
            doResolveInclude(includePath, isSystem, sourceFile) ?: UNRESOLVED_SENTINEL
        }
        return if (cached === UNRESOLVED_SENTINEL) null else cached
    }

    /**
     * Resolve an #include path to an absolute file path.
     *
     * Uses java.io.File for all filesystem operations so that it works even when
     * IntelliJ's VFS has not been fully populated (pure Nova mode without prior
     * Classic-mode indexing).
     */
    private fun doResolveInclude(includePath: String, isSystem: Boolean, sourceFile: String): String? {
        // 1. Relative to source directory (for quoted includes)
        if (!isSystem) {
            val srcDir = IoFile(sourceFile).parentFile
            if (srcDir != null) {
                val candidate = IoFile(srcDir, includePath)
                if (candidate.isFile) return normalizeFsPath(candidate)
            }
        }

        // 2. Relative to each project root (content roots + basePath)
        for (rootPath in getSearchRoots()) {
            val candidate = IoFile(rootPath, includePath)
            if (candidate.isFile) return normalizeFsPath(candidate)
        }

        // 3. Recursive search by filename
        val fileName = includePath.substringAfterLast('/')
        for (rootPath in getSearchRoots()) {
            val found = searchFileOnDisk(IoFile(rootPath), includePath, fileName)
            if (found != null) return normalizeFsPath(found)
        }

        return null
    }

    /**
     * Filesystem-based recursive search — replaces the old VFS-based searchFileRecursive.
     * Uses java.io.File.listFiles() which always works regardless of VFS state.
     */
    private fun searchFileOnDisk(dir: IoFile, target: String, fileName: String): IoFile? {
        if (!dir.isDirectory) return null
        if (dir.name.startsWith(".") || dir.name in SKIP_DIRS) return null

        val children = dir.listFiles() ?: return null

        // Check files first (breadth-first for efficiency)
        for (child in children) {
            if (child.isFile && child.name == fileName) {
                // Verify the full relative path matches (e.g. "stbox/gmssl/sm4.h" not just "sm4.h")
                if (normalizeFsPath(child).endsWith(target)) return child
            }
        }

        // Recurse into subdirectories
        for (child in children) {
            if (child.isDirectory) {
                val found = searchFileOnDisk(child, target, fileName)
                if (found != null) return found
            }
        }

        return null
    }

    // ==========================================================
    //  3. Raw struct / class / enum / typedef / global-var text
    // ==========================================================

    private val typeBlockRegex = Regex(
        """(?:template\s*<[^>]*>\s*)?(?:typedef\s+)?(struct|class|union|enum(?:\s+class|\s+struct)?)\s+""" +
        """(?:\[\[[^\]]*\]\]\s*)?(\w+)[^;{]*\{""",
        RegexOption.MULTILINE
    )

    /** Matches `typedef struct { ... } Name;` where there is NO name between struct and `{`. */
    private val typedefAnonBlockRegex = Regex(
        """typedef\s+(struct|class|union|enum(?:\s+class|\s+struct)?)\s*\{""",
        RegexOption.MULTILINE
    )

    private val typedefLineRegex = Regex(
        """^\s*typedef\s+(.+?)\s+(\w+)\s*;""", RegexOption.MULTILINE
    )
    private val usingLineRegex = Regex(
        """^\s*using\s+(\w+)\s*=\s*(.+?)\s*;""", RegexOption.MULTILINE
    )
    private val globalVarRegex = Regex(
        """^((?:static|extern|const|constexpr|volatile)\s+[\w:*&<>,\s]+?\s+(\w+)\s*(?:\[[^\]]*\])?\s*(?:=\s*[^;]+)?\s*;)""",
        RegexOption.MULTILINE
    )

    private fun collectRawTypeDefinitions(text: String, usedIds: Set<String>): List<String> {
        val results = mutableListOf<String>()

        // struct / class / union / enum blocks (named: struct Name { ... })
        for (m in typeBlockRegex.findAll(text)) {
            val name = m.groupValues[2]
            if (name !in usedIds) continue
            val blockStart = m.range.first
            val openBrace = m.range.last  // index of '{'
            var depth = 1; var i = openBrace + 1
            while (i < text.length && depth > 0) {
                when (text[i]) { '{' -> depth++; '}' -> depth-- }; i++
            }
            // optional trailing semicolon
            var end = i
            while (end < text.length && text[end].isWhitespace()) end++
            if (end < text.length && text[end] == ';') end++
            // optional trailing name for typedef struct Name {...} Alias;
            val tail = text.substring(i, end.coerceAtMost(text.length)).trim()
            if (tail.isEmpty() || tail == ";") {
                val afterBrace = text.substring(i).trimStart()
                val typedefNameMatch = Regex("""^(\w+)\s*;""").find(afterBrace)
                if (typedefNameMatch != null) {
                    end = i + afterBrace.indexOf(';') + 1
                }
            }
            results.add(text.substring(blockStart, end).trim())
        }

        // Anonymous typedef blocks: typedef struct { ... } Name;
        // These have NO name between struct and '{', so typeBlockRegex misses them.
        for (m in typedefAnonBlockRegex.findAll(text)) {
            val blockStart = m.range.first
            val openBrace = m.range.last  // index of '{'
            var depth = 1; var i = openBrace + 1
            while (i < text.length && depth > 0) {
                when (text[i]) { '{' -> depth++; '}' -> depth-- }; i++
            }
            if (depth != 0) continue
            // After '}', expect optional whitespace, then Name, then ';'
            val afterBrace = text.substring(i).trimStart()
            val nameMatch = Regex("""^(\w+)\s*;""").find(afterBrace) ?: continue
            val typedefName = nameMatch.groupValues[1]
            if (typedefName !in usedIds) continue
            // Find the semicolon after closing brace to capture the full typedef
            val semiPos = text.indexOf(';', i)
            if (semiPos < 0) continue
            results.add(text.substring(blockStart, semiPos + 1).trim())
        }

        // typedef ... name;
        for (m in typedefLineRegex.findAll(text)) {
            val name = m.groupValues[2]
            if (name in usedIds) results.add(m.value.trim())
        }

        // using name = ...;
        for (m in usingLineRegex.findAll(text)) {
            val name = m.groupValues[1]
            if (name in usedIds) results.add(m.value.trim())
        }

        // top-level global variables referenced in body
        for (m in globalVarRegex.findAll(text)) {
            val name = m.groupValues[2]
            if (name in usedIds) results.add(m.groupValues[1].trim())
        }

        return results
    }

    // ==========================================================
    //  4. Raw #define macros
    // ==========================================================

    private fun collectRawMacros(text: String, usedIds: Set<String>): List<String> {
        val results = mutableListOf<String>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val dm = Regex("""^\s*#\s*define\s+(\w+)""").find(line)
            if (dm != null && dm.groupValues[1] in usedIds) {
                val buf = StringBuilder(line)
                var j = i
                while (j < lines.size && lines[j].trimEnd().endsWith("\\")) {
                    j++
                    if (j < lines.size) buf.append("\n").append(lines[j])
                }
                results.add(buf.toString().trim())
            }
            i++
        }
        return results
    }

    // ==========================================================
    //  5. External functions  (filesystem-based search)
    // ==========================================================

    /**
     * Find functions called in [function]'s body but defined elsewhere.
     *
     * @param localFuncNames Names of functions defined in the same source file.
     *                       Used to exclude local functions from the result.
     */
    private fun findExternalFunctions(
        function: FunctionInfo,
        localFuncNames: Set<String>,
        filePath: String,
        resolvedIncludes: Map<String, String>,
        basePath: String,
        fileText: String = ""
    ): List<String> {
        // Use function.body if available; fallback to re-extract from fileText if needed
        var bodyText = function.body
        if (bodyText.isNullOrEmpty() && fileText.isNotEmpty() && function.lineNumber > 0) {
            // Attempt to re-extract function body from source text
            bodyText = extractFunctionBodyFromText(fileText, function)
        }
        if (bodyText.isNullOrEmpty()) return emptyList()

        // a) Identifiers that look like function calls
        val calledNames = extractCalledFunctionNames(bodyText)

        // b) Filter out local, language keywords, and standard library functions
        val allLocal = localFuncNames + setOf(function.name)
        val externalNames = calledNames - allLocal - CPP_KEYWORDS - STANDARD_FUNCTIONS

        if (externalNames.isEmpty()) return emptyList()

        val results = mutableListOf<String>()
        val found = mutableSetOf<String>()

        // Search resolved headers first (read via filesystem fallback)
        for ((_, absPath) in resolvedIncludes) {
            val hdrText = readTextByPath(absPath) ?: continue
            val fileName = absPath.substringAfterLast('/')
            val hdrCtx = textExtractor.extractFileContext(hdrText, absPath, fileName) ?: continue
            for (f in hdrCtx.functions) {
                if (f.name in externalNames && f.name !in found) {
                    results.add("${f.signature} @ ${relativize(absPath, basePath)}")
                    found.add(f.name)
                }
            }
            // Also check struct methods
            for (s in hdrCtx.structs) {
                for (f in s.methods) {
                    if (f.name in externalNames && f.name !in found) {
                        results.add("${f.signature} @ ${relativize(absPath, basePath)}")
                        found.add(f.name)
                    }
                }
            }
        }

        // Broaden: search project files for remaining names (filesystem-based)
        val remaining = externalNames - found
        if (remaining.isNotEmpty()) {
            for (rootPath in getSearchRoots()) {
                searchProjectForFunctions(
                    IoFile(rootPath), remaining, found, results, filePath, basePath
                )
                if (found.containsAll(remaining)) break
            }
        }

        return results
    }

    /**
     * Extract identifiers followed by `(` — likely function calls.
     * Also matches template function calls like `func<Type>(...)`.
     */
    private fun extractCalledFunctionNames(body: String): Set<String> {
        val results = mutableSetOf<String>()
        // Standard calls: name(
        val callRegex = Regex("""(?<![.\w])(\w+)\s*\(""")
        results.addAll(callRegex.findAll(body).map { it.groupValues[1] })
        // Template calls: name<...>(
        val templateCallRegex = Regex("""(?<![.\w])(\w+)\s*<[^>]*>\s*\(""")
        results.addAll(templateCallRegex.findAll(body).map { it.groupValues[1] })
        return results
    }

    /**
     * Filesystem-based project search for external functions.
     * Traverses directories using java.io.File, reads C/C++ files via readTextByPath,
     * and extracts function signatures using TextBasedCppExtractor.
     */
    private fun searchProjectForFunctions(
        dir: IoFile,
        targets: Set<String>,
        found: MutableSet<String>,
        results: MutableList<String>,
        excludePath: String,
        basePath: String
    ) {
        if (found.containsAll(targets)) return

        if (!dir.isDirectory) {
            val normalizedPath = normalizeFsPath(dir)
            if (isCppFileName(dir.name) && normalizedPath != excludePath) {
                val text = readTextByPath(normalizedPath) ?: return
                val ctx = textExtractor.extractFileContext(text, normalizedPath, dir.name) ?: return
                for (f in ctx.functions) {
                    if (f.name in targets && f.name !in found) {
                        results.add("${f.signature} @ ${relativize(normalizedPath, basePath)}")
                        found.add(f.name)
                    }
                }
                for (s in ctx.structs) {
                    for (f in s.methods) {
                        if (f.name in targets && f.name !in found) {
                            results.add("${f.signature} @ ${relativize(normalizedPath, basePath)}")
                            found.add(f.name)
                        }
                    }
                }
            }
            return
        }

        if (dir.name.startsWith(".") || dir.name in SKIP_DIRS) return

        val children = dir.listFiles() ?: return
        for (child in children) {
            searchProjectForFunctions(child, targets, found, results, excludePath, basePath)
            if (found.containsAll(targets)) return
        }
    }

    // ==========================================================
    //  Extract function body from text (fallback)
    // ==========================================================

    /**
     * Re-extract a function body from source text when it wasn't in FunctionInfo.
     * Uses line number and function name to locate and extract the body.
     */
    private fun extractFunctionBodyFromText(text: String, func: FunctionInfo): String? {
        if (func.lineNumber <= 0) return null
        val lines = text.lines()
        val startIdx = (func.lineNumber - 1).coerceIn(0, lines.lastIndex)
        val startOffset = lines.take(startIdx).sumOf { it.length + 1 }

        // Find opening brace
        val openBrace = findOpeningBrace(text, startOffset) ?: return null

        // Find closing brace
        val closeBrace = findClosingBrace(text, openBrace) ?: return null

        // Extract body (without braces)
        val body = text.substring(openBrace + 1, closeBrace).trim()
        return if (body.isNotEmpty()) body else null
    }

    // ==========================================================
    //  Utilities
    // ==========================================================

    /** Parse #include directives directly from raw text (fallback when fileCtx is null). */
    private fun extractIncludesFromText(text: String): List<IncludeInfo> {
        val includeRegex = Regex("""^\s*#\s*include\s+([<"])(.+?)[>"]""", RegexOption.MULTILINE)
        return includeRegex.findAll(text).map { m ->
            IncludeInfo(
                path = m.groupValues[2],
                isSystem = m.groupValues[1] == "<",
                resolvedPath = null
            )
        }.toList()
    }

    /** Extract all C/C++ identifiers from text. */
    private fun extractAllIdentifiers(text: String): Set<String> {
        return Regex("""\b([A-Za-z_]\w*)\b""").findAll(text)
            .map { it.groupValues[1] }
            .filter { it !in CPP_KEYWORDS }
            .toSet()
    }

    /**
     * Rough extraction of function names from file text (fallback when fileCtx is null).
     * Used to build the "local functions" set for filtering external function calls.
     */
    private fun extractFunctionNamesFromText(text: String): Set<String> {
        val regex = Regex(
            """\b(\w+)\s*\([^)]*\)\s*(?:const\s*)?(?:noexcept\s*)?(?:override\s*)?(?:final\s*)?\{""",
            RegexOption.MULTILINE
        )
        return regex.findAll(text)
            .map { it.groupValues[1] }
            .filter { it !in CPP_KEYWORDS }
            .toSet()
    }

    /**
     * Collect project search roots: content roots + project basePath (deduplicated).
     * This ensures we always have at least one root to search from, even when
     * contentRoots is empty (possible in pure Nova mode).
     */
    private fun getSearchRoots(): List<String> {
        val roots = mutableListOf<String>()
        try {
            ReadAction.compute<Unit, Throwable> {
                ProjectRootManager.getInstance(project).contentRoots.mapTo(roots) { it.path }
            }
        } catch (_: Throwable) {}
        val basePath = project.basePath
        if (basePath != null && roots.none { it == basePath }) {
            roots.add(basePath)
        }
        return roots
    }

    /**
     * Read file text by absolute path. Tries VFS first; falls back to java.io.File
     * when VFS has not been populated for this path.
     */
    private fun readTextByPath(path: String): String? {
        // Try VFS (fast path — works when VFS is populated)
        val vf = LocalFileSystem.getInstance().findFileByPath(path)
        if (vf != null) {
            return try { String(vf.contentsToByteArray(), Charsets.UTF_8) } catch (_: Throwable) { null }
        }
        // Fallback: read directly from filesystem
        val f = IoFile(path)
        return if (f.isFile) {
            try { f.readText(Charsets.UTF_8) } catch (_: Throwable) { null }
        } else null
    }

    private fun readText(vf: VirtualFile): String =
        String(vf.contentsToByteArray(), Charsets.UTF_8)

    private fun relativize(abs: String, base: String): String =
        if (abs.startsWith(base)) abs.removePrefix(base).removePrefix("/") else abs

    /** Normalise a java.io.File path to forward slashes (VFS / cross-platform format). */
    private fun normalizeFsPath(f: IoFile): String =
        f.absolutePath.replace('\\', '/')

    private fun isCppFileName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in CPP_EXTENSIONS
    }

    // ==========================================================
    //  Brace matching helpers with comment/string support
    // ==========================================================
    /**
     * Find the opening brace '{' of a function body, starting from a given position.
     * Ignores comments, string literals, and char literals.
     */
    private fun findOpeningBrace(text: String, startOffset: Int): Int? {
        var i = startOffset
        var inSingleLineComment = false
        var inMultiLineComment = false
        var inCharLiteral = false
        var inStringLiteral = false
        while (i < text.length) {
            val c = text[i]
            val prev = if (i > 0) text[i - 1] else '\u0000'
            val next = if (i < text.length - 1) text[i + 1] else '\u0000'
            when {
                inSingleLineComment -> { if (c == '\n') inSingleLineComment = false }
                inMultiLineComment -> { if (c == '*' && next == '/') { inMultiLineComment = false; i++ } }
                inCharLiteral -> { if (c == '\'' && prev != '\\') inCharLiteral = false }
                inStringLiteral -> { if (c == '"' && prev != '\\') inStringLiteral = false }
                c == '/' && next == '/' -> inSingleLineComment = true
                c == '/' && next == '*' -> { inMultiLineComment = true; i++ }
                c == '\'' && !inStringLiteral -> inCharLiteral = true
                c == '"' && !inCharLiteral -> inStringLiteral = true
                !inSingleLineComment && !inMultiLineComment && !inCharLiteral && !inStringLiteral -> {
                    when (c) {
                        '{' -> return i
                        ';' -> return null  // declaration, not definition
                    }
                }
            }
            i++
        }
        return null
    }

    /**
     * Find the closing brace '}' that matches the opening brace at openBraceIndex.
     * Ignores comments, string literals, and char literals.
     */
    private fun findClosingBrace(text: String, openBraceIndex: Int): Int? {
        var depth = 1
        var i = openBraceIndex + 1
        var inSingleLineComment = false
        var inMultiLineComment = false
        var inCharLiteral = false
        var inStringLiteral = false
        while (i < text.length && depth > 0) {
            val c = text[i]
            val prev = if (i > 0) text[i - 1] else '\u0000'
            val next = if (i < text.length - 1) text[i + 1] else '\u0000'
            when {
                inSingleLineComment -> { if (c == '\n') inSingleLineComment = false }
                inMultiLineComment -> { if (c == '*' && next == '/') { inMultiLineComment = false; i++ } }
                inCharLiteral -> { if (c == '\'' && prev != '\\') inCharLiteral = false }
                inStringLiteral -> { if (c == '"' && prev != '\\') inStringLiteral = false }
                c == '/' && next == '/' -> inSingleLineComment = true
                c == '/' && next == '*' -> { inMultiLineComment = true; i++ }
                c == '\'' && !inStringLiteral -> inCharLiteral = true
                c == '"' && !inCharLiteral -> inStringLiteral = true
                !inSingleLineComment && !inMultiLineComment && !inCharLiteral && !inStringLiteral -> {
                    when (c) {
                        '{' -> depth++
                        '}' -> depth--
                    }
                }
            }
            i++
        }
        return if (depth == 0) i - 1 else null
    }

    // ==========================================================
    //  Constants
    // ==========================================================

    companion object {
        private val SKIP_DIRS = setOf(
            "build", "cmake-build-debug", "cmake-build-release",
            "cmake-build-relwithdebinfo", "cmake-build-minsizerel",
            "node_modules", ".git", "__pycache__", "third_party", "3rdparty"
        )
        private val CPP_EXTENSIONS = setOf(
            "c", "cpp", "cc", "cxx", "h", "hpp", "hxx", "hh"
        )
        private val HEADER_EXTENSIONS = setOf(
            "h", "hpp", "hxx", "hh"
        )
        private val CPP_KEYWORDS = setOf(
            "if", "else", "for", "while", "do", "switch", "case", "return",
            "break", "continue", "goto", "try", "catch", "throw", "new",
            "delete", "sizeof", "alignof", "decltype", "typeid",
            "static_cast", "dynamic_cast", "const_cast", "reinterpret_cast",
            "auto", "void", "int", "char", "float", "double", "bool",
            "long", "short", "unsigned", "signed", "const", "volatile",
            "static", "extern", "inline", "virtual", "override", "final",
            "class", "struct", "union", "enum", "namespace", "template",
            "typename", "typedef", "using", "public", "private", "protected",
            "friend", "explicit", "constexpr", "nullptr", "true", "false",
            "this", "operator", "noexcept", "default", "register", "mutable"
        )
        private val STANDARD_FUNCTIONS = setOf(
            "printf", "fprintf", "sprintf", "snprintf", "scanf", "sscanf",
            "malloc", "calloc", "realloc", "free", "memcpy", "memset",
            "memmove", "strcmp", "strncmp", "strlen", "strcpy", "strncpy",
            "strcat", "assert", "abort", "exit", "atexit",
            "make_shared", "make_unique", "make_pair", "make_tuple",
            "move", "forward", "swap", "min", "max", "sort", "find",
            "begin", "end", "size", "empty", "push_back", "emplace_back",
            "insert", "erase", "clear", "reserve", "resize",
            "get", "set", "at", "front", "back", "data",
            "to_string", "stoi", "stol", "stod", "stof"
        )
    }
}
