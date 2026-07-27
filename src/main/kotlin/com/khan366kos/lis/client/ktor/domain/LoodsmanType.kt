package com.khan366kos.lis.client.ktor.domain

sealed class LoodsmanType(
    open val name: String,
) {
    data class TypeWithId(
        val id: Int,
        override val name: String
    ): LoodsmanType(name)

    data object Folder : LoodsmanType(name = "Папка")
}


