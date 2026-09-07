package com.staysafeai.app.managers

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class UserConfirmationDialog(private val context: Context) {

    suspend fun ask(context_info: String, question: String): Boolean =
        suspendCancellableCoroutine { cont ->
            MaterialAlertDialogBuilder(context)
                .setTitle("Quick Check")
                .setMessage("$context_info\n\n$question")
                .setPositiveButton("Yes") { _, _ -> cont.resume(true) }
                .setNegativeButton("No")  { _, _ -> cont.resume(false) }
                .setCancelable(false)
                .show()
        }
}
