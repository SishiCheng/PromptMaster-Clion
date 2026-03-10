package com.promptmaster.clion.api

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.ide.RestService
import com.promptmaster.clion.models.*
import com.promptmaster.clion.services.ContextExtractionService

/**
 * REST API exposed on IntelliJ's built-in HTTP server.
 *
 * Base URL: http://localhost:63342/api/cpp-context
 *
 * Endpoints:
 *   GET /api/cpp-context/health                      - Health check
 *   GET /api/cpp-context/file?path=<abs_path>        - Full file context
 *   GET /api/cpp-context/function?path=<p>&name=<n>  - Function-level context
 *   GET /api/cpp-context/project                     - Project summary
 *   GET /api/cpp-context/cmake                       - CMake configuration
 *   GET /api/cpp-context/ut-context?path=<p>&name=<n> - Unit-test context (VS Code format)
 *   GET /api/cpp-context/search?q=<query>            - Symbol search
 *   GET /api/cpp-context/invalidate                  - Invalidate cache
 */
class ContextRestService : RestService() {

    private val logger = Logger.getInstance(ContextRestService::class.java)

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    override fun getServiceName(): String = "cpp-context"

    override fun isMethodSupported(method: HttpMethod): Boolean {
        return method == HttpMethod.GET || method == HttpMethod.POST
    }

    override fun execute(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext
    ): String? {
        val path = urlDecoder.path()
        val subPath = path
            .removePrefix("/api/${getServiceName()}")
            .removePrefix("/")
            .split("/")
            .firstOrNull() ?: ""

        // Resolve target project
        val projectName = getStringParameter("project", urlDecoder)
        val project = if (projectName != null) {
            ProjectManager.getInstance().openProjects.find { it.name == projectName }
        } else {
            ProjectManager.getInstance().openProjects.firstOrNull()
        }

        if (project == null) {
            return sendJsonError(request, context, "No open project found", HttpResponseStatus.NOT_FOUND)
        }

        val service = ContextExtractionService.getInstance(project)

        return try {
            when (subPath) {
                "health" -> {
                    sendJsonOk(request, context, json.encodeToString(service.getHealth()))
                }

                "file" -> {
                    val filePath = normalizePath(getStringParameter("path", urlDecoder)
                        ?: return sendJsonError(request, context, "Missing 'path' parameter", HttpResponseStatus.BAD_REQUEST))

                    val fileCtx = service.getFileContext(filePath)
                    if (fileCtx != null) {
                        sendJsonOk(request, context, json.encodeToString(
                            ApiResponse(success = true, data = fileCtx)
                        ))
                    } else {
                        sendJsonError(request, context, "File not found or not a C/C++ file: $filePath", HttpResponseStatus.NOT_FOUND)
                    }
                }

                "function" -> {
                    val filePath = normalizePath(getStringParameter("path", urlDecoder)
                        ?: return sendJsonError(request, context, "Missing 'path' parameter", HttpResponseStatus.BAD_REQUEST))
                    val funcName = getStringParameter("name", urlDecoder)
                        ?: return sendJsonError(request, context, "Missing 'name' parameter", HttpResponseStatus.BAD_REQUEST)

                    val funcCtx = service.getFunctionContext(filePath, funcName)
                    if (funcCtx != null) {
                        sendJsonOk(request, context, json.encodeToString(
                            ApiResponse(success = true, data = funcCtx)
                        ))
                    } else {
                        sendJsonError(request, context, "Function '$funcName' not found in: $filePath", HttpResponseStatus.NOT_FOUND)
                    }
                }

                "ut-context" -> {
                    val filePath = normalizePath(getStringParameter("path", urlDecoder)
                        ?: return sendJsonError(request, context, "Missing 'path' parameter", HttpResponseStatus.BAD_REQUEST))
                    val funcName = getStringParameter("name", urlDecoder)
                        ?: return sendJsonError(request, context, "Missing 'name' parameter", HttpResponseStatus.BAD_REQUEST)

                    val utContext = service.getUnitTestContext(filePath, funcName)
                    if (utContext != null) {
                        sendJsonOk(request, context, json.encodeToString(
                            ApiResponse(success = true, data = utContext)
                        ))
                    } else {
                        sendJsonError(request, context,
                            "ut-context extraction failed for '$funcName' in $filePath. " +
                            "Check IDE log (Help > Show Log) for details.",
                            HttpResponseStatus.NOT_FOUND)
                    }
                }

                "project" -> {
                    val projectCtx = service.getProjectContext()
                    sendJsonOk(request, context, json.encodeToString(
                        ApiResponse(success = true, data = projectCtx)
                    ))
                }

                "cmake" -> {
                    val cmakeCtx = service.getCMakeContext()
                    if (cmakeCtx != null) {
                        sendJsonOk(request, context, json.encodeToString(
                            ApiResponse(success = true, data = cmakeCtx)
                        ))
                    } else {
                        sendJsonError(request, context, "CMake workspace not available", HttpResponseStatus.NOT_FOUND)
                    }
                }

                "search" -> {
                    val query = getStringParameter("q", urlDecoder)
                        ?: return sendJsonError(request, context, "Missing 'q' parameter", HttpResponseStatus.BAD_REQUEST)

                    val results = service.searchSymbols(query)
                    sendJsonOk(request, context, json.encodeToString(
                        ApiResponse(success = true, data = results)
                    ))
                }

                "invalidate" -> {
                    service.invalidateAllCache()
                    sendJsonOk(request, context, json.encodeToString(
                        ApiResponse(success = true, data = "Cache invalidated")
                    ))
                }

                else -> {
                    sendJsonError(
                        request, context,
                        "Unknown endpoint: /$subPath. Available: /health, /file, /function, /ut-context, /project, /cmake, /search, /invalidate",
                        HttpResponseStatus.NOT_FOUND
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("Error processing request: $path", e)
            sendJsonError(request, context, "Internal error: ${e.message}", HttpResponseStatus.INTERNAL_SERVER_ERROR)
        }
    }

    // ----------------------------------------------------------
    // Path helpers
    // ----------------------------------------------------------

    /**
     * Normalise a file path received from the query string.
     *
     * On Windows, users typically copy paths with backslashes (e.g. D:\project\src\file.cpp).
     * IntelliJ's VirtualFileSystem always uses forward slashes internally, even on Windows,
     * so we replace every backslash with a forward slash before passing the path downstream.
     *
     * This is a no-op on macOS / Linux where paths already use forward slashes.
     */
    private fun normalizePath(rawPath: String): String = rawPath.replace('\\', '/')

    // ----------------------------------------------------------
    // Response helpers
    // ----------------------------------------------------------

    private fun sendJsonOk(
        request: HttpRequest,
        context: ChannelHandlerContext,
        jsonBody: String
    ): String? {
        val bytes = jsonBody.toByteArray(Charsets.UTF_8)
        val response = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.OK,
            Unpooled.wrappedBuffer(bytes)
        )
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8")
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.size)
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, OPTIONS")
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type")
        sendResponse(request, context, response)
        return null
    }

    private fun sendJsonError(
        request: HttpRequest,
        context: ChannelHandlerContext,
        message: String,
        status: HttpResponseStatus
    ): String? {
        val errorResponse = json.encodeToString(
            ApiResponse<String>(success = false, error = message)
        )
        val bytes = errorResponse.toByteArray(Charsets.UTF_8)
        val response = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            status,
            Unpooled.wrappedBuffer(bytes)
        )
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8")
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.size)
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
        sendResponse(request, context, response)
        return null
    }
}
