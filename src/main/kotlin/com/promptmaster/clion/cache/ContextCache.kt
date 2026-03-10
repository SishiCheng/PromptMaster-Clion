package com.promptmaster.clion.cache

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ContextCache(
    private val ttlMs: Long = TimeUnit.MINUTES.toMillis(5)
) {
    private data class CacheEntry(
        val value: Any?,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    @Suppress("UNCHECKED_CAST")
    fun <T> getOrCompute(key: String, compute: () -> T): T {
        val existing = cache[key]
        if (existing != null && !isExpired(existing)) {
            return existing.value as T
        }
        val value = compute()
        cache[key] = CacheEntry(value)
        return value
    }

    fun invalidate(key: String) {
        cache.remove(key)
    }

    fun invalidateByPrefix(keyPrefix: String) {
        cache.keys.removeAll { it.startsWith(keyPrefix) }
    }

    fun clear() {
        cache.clear()
    }

    val size: Int get() = cache.size

    private fun isExpired(entry: CacheEntry): Boolean {
        return System.currentTimeMillis() - entry.timestamp > ttlMs
    }
}
