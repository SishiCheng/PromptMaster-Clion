package com.promptmaster.clion.cache

import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.promptmaster.clion.services.ContextExtractionService

/**
 * Listens for PSI tree changes in C/C++ files and invalidates
 * the corresponding cache entries.
 *
 * Note: OCFile is referenced via reflection instead of a direct import so that
 * this class loads cleanly in CLion 2025.3 Nova/Radler mode where
 * com.intellij.cidr.lang is suppressed and its classes are not on the
 * runtime classpath.
 */
class CacheInvalidator(
    private val service: ContextExtractionService
) : PsiTreeChangeAdapter() {

    override fun childAdded(event: PsiTreeChangeEvent) = handleChange(event)
    override fun childRemoved(event: PsiTreeChangeEvent) = handleChange(event)
    override fun childReplaced(event: PsiTreeChangeEvent) = handleChange(event)
    override fun childrenChanged(event: PsiTreeChangeEvent) = handleChange(event)
    override fun childMoved(event: PsiTreeChangeEvent) = handleChange(event)

    private fun handleChange(event: PsiTreeChangeEvent) {
        try {
            val file = event.file ?: return
            if (!isOCFile(file)) return

            val path = file.virtualFile?.path ?: return
            service.invalidateCacheForFile(path)
        } catch (_: Throwable) {
            // Silently ignore any class-loading or runtime errors when
            // CIDR PSI is not available (Nova mode).
        }
    }

    /**
     * Checks whether [element] is an instance of OCFile using reflection.
     * Returns false (and does NOT throw) if the OCFile class cannot be loaded.
     */
    private fun isOCFile(element: Any): Boolean {
        return try {
            val ocFileClass = Class.forName(
                "com.jetbrains.cidr.lang.psi.OCFile",
                false,
                javaClass.classLoader
            )
            ocFileClass.isInstance(element)
        } catch (_: Throwable) {
            false
        }
    }
}
