package com.mmtube.app.data

import java.security.MessageDigest

object UserCode {
    private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

    fun fromUid(uid: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder("MM-")
        for (index in 0 until 6) {
            val value = digest[index].toInt() and 0xFF
            builder.append(ALPHABET[value % ALPHABET.length])
        }
        return builder.toString()
    }

    fun normalize(input: String): String {
        val trimmed = input.trim().uppercase()
        return if (trimmed.startsWith("MM-")) trimmed else "MM-$trimmed"
    }
}
