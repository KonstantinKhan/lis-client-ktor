package com.khan366kos.lis.client.ktor.loodsman.api.dto

import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class IdentifierDto(private val value: Int) {
    fun asInt(): Int = value
}