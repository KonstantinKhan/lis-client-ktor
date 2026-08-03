package com.khan366kos.lis.client.ktor.domain

data class Identifier(
    val loodsmanId: Int,
    val classifierId: Long,
    val childLinkType: String? = null,
    val childOfSameTypeLinkType: String? = null
)
