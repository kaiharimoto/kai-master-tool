package com.kaiharimoto.neue.ai.course

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.ai.AgentEvent
import com.kaiharimoto.mastertool.core.ai.AgentLoop
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.CourseTools
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.ToolArgs
import com.kaiharimoto.mastertool.core.ai.ToolRunner
import com.kaiharimoto.mastertool.core.ai.TurnRequest
import com.kaiharimoto.mastertool.core.ai.Usage
import com.kaiharimoto.mastertool.core.ai.course.BrowseGuard
import com.kaiharimoto.mastertool.core.ai.course.CaptionCues
import com.kaiharimoto.mastertool.core.ai.course.KeyFrames
import com.kaiharimoto.mastertool.core.ai.course.Transcript
import com.kaiharimoto.mastertool.core.ai.vision.Vision
import com.kaiharimoto.mastertool.core.ai.voice.VoiceModel
import com.kaiharimoto.neue.browser.VideoListening
import com.kaiharimoto.mastertool.core.ai.course.Chapter
import com.kaiharimoto.mastertool.core.ai.course.Chapters
import com.kaiharimoto.mastertool.core.ai.course.Course
import com.kaiharimoto.mastertool.core.ai.course.CourseBrief
import com.kaiharimoto.mastertool.core.ai.course.CourseCodec
import com.kaiharimoto.mastertool.core.ai.course.CoursePaths
import com.kaiharimoto.mastertool.core.ai.course.CourseText
import com.kaiharimoto.mastertool.core.ai.course.HumanPace
import com.kaiharimoto.mastertool.core.ai.course.PageElement
import com.kaiharimoto.mastertool.core.ai.course.StudyQueue
import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import com.kaiharimoto.mastertool.core.ai.memory.MemoryKind
import com.kaiharimoto.mastertool.core.ai.memory.MemoryReview
import com.kaiharimoto.mastertool.core.ai.web.HtmlText
import com.kaiharimoto.mastertool.core.ai.web.Untrusted
import com.kaiharimoto.neue.ai.AiState
import com.kaiharimoto.neue.ai.MetaAnswer
import com.kaiharimoto.neue.ai.budgetFor
import com.kaiharimoto.neue.ai.closeBackend
import com.kaiharimoto.neue.ai.newBackend
import com.kaiharimoto.neue.ai.ownMcp
import com.kaiharimoto.neue.ai.runsAsCli
import com.kaiharimoto.neue.ai.offerCourseReview
import com.kaiharimoto.neue.browser.WebSurface
import com.kaiharimoto.neue.browser.WebSurfaces
import com.kaiharimoto.neue.platform.Platform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.random.Random

/**
 * Study a course (kai, 2026-10: Chessy learns a Metafy guide "on its own without human intervention"): the holder that
 * walks one course from its contents to the deck's guide, a step at a time ([StudyQueue]), in the person's own browser
 * ([WebSurface]) — one course at a time, off the frame thread, saved after every step so it goes on where it stopped.
 *
 * The person does two things: pastes the guide's address and logs in once in the browser window ([prepare], [begin]).
 * Everything after is the study's; what it wrote waits for the person's review ([offerReview]).
 */
class CourseStudies(private val ai: AiState) {
    /** The course on screen: being studied, waiting for the login, or the last one studied. */
    var current by mutableStateOf<Course?>(null)
        private set

    /** What it is doing, in words, for the panel. */
    var line by mutableStateOf("")
        private set

    /** The browser is open on the course and waits for the person to log in and press Begin. */
    var awaitingLogin by mutableStateOf(false)
        private set

    val running: Boolean get() = job?.isActive == true

    /** A problem the person should see, when the study could not start or stopped. */
    var problem by mutableStateOf<String?>(null)

    private var job: Job? = null
    private var browser: WebSurface? = null
    private var lastLoad = 0L
    private val lock = Mutex()

    /** The last elements listed, by ref: what browser_click presses. */
    private var shown: Map<Int, PageElement> = emptyMap()

    private val files get() = ai.files

    /** Where the study's browser keeps its profile: the person's login to the course, on this device alone. */
    val profile: File get() = File(Platform.dataDir, PROFILE)

    // ---- the person's two steps ------------------------------------------------

