package com.promptmaster.clion.services

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.psi.PsiManager
import com.promptmaster.clion.cache.CacheInvalidator

/**
 * Runs after project open to initialize the context extraction service
 * and register PSI change listeners for cache invalidation.
 */
class ContextStartupActivity : ProjectActivity {

    private val logger = Logger.getInstance(ContextStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        val service = ContextExtractionService.getInstance(project)

        // Register PSI change listener to automatically invalidate cache on file edits
        PsiManager.getInstance(project).addPsiTreeChangeListener(
            CacheInvalidator(service),
            service // use service as Disposable parent — listener is removed when service is disposed
        )

        logger.info("PromptMaster-Clion initialized for project: ${project.name}")
    }
}
