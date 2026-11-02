package com.ylmac.adapt.compose.screens.music

import com.ylmac.adapt.module.misc.music.PlayerController
import com.ylmac.adapt.module.misc.music.bridge.SearchRow
import java.util.Calendar

internal fun mapStr(m: Map<*, *>?, key: String, fallback: String = ""): String =
    m?.get(key)?.toString() ?: fallback

internal fun mapId(m: Map<*, *>?): Any = m?.get("id") ?: 0L

internal fun mapLong(m: Map<*, *>?, key: String, fallback: Long = 0L): Long =
    (m?.get(key) as? Number)?.toLong() ?: fallback

internal fun mapInt(m: Map<*, *>?, key: String, fallback: Int = 0): Int =
    (m?.get(key) as? Number)?.toInt() ?: fallback

@Suppress("UNCHECKED_CAST")
internal fun <T> peekList(prop: Any?): List<T> =
    try {
        val peek = prop?.javaClass?.methods?.firstOrNull { it.name == "peek" && it.parameterCount == 0 }
        (peek?.invoke(prop) as? List<*>)?.mapNotNull { it as? T } ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

@Suppress("UNCHECKED_CAST")
internal fun peekMaps(pcProp: Any?): List<Map<String, Any>> {
    return try {
        val list = (pcProp as? com.ylmac.adapt.config.Slot<*>)?.peek() as? List<*>
            ?: return emptyList()
        list.mapNotNull { item ->
            @Suppress("UNCHECKED_CAST")
            item as? Map<String, Any>
        }
    } catch (_: Throwable) {
        emptyList()
    }
}

internal fun peekStrings(pcProp: Any?): List<String> {
    return try {
        val list = (pcProp as? com.ylmac.adapt.config.Slot<*>)?.peek() as? List<*>
            ?: return emptyList()
        list.mapNotNull { it?.toString() }
    } catch (_: Throwable) {
        emptyList()
    }
}

internal fun peekSearchRows(pc: PlayerController): List<SearchRow> {
    return try {
        @Suppress("UNCHECKED_CAST")
        (pc.searchRows.peek() as? List<SearchRow>) ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }
}

internal fun greeting(): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when {
        h < 6 -> "夜深了"
        h < 12 -> "早上好"
        h < 14 -> "中午好"
        h < 18 -> "下午好"
        else -> "晚上好"
    }
}

internal fun boolPeek(prop: com.ylmac.adapt.config.Slot<Boolean>?): Boolean =
    try {
        java.lang.Boolean.TRUE == prop?.peek()
    } catch (_: Throwable) {
        false
    }

internal fun strPeek(prop: com.ylmac.adapt.config.Slot<String>?, fallback: String = ""): String =
    try {
        prop?.peek()?.toString() ?: fallback
    } catch (_: Throwable) {
        fallback
    }

internal fun numPeek(prop: com.ylmac.adapt.config.Slot<out Number>?, fallback: Int = 0): Int =
    try {
        (prop?.peek() as? Number)?.toInt() ?: fallback
    } catch (_: Throwable) {
        fallback
    }

internal fun floatPeek(prop: com.ylmac.adapt.config.Slot<out Number>?, fallback: Float = 0f): Float =
    try {
        (prop?.peek() as? Number)?.toFloat() ?: fallback
    } catch (_: Throwable) {
        fallback
    }

internal fun longPeek(prop: com.ylmac.adapt.config.Slot<out Number>?, fallback: Long = 0L): Long =
    try {
        (prop?.peek() as? Number)?.toLong() ?: fallback
    } catch (_: Throwable) {
        fallback
    }

/** 列表长度戳：用于 MusicShell 脏签名，避免 forever 空列表。 */
internal fun listSize(prop: com.ylmac.adapt.config.Slot<*>?): Int =
    try {
        (prop?.peek() as? List<*>)?.size ?: 0
    } catch (_: Throwable) {
        0
    }

/**
 * 列表内容粗指纹：size + 首/末项 id（或 hash）。
 * 同长度整表替换时 length-only 签名会漏脏。
 */
internal fun listStamp(prop: com.ylmac.adapt.config.Slot<*>?): String {
    try {
        val list = prop?.peek() as? List<*> ?: return "0"
        val n = list.size
        if (n == 0) return "0"
        fun idOf(o: Any?): Any {
            if (o is Map<*, *>) {
                val id = o["id"] ?: o["songId"] ?: o["playlistId"] ?: o["name"] ?: o["title"]
                if (id != null) return id
            }
            return System.identityHashCode(o)
        }
        return "$n:${idOf(list.first())}:${idOf(list.last())}"
    } catch (_: Throwable) {
        return "0"
    }
}