    /**
     * A course to study, from its contents page [start], for the deck open in the builder: saved, and the browser opened
     * on it for the person to log in. Null when it could not start, [problem] saying why.
     */
    fun prepare(start: String): Course? {
        problem = null
        val url = start.trim().let { if (it.startsWith("http://", true)) "https://" + it.substringAfter("://") else it }
        val deckId = ai.h.builder.deckId ?: return null.also { problem = "Save the deck first: the course is studied for its guide." }
        if (!url.startsWith("https://", ignoreCase = true) || BrowseGuard.host(url).isBlank()) return null.also { problem = "That is not a course's address." }
        WebSurfaces.missing(ai.prefs.courseBrowser)?.let { return null.also { _ -> problem = it } }
        if (running) return null.also { problem = "A course is being studied already: stop it first." }
        val deckName = ai.h.builder.deckName
        val now = System.currentTimeMillis()
        val guide = files.read(AiMemory.path(MemoryKind.GUIDE, deckId))
        val course = Course(
            id = CoursePaths.idFor(url, now), start = url, deckId = deckId, deckName = deckName,
            createdAt = now, updatedAt = now, cap = ai.prefs.courseCap, guideBefore = guide.orEmpty(),
        )
        save(course)
        current = course
        awaitingLogin = true
        line = "Log in to the course in the browser window, then press Begin."
        ai.scope.launch {
            runCatching { openBrowser(course) }.onFailure {
                problem = it.message ?: "The browser did not open."
                awaitingLogin = false
            }
        }
        return course
    }

    /** The person is logged in: the study goes on alone from here. */
    fun begin() {
        val course = current ?: return
        awaitingLogin = false
        run(course.copy(state = Course.State.STUDYING, note = ""))
    }

    fun pause() {
        val c = current ?: return
        job?.cancel()
        save(c.copy(state = Course.State.PAUSED))
        line = "Paused."
    }

    fun resume() {
        val c = load(current?.id ?: return) ?: return
        run(c.copy(state = Course.State.STUDYING, note = ""))
    }

    /** Stops for good: the course stays as far as it got, its notes and guide entries kept for the review. */
    fun stop() {
        val c = current ?: return
        job?.cancel()
        awaitingLogin = false
        closeBrowser()
        save(c.copy(state = Course.State.BLOCKED, note = "Stopped."))
        offerReview(load(c.id) ?: c)
        current = null
        line = ""
    }

    /** The studies that were going when the app closed go on; a finished one not yet reviewed is offered. */
    fun reopen() {
        val all = courses()
        all.firstOrNull { it.state == Course.State.DONE && !it.reviewed }?.let { offerReview(it) }
        val going = all.firstOrNull { it.state == Course.State.STUDYING } ?: all.firstOrNull { it.state == Course.State.PAUSED } ?: return
        current = going
        line = StudyQueue.line(going)
        if (going.state == Course.State.STUDYING && ai.configured && WebSurfaces.missing(ai.prefs.courseBrowser) == null) run(going)
    }

    /** Every course studied or begun, newest first. */
    fun courses(): List<Course> = files.file(CoursePaths.ROOT).listFiles()?.mapNotNull { dir ->
        CourseCodec.read(File(dir, "course.json").takeIf { it.isFile }?.readText())
    }.orEmpty().sortedByDescending { it.updatedAt }

    // ---- the study --------------------------------------------------------------

    private fun run(start: Course) {
        if (running) return
        problem = null
        save(start)
        current = start
        job = ai.scope.launch {
            try {
                withContext(Dispatchers.IO) { loop(start.id) }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                val c = load(start.id) ?: start
                block(c, t.message ?: t::class.simpleName.orEmpty())
            } finally {
                // Paused, stopped, done or turned off: the browser goes with the study (the login stays in its profile).
                closeBrowser()
            }
        }.also { j ->
            ai.backgroundJobs.removeAll { it.isCompleted }
            ai.backgroundJobs += j
        }
    }

    private suspend fun loop(id: String) {
        while (true) {
            val course = load(id) ?: return
            current = course
            val step = StudyQueue.next(course, canWatch = VideoListening.missing(voiceModel) == null)
            line = StudyQueue.line(course, step)
            when (step) {
                is StudyQueue.Step.Waiting -> {
                    if (course.state == Course.State.STUDYING) save(course.copy(state = Course.State.BLOCKED, note = step.why))
                    closeBrowser()
                    return
                }
                StudyQueue.Step.Done -> {
                    finish(course)
                    return
                }
                StudyQueue.Step.List -> list(course)
                is StudyQueue.Step.Read -> read(course, step.n)
                is StudyQueue.Step.Notes -> notes(course, step.n)
                StudyQueue.Step.Distil -> distil(course)
            }
        }
    }

