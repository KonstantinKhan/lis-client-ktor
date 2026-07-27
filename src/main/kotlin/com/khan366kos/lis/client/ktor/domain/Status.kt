package com.khan366kos.lis.client.ktor.domain

enum class Status {
    START,
    EXIST_CONFIG,
    NOT_CONFIG,
    LOGIN,
    LOGIN_SUCCESS,
    API_ERROR,
    EMPTY_SESSION,
    CHECKOUT,
    CONNECT_CHECKOUT,
    NOT_ENOUGH_RIGHTS,
    OBJECTS_MIGRATED,
    LINKS_MIGRATED
}