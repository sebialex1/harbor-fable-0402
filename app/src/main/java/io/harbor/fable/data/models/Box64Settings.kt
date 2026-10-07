package io.harbor.fable.data.models

/**
 * Box64 dynarec presets, modelled on Winlator's `Box64PresetManager` (STABILITY, COMPATIBILITY,
 * INTERMEDIATE, PERFORMANCE). Each preset is a complete set of `BOX64_*` values, so switching
 * presets never leaves a value from the previous one behind. [BALANCED] is Winlator's
 * "Intermediate".
 *
 * The values are Winlator-Ludashi's, with two additions every preset sets explicitly:
 * `BOX64_DYNAREC=1` and `BOX64_DYNAREC_BLEEDING_EDGE=1` (Box64's own default: detect Unity's
 * MonoBleedingEdge runtime and apply its safer settings to it).
 */
enum class Box64Preset(val id: String, val label: String, val description: String) {
    STABILITY(
        id = "stability",
        label = "Stability",
        description = "Strictest memory ordering, no wait on blocks. Slowest; for games that crash",
    ),
    COMPATIBILITY(
        id = "compatibility",
        label = "Compatibility",
        description = "Winlator's default: safe flags, strong memory ordering, small blocks",
    ),
    BALANCED(
        id = "balanced",
        label = "Balanced",
        description = "Bigger blocks, fast NaN, call/return optimisation (Winlator's Intermediate)",
    ),
    PERFORMANCE(
        id = "performance",
        label = "Performance",
        description = "Fastest. Relaxed flags and x87 precision; may break some games",
    ),
    ;

    /** Every `BOX64_*` variable this preset sets, in launch-log order. */
    val variables: Map<String, String>
        get() = when (this) {
            STABILITY -> preset(
                safeFlags = "2", fastNan = "0", fastRound = "0", x87Double = "1", bigBlock = "0",
                strongMem = "2", forward = "128", callRet = "0", wait = "0", unityPlayer = "1", mmap32 = "0",
            )
            COMPATIBILITY -> preset(
                safeFlags = "2", fastNan = "0", fastRound = "0", x87Double = "1", bigBlock = "0",
                strongMem = "1", forward = "128", callRet = "0", wait = "1", unityPlayer = "1", mmap32 = "0",
            )
            BALANCED -> preset(
                safeFlags = "2", fastNan = "1", fastRound = "0", x87Double = "1", bigBlock = "1",
                strongMem = "0", forward = "128", callRet = "1", wait = "1", unityPlayer = "0", mmap32 = "1",
            )
            PERFORMANCE -> preset(
                safeFlags = "1", fastNan = "1", fastRound = "1", x87Double = "0", bigBlock = "3",
                strongMem = "0", forward = "512", callRet = "1", wait = "1", unityPlayer = "0", mmap32 = "1",
            )
        }

    companion object {
        /** What Winlator gives a new container, and what Fable used before presets existed. */
        val DEFAULT = COMPATIBILITY

        /** [id] (or the enum name, or Winlator's "intermediate") to a preset; unknown ids map to [DEFAULT]. */
        fun fromId(id: String?): Box64Preset {
            val key = id?.trim().orEmpty()
            if (key.equals("intermediate", ignoreCase = true)) return BALANCED
            return entries.firstOrNull { it.id.equals(key, ignoreCase = true) || it.name.equals(key, ignoreCase = true) }
                ?: DEFAULT
        }

        private fun preset(
            safeFlags: String,
            fastNan: String,
            fastRound: String,
            x87Double: String,
            bigBlock: String,
            strongMem: String,
            forward: String,
            callRet: String,
            wait: String,
            unityPlayer: String,
            mmap32: String,
        ): Map<String, String> = linkedMapOf(
            Box64Options.DYNAREC to "1",
            Box64Options.SAFEFLAGS to safeFlags,
            Box64Options.FASTNAN to fastNan,
            Box64Options.FASTROUND to fastRound,
            Box64Options.X87DOUBLE to x87Double,
            Box64Options.BIGBLOCK to bigBlock,
            Box64Options.STRONGMEM to strongMem,
            Box64Options.FORWARD to forward,
            Box64Options.CALLRET to callRet,
            Box64Options.WAIT to wait,
            Box64Options.BLEEDING_EDGE to "1",
            // Winlator turns AVX off in every preset; AVX2 is an explicit opt-in.
            Box64Options.AVX to "0",
            Box64Options.SSE42 to "1",
            Box64Options.UNITYPLAYER to unityPlayer,
            Box64Options.MMAP32 to mmap32,
            Box64Options.LOG to "0",
        )
    }
}

/** One value a [Box64Option] can take, with the label the settings screen shows for it. */
data class Box64Choice(val value: String, val label: String)

/**
 * A Box64 variable the container settings screen exposes. [isToggle] options take `0`/`1` and
 * are shown as a switch; the others as a list of [choices].
 */
data class Box64Option(
    val key: String,
    val label: String,
    val description: String,
    val choices: List<Box64Choice>,
) {
    val isToggle: Boolean get() = choices.map { it.value } == listOf("0", "1")

    fun labelFor(value: String): String = choices.firstOrNull { it.value == value }?.label ?: value
}

/** The `BOX64_*` variables Fable lets a container change, with what each value means. */
object Box64Options {
    const val DYNAREC = "BOX64_DYNAREC"
    const val SAFEFLAGS = "BOX64_DYNAREC_SAFEFLAGS"
    const val FASTNAN = "BOX64_DYNAREC_FASTNAN"
    const val FASTROUND = "BOX64_DYNAREC_FASTROUND"
    const val X87DOUBLE = "BOX64_DYNAREC_X87DOUBLE"
    const val BIGBLOCK = "BOX64_DYNAREC_BIGBLOCK"
    const val STRONGMEM = "BOX64_DYNAREC_STRONGMEM"
    const val FORWARD = "BOX64_DYNAREC_FORWARD"
    const val CALLRET = "BOX64_DYNAREC_CALLRET"
    const val WAIT = "BOX64_DYNAREC_WAIT"
    const val BLEEDING_EDGE = "BOX64_DYNAREC_BLEEDING_EDGE"
    const val AVX = "BOX64_AVX"
    const val SSE42 = "BOX64_SSE42"
    const val UNITYPLAYER = "BOX64_UNITYPLAYER"
    const val MMAP32 = "BOX64_MMAP32"
    const val LOG = "BOX64_LOG"

    private fun onOff(vararg labels: String) = listOf(Box64Choice("0", labels[0]), Box64Choice("1", labels[1]))

    /** Display order on the settings screen: the dynarec switch first, logging last. */
    val all: List<Box64Option> = listOf(
        Box64Option(DYNAREC, "Dynarec", "Recompile x86_64 code to ARM64. Off interprets everything (very slow)", onOff("Off", "On")),
        Box64Option(
            SAFEFLAGS, "Safe flags", "How carefully CPU flags are kept across calls and returns",
            listOf(Box64Choice("0", "Off"), Box64Choice("1", "On returns"), Box64Choice("2", "On calls and returns")),
        ),
        Box64Option(
            STRONGMEM, "Strong memory", "Memory ordering between threads. Higher is safer and slower",
            listOf(Box64Choice("0", "Off"), Box64Choice("1", "Writes"), Box64Choice("2", "Writes + reads"), Box64Choice("3", "Full")),
        ),
        Box64Option(
            BIGBLOCK, "Big blocks", "How much code is translated as one block. Higher is faster",
            listOf(Box64Choice("0", "Small"), Box64Choice("1", "Normal"), Box64Choice("2", "Big"), Box64Choice("3", "Biggest")),
        ),
        Box64Option(
            FORWARD, "Forward bytes", "How far a block may extend past a jump",
            listOf("0", "128", "256", "512", "1024").map { Box64Choice(it, it) },
        ),
        Box64Option(CALLRET, "Call/return optimisation", "Faster calls; breaks self-modifying code", onOff("Off", "On")),
        Box64Option(FASTNAN, "Fast NaN", "Skip x86 NaN sign handling", onOff("Off", "On")),
        Box64Option(
            FASTROUND, "Fast rounding", "Skip exact x86 float-to-int rounding",
            listOf(Box64Choice("0", "Exact"), Box64Choice("1", "Fast"), Box64Choice("2", "Fast, keep rounding mode")),
        ),
        Box64Option(
            X87DOUBLE, "x87 precision", "Run x87 math in double precision",
            listOf(Box64Choice("0", "Off"), Box64Choice("1", "Double"), Box64Choice("2", "Double, check overflow")),
        ),
        Box64Option(WAIT, "Wait for blocks", "Wait for another thread's translation instead of interpreting", onOff("Off", "On")),
        Box64Option(BLEEDING_EDGE, "Mono BleedingEdge fix", "Safer settings for Unity's MonoBleedingEdge runtime", onOff("Off", "On")),
        Box64Option(UNITYPLAYER, "Unity player fix", "Strong memory ordering when UnityPlayer.dll loads", onOff("Off", "On")),
        Box64Option(
            AVX, "AVX", "Which AVX extensions the emulated CPU reports",
            listOf(Box64Choice("0", "Off"), Box64Choice("1", "AVX"), Box64Choice("2", "AVX + AVX2")),
        ),
        Box64Option(SSE42, "SSE 4.2", "Report SSE 4.2 to programs", onOff("Off", "On")),
        Box64Option(MMAP32, "32-bit mappings", "Keep memory below 4 GB for WoW64 programs", onOff("Off", "On")),
        Box64Option(
            LOG, "Logging", "Box64 messages in the launch log",
            listOf(Box64Choice("0", "Off"), Box64Choice("1", "Info"), Box64Choice("2", "Debug"), Box64Choice("3", "Dump")),
        ),
    )

    val keys: Set<String> = all.map { it.key }.toSet()

    fun option(key: String): Box64Option? = all.firstOrNull { it.key == key }
}

/**
 * A container's Box64 configuration: a [preset] plus the variables the user changed on top of it
 * ([overrides], `BOX64_*` name to value). Selecting another preset clears the overrides.
 * [useRcFile] lets Box64 read a `box64rc` file (per-game fixes from the Box64 package, or
 * `<container>/.box64rc`), which Fable otherwise switches off with `BOX64_NORCFILES=1`.
 */
data class Box64Settings(
    val preset: Box64Preset = Box64Preset.DEFAULT,
    val overrides: Map<String, String> = emptyMap(),
    val useRcFile: Boolean = false,
) {
    /** The preset's values with [overrides] applied. Unknown override keys are ignored. */
    val values: Map<String, String>
        get() = LinkedHashMap(preset.variables).apply {
            overrides.forEach { (key, value) -> if (key in Box64Options.keys) put(key, value) }
        }

    val isCustomized: Boolean get() = overrides.isNotEmpty()

    fun value(key: String): String = values[key].orEmpty()

    /** This configuration with [key] set to [value]; an override equal to the preset's value is dropped. */
    fun with(key: String, value: String): Box64Settings {
        val next = LinkedHashMap(overrides)
        if (preset.variables[key] == value) next.remove(key) else next[key] = value
        return copy(overrides = next)
    }

    /** [preset] with no overrides. */
    fun withPreset(preset: Box64Preset): Box64Settings = copy(preset = preset, overrides = emptyMap())

    /** "Performance" or "Performance, 2 changes". */
    val summary: String
        get() = if (overrides.isEmpty()) preset.label else "${preset.label}, ${overrides.size} change${if (overrides.size == 1) "" else "s"}"

    /**
     * `KEY=VALUE` pairs for the Wine process: the base variables every Fable launch needs
     * (banner, GLX, no dynarec cache, rc file handling), then [values]. [rcFile] is the `box64rc`
     * shipped with the Box64 package, used when [useRcFile] is on. With logging on, the banner
     * and missing-opcode reports are printed too (Winlator's "enable Box64 logs").
     */
    fun environment(rcFile: String? = null): List<String> = buildList {
        val logging = value(Box64Options.LOG).let { it.isNotEmpty() && it != "0" }
        add("BOX64_NOBANNER=${if (logging) "0" else "1"}")
        if (logging) add("BOX64_DYNAREC_MISSING=1")
        // Box64's wrapped libX11 advertises GLX.
        add("BOX64_X11GLX=1")
        // Winlator's GuestProgramLauncherComponent sets this before every launch: no on-disk
        // dynarec cache. Box64 would otherwise write cache files into the container (or fail
        // trying, on Android's app-private storage), and a stale cache can hide a preset change.
        add("BOX64_DYNACACHE=0")
        if (useRcFile) {
            rcFile?.let { add("BOX64_RCFILE=$it") }
        } else {
            // There is no /etc/box64.box64rc on Android; don't let Box64 go looking for one.
            add("BOX64_NORCFILES=1")
        }
        values.forEach { (key, value) -> add("$key=$value") }
    }
}
