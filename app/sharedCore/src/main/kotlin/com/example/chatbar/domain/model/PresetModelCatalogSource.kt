package com.example.chatbar.domain.model

import com.example.chatbar.data.local.entity.PresetModelCatalog

/** JVM-neutral preset data required by model resolution. */
interface PresetModelCatalogSource {
    val catalog: PresetModelCatalog
    val modelCatalogVersion: Int?
}
