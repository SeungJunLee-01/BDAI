package com.example.sleepsync

import android.content.Context
import java.time.LocalDate

// Health Connect can't hold a zero-length sleep session, so all-nighters live in app storage.
class AllNighterStore(context: Context) {
    private val prefs = context.getSharedPreferences("all_nighters", Context.MODE_PRIVATE)

    fun load(): Set<LocalDate> =
        prefs.getStringSet(KEY, emptySet()).orEmpty().mapTo(mutableSetOf(), LocalDate::parse)

    fun add(date: LocalDate) = save(load() + date)

    fun remove(date: LocalDate) = save(load() - date)

    private fun save(dates: Set<LocalDate>) {
        prefs.edit().putStringSet(KEY, dates.mapTo(mutableSetOf(), LocalDate::toString)).apply()
    }

    private companion object {
        const val KEY = "dates"
    }
}
