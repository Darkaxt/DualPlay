/*
 * DualPlay — settings model. GPL-3.0-or-later.
 */
package dev.darkaxt.dualplay.settings

import android.content.Context
import android.util.Log
import com.swordfish.libretrodroid.Variable
import dev.darkaxt.dualplay.CoreSpec
import dev.darkaxt.dualplay.GameActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Folder of the bundled presets under `files/shaders`. */
const val BUILTIN_SHADERS = "builtin"

/** One setting: a frontend setting (key prefix `dualplay_`) or a core option reported by the core. */
data class OptionInfo(
    val key: String,
    val label: String,
    val values: List<String>,
    val default: String,
    /** The core reads it only at load ("(Restart)" in its label). */
    val restart: Boolean = false,
)

/** Where a resolved value came from. Later entries win. */
enum class Source { DEFAULT, CORE, GAME, LOCKED }

data class Resolved(val values: Map<String, String>, val sources: Map<String, Source>) {
    operator fun get(key: String): String? = values[key]
    fun int(key: String): Int = values[key]?.toIntOrNull() ?: 0
    fun flag(key: String): Boolean = values[key] == ON
}

const val ON = "on"
const val OFF = "off"

/** Frontend settings shared by every core. */
object Frontend {
    const val SHADER = "dualplay_shader"
    const val REWIND = "dualplay_rewind"
    const val REWIND_MB = "dualplay_rewind_mb"
    const val RUN_AHEAD = "dualplay_runahead"
    const val FF_SPEED = "dualplay_ff_speed"
    const val ACHIEVEMENTS = "dualplay_achievements"
    const val NETCMD = "dualplay_netcmd"

    fun isFrontend(key: String) = key.startsWith("dualplay_")

    fun options(shaders: List<String>): List<OptionInfo> = listOf(
        OptionInfo(SHADER, "Shader", listOf("") + shaders, ""),
        OptionInfo(REWIND, "Rewind", listOf(OFF, ON), OFF),
        OptionInfo(REWIND_MB, "Rewind buffer (MB)", listOf("32", "64", "128", "256"), "64"),
        OptionInfo(RUN_AHEAD, "Run-ahead frames", listOf("0", "1", "2", "3", "4"), "0"),
        OptionInfo(FF_SPEED, "Fast-forward speed", listOf("2", "3", "4", "6", "8"), "3"),
        OptionInfo(ACHIEVEMENTS, "RetroAchievements (Restart)", listOf(OFF, ON), OFF, restart = true),
        OptionInfo(NETCMD, "DualDex link, read-only (Restart)", listOf(ON, OFF), ON, restart = true),
    )
}

/**
 * Settings files under `files/settings`:
 * - `core/<core>.json` and `game/<core>/<game>.json`: `{key: value}` layers (game > core > defaults);
 * - `catalog/<core>.json`: the core's options as last reported by the core (labels, values, defaults).
 * The core's pinned options ([CoreSpec.options]) are locked and override every layer.
 */
class SettingsStore(private val context: Context) {

    private val root = File(context.filesDir, "settings")
    private val shaderRoot = File(context.filesDir, "shaders")

    init {
        installBuiltinShaders()
    }

    /**
     * Copies the bundled presets (`assets/shaders/builtin`, licences in its LICENSES.md) to
     * `files/shaders/builtin` once per installed APK. User presets elsewhere under `files/shaders` stay.
     */
    private fun installBuiltinShaders() {
        synchronized(SettingsStore::class.java) {
            val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
            val dir = File(shaderRoot, BUILTIN_SHADERS)
            val stampFile = File(dir, ".installed")
            if (stampFile.isFile && stampFile.readText() == stamp) return
            dir.deleteRecursively()
            val count = copyAssets("shaders/$BUILTIN_SHADERS", dir)
            stampFile.writeText(stamp)
            Log.i(GameActivity.TAG, "builtin shaders installed files=$count")
        }
    }