    /** The contents: read off the guide's own links, or by Ai when the page does not list them as links. */
    private suspend fun list(course: Course) {
        val page = paced { surface(course).open(course.start) }
        val found = Chapters.fromLinks(surface(course).links(), page.url.ifBlank { course.start })
        if (found.isNotEmpty()) {
            save(course.copy(chapters = found, listed = true, title = course.title.ifBlank { page.title }))
            return
        }
        step(course, CourseTools.STEP_LIST, CourseBrief.list(course))
        val after = load(course.id) ?: return
        // Nothing listed: the guide is the one page.
        if (!after.listed) save(after.copy(listed = true, title = after.title.ifBlank { page.title }, chapters = listOf(Chapter(1, page.title.ifBlank { "The guide" }, course.start))))
    }

    /** A chapter's text, kept: read by the app when the page shows it, by Ai when something must be pressed first. */
    private suspend fun read(course: Course, n: Int) {
        val chapter = course.chapter(n) ?: return
        BrowseGuard.openRefusal(chapter.url, course)?.let { return save(StudyQueue.failed(course, n, it)) }
        paced { surface(course).open(chapter.url) }
        val text = pageText()
        val words = CourseText.words(text)
        when {
            words >= ENOUGH_WORDS -> keep(course, n, text)
            surface(course).hasVideo() && words < VIDEO_WORDS -> watch(course, chapter, text)
            else -> {
                step(course, CourseTools.STEP_READ, CourseBrief.read(course, chapter, "the page showed only $words words."))
                val after = load(course.id) ?: return
                if (after.chapter(n)?.state != Chapter.State.READ) {
                    if (words > 0) keep(after, n, text) else save(StudyQueue.failed(after, n, "No words could be read on the page."))
                }
            }
        }
    }

    /**
     * A video chapter (Phase 2): its captions when the player has them, else its sound — recorded from the page as it
     * plays, muted, at [WATCH_RATE] — transcribed on the computer; and a picture at each new scene. Nothing is
     * downloaded: the browser plays the video as it would for the person. A video in another site's player is opened in
     * that player, with the chapter as the page that embeds it.
     */
    private suspend fun watch(course: Course, chapter: Chapter, pageWords: String) {
        val n = chapter.n
        val s = surface(course)
        var video = s.video() ?: return save(StudyQueue.failed(course, n, "The video would not load."))
        var c = course
        if (video.frame.isNotBlank()) {
            // The player is another site's, embedded by the course: it may be opened, from the chapter, and nowhere else.
            val host = BrowseGuard.host(video.frame).removePrefix("www.")
            if (host.isBlank() || !video.frame.startsWith("https://", ignoreCase = true)) return save(StudyQueue.failed(c, n, "The video's player is not on https."))
            if (host !in c.hosts) c = c.copy(hosts = c.hosts + host).also(::save)
            paced { s.open(video.frame, referrer = chapter.url) }
            video = s.video()?.takeIf { it.frame.isBlank() } ?: return save(StudyQueue.failed(c, n, "The video's player would not open by itself."))
        }
        line = "Watching chapter ${chapter.n}: ${chapter.title}"
        val cues = s.captions()
        val captioned = cues.firstOrNull { it.first < 0 }?.let { CaptionCues.parse(it.second) }
            ?: cues.map { (sec, words) -> Transcript.Line((sec * 1000).toLong(), words) }
        var transcript = Transcript.of(captioned)
        val listen = transcript.words < CAPTION_WORDS
        val model = voiceModel
        if (listen) VideoListening.missing(model)?.let { why ->
            files.write(CoursePaths.page(c.id, n), pageWords)
            return save(c.with(chapter.copy(kind = Chapter.Kind.VIDEO, state = Chapter.State.WAITING, error = why)))
        }
        if (video.protected && listen) return save(StudyQueue.failed(c, n, "The video is protected (DRM): its sound cannot be recorded, and it has no captions."))
        // Played whole either way: the pictures are kept as it goes, and the sound when there are no captions.
        val started = s.listen(WATCH_RATE)
        val sound = java.io.ByteArrayOutputStream()
        val shots = ArrayList<Pair<Long, ByteArray>>()
        if (started == "ok") {
            // As long as the video takes at its pace, and a minute more; never past the longest video it plays.
            val seconds = video.duration.takeIf { it > 0 }?.coerceAtMost(MAX_VIDEO_S.toDouble()) ?: MAX_VIDEO_S.toDouble()
            val limit = System.currentTimeMillis() + (seconds / WATCH_RATE * 1000).toLong() + 60_000
            var lastShot = 0L
            while (System.currentTimeMillis() < limit) {
                val now = s.video() ?: break
                if (now.ended) break
                if (System.currentTimeMillis() - lastShot >= SHOT_EVERY_MS) {
                    lastShot = System.currentTimeMillis()
                    s.videoFrame(SHOT_SCALE)?.let { shots += (now.time * 1000).toLong() to it }
                }
                if (listen) s.takeSound().forEach(sound::write) else s.takeSound()
                delay(1_000)
            }
            s.stopListening()
            if (listen) s.takeSound().forEach(sound::write)
        }
        if (listen && sound.size() > 0) {
            line = "Listening to chapter ${chapter.n}: ${chapter.title}"
            transcript = Transcript.of(VideoListening.transcribe(sound.toByteArray(), model, WATCH_RATE))
        }
        val kept = keepFrames(c.id, n, shots)
        if (transcript.words == 0 && kept.isEmpty()) {
            return save(StudyQueue.failed(c, n, if (started != "ok") "The video would not play: $started" else "Nothing could be heard or seen in the video."))
        }
        val text = transcript.render(chapter.title) +
            (if (kept.isNotEmpty()) "\n(${kept.size} pictures kept from the video, at " + kept.joinToString { Transcript.clock(it) } + ": course_frames.)\n" else "") +
            (if (CourseText.words(pageWords) > 20) "\n## On the page\n\n$pageWords" else "")
        files.write(CoursePaths.page(c.id, n), text)
        save(c.with(chapter.copy(kind = Chapter.Kind.VIDEO, state = Chapter.State.READ, words = CourseText.words(text), error = "")))
    }

    /** The shots worth keeping, saved as the chapter's frames; their times. */
    private fun keepFrames(id: String, n: Int, shots: List<Pair<Long, ByteArray>>): List<Long> {
        if (shots.isEmpty()) return emptyList()
        val thumbs = shots.map { (at, jpeg) -> at to thumbnail(jpeg) }
        val picked = KeyFrames.pick(thumbs).map { shots[it] }
        val dir = files.file(CoursePaths.frames(id, n))
        dir.deleteRecursively()
        dir.mkdirs()
        picked.forEach { (at, jpeg) -> File(dir, "$at.jpg").writeBytes(jpeg) }
        return picked.map { it.first }
    }

    /** A picture as a 32 × 18 grey thumbnail, for telling scenes apart. */
    private fun thumbnail(jpeg: ByteArray): ByteArray {
        val image = com.kaiharimoto.neue.platform.decodePicture(jpeg) ?: return ByteArray(0)
        val pixels = IntArray(image.width * image.height)
        image.readPixels(pixels)
        val w = 32
        val h = 18
        return ByteArray(w * h) { i ->
            val x = (i % w) * image.width / w
            val y = (i / w) * image.height / h
            val p = pixels[y * image.width + x]
            ((((p shr 16) and 0xff) * 3 + ((p shr 8) and 0xff) * 6 + (p and 0xff)) / 10).toByte()
        }
    }

    private val voiceModel: VoiceModel get() = VoiceModel.of(ai.prefs.voiceModel)

    private fun keep(course: Course, n: Int, text: String) {
        val chapter = course.chapter(n) ?: return
        files.write(CoursePaths.page(course.id, n), text)
        save(course.with(chapter.copy(kind = Chapter.Kind.TEXT, state = Chapter.State.READ, words = CourseText.words(text), error = "")))
    }

    private suspend fun notes(course: Course, n: Int) {
        val chapter = course.chapter(n) ?: return
        step(course, CourseTools.STEP_NOTES, CourseBrief.notes(course, chapter))
        val after = load(course.id) ?: return
        val written = files.read(CoursePaths.notes(course.id, n))
        save(
            if (written.isNullOrBlank()) StudyQueue.failed(after, n, "No notes were written.")
            else after.with((after.chapter(n) ?: chapter).copy(state = Chapter.State.NOTED, error = "")),
        )
    }

