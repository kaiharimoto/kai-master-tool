package com.kaiharimoto.mastertool.core.present.record

/**
 * The cameras a system has, read off what FFmpeg prints when asked to list them (1.1.13): DirectShow on Windows and
 * AVFoundation on a Mac only print, they return nothing — so the words are the answer, and their reading is tested
 * here on what each prints. Linux names its cameras in `/sys/class/video4linux`, read directly.
 */
object CameraNames {
    /** One device: its [name] as the person sees it, and what FFmpeg is asked to open ([input]). */
    data class Device(val name: String, val input: String)

    /** The `[dshow @ 0x…]`-style prefix FFmpeg puts before each line. */
    private val prefix = Regex("^\\[[^\\]]*@\\s*[0-9a-fA-Fx]+]\\s?")

    private fun lines(log: String): List<String> = log.lines().map { it.replace(prefix, "").trimEnd() }

    /**
     * DirectShow's cameras (`-list_devices true -f dshow -i dummy`), in either way FFmpeg has printed them: a
     * `"name" (video)` line each since FFmpeg 5, or a "DirectShow video devices" heading before 5. Opened as
     * `video=name`.
     */
    fun dshow(log: String, audio: Boolean = false): List<Device> {
        val out = ArrayList<String>()
        var section: String? = null
        val tagged = Regex("^\\s*\"(.+)\"\\s*\\((video|audio|none|video,audio|audio,video)\\)\\s*$")
        val bare = Regex("^\\s*\"(.+)\"\\s*$")
        for (raw in lines(log)) {
            val line = raw.trim()
            when {
                line.startsWith("DirectShow video devices", ignoreCase = true) -> section = "video"
                line.startsWith("DirectShow audio devices", ignoreCase = true) -> section = "audio"
                line.startsWith("Alternative name", ignoreCase = true) -> Unit
                else -> {
                    val t = tagged.find(raw)
                    if (t != null) {
                        val kinds = t.groupValues[2]
                        if (if (audio) "audio" in kinds else "video" in kinds) out += t.groupValues[1]
                    } else {
                        bare.find(raw)?.let { m -> if (section == (if (audio) "audio" else "video")) out += m.groupValues[1] }
                    }
                }
            }
        }
        return out.distinct().map { Device(it, (if (audio) "audio=" else "video=") + it) }
    }

    /**
     * AVFoundation's cameras (`-f avfoundation -list_devices true -i ""`): `[n] name` under "AVFoundation video
     * devices", leaving out the screens it offers beside them. Opened by index, `n:none`, since names repeat.
     */
    fun avfoundation(log: String): List<Device> {
        val out = ArrayList<Device>()
        var video = false
        val item = Regex("^\\[(\\d+)]\\s+(.+)$")
        for (raw in lines(log)) {
            val line = raw.trim()
            when {
                line.startsWith("AVFoundation video devices", ignoreCase = true) -> video = true
                line.startsWith("AVFoundation audio devices", ignoreCase = true) -> video = false
                video -> item.find(line)?.let { m ->
                    val name = m.groupValues[2].trim()
                    if (!name.startsWith("Capture screen", ignoreCase = true)) out += Device(name, "${m.groupValues[1]}:none")
                }
            }
        }
        return out
    }

    /**
     * Linux's cameras from `/sys/class/video4linux`: [nodes] maps each `videoN` to the name in its `name` file. A
     * camera often has two nodes (the picture and its metadata) with one name: the first of each name is kept.
     */
    fun video4linux(nodes: Map<String, String>): List<Device> =
        nodes.entries.sortedBy { it.key.removePrefix("video").toIntOrNull() ?: Int.MAX_VALUE }
            .distinctBy { it.value.trim() }
            .map { (node, name) -> Device(name.trim().ifBlank { node }, "/dev/$node") }

    /** [wanted] among [devices] by name, else the first: a camera unplugged since falls back, never fails. */
    fun choose(devices: List<Device>, wanted: String?): Device? =
        devices.firstOrNull { it.name == wanted } ?: devices.firstOrNull()
}
