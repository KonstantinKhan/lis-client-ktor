package com.khan366kos.lis.client.ktor

data class ConnectConfiguration(
    val dbName: String,
    val userName: String,
    val password: String,
    val rememberMe: Boolean = false,
    val url: String
) {
}