package com.kaiharimoto.mastertool.core.ai.skills

/**
 * A skill: know-how for one kind of task, in markdown, with a name and a line on
 * when to use it in front matter:
 *
 * ```
 * ---
 * name: format-webs
 * description: Building a web of decks for an event from recent results.
 * ---
 * 1. Ask the event's date and format…
 * ```
 *
 * Only the names and descriptions are in the prompt; the body is read with
 * `skill_view` when a task calls for it (progressive disclosure: twenty skills cost
 * twenty lines, not twenty pages). The app ships some ([builtIn]); Ai writes others
 * from experience, and the person may edit either kind of its own.
 */
data class Skill(val name: String, val description: String, val body: String, val builtIn: Boolean = false) {
    fun render(): String = "---\nname: $name\ndescription: $description\n---\n\n${body.trim()}\n"
}

object Skills {
    /** Reads a SKILL.md; null when it has no name. [fallbackName] is its folder's. */
    fun parse(text: String, fallbackName: String? = null, builtIn: Boolean = false): Skill? {
        val normalized = text.replace("\r\n", "\n")
        var name = fallbackName
        var description = ""
        var body = normalized
        if (normalized.startsWith("---\n")) {
            val end = normalized.indexOf("\n---", 4)
            if (end > 0) {
                normalized.substring(4, end).lines().forEach { line ->
                    val key = line.substringBefore(':').trim().lowercase()
                    val value = line.substringAfter(':', "").trim().trim('"')
                    when (key) {
                        "name" -> if (value.isNotBlank()) name = value
                        "description" -> description = value
                    }
                }
                body = normalized.substring(end + 4).trimStart('\n')
            }
        }
        val n = name?.let(::slug)?.takeIf { it.isNotBlank() } ?: return null
        return Skill(n, description.ifBlank { firstLine(body) }, body.trim(), builtIn)
    }

    /** A skill's name as a file name: lowercase words joined by dashes. */
    fun slug(name: String): String =
        name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60)

    /** The prompt's list: one line a skill. */
    fun index(skills: List<Skill>): String =
        skills.sortedBy { it.name }.joinToString("\n") { "- ${it.name}: ${it.description}" + if (!it.builtIn) " (yours)" else "" }

    /**
     * The skills in force: the person's and Ai's own over the app's where names meet,
     * so a built-in skill can be improved in place by writing one of the same name.
     */
    fun merge(builtIn: List<Skill>, own: List<Skill>): List<Skill> {
        val mine = own.associateBy { it.name }
        return (builtIn.filter { it.name !in mine } + own).sortedBy { it.name }
    }

    private fun firstLine(body: String) = body.lines().firstOrNull { it.isNotBlank() }?.trim()?.trimStart('#', ' ')?.take(120) ?: ""
}
