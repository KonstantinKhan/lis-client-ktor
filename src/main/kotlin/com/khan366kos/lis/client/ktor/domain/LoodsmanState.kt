package com.khan366kos.lis.client.ktor.domain

sealed class LoodsmanState(
    val name: String,
) {
    data object ReadFolder : LoodsmanState("Папка для чтения")
}

