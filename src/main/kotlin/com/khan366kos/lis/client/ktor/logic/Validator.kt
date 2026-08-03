package com.khan366kos.lis.client.ktor.logic

import com.khan366kos.lis.client.ktor.domain.LoodsmanType
import com.khan366kos.lis.client.ktor.domain.Settings

class Validator(
    private val settings: Settings,
    private val types: List<LoodsmanType>
) {
    fun isValidTarget() =
        settings.mapping.types.all { element -> element.target in types.mapToSet { it.name } }

    fun isValidMaterialTarget() =
        settings.mapping.materials.materialTarget in types.mapToSet { it.name }

    // mapping.blanks выключен целиком, если target пуст (см. BlanksSettings) — тогда проверять
    // нечего, валидация всегда проходит.
    fun isValidBlankTargets(): Boolean {
        val blanks = settings.mapping.blanks
        if (blanks.target.isBlank()) return true
        val typeNames = types.mapToSet { it.name }
        return blanks.target in typeNames && blanks.materialTarget in typeNames
    }
}

inline fun <T, R> List<T>.mapToSet(transform: (T) -> R): Set<R> {
    return when {
        isEmpty() -> emptySet()
        else -> mapTo(HashSet(size)) { transform(it) }
    }
}