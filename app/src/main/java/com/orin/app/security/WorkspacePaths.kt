package com.orin.app.security

import java.io.File

/** Resolves a user/model supplied relative path without allowing workspace escapes. */
object WorkspacePaths {
    fun resolve(root: File, relative: String): File {
        require(!relative.contains('\u0000')) { "Invalid path." }
        val rootCanonical = root.canonicalFile
        val clean = relative.trim().replace('\\', '/').removePrefix("/")
        val target = File(rootCanonical, clean).canonicalFile
        require(target.toPath().startsWith(rootCanonical.toPath())) {
            "Path escapes the workspace."
        }
        return target
    }
}