    private fun copyAssets(path: String, target: File): Int {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
            return 1
        }
        target.mkdirs()
        return children.sumOf { copyAssets("$path/$it", File(target, it)) }
    }

    /** Slang presets under `files/shaders`, as paths relative to it. */
    fun shaderPresets(): List<String> =
        shaderRoot.walkTopDown().filter { it.isFile && it.extension == "slangp" }
            .map { it.relativeTo(shaderRoot).invariantSeparatorsPath }.sorted().toList()

    fun shaderFile(relative: String): File = File(shaderRoot, relative)

    fun frontendOptions(): List<OptionInfo> = Frontend.options(shaderPresets())

    // ---- Layers ----

    fun coreLayer(core: CoreSpec): Map<String, String> = readMap(coreFile(core))
    fun gameLayer(core: CoreSpec, game: String): Map<String, String> = readMap(gameFile(core, game))

    /** Merges [changes] into the core layer. Locked keys are ignored. */
    fun saveCore(core: CoreSpec, changes: Map<String, String>) =
        writeMap(coreFile(core), coreLayer(core) + changes.filterKeys { it !in core.options })

    /** Merges [changes] into the game layer. Locked keys are ignored. */
    fun saveGame(core: CoreSpec, game: String, changes: Map<String, String>) =
        writeMap(gameFile(core, game), gameLayer(core, game) + changes.filterKeys { it !in core.options })

    /** Removes [keys] from the game layer, so the core level applies to this game again. */
    fun clearGame(core: CoreSpec, game: String, keys: Set<String>) {
        val layer = gameLayer(core, game)
        if (layer.keys.any { it in keys }) writeMap(gameFile(core, game), layer - keys)
    }

    /** Defaults < core layer < game layer < locked core options. */
    fun resolve(core: CoreSpec, game: String?): Resolved {
        val values = LinkedHashMap<String, String>()
        val sources = HashMap<String, Source>()
        fun put(map: Map<String, String>, source: Source) = map.forEach { (k, v) -> values[k] = v; sources[k] = source }
        put(frontendOptions().associate { it.key to it.default }, Source.DEFAULT)
        put(catalog(core).orEmpty().associate { it.key to it.default }, Source.DEFAULT)
        put(coreLayer(core), Source.CORE)
        if (game != null) put(gameLayer(core, game), Source.GAME)
        put(core.options, Source.LOCKED)
        return Resolved(values, sources)
    }

    /** Core options to pass at load: everything set in a layer or locked (the core defaults the rest). */
    fun launchVariables(core: CoreSpec, resolved: Resolved): Array<Variable> =
        resolved.values.filter { (k, _) -> !Frontend.isFrontend(k) && resolved.sources[k] != Source.DEFAULT }
            .map { Variable(it.key, it.value) }.toTypedArray()

    // ---- Catalog ----

    fun catalog(core: CoreSpec): List<OptionInfo>? {
        val file = catalogFile(core)
        if (!file.isFile) return null
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val values = o.getJSONArray("values").let { v -> (0 until v.length()).map(v::getString) }
                OptionInfo(o.getString("key"), o.getString("label"), values, o.getString("default"), o.optBoolean("restart"))
            }
        } catch (error: Exception) {
            Log.w(GameActivity.TAG, "catalog unreadable ${file.name}", error)
            null
        }
    }

    /**
     * Stores the options the running core reports. libretro v0 descriptions read `Label; default|other|...`,
     * so the first value is the default.
     */
    fun saveCatalog(core: CoreSpec, variables: Array<Variable>): Int {
        val options = variables.mapNotNull { v ->
            val key = v.key ?: return@mapNotNull null
            val description = v.description ?: return@mapNotNull null
            val split = description.indexOf("; ")
            if (split < 0) return@mapNotNull null
            val label = description.substring(0, split).trim()
            val values = description.substring(split + 2).split('|').filter(String::isNotEmpty)
            if (values.isEmpty()) return@mapNotNull null
            OptionInfo(key, label, values, values.first(), restart = label.contains("(Restart)", ignoreCase = true))
        }.sortedBy { it.key }
        val array = JSONArray()
        options.forEach { o ->
            array.put(JSONObject().put("key", o.key).put("label", o.label).put("values", JSONArray(o.values))
                .put("default", o.default).put("restart", o.restart))
        }
        writeText(catalogFile(core), array.toString(1))
        return options.size
    }

    // ---- Files ----

    private fun coreFile(core: CoreSpec) = File(root, "core/${core.id}.json")
    private fun gameFile(core: CoreSpec, game: String) = File(root, "game/${core.id}/${GameActivity.safeName(game)}.json")
    private fun catalogFile(core: CoreSpec) = File(root, "catalog/${core.id}.json")

    private fun readMap(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()
        return try {
            val o = JSONObject(file.readText())
            o.keys().asSequence().associateWith { o.getString(it) }
        } catch (error: Exception) {
            Log.w(GameActivity.TAG, "settings unreadable ${file.path}", error)
            emptyMap()
        }
    }

    private fun writeMap(file: File, map: Map<String, String>) =
        writeText(file, JSONObject(map.toSortedMap() as Map<*, *>).toString(1))

    @Synchronized
    private fun writeText(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }
}
