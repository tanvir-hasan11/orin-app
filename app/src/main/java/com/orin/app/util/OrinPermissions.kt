package com.orin.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

data class PermissionGroup(
    val id: String,
    val title: String,
    val rationale: String,
    val permissions: List<String>,
)

object OrinPermissions {
    val groups = listOf(
        PermissionGroup(
            id = "calendar",
            title = "Calendar",
            rationale = "Read your upcoming events and create events you explicitly ask for.",
            permissions = listOf(
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR,
            ),
        ),
        PermissionGroup(
            id = "contacts",
            title = "Contacts",
            rationale = "Find a contact when you ask Orin to message or call someone.",
            permissions = listOf(Manifest.permission.READ_CONTACTS),
        ),
        PermissionGroup(
            id = "sms",
            title = "SMS",
            rationale = "Read recent messages and send texts only after you approve each message.",
            permissions = listOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS,
            ),
        ),
    )

    fun isGranted(context: Context, group: PermissionGroup): Boolean =
        group.permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    fun allGranted(context: Context): Boolean = groups.all { isGranted(context, it) }
}
