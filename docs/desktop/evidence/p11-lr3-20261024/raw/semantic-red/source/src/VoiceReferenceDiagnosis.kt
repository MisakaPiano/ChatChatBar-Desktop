package com.example.chatbar.desktop.backup

/** Aggregate relationship evidence only. No values, guessed repairs, scope inference or restore authority. */
internal object VoiceReferenceDiagnosis {
    data class Owner(val session: String, val versions: Set<String>)
    data class Voice(val message: String, val session: String, val version: String?)
    fun classify(owners: Map<String,Owner>, voices: List<Voice>): Map<String,Int> {
        val result=linkedMapOf<String,Int>()
        fun add(key:String){result[key]=(result[key]?:0)+1}
        voices.forEach { v ->
            val owner=owners[v.message]
            when {
                owner==null -> add("OWNER_NOT_PRESENT_IN_SELECTED_PACKAGE")
                owner.session!=v.session -> add("CROSS_SESSION_VOICE")
                v.version==null -> add("LEGACY_VERSION_REPRESENTATION")
                v.version in owner.versions -> add("KNOWN_VERSION")
                else -> add("NONNULL_VERSION_NOT_IN_OFFICIAL_VERSIONS")
            }
        }
        return result
    }
    fun blocked(result:Map<String,Int>)=result.keys.any{it !in setOf("KNOWN_VERSION","LEGACY_VERSION_REPRESENTATION")}
}
