package com.khan366kos.lis.client.ktor.loodsman.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class CheckInModel(
    val checkOutName: String,
    val dbName: String
)
