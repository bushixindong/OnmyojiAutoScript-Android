package com.oas.android.engine

import android.content.SharedPreferences

private fun taskSummaryKey(preset: String) = "taskSummary:$preset"

internal fun SharedPreferences.getTaskSummary(preset: String): String? =
    getString(taskSummaryKey(preset), null)

internal fun SharedPreferences.Editor.putTaskSummary(preset: String, raw: String): SharedPreferences.Editor =
    putString(taskSummaryKey(preset), raw)

internal fun SharedPreferences.Editor.removeTaskSummary(preset: String): SharedPreferences.Editor =
    remove(taskSummaryKey(preset))
