package com.example.chatbar.domain.card

import kotlinx.serialization.json.Json

sealed interface SharedImportInspection {
    val kind: SharedImportKind

    data class Character(val request: CharacterCardImportRequest) : SharedImportInspection {
        override val kind = SharedImportKind.CHARACTER
    }

    data class Format(val packageData: FormatCardPackage) : SharedImportInspection {
        override val kind = SharedImportKind.FORMAT
    }

    data class WorldBook(val packageData: WorldBookPackage) : SharedImportInspection {
        override val kind = SharedImportKind.WORLD_BOOK
    }

    data class ModelTemplate(val packageData: ModelTemplatePackage) : SharedImportInspection {
        override val kind = SharedImportKind.MODEL_TEMPLATE
    }

    data class Image(val info: SharedImportImageInfo) : SharedImportInspection {
        override val kind = SharedImportKind.IMAGE
    }

    data class Unknown(val textLike: Boolean) : SharedImportInspection {
        override val kind = SharedImportKind.UNKNOWN
    }
}

/** Android-compatible facade over the shared content-first classifier authority. */
object SharedImportClassifier {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val core = SharedImportClassifierCore(
        sillyTavernMapper = AndroidSillyTavernCardMapper.delegate,
        modelTemplateDecoder = SharedImportModelTemplateDecoder<ModelTemplatePackage> { rawJson ->
            json.decodeFromString(ModelTemplatePackage.serializer(), rawJson).also {
                it.validateForImport()
            }
        },
        json = json,
    )

    fun inspect(
        bytes: ByteArray,
        displayName: String = "共享文件",
        imageInfo: SharedImportImageInfo? = null,
    ): SharedImportInspection = core.inspect(bytes, displayName, imageInfo).toAndroidInspection()

    fun decodeAs(
        bytes: ByteArray,
        kind: SharedImportKind,
        displayName: String = "共享文件",
    ): SharedImportInspection = core.decodeAs(bytes, kind, displayName).toAndroidInspection()

    private fun SharedImportCoreInspection<ModelTemplatePackage>.toAndroidInspection(): SharedImportInspection =
        when (this) {
            is SharedImportCoreInspection.Character -> SharedImportInspection.Character(request)
            is SharedImportCoreInspection.Format -> SharedImportInspection.Format(packageData)
            is SharedImportCoreInspection.WorldBook -> SharedImportInspection.WorldBook(packageData)
            is SharedImportCoreInspection.ModelTemplate -> SharedImportInspection.ModelTemplate(packageData)
            is SharedImportCoreInspection.Image -> SharedImportInspection.Image(info)
            is SharedImportCoreInspection.Unknown -> SharedImportInspection.Unknown(textLike)
        }
}