    private suspend fun distil(course: Course) {
        closeBrowser()
        val used = files.memory(MemoryKind.GUIDE, course.deckId, course.deckName).used
        val room = Triple(used, DISTIL_ROOM, "the course study")
        val filled = step(course, CourseTools.STEP_DISTIL, CourseBrief.distil(course), room)
        val after = load(course.id) ?: return
        save(after.copy(distilled = true, note = filled.orEmpty()))
    }

    private fun finish(course: Course) {
        closeBrowser()
        val done = course.copy(state = Course.State.DONE)
        save(done)
        line = StudyQueue.line(done, StudyQueue.Step.Done)
        offerReview(done)
    }

    /**
     * One step as a conversation of its own: the step's brief, its few tools ([CourseTools.forStep]), the host answering
     * for this study ([StudyRun]). Returns what the step was told when it used its room in the guide.
     */
    private suspend fun step(course: Course, kind: String, brief: String, room: Triple<Int, Int, String>? = null): String? {
        val connection = ai.prefs.connection ?: error("${ai.name} has no connection set up.")
        val names = CourseTools.forStep(kind)
        val offered = ai.tools.filter { it.name in names }
        val system = CourseBrief.system(ai.name, files.soul(ai.name), course)
        var turns = listOf(ChatTurn.user(brief))
        val run = StudyRun(course.id, course.deckId, course.deckName, { turns.drop(1) }, room)
        suspend fun answer(call: Part.ToolUse): Part.ToolResult =
            if (call.name.removePrefix("mcp__neue__") !in names) {
                Part.ToolResult(call.id, call.name, "${call.name} is not part of this step of the study.", isError = true)
            } else {
                ai.host.run(call)
            }
        // A coding plan's command-line app runs its own loop and reaches the tools over MCP: a server of the step's own,
        // offering only its tools and answering for this study — never the panel's, which answers for the conversation.
        val served = if (runsAsCli(connection)) {
            ai.ownMcp(offered) { call -> withContext(Dispatchers.Main + run) { answer(call).also { run.record(call, it) } } }
                ?: error("The app could not open the study's tools to the command-line app.")
        } else {
            null
        }
        val model = try {
            ai.newBackend(connection, served)
        } catch (t: Throwable) {
            served?.stop()
            throw t
        }
        try {
            val runner = ToolRunner { call -> answer(call) }
            var spent = Usage()
            withContext(run) {
                AgentLoop(model, runner, maxSteps = steps(kind), now = System::currentTimeMillis, budget = ai.budgetFor(connection))
                    .run(TurnRequest(system, turns, offered, connection.model, "medium"))
                    .collect { e ->
                        when (e) {
                            is AgentEvent.Appended -> turns = turns + e.turn
                            is AgentEvent.Round -> spent += e.usage
                            is AgentEvent.Failed -> error(e.message)
                            else -> Unit
                        }
                    }
            }
            load(course.id)?.let { c -> save(c.copy(spent = c.spent + spent.input + spent.output + spent.cacheRead + spent.cacheWrite)) }
            return run.filled
        } finally {
            closeBackend(model)
            served?.stop()
        }
    }

    private fun steps(kind: String): Int = when (kind) {
        CourseTools.STEP_LIST, CourseTools.STEP_READ -> 16
        CourseTools.STEP_NOTES -> 24
        else -> AgentLoop.MAX_STEPS * 2
    }

    // ---- the course's tools, for the host ----------------------------------------

