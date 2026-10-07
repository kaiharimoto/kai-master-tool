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
import com.kaiharimoto.mastertool.core.ai.course.CourseDepth
import com.kaiharimoto.mastertool.core.ai.course.CardMentions
import com.kaiharimoto.mastertool.core.ai.course.Sections
import com.kaiharimoto.mastertool.core.ai.course.DbReplay
import com.kaiharimoto.mastertool.core.ai.course.DbReplays
import com.kaiharimoto.mastertool.core.ai.course.ReplayStats
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

    /** Replays parsed this run, by course and number: `course_replays` counts them all, and parsing is not free. */
    private val parsedReplays = java.util.concurrent.ConcurrentHashMap<String, DbReplay>()

    /** Replays in a row DuelingBook sent nothing for. */
    private var unsent = 0

    /** Whether this build can take notes from a video chapter. */
    val canWatch: Boolean get() = VideoListening.missing(voiceModel) == null

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
        // The same guide for the same deck again: the course it began goes on from where it stopped.
        courses().firstOrNull { it.start == url && it.deckId == deckId }?.let { again ->
            current = again
            awaitingLogin = true
            line = "Log in to the course in the browser window, then press Begin: it goes on from where it stopped."
            ai.scope.launch {
                runCatching { openBrowser(again) }.onFailure {
                    problem = it.message ?: "The browser did not open."
                    awaitingLogin = false
                }
            }
            return again
        }
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
        val course = load(current?.id ?: return) ?: return
        awaitingLogin = false
        run(goingOn(course))
    }

    fun pause() {
        val c = current ?: return
        job?.cancel()
        save(c.copy(state = Course.State.PAUSED))
        line = "Paused."
    }

    fun resume() {
        val c = load(current?.id ?: return) ?: return
        run(goingOn(c))
    }

    /** A finished course with more to study, put aside until the app opens again. */
    fun dismiss() {
        if (running) return
        current = null
        line = ""
    }

    /**
     * [c] studying again. One already reviewed that has study left (its replays, 1.1.41): what it writes now is reviewed
     * afresh, against the guide as it is now.
     */
    private fun goingOn(c: Course): Course {
        val fresh = if (c.reviewed) c.copy(reviewed = false, guideBefore = files.read(AiMemory.path(MemoryKind.GUIDE, c.deckId)).orEmpty()) else c
        return fresh.copy(state = Course.State.STUDYING, note = "")
    }

    /** Stops for good: the course stays as far as it got, its notes and guide entries kept for the review. */
    fun stop() {
        val c = current ?: return
        job?.cancel()
        awaitingLogin = false
        closeBrowser()
        save(c.copy(state = Course.State.BLOCKED, note = STOPPED))
        offerReview(load(c.id) ?: c)
        current = null
        line = ""
    }

    /** The studies that were going when the app closed go on; a finished one not yet reviewed is offered. */
    fun reopen() {
        val all = courses()
        all.firstOrNull { it.state == Course.State.DONE && !it.reviewed }?.let { offerReview(it) }
        val going = all.firstOrNull { it.state == Course.State.STUDYING } ?: all.firstOrNull { it.state == Course.State.PAUSED }
        if (going == null) {
            // A course finished before this build could study its replays: offered, never started without the person.
            // One that stopped on something the person can fix is offered too, so Go on is a press away.
            all.firstOrNull { it.state == Course.State.DONE && StudyQueue.more(it, canWatch) }?.let {
                current = it
                line = moreToStudy(it)
            } ?: all.firstOrNull { it.state == Course.State.BLOCKED && it.note != STOPPED }?.let {
                current = it
                line = it.note
            }
            return
        }
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
            // A course begun before 1.1.42 draws its exam once, from the replays it has not studied.
            val course = load(id)?.let { c -> if (c.examDrawn) c else c.drawExam().also(::save) } ?: return
            current = course
            val step = StudyQueue.next(course, canWatch)
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
                is StudyQueue.Step.Scan -> scan(course, step.n)
                is StudyQueue.Step.Replay -> replay(course, step.n)
                is StudyQueue.Step.ReplayNotes -> replayNotes(course, step.n)
                StudyQueue.Step.Consolidate -> consolidate(course)
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
    private suspend fun read(start: Course, n: Int) {
        val chapter = start.chapter(n) ?: return
        BrowseGuard.openRefusal(chapter.url, start)?.let { return save(StudyQueue.failed(start, n, it)) }
        paced { surface(start).open(chapter.url) }
        val text = pageText()
        val course = harvest(start.id, n, text) ?: return
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

    /**
     * A chapter mastered (1.1.42): read section by section, noted with every section cited, the playbook written as it
     * goes. Sections the notes leave out send the study back to them once, by name; a chapter noted at a shallower
     * depth before is noted again from its kept text.
     */
    private suspend fun notes(course: Course, n: Int) {
        val chapter = course.chapter(n) ?: return
        val text = files.read(CoursePaths.page(course.id, n)).orEmpty()
        step(course, CourseTools.STEP_NOTES, CourseBrief.notes(course, chapter))
        Sections.uncovered(text, files.read(CoursePaths.notes(course.id, n)).orEmpty()).takeIf { it.isNotEmpty() }?.let { left ->
            load(course.id)?.let { step(it, CourseTools.STEP_NOTES, CourseBrief.uncovered("chapter $n", chapter.title, left)) }
        }
        val after = load(course.id) ?: return
        val written = files.read(CoursePaths.notes(course.id, n))
        save(
            if (written.isNullOrBlank()) StudyQueue.failed(after, n, "No notes were written.")
            else after.copy(consolidated = false).with((after.chapter(n) ?: chapter).copy(state = Chapter.State.NOTED, error = "", depth = CourseDepth.CURRENT)),
        )
    }

    /** The playbook put together from everything studied: merged, checked against the cards, linked, its gaps named. */
    private suspend fun consolidate(course: Course) {
        closeBrowser()
        step(course, CourseTools.STEP_CONSOLIDATE, CourseBrief.consolidate(course))
        load(course.id)?.let { save(it.copy(consolidated = true)) }
    }

    private suspend fun distil(course: Course) {
        closeBrowser()
        // No room limit (1.1.42, kai: mastery): the guide takes the plan whole; the playbook already holds the detail.
        step(course, CourseTools.STEP_DISTIL, CourseBrief.distil(course))
        val after = load(course.id) ?: return
        save(after.copy(distilled = true, distilDepth = CourseDepth.CURRENT, replaysDistilled = after.replays.any { it.state == Chapter.State.NOTED }, note = ""))
    }

    // ---- the replays a course links to (1.1.41) ----------------------------------------

    /** Chapter [n]'s page, open now, looked over for DuelingBook replays: the course with them added, saved. */
    private suspend fun harvest(id: String, n: Int, text: String): Course? {
        val links = runCatching { browser?.links().orEmpty() }.getOrDefault(emptyList())
        val c = load(id) ?: return null
        return c.found(n, DbReplays.found(links, text)).also(::save)
    }

    /** A chapter read before replays were looked for: its page opened again, only to find them. */
    private suspend fun scan(course: Course, n: Int) {
        val chapter = course.chapter(n) ?: return
        if (BrowseGuard.openRefusal(chapter.url, course) != null) return save(course.found(n, emptyList()))
        paced { surface(course).open(chapter.url) }
        // The kept text too: a replay named in the words, not linked.
        harvest(course.id, n, pageText() + "\n" + files.read(CoursePaths.page(course.id, n)).orEmpty())
    }

    /**
     * Replay [n]: its page opened in the study's browser as the person would open it, and what DuelingBook sends the
     * page read from the browser ([WebSurface.openReceiving]) — never asked for by the app, which would be getting round
     * DuelingBook's bot check. Kept whole, then in words. A replay received before is read again from what was kept.
     */
    private suspend fun replay(course: Course, n: Int) {
        val r = course.replay(n) ?: return
        var raw = files.read(CoursePaths.replayRaw(course.id, n))
        if (raw.isNullOrBlank()) {
            BrowseGuard.openRefusal(r.url, course)?.let { return save(StudyQueue.replayFailed(course, n, it, giveUp = true)) }
            line = "Reading replay $n of ${course.replays.size}: if DuelingBook asks to check the browser, tick its box in the browser window."
            val got = paced { surface(course).openReceiving(r.url, DbReplays.DATA, REPLAY_WAIT_MS) }
            val after = load(course.id) ?: return
            if (got.body == null) {
                save(StudyQueue.replayFailed(after, n, NOT_SENT))
                // Twice running: DuelingBook's check wants a person, and every replay after would spend a page on it.
                if (++unsent >= 2) {
                    unsent = 0
                    // Blocked: the loop sees it and stops; Go on starts again from this replay.
                    block(load(course.id) ?: after, CHECK_WANTS_PERSON)
                }
                return
            }
            unsent = 0
            raw = got.body
            DbReplays.error(raw)?.let { why -> return save(StudyQueue.replayFailed(after, n, "DuelingBook: $why", giveUp = true)) }
            files.write(CoursePaths.replayRaw(course.id, n), raw)
        }
        val after = load(course.id) ?: return
        val parsed = DbReplays.parse(raw)
            ?: return save(StudyQueue.replayFailed(after, n, "This version could not read the replay's record; it is kept, for a later version to read.", giveUp = true))
        parsedReplays[course.id + "/" + n] = parsed
        val chapter = after.chapter(r.chapter)
        val heading = "Replay $n — linked from chapter ${r.chapter}" + (chapter?.let { " “${it.title}”" } ?: "") + " (${r.url})"
        files.write(CoursePaths.replayText(course.id, n), DbReplays.render(parsed, heading))
        save(after.with(r.copy(state = Chapter.State.READ, error = "", players = parsed.players.joinToString(" vs "), games = parsed.games.size)))
    }

    private suspend fun replayNotes(course: Course, n: Int) {
        val r = course.replay(n) ?: return
        if (r.exam) return
        val text = files.read(CoursePaths.replayText(course.id, n)).orEmpty()
        step(course, CourseTools.STEP_REPLAY_NOTES, CourseBrief.replayNotes(course, r))
        Sections.uncovered(text, files.read(CoursePaths.replayNotes(course.id, n)).orEmpty()).takeIf { it.isNotEmpty() }?.let { left ->
            load(course.id)?.let { step(it, CourseTools.STEP_REPLAY_NOTES, CourseBrief.uncovered("replay $n", r.players, left)) }
        }
        val after = load(course.id) ?: return
        val written = files.read(CoursePaths.replayNotes(course.id, n))
        val now = after.replay(n) ?: return
        save(
            if (written.isNullOrBlank()) StudyQueue.replayFailed(after, n, "No notes were written.")
            else after.copy(consolidated = false).with(now.copy(state = Chapter.State.NOTED, error = "", depth = CourseDepth.CURRENT)),
        )
    }

    /** Every replay of [course] read so far, parsed (once each, kept while the app runs). */
    private fun readReplays(course: Course): List<ReplayStats.Entry> = course.replays
        // The exam's replays are never counted either: their openers and results would teach what the exam tests.
        .filter { !it.exam && (it.state == Chapter.State.READ || it.state == Chapter.State.NOTED) }
        .mapNotNull { r ->
            val key = course.id + "/" + r.n
            val parsed = parsedReplays[key] ?: files.read(CoursePaths.replayRaw(course.id, r.n))?.let(DbReplays::parse)?.also { parsedReplays[key] = it }
            parsed?.let { ReplayStats.Entry(r.n, r.chapter, it) }
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
                    .run(TurnRequest(system, turns, offered, connection.model, EFFORT))
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

    /** A step's rounds (1.1.42): mastering a chapter is reading it in parts, checking its cards and writing many entries. */
    private fun steps(kind: String): Int = when (kind) {
        CourseTools.STEP_LIST, CourseTools.STEP_READ -> 16
        CourseTools.STEP_NOTES -> 80
        CourseTools.STEP_REPLAY_NOTES -> 60
        CourseTools.STEP_CONSOLIDATE -> 160
        else -> 100
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
                "course_notes" -> courseNotes(course, ToolArgs.int(i, "chapter") ?: 0, ToolArgs.string(i, "notes").orEmpty(), ToolArgs.bool(i, "append") == true)
                "course_page_save" -> pageSave(course, ToolArgs.int(i, "chapter") ?: 0)
                "replay_read" -> replayRead(course, ToolArgs.int(i, "replay") ?: 0, ToolArgs.string(i, "what") ?: "text", ToolArgs.int(i, "from") ?: 0)
                "replay_notes" -> replayNotesWrite(course, ToolArgs.int(i, "replay") ?: 0, ToolArgs.string(i, "notes").orEmpty(), ToolArgs.bool(i, "append") == true)
                "course_cards" -> courseCards(course, ToolArgs.int(i, "chapter"), ToolArgs.int(i, "replay"))
                "notes_coverage" -> notesCoverage(course, ToolArgs.int(i, "chapter"), ToolArgs.int(i, "replay"))
                "course_replays" -> courseReplays(course, ToolArgs.string(i, "player"))
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
        if (course.replays.isNotEmpty()) {
            appendLine()
            appendLine("DuelingBook replays the chapters link to:")
            course.replays.forEach { r ->
                if (r.exam) appendLine("Replay ${r.n} (ch. ${r.chapter}) — held out for the exam: never read in the study")
                else appendLine("Replay ${r.n} (ch. ${r.chapter}) — ${r.state.name.lowercase()}" + (if (r.players.isNotBlank()) ", ${r.players}" else "") + (if (r.games > 0) ", ${r.games} game${if (r.games == 1) "" else "s"}" else ""))
            }
        }
    }.trim()

    private fun replayRead(course: Course, n: Int, what: String, from: Int): MetaAnswer {
        val r = course.replay(n) ?: return fail("No replay $n: course_state lists them.")
        if (r.exam) return fail("Replay $n is held out: it is the exam, never read in the study.")
        val notes = what == "notes"
        val text = files.read(if (notes) CoursePaths.replayNotes(course.id, n) else CoursePaths.replayText(course.id, n))
            ?: return fail(if (notes) "No notes on replay $n yet." else "Replay $n is not read yet.")
        val source = "${course.label}, replay $n (${r.url})" + if (notes) " (notes)" else ""
        val shown = if (notes) text else Sections.numbered(text)
        return ok(Untrusted.wrap(source, CourseText.part(shown, from)), if (notes) "Read the notes on replay $n" else "Read replay $n: ${r.players.ifBlank { "a duel" }}")
    }

    private fun replayNotesWrite(course: Course, n: Int, notes: String, append: Boolean): MetaAnswer {
        val r = course.replay(n) ?: return fail("No replay $n.")
        if (r.exam) return fail("Replay $n is held out: it is the exam.")
        if (notes.isBlank()) return fail("The notes are empty.")
        val all = keepNotes(CoursePaths.replayNotes(course.id, n), notes, append) ?: return fail(NOTES_FULL)
        val (cited, of) = Sections.coverage(files.read(CoursePaths.replayText(course.id, n)).orEmpty(), all)
        return ok("Notes on replay $n kept (${CourseText.words(all)} words); they cite $cited of its $of sections.", "Took notes on replay $n")
    }

    private fun courseReplays(course: Course, player: String?): MetaAnswer {
        val entries = readReplays(course)
        if (entries.isEmpty()) return fail("No replay of this course has been read.")
        val summary = ReplayStats.of(entries, player?.trim()?.takeIf { it.isNotEmpty() })
        return ok(ReplayStats.words(summary, entries), "Counted ${entries.size} replays")
    }

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
        // The chapter with its sections numbered, as its notes cite them (§N).
        val shown = if (notes) text else Sections.numbered(text)
        return ok(Untrusted.wrap(source, CourseText.part(shown, from)), if (notes) "Read the notes on chapter $n" else "Read chapter $n: ${chapter.title}")
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

    private fun courseNotes(course: Course, n: Int, notes: String, append: Boolean): MetaAnswer {
        course.chapter(n) ?: return fail("No chapter $n.")
        if (notes.isBlank()) return fail("The notes are empty.")
        val all = keepNotes(CoursePaths.notes(course.id, n), notes, append) ?: return fail(NOTES_FULL)
        val (cited, of) = Sections.coverage(files.read(CoursePaths.page(course.id, n)).orEmpty(), all)
        return ok("Notes on chapter $n kept (${CourseText.words(all)} words); they cite $cited of its $of sections. notes_coverage lists the rest.", "Took notes on chapter $n")
    }

    /** [notes] written to [path], after what is there when [append]; the whole notes, or null past [NOTES_CAP]. */
    private fun keepNotes(path: String, notes: String, append: Boolean): String? {
        val all = (if (append) files.read(path).orEmpty().trimEnd() + "\n\n" else "") + notes.trim()
        if (all.length > NOTES_CAP) return null
        files.write(path, all.trim() + "\n")
        return all
    }

    /** The cards a chapter or replay names, each with its type and text: its notes are written against them. */
    private fun courseCards(course: Course, chapter: Int?, replay: Int?): MetaAnswer {
        val (text, label) = unit(course, chapter, replay) ?: return fail("Name a chapter or a replay.")
        if (text.isBlank()) return fail("Nothing is kept of $label yet.")
        val index = ai.h.builder.index
        val names = CardMentions.find(text, index.cards.map { it.name })
        if (names.isEmpty()) return ok("$label names no card the pool knows.", "Found no cards in $label")
        val body = names.take(CARDS_AT_ONCE).joinToString("\n\n") { name -> index.byName(name)?.let { "$name — ${it.type}\n${it.description}" } ?: name }
        val more = if (names.size > CARDS_AT_ONCE) "\n\n(${names.size - CARDS_AT_ONCE} more: " + names.drop(CARDS_AT_ONCE).joinToString() + " — card_info reads them.)" else ""
        return ok("$label names ${names.size} cards:\n\n$body$more", "Read the ${names.size} cards $label names")
    }

    private fun notesCoverage(course: Course, chapter: Int?, replay: Int?): MetaAnswer {
        val (text, label) = unit(course, chapter, replay) ?: return fail("Name a chapter or a replay.")
        val notes = files.read(if (chapter != null) CoursePaths.notes(course.id, chapter) else CoursePaths.replayNotes(course.id, replay!!)).orEmpty()
        val (cited, of) = Sections.coverage(text, notes)
        val left = Sections.uncovered(text, notes)
        return ok(
            "$label: the notes cite $cited of $of sections." +
                if (left.isEmpty()) " Every section is covered." else " Not yet:\n" + left.joinToString("\n") { "§${it.n} ${it.title} (${it.words} words)" },
            "Checked the notes on $label: $cited of $of sections",
        )
    }

    /** A chapter's or a replay's kept text and its name; null when neither is named. A replay of the exam is never shown. */
    private fun unit(course: Course, chapter: Int?, replay: Int?): Pair<String, String>? = when {
        chapter != null -> files.read(CoursePaths.page(course.id, chapter)).orEmpty() to "chapter $chapter"
        replay != null -> if (course.replay(replay)?.exam == true) "" to "replay $replay (held out)" else files.read(CoursePaths.replayText(course.id, replay)).orEmpty() to "replay $replay"
        else -> null
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

        /** One chapter's or replay's notes, all told (1.1.42: mastery notes are long; it was 40,000, silently cut). */
        const val NOTES_CAP = 300_000
        const val NOTES_FULL = "The notes have reached their limit for this part: write what is left into the playbook instead."

        /** The thought each step of a study is given (1.1.42: it was medium). */
        const val EFFORT = "high"

        /** Cards read out at once by course_cards. */
        const val CARDS_AT_ONCE = 40

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

        /** How long a replay page has to receive its replay: DuelingBook's check, then the duel's record. */
        const val REPLAY_WAIT_MS = 90_000L

        const val NOT_SENT = "DuelingBook did not send the replay: its check may want a person, or the page did not load."
        const val CHECK_WANTS_PERSON = "DuelingBook's check did not pass by itself twice running. Press Go on and watch " +
            "the browser window: tick the check's box when DuelingBook asks."

        /** A course the person stopped: never offered again by itself. */
        const val STOPPED = "Stopped."

        /** What the panel says of a finished course this build can study further. */
        fun moreToStudy(course: Course): String {
            val shallow = course.chapters.count { it.state == Chapter.State.NOTED && it.depth < CourseDepth.CURRENT }
            val left = course.replays.count { it.state != Chapter.State.NOTED && !it.gaveUp && !it.exam }
            return when {
                shallow > 0 -> "This version studies a course to mastery: section by section, every card checked, a playbook written as it goes. " +
                    "It can take $shallow chapter${if (shallow == 1) "" else "s"} again from what it kept, without loading a page" +
                    if (left > 0) ", and $left replay${if (left == 1) "" else "s"}." else "."
                left > 0 -> "It links to $left DuelingBook replay${if (left == 1) "" else "s"} not studied yet."
                else -> "This version can study the DuelingBook replays a course links to: it looks for them in each chapter first."
            }
        }
    }
}
