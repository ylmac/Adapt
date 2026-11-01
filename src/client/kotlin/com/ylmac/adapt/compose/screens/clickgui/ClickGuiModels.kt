package com.ylmac.adapt.compose.screens.clickgui

import com.google.gson.JsonParser
import com.ylmac.adapt.render.clickgui.ClickGuiBridge

data class ClickGuiModule(
    val name: String,
    var enabled: Boolean,
    var tag: String,
    val desc: String,
)

data class ClickGuiCategory(
    val name: String,
    val icon: String,
    val modules: List<ClickGuiModule>,
)

data class ClickGuiSetting(
    val name: String,
    val type: String,
    var value: Any?,
    val min: Double = 0.0,
    val max: Double = 1.0,
    val step: Double = 1.0,
    val options: List<String> = emptyList(),
    var colorR: Int = 136,
    var colorG: Int = 136,
    var colorB: Int = 136,
)

object ClickGuiData {
    fun loadCategories(bridge: ClickGuiBridge = ClickGuiBridge.INSTANCE): List<ClickGuiCategory> {
        return try {
            val arr = JsonParser.parseString(bridge.categoriesJson).asJsonArray
            arr.map { el ->
                val o = el.asJsonObject
                val mods = o.getAsJsonArray("modules").map { m ->
                    val mo = m.asJsonObject
                    ClickGuiModule(
                        name = mo.get("name").asString,
                        enabled = mo.get("enabled").asBoolean,
                        tag = mo.get("tag")?.asString ?: "",
                        desc = mo.get("desc")?.asString ?: "",
                    )
                }
                ClickGuiCategory(
                    name = o.get("name").asString,
                    icon = o.get("icon")?.asString ?: "",
                    modules = mods,
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun syncEnabled(categories: List<ClickGuiCategory>, bridge: ClickGuiBridge = ClickGuiBridge.INSTANCE) {
        for (cat in categories) {
            for (mod in cat.modules) {
                mod.enabled = bridge.isModuleEnabled(mod.name)
                mod.tag = bridge.getModuleTag(mod.name) ?: ""
            }
        }
    }

    fun loadSettings(moduleName: String, bridge: ClickGuiBridge = ClickGuiBridge.INSTANCE): List<ClickGuiSetting> {
        return try {
            val arr = JsonParser.parseString(bridge.getModuleSettings(moduleName)).asJsonArray
            arr.map { el ->
                val o = el.asJsonObject
                val type = o.get("type").asString
                val options = o.getAsJsonArray("options")?.map { it.asString } ?: emptyList()
                val color = o.getAsJsonObject("color")
                ClickGuiSetting(
                    name = o.get("name").asString,
                    type = type,
                    value = when {
                        o.has("value") && o.get("value").isJsonPrimitive -> {
                            val p = o.get("value").asJsonPrimitive
                            when {
                                p.isBoolean -> p.asBoolean
                                p.isNumber -> p.asDouble
                                else -> p.asString
                            }
                        }
                        else -> null
                    },
                    min = o.get("min")?.asDouble ?: 0.0,
                    max = o.get("max")?.asDouble ?: 1.0,
                    step = o.get("step")?.asDouble ?: 1.0,
                    options = options,
                    colorR = color?.get("r")?.asInt ?: 136,
                    colorG = color?.get("g")?.asInt ?: 136,
                    colorB = color?.get("b")?.asInt ?: 136,
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
