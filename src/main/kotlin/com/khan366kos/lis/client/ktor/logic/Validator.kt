package com.khan366kos.lis.client.ktor.logic

import com.khan366kos.lis.client.ktor.domain.LoodsmanType
import com.khan366kos.lis.client.ktor.domain.Settings

class Validator(
    private val settings: Settings,
    private val types: List<LoodsmanType>
) {
    fun isValidTarget() =
        settings.mapping.types.all { element -> element.target in types.mapToSet { it.name } }
}

inline fun <T, R> List<T>.mapToSet(transform: (T) -> R): Set<R> {
    return when {
        isEmpty() -> emptySet()
        else -> mapTo(HashSet(size)) { transform(it) }
    }
}