package com.khan366kos.lis.client.ktor.domain

sealed class LoodsmanObject {
    data class NewObject(
        val type: LoodsmanType,
        val state: LoodsmanState,
        val name: String,
        val isProject: Boolean,
    ) : LoodsmanObject()

    data object Empty : LoodsmanObject()
}

