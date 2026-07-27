package com.khan366kos.lis.client.ktor.domain

import kotlinx.serialization.Serializable

@Serializable
data class Conditions(
    val single: Rule? = null,
    val or: List<Rule> = emptyList(),
    val and: List<Rule> = emptyList(),
)