    /** Answers a course study's tool [name] for [run]; null when [name] is not one of them. */
    internal suspend fun tool(name: String, i: JsonObject, run: StudyRun?): MetaAnswer? {
        if (name !in CourseTools.names) return null
        run ?: return fail("$name answers only while a course is studied.")
        val course = load(run.courseId) ?: return fail("The course is gone.")
        return try {
            when (name) {
                "course_state" -> ok(state(course), "Read the course's contents")
                "course_chapters" -> chapters(course, i)
                "course_frames" -> courseFrames(course, run, ToolArgs.int(i, "chapter") ?: 0, ToolArgs.int(i, "from") ?: 0)
                "course_read" -> courseRead(course, ToolArgs.int(i, "chapter") ?: 0, ToolArgs.string(i, "what") ?: "text", ToolArgs.int(i, "from") ?: 0)
                "course_notes" -> courseNotes(course, ToolArgs.int(i, "chapter") ?: 0, ToolArgs.string(i, "notes").orEmpty())
                "course_page_save" -> pageSave(course, ToolArgs.int(i, "chapter") ?: 0)
                "browser_open" -> browserOpen(course, ToolArgs.string(i, "url").orEmpty())
                "browser_read" -> browserRead(course, ToolArgs.int(i, "from") ?: 0)
                "browser_elements" -> browserElements(course, ToolArgs.string(i, "near"))
                "browser_click" -> browserClick(course, ToolArgs.int(i, "ref"), ToolArgs.int(i, "x"), ToolArgs.int(i, "y"))
                "browser_scroll" -> {
                    surface(course).scroll(ToolArgs.string(i, "direction") != "up")
                    ok("Scrolled.", "Scrolled the page")
                }
                "browser_screenshot" -> screenshot(course, run)
                else -> null
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            fail("$name failed: ${t.message ?: t::class.simpleName}")
        }
    }

    private fun state(course: Course): String = buildString {
        appendLine("Course: ${course.label}" + if (course.author.isNotBlank()) " by ${course.author}" else "")
        appendLine("For the deck: ${course.deckName}")
        appendLine("Now: ${StudyQueue.line(course)}")
        course.chapters.forEach { c ->
            appendLine("${c.n}. ${c.title} — ${c.state.name.lowercase()}${if (c.kind != Chapter.Kind.UNKNOWN) ", ${c.kind.name.lowercase()}" else ""}${if (c.words > 0) ", ${c.words} words" else ""}")
        }
        if (!course.listed) appendLine("(The contents are not read yet.)")
    }.trim()

    private fun chapters(course: Course, i: JsonObject): MetaAnswer {
        if (course.listed) return fail("The contents are recorded already.")
        val list = ToolArgs.objects(i, "chapters").mapIndexedNotNull { k, o ->
            val title = ToolArgs.string(o, "title")?.trim().orEmpty()
            val url = ToolArgs.string(o, "url")?.trim().orEmpty()
            if (title.isEmpty() || BrowseGuard.openRefusal(url, course) != null) null else Chapter(k + 1, title.take(160), url)
        }.distinctBy { it.url }.mapIndexed { k, c -> c.copy(n = k + 1) }
        if (list.isEmpty()) return fail("No chapter had a title and an address on the course's site.")
        save(course.copy(chapters = list, listed = true, title = ToolArgs.string(i, "title")?.take(160) ?: course.title, author = ToolArgs.string(i, "author")?.take(80) ?: course.author))
        return ok("Recorded ${list.size} chapters.", "Read the course's contents: ${list.size} chapters")
    }

    private fun courseRead(course: Course, n: Int, what: String, from: Int): MetaAnswer {
        val chapter = course.chapter(n) ?: return fail("No chapter $n: course_state lists them.")
        val notes = what == "notes"
        val text = files.read(if (notes) CoursePaths.notes(course.id, n) else CoursePaths.page(course.id, n))
            ?: return fail(if (notes) "No notes on chapter $n yet." else "Chapter $n's text is not kept yet.")
        val source = "${course.label}, ch. $n “${chapter.title}”" + if (notes) " (notes)" else ""
        return ok(Untrusted.wrap(source, CourseText.part(text, from)), if (notes) "Read the notes on chapter $n" else "Read chapter $n: ${chapter.title}")
    }

    private fun courseFrames(course: Course, run: StudyRun, n: Int, from: Int): MetaAnswer {
        val chapter = course.chapter(n) ?: return fail("No chapter $n.")
        val all = files.file(CoursePaths.frames(course.id, n)).listFiles { f -> f.extension == "jpg" }
            ?.mapNotNull { f -> f.nameWithoutExtension.toLongOrNull()?.let { it to f } }?.sortedBy { it.first }.orEmpty()
        if (all.isEmpty()) return fail("No pictures were kept from chapter $n.")
        if (ai.sight == Vision.Sight.NO) return fail("This model cannot see pictures: the transcript is all there is.")
        val page = all.drop(from.coerceAtLeast(0)).take(FRAMES_AT_ONCE)
        if (page.isEmpty()) return fail("There are ${all.size} pictures; start below that.")
        val pictures = page.map { (_, f) ->
            val bytes = f.readBytes()
            val picture = com.kaiharimoto.neue.platform.decodePicture(bytes)
            files.putImage("course-" + run.courseId, bytes, "image/jpeg", picture?.width ?: 0, picture?.height ?: 0)
                .copy(data = java.util.Base64.getEncoder().encodeToString(bytes))
        }
        val next = from + page.size
        val words = "Pictures from chapter $n “${chapter.title}”, in order, at " + page.joinToString { Transcript.clock(it.first) } + "." +
            if (next < all.size) " More: from $next." else ""
        return MetaAnswer(words, "Looked at ${page.size} pictures from chapter $n", pictures = pictures)
    }

    private fun courseNotes(course: Course, n: Int, notes: String): MetaAnswer {
        course.chapter(n) ?: return fail("No chapter $n.")
        if (notes.isBlank()) return fail("The notes are empty.")
        files.write(CoursePaths.notes(course.id, n), notes.trim().take(NOTES_CAP) + "\n")
        return ok("Notes on chapter $n kept (${CourseText.words(notes)} words).", "Took notes on chapter $n")
    }

    private suspend fun pageSave(course: Course, n: Int): MetaAnswer {
        course.chapter(n) ?: return fail("No chapter $n.")
        val text = pageText()
        if (CourseText.words(text) < 30) return fail("The page shows almost no words yet.")
        keep(course, n, text)
        return ok("Chapter $n's text is kept (${CourseText.words(text)} words).", "Kept chapter $n's text")
    }

    private suspend fun browserOpen(course: Course, url: String): MetaAnswer {
        BrowseGuard.openRefusal(url, course)?.let { return fail(it) }
        val page = paced { surface(course).open(url) }
        return ok("Open: ${page.title} — ${page.url}. Read it with browser_read.", "Opened ${page.title.ifBlank { page.url }}")
    }

    private suspend fun browserRead(course: Course, from: Int): MetaAnswer {
        val here = surface(course).here()
        return ok(Untrusted.wrap(here.url, "${here.title}\n${here.url}\n\n" + CourseText.part(pageText(), from)), "Read ${here.title.ifBlank { "the page" }}")
    }

    private suspend fun browserElements(course: Course, near: String?): MetaAnswer {
        val all = surface(course).elements()
        shown = all.associateBy { it.ref }
        val list = all.filter { near.isNullOrBlank() || it.text.contains(near, ignoreCase = true) }.take(150)
        val text = list.joinToString("\n") { e ->
            val why = BrowseGuard.clickRefusal(e, course)
            "[${e.ref}] ${e.tag}${if (e.type.isNotBlank()) "(${e.type})" else ""} “${e.text}”" +
                (if (e.href.isNotBlank()) " → ${e.href}" else "") + (if (why != null) "  (never pressed: $why)" else "")
        }.ifBlank { "Nothing to press" + if (near != null) " with “$near”." else "." }
        return ok(Untrusted.wrap(surface(course).here().url, text), "Looked at what can be pressed")
    }

    private suspend fun browserClick(course: Course, ref: Int?, x: Int?, y: Int?): MetaAnswer {
        val s = surface(course)
        val e = when {
            ref != null -> shown[ref] ?: return fail("No element $ref: list them again with browser_elements.")
            x != null && y != null -> s.elementAt(x, y) ?: return fail("Nothing to press there.")
            else -> return fail("Name a ref, or an x and a y.")
        }
        BrowseGuard.clickRefusal(e, course)?.let { return fail(it) }
        paced { s.click(e.ref); s.here() }
        shown = emptyMap()
        val here = s.here()
        // A press that left the course comes back: the study never stays where it may not be.
        BrowseGuard.openRefusal(here.url, course)?.let {
            paced { s.open(course.start) }
            return fail("That led off the course ($it); went back to its start.")
        }
        return ok("Pressed “${e.text.take(60)}”. Now: ${here.title} — ${here.url}", "Pressed “${e.text.take(40)}”")
    }

    private suspend fun screenshot(course: Course, run: StudyRun): MetaAnswer {
        val png = surface(course).screenshot()
        val picture = com.kaiharimoto.neue.platform.decodePicture(png)
        val part = files.putImage("course-" + run.courseId, png, "image/png", picture?.width ?: 0, picture?.height ?: 0)
            .copy(data = java.util.Base64.getEncoder().encodeToString(png))
        return MetaAnswer("The page as the browser shows it is attached (${part.width} × ${part.height}); x and y for browser_click are in its pixels.", "Looked at the page", pictures = listOf(part))
    }

    // ---- the browser ------------------------------------------------------------------

    private suspend fun openBrowser(course: Course): WebSurface = lock.withLock {
        browser?.takeIf { it.alive }?.let { return it }
        WebSurfaces.launch(profile, course.start, ai.prefs.courseBrowser, visible = true).also { browser = it }
    }

    private suspend fun surface(course: Course): WebSurface = browser?.takeIf { it.alive } ?: openBrowser(course)

    private fun closeBrowser() {
        browser?.let { b -> runCatching { b.close() } }
        browser = null
    }

    private suspend fun pageText(): String {
        val b = browser ?: return ""
        return HtmlText.text(b.html(), PAGE_CAP)
    }

    /** A load at a person's pace: never sooner than [HumanPace] allows, and no more today once the day's are spent. */
    private suspend fun <T> paced(action: suspend () -> T): T {
        val id = current?.id
        val now = System.currentTimeMillis()
        id?.let(::load)?.let { c ->
            if (HumanPace.spent(c, now)) {
                line = "Resting: it has read as many pages today as a person would. It goes on tomorrow."
                delay(HumanPace.untilTomorrow(now))
            }
        }
        delay(HumanPace.wait(lastLoad, System.currentTimeMillis(), Random.nextDouble()))
        val result = action()
        lastLoad = System.currentTimeMillis()
        id?.let(::load)?.let { save(HumanPace.loaded(it, lastLoad)) }
        return result
    }

    // ---- saving, review ------------------------------------------------------------

    private fun load(id: String): Course? = CourseCodec.read(files.read(CoursePaths.course(id)))

    private fun save(course: Course) {
        val stamped = course.copy(updatedAt = System.currentTimeMillis())
        files.write(CoursePaths.course(course.id), CourseCodec.write(stamped))
        if (current?.id == course.id) current = stamped
    }

    private fun block(course: Course, why: String) {
        closeBrowser()
        save(course.copy(state = Course.State.BLOCKED, note = why.take(300)))
        // Said once: the strip shows the line, and the problem only where the line is not on screen (Fine Tuning's box).
        line = why
        problem = why
    }

    /** What the study wrote into the deck's guide, put before the person: Keep, or Undo all. Offered once. */
    private fun offerReview(course: Course) {
        if (course.reviewed || course.deckId.isBlank()) return
        val path = AiMemory.path(MemoryKind.GUIDE, course.deckId)
        val before = mapOf(path to course.guideBefore)
        val after = mapOf(path to files.read(path))
        val changes = MemoryReview.diff(before, after)
        // Offered once: marked shown only when it is on screen (a review already waiting puts it off to the next opening).
        if (changes.isEmpty() || ai.offerCourseReview(before, changes)) save(course.copy(reviewed = true))
    }

    private fun ok(content: String, summary: String) = MetaAnswer(content, summary)
    private fun fail(message: String) = MetaAnswer(message, message, isError = true)

    companion object {
        /** The study's browser profile under the data folder: never synced, never backed up. */
        const val PROFILE = "browser"

        /** Words a page must show for the app to keep it as the chapter's text without asking Ai. */
        const val ENOUGH_WORDS = 150

        /** Fewer words than this beside a video player: the chapter is the video. */
        const val VIDEO_WORDS = 400

        const val PAGE_CAP = 400_000
        const val NOTES_CAP = 40_000

        /** How fast a video chapter is played while the study listens: Whisper reads speech at this pace well. */
        const val WATCH_RATE = 1.5

        /** The longest video it plays, in seconds. */
        const val MAX_VIDEO_S = 3 * 3600L

        /** Captions with fewer words than this are not enough: the study listens. */
        const val CAPTION_WORDS = 60

        /** A picture of the video every this often while it plays, for [KeyFrames] to choose from. */
        const val SHOT_EVERY_MS = 4_000L
        const val SHOT_SCALE = 1.0
        const val FRAMES_AT_ONCE = 6

        /** What one course may add to the guide when it is distilled. */
        const val DISTIL_ROOM = 30_000
    }
}
