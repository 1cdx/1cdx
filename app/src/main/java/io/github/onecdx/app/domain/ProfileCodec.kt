package io.github.onecdx.app.domain

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken

/** Serialisasi daftar profil ke JSON (sebelum dienkripsi) dan sebaliknya. */
object ProfileCodec {
    private val gson = Gson()
    private val listType = object : TypeToken<List<ServerProfile>>() {}.type

    fun encode(profiles: List<ServerProfile>): String = gson.toJson(profiles, listType)

    fun decode(json: String): List<ServerProfile> {
        if (json.isBlank()) return emptyList()
        val parsed: List<ServerProfile?> = try {
            gson.fromJson(json, listType) ?: emptyList()
        } catch (e: JsonParseException) {
            throw IllegalArgumentException("Data profil rusak", e)
        }
        return parsed.filterNotNull().map { it.normalized() }.distinctBy { it.id }
    }
}
