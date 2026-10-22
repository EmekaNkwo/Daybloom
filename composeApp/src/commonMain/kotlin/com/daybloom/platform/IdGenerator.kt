package com.daybloom.platform

import kotlin.uuid.Uuid

fun interface IdGenerator {
    fun newId(): String

    companion object {
        val Random = IdGenerator { Uuid.random().toString() }
    }
}
