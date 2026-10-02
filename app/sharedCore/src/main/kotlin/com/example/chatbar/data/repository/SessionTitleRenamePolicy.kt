package com.example.chatbar.data.repository

/** Keeps completed replacements intact when the new name contains the old name. */
internal object SessionTitleRenamePolicy {
    fun rewrite(title: String, oldName: String, newName: String): String {
        if (!newName.contains(oldName)) return title.replace(oldName, newName)

        // Protect every existing new-name span, including spans that overlap an old-name match.
        val protected = BooleanArray(title.length)
        for (start in 0..(title.length - newName.length)) {
            if (title.startsWith(newName, start)) {
                for (index in start until start + newName.length) protected[index] = true
            }
        }
        val result = StringBuilder(title.length)
        var index = 0
        while (index < title.length) {
            if (title.startsWith(oldName, index) &&
                (index until index + oldName.length).none { protected[it] }) {
                result.append(newName)
                index += oldName.length
            } else {
                result.append(title[index])
                index++
            }
        }
        return result.toString()
    }
}
