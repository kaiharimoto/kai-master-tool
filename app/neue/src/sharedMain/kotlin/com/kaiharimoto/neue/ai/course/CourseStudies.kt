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
import com.kaiharimoto.mastertool.core.ai.course.CourseExport
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
import com.kaiharimoto.mastertool.core.ai.course.SavedPicture
import com.kaiharimoto.mastertool.core.ai.course.SavedLink
import com.kaiharimoto.mastertool.core.ai.course.PageSnapshots
import com.kaiharimoto.mastertool.core.ai.course.PageSnapshot
import com.kaiharimoto.mastertool.core.ai.course.StudyChunks
import com.kaiharimoto.mastertool.core.ai.course.StudyQueue
import com.kaiharimoto.mastertool.core.ai.course.StudyRetry
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
import com.kaiharimoto.neue.platform.deliverFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
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

    /** A study's job is going, or still ending after Pause or Stop (until it has, nothing else may save the course). */
    val running: Boolean get() = job?.isCompleted == false

    /** A problem the person should see, when the study could not start or stopped. */
    var problem by mutableStateOf<String?>(null)

    private var job: Job? = null
    private var browser: WebSurface? = null
    private var lastLoad = 0L
    private val lock = Mutex()

    /** What the study is reading beside what it is writing, live, for the person to follow (`CourseMonitor`). */
    val monitor = StudyMonitor()

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
            createdAt = now, updatedAt = now, guideBefore = guide.orEmpty(),
        )
        save(course)
        current = course
        monitor.clear()
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
        val id = current?.id ?: return
        awaitingLogin = false
        afterJob(cancel = false) { load(id)?.let { run(goingOn(it)) } }
    }

    /**
     * [then], once the study's job has ended — cancelled first when [cancel]. Pause, Stop and Go on wait for it (1.1.47):
     * a job still ending saved its older copy of the course over the person's Pause or Stop, and closed the browser a
     * new job had opened.
     */
    private fun afterJob(cancel: Boolean, then: suspend () -> Unit) {
        val going = job
        if (cancel) going?.cancel()
        ai.scope.launch {
            going?.join()
            then()
        }
    }

    fun pause() {
        val id = current?.id ?: return
        line = "Pausing…"
        afterJob(cancel = true) {
            load(id)?.let { save(it.copy(state = Course.State.PAUSED)) }
            line = "Paused."
        }
    }

    fun resume() {
        val id = current?.id ?: return
        afterJob(cancel = false) { load(id)?.let { run(goingOn(it)) } }
    }

    /** A study waiting out the model's limit or the network ([StudyRetry]): it tries again now, from the same part. */
    fun tryNow() {
        val id = current?.id ?: return
        if (load(id)?.retryAt == 0L) return
        afterJob(cancel = true) {
            // Only a study still waiting: a Pause or Stop pressed meanwhile stands (1.1.47).
            load(id)?.takeIf { it.state == Course.State.STUDYING && it.retryAt > 0 }?.let { run(goingOn(it)) }
        }
    }

    /** What became of the last copy saved ([saveCopy]), for the strip. */
    var copySaid by mutableStateOf<String?>(null)

    /**
     * [course] as kept on this computer written out as one page to read anywhere, offline (1.1.51): every chapter's words
     * with its pictures in place, the replays in words — the exam's named only. Saved where the person chooses.
     */
    fun saveCopy(course: Course) {
        copySaid = "Writing the copy…"
        ai.scope.launch {
            copySaid = try {
                val bytes = withContext(Dispatchers.IO) { copyOf(load(course.id) ?: course).encodeToByteArray() }
                deliverFile(CourseExport.fileName(course), "text/html", bytes)?.let { "Saved: $it" } ?: "Not saved."
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                "The copy could not be written: ${t.message ?: t::class.simpleName}"
            }
        }
    }

    private fun copyOf(course: Course): String {
        val b64 = java.util.Base64.getEncoder()
        val chapters = course.chapters.sortedBy { it.n }.mapNotNull { ch ->
            val text = files.read(CoursePaths.page(course.id, ch.n)) ?: return@mapNotNull null
            val dir = files.file(CoursePaths.pictures(course.id, ch.n))
            val pictures = PageSnapshots.read(files.read(CoursePaths.snapshot(course.id, ch.n)))?.pictures.orEmpty().mapNotNull { p ->
                File(dir, p.file).takeIf { it.isFile }?.let { f -> p.n to "data:${PageSnapshots.mediaType(p.file)};base64,${b64.encodeToString(f.readBytes())}" }
            }.toMap()
            CourseExport.ChapterDoc(ch.n, ch.title, ch.url, text, pictures)
        }
        val replays = course.replays.filter { it.state == Chapter.State.READ || it.state == Chapter.State.NOTED }.sortedBy { it.n }.map { r ->
            CourseExport.ReplayDoc(r.n, r.players, r.url, if (r.exam) "" else files.read(CoursePaths.replayText(course.id, r.n)).orEmpty(), r.exam)
        }
        return CourseExport.html(course, chapters, replays)
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
        // Go on is now: a wait for the model ([StudyRetry]) is over when the person says so.
        return fresh.copy(state = Course.State.STUDYING, note = "", retryAt = 0, tries = 0)
    }

    /** Stops for good: the course stays as far as it got, its notes and guide entries kept for the review. */
    fun stop() {
        val id = current?.id ?: return
        awaitingLogin = false
        line = "Stopping…"
        afterJob(cancel = true) {
            closeBrowser()
            val c = load(id) ?: return@afterJob
            save(c.copy(state = Course.State.BLOCKED, note = STOPPED, retryAt = 0, tries = 0))
            offerReview(load(id) ?: c)
            if (current?.id == id) current = null
            line = ""
        }
    }

    /** The studies that were going when the app closed go on; a finished one not yet reviewed is offered. */
    fun reopen() {
        // Stopped by the spending cap before 1.1.46, which has none: going on by itself, from where it stopped.
        courses().filter { it.state == Course.State.BLOCKED && it.note == StudyQueue.CAP_REACHED }.forEach { save(it.copy(state = Course.State.STUDYING, note = "")) }
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
                if (!currentCoroutineContext().isActive) throw c
                block(load(start.id) ?: start, c.message ?: "The study stopped.")
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
            // A course begun before 1.1.43 draws its exam once, from the replays it has not studied.
            val course = load(id)?.let { c -> if (c.examDrawn) c else c.drawExam().also(::save) } ?: return
            current = course
            // Stopped by the model or the network: it waits, then goes on from the same part (after a restart too).
            val wait = course.retryAt - System.currentTimeMillis()
            if (wait > 0 && course.state == Course.State.STUDYING) {
                line = course.note.ifBlank { "Waiting to go on." }
                delay(wait)
                // The wait is over: said so before the part runs again, or Try now would offer to start it over (1.1.47).
                load(id)?.takeIf { it.state == Course.State.STUDYING }?.let { save(it.copy(retryAt = 0, note = "")) }
                continue
            }
            if (course.retryAt > 0) save(course.copy(retryAt = 0, note = ""))
            val step = StudyQueue.next(course, canWatch)
            line = StudyQueue.line(course, step)
            try {
                if (!take(course, step)) return
            } catch (c: CancellationException) {
                // Only this study's own end is a cancellation; anything else that says it is one failed (1.1.47).
                if (!currentCoroutineContext().isActive) throw c
                if (!stumbled(id, step, c)) return
                continue
            } catch (t: Throwable) {
                if (!stumbled(id, step, t)) return
                continue
            }
            // A step went through: whatever stopped the study before is over.
            load(id)?.takeIf { it.state == Course.State.STUDYING && (it.tries > 0 || it.retryAt > 0) }?.let { save(it.copy(tries = 0, retryAt = 0, note = "")) }
        }
    }

    /**
     * A step that failed, [t]: the study waits and tries the same part again ([StudyRetry]) — true — or waits for the
     * person, blocked, saying why — false.
     */
    private fun stumbled(id: String, step: StudyQueue.Step, t: Throwable): Boolean {
        val c = load(id) ?: return false
        val why = t.message ?: t::class.simpleName.orEmpty()
        val kind = StudyRetry.kind(why, (t as? StepFailed)?.auth == true, (t as? StepFailed)?.retryable == true)
        val tries = c.tries + 1
        // A step about one page or one replay that keeps failing passes that one over for now, and the course goes on
        // (1.1.47: it blocked the whole course, or was waited on hourly for ever).
        if (StudyRetry.unitGivesUp(kind, tries)) {
            passedOver(c, step, why)?.let { next ->
                save(next.copy(tries = 0, retryAt = 0, note = ""))
                return true
            }
        }
        if (StudyRetry.givesUp(kind, tries)) {
            block(c.copy(tries = 0, retryAt = 0), why)
            return false
        }
        val wait = StudyRetry.waitMs(tries)
        closeBrowser()
        save(c.copy(tries = tries, retryAt = System.currentTimeMillis() + wait, note = StudyRetry.note(why, wait / 60_000)))
        return true
    }

    /** [c] with the one chapter or replay [step] was about counted as failed, or null when [step] is not about one. */
    private fun passedOver(c: Course, step: StudyQueue.Step, why: String): Course? = when (step) {
        is StudyQueue.Step.Read -> StudyQueue.failed(c, step.n, why)
        is StudyQueue.Step.Scan -> c.found(step.n, emptyList())
        is StudyQueue.Step.Save -> c.chapter(step.n)?.let { ch -> c.with(ch.copy(saved = true)) }
        is StudyQueue.Step.Watch -> c.chapter(step.n)?.let { ch -> c.with(ch.copy(videoChecked = true, watched = true, videoNote = why.take(300))) }
        is StudyQueue.Step.Replay -> StudyQueue.replayFailed(c, step.n, why)
        else -> null
    }

    /** Does [step] of [course]; false when the study stops here (waiting for the person, or done). */
    private suspend fun take(course: Course, step: StudyQueue.Step): Boolean {
        when (step) {
            is StudyQueue.Step.Waiting -> {
                if (course.state == Course.State.STUDYING) save(course.copy(state = Course.State.BLOCKED, note = step.why))
                closeBrowser()
                return false
            }
            StudyQueue.Step.Done -> {
                finish(course)
                return false
            }
            StudyQueue.Step.List -> list(course)
            is StudyQueue.Step.Read -> read(course, step.n)
            is StudyQueue.Step.Notes -> notes(course, step.n)
            StudyQueue.Step.Distil -> distil(course)
            is StudyQueue.Step.Scan -> scan(course, step.n)
            is StudyQueue.Step.Save -> savePage(course, step.n)
            is StudyQueue.Step.Watch -> watchStep(course, step.n)
            is StudyQueue.Step.Replay -> replay(course, step.n)
            is StudyQueue.Step.ReplayNotes -> replayNotes(course, step.n)
            StudyQueue.Step.Consolidate -> consolidate(course)
        }
        return true
    }

    /** The contents: read off the guide's own links, or by Ai when the page does not list them as links. */
    private suspend fun list(course: Course) {
        val page = paced { surface(course).open(course.start) }
        val found = Chapters.fromLinks(surface(course).links(), page.url.ifBlank { course.start })
        if (found.isNotEmpty()) {
            // As saved after the load (its count of pages read today), never the copy from before it (1.1.47).
            val now = load(course.id) ?: course
            save(now.copy(chapters = found, listed = true, title = now.title.ifBlank { page.title }))
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
        // Kept whole as it is read (1.1.51): its pictures, links and video, so it is never opened again but to watch.
        val snap = keepPage(start.id, n)
        val text = pageText()
        monitor.reading("Reading chapter $n: ${chapter.title}", text, "ch. $n")
        harvest(start.id, n, text) ?: return
        val course = (load(start.id) ?: return).let { c ->
            c.with((c.chapter(n) ?: chapter).copy(saved = true, pictures = snap?.pictures?.size ?: 0)).also(::save)
        }
        val words = CourseText.words(text)
        // A chapter with a video has it watched, whatever the page says beside it (1.1.44: a page of 150 words or more kept
        // its text alone, and its video was never played).
        if (surface(course).hasVideo()) return settleVideo(course, n, text, watch(course, chapter, text), fresh = true)
        when {
            words >= ENOUGH_WORDS -> keep(course, n, text)
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
    /** What became of a chapter's video. */
    private sealed interface Watched {
        /** Its transcript and pictures, with the page's words: the chapter's text. */
        data class Done(val text: String) : Watched

        /** It cannot be heard on this computer yet (no captions, no voice model): it waits. */
        data class Waiting(val why: String) : Watched

        /** It cannot be watched at all: [why]. */
        data class Failed(val why: String) : Watched
    }

    private suspend fun watch(course: Course, chapter: Chapter, pageWords: String): Watched {
        val n = chapter.n
        val s = surface(course)
        var video = s.video() ?: return Watched.Failed("The video would not load.")
        var c = course
        if (video.frame.isNotBlank()) {
            // The player is another site's, embedded by the course: it may be opened, from the chapter, and nowhere else.
            val host = BrowseGuard.host(video.frame).removePrefix("www.")
            if (host.isBlank() || !video.frame.startsWith("https://", ignoreCase = true)) return Watched.Failed("The video's player is not on https.")
            if (host !in c.hosts) c = (load(c.id) ?: c).let { now -> now.copy(hosts = now.hosts + host) }.also(::save)
            paced { s.open(video.frame, referrer = chapter.url) }
            video = s.video()?.takeIf { it.frame.isBlank() } ?: return Watched.Failed("The video's player would not open by itself.")
        }
        line = "Watching chapter ${chapter.n}: ${chapter.title}"
        monitor.reading("Watching chapter $n: ${chapter.title}", "Playing the video muted at ${WATCH_RATE}× — its captions, or its sound for the voice model, and a picture at each new scene.", "ch. $n")
        val cues = s.captions()
        val captioned = cues.firstOrNull { it.first < 0 }?.let { CaptionCues.parse(it.second) }
            ?: cues.map { (sec, words) -> Transcript.Line((sec * 1000).toLong(), words) }
        var transcript = Transcript.of(captioned)
        if (transcript.words > 0) monitor.reading("Watching chapter $n: ${chapter.title}", transcript.render(chapter.title), "ch. $n")
        val listen = transcript.words < CAPTION_WORDS
        val model = voiceModel
        if (listen) VideoListening.missing(model)?.let { why -> return Watched.Waiting(why) }
        if (video.protected && listen) return Watched.Failed("The video is protected (DRM): its sound cannot be recorded, and it has no captions.")
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
                    s.videoFrame(SHOT_SCALE)?.let {
                        shots += (now.time * 1000).toLong() to it
                        monitor.picture(it, "${Transcript.clock((now.time * 1000).toLong())} of ${Transcript.clock((video.duration * 1000).toLong())}")
                    }
                }
                if (listen) s.takeSound().forEach(sound::write) else s.takeSound()
                delay(1_000)
            }
            s.stopListening()
            if (listen) s.takeSound().forEach(sound::write)
        }
        if (listen && sound.size() > 0) {
            line = "Listening to chapter ${chapter.n}: ${chapter.title}"
            monitor.reading("Listening to chapter $n: ${chapter.title}", "Transcribing the video's sound on this computer…", "ch. $n")
            transcript = Transcript.of(VideoListening.transcribe(sound.toByteArray(), model, WATCH_RATE))
        }
        val kept = keepFrames(c.id, n, shots)
        if (transcript.words == 0 && kept.isEmpty()) {
            return Watched.Failed(if (started != "ok") "The video would not play: $started" else "Nothing could be heard or seen in the video.")
        }
        monitor.reading("Chapter $n's video, heard: ${chapter.title}", transcript.render(chapter.title), "ch. $n")
        return Watched.Done(
            transcript.render(chapter.title) +
                (if (kept.isNotEmpty()) "\n(${kept.size} pictures kept from the video, at " + kept.joinToString { Transcript.clock(it) } + ": course_frames.)\n" else "") +
                (if (CourseText.words(pageWords) > 20) "\n## On the page\n\n$pageWords" else ""),
        )
    }

    /**
     * Chapter [n] with what became of its video: [watched]'s transcript kept as its text, or its page's words kept while the
     * video waits or could not be watched. [fresh]: read now for the first time; else a chapter read before, whose notes
     * are taken again from the transcript.
     */
    private fun settleVideo(course: Course, n: Int, pageWords: String, watched: Watched, fresh: Boolean) {
        val now = load(course.id) ?: course
        val chapter = now.chapter(n) ?: return
        val words = CourseText.words(pageWords)
        val seen = chapter.copy(videoChecked = true, hasVideo = true)
        when (watched) {
            is Watched.Done -> {
                files.write(CoursePaths.page(now.id, n), watched.text)
                val kind = if (words < VIDEO_WORDS) Chapter.Kind.VIDEO else Chapter.Kind.TEXT
                // Noted before without its video: noted again, and the playbook put together and distilled again after.
                save(now.renoted().copy(distilDepth = minOf(now.distilDepth, CourseDepth.CURRENT - 1)).with(
                    seen.copy(kind = kind, state = Chapter.State.READ, words = CourseText.words(watched.text), error = "", watched = true, videoNote = "", depth = 0, notedThrough = 0, notesMark = -1),
                ))
            }
            is Watched.Waiting -> when {
                !fresh -> save(now.with(seen.copy(watched = false, videoNote = watched.why)))
                words >= ENOUGH_WORDS -> {
                    files.write(CoursePaths.page(now.id, n), pageWords)
                    save(now.with(seen.copy(kind = Chapter.Kind.TEXT, state = Chapter.State.READ, words = words, error = "", watched = false, videoNote = watched.why)))
                }
                else -> {
                    files.write(CoursePaths.page(now.id, n), pageWords)
                    save(now.with(seen.copy(kind = Chapter.Kind.VIDEO, state = Chapter.State.WAITING, error = watched.why, watched = false, videoNote = watched.why)))
                }
            }
            is Watched.Failed -> when {
                !fresh -> save(now.with(seen.copy(watched = true, videoNote = watched.why)))
                words > 0 -> {
                    files.write(CoursePaths.page(now.id, n), pageWords)
                    save(now.with(seen.copy(kind = Chapter.Kind.TEXT, state = Chapter.State.READ, words = words, error = "", watched = true, videoNote = watched.why)))
                }
                else -> save(StudyQueue.failed(now, n, watched.why))
            }
        }
    }

    /** A chapter read before: its page looked over for a video, and the video watched. */
    private suspend fun watchStep(course: Course, n: Int) {
        val chapter = course.chapter(n) ?: return
        if (BrowseGuard.openRefusal(chapter.url, course) != null) return save(course.with(chapter.copy(videoChecked = true, watched = true)))
        paced { surface(course).open(chapter.url) }
        if (!surface(course).hasVideo()) {
            return save((load(course.id) ?: course).let { c -> c.with((c.chapter(n) ?: chapter).copy(videoChecked = true, hasVideo = false, watched = true)) })
        }
        val page = pageText()
        settleVideo(course, n, page, watch(course, chapter, page), fresh = false)
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
        // Kept as text by the app or by Ai: its page held no video (a page with one is watched first).
        save(course.with(chapter.copy(kind = Chapter.Kind.TEXT, state = Chapter.State.READ, words = CourseText.words(text), error = "", videoChecked = true, watched = !chapter.hasVideo || chapter.watched)))
    }

    /**
     * A chapter mastered (1.1.43), a part at a time (1.1.46): its sections in runs of about [StudyChunks.WORDS] words, each
     * read and noted in a step of its own, with every section cited and the playbook written as it goes; then once more for
     * the sections the notes leave out. A chapter noted at a shallower depth before is noted again from its kept text.
     */
    private suspend fun notes(course: Course, n: Int) {
        val chapter = course.chapter(n) ?: return
        val text = files.read(CoursePaths.page(course.id, n)).orEmpty()
        noteInParts(
            course, text, CoursePaths.notes(course.id, n), "ch. $n", chapter.notedThrough, chapter.notesMark, CourseTools.STEP_NOTES,
            mark = { c, through, sections, at -> c.with((c.chapter(n) ?: chapter).copy(notedThrough = through, sections = sections, notesMark = at)) },
            brief = { c, part, begun -> CourseBrief.notesPart(c, c.chapter(n) ?: chapter, part, begun) },
            last = { c, left, begun -> CourseBrief.uncoveredAgain("chapter $n", chapter.title, left, begun) },
            done = { c, written ->
                if (!written) StudyQueue.failed(c, n, "No notes were written.").let { f -> f.with(f.chapter(n)!!.copy(notedThrough = 0, notesMark = -1)) }
                else c.renoted().with((c.chapter(n) ?: chapter).copy(state = Chapter.State.NOTED, error = "", depth = CourseDepth.CURRENT, notedThrough = 0, notesMark = -1))
            },
        )
    }

    /**
     * Notes on one chapter or replay, [text], kept at [path], taken a part at a time: the next part after section
     * [through], or — every part noted — one more pass for the sections left out, then [done]. [at] is where the notes
     * ended when the part going began, or -1: a part stopped half-way (the model's limit, Pause, the app closed) is set
     * back to there and taken again whole, so nothing is noted twice and nothing is lost but that part's work.
     */
    private suspend fun noteInParts(
        course: Course,
        text: String,
        path: String,
        unit: String,
        through: Int,
        at: Int,
        kind: String,
        mark: (Course, Int, Int, Int) -> Course,
        brief: (Course, StudyChunks.Part, Boolean) -> String,
        last: (Course, List<Sections.Section>, Boolean) -> String,
        done: (Course, Boolean) -> Course,
    ) {
        val parts = StudyChunks.parts(text)
        val sections = parts.lastOrNull()?.of ?: 0
        val begun = at >= 0
        val notes = files.read(path).orEmpty()
        // Where this part begins: a stopped part's own beginning; the first part from nothing (notes taken again whole).
        val from = when {
            begun -> at
            through == 0 -> 0
            else -> notes.length
        }
        val kept = if (through == 0 && !begun) "" else StudyChunks.setBack(notes, from)
        if (kept != notes) files.write(path, kept)
        save(mark(load(course.id) ?: course, through, sections, kept.length))
        val part = parts.firstOrNull { it.last > through }
        if (part != null) {
            step(load(course.id) ?: course, kind, brief(course, part, begun), steps = PART_STEPS)
            // A part is done when its own sections are cited (1.1.47: a step that ran out of rounds counted as done): what
            // it left out is asked for once more, by name, before the next part.
            Sections.uncovered(text, files.read(path).orEmpty(), unit).filter { it.n in part.first..part.last }.takeIf { it.isNotEmpty() }?.let { left ->
                load(course.id)?.let { step(it, kind, last(it, left, false), steps = PART_STEPS) }
            }
            save(mark(load(course.id) ?: return, part.last, sections, -1))
            return
        }
        // Every part noted: the sections the notes still leave out, by name, once.
        Sections.uncovered(text, kept, unit).takeIf { it.isNotEmpty() }?.let { left ->
            step(load(course.id) ?: course, kind, last(course, left, begun), steps = PART_STEPS)
        }
        val after = load(course.id) ?: return
        save(done(after, !files.read(path).isNullOrBlank()))
    }

    /** The playbook put together from everything studied, a kind of entry at a time, then whole: merged, checked, linked. */
    private suspend fun consolidate(course: Course) {
        closeBrowser()
        val part = StudyChunks.nextConsolidate(course) ?: return save(course.copy(consolidated = true, consolidateDone = emptyList(), partBegun = ""))
        val key = "consolidate:$part"
        val begun = course.partBegun == key
        save(course.copy(partBegun = key))
        step(course, CourseTools.STEP_CONSOLIDATE, CourseBrief.consolidatePart(course, part, begun), steps = if (part == StudyChunks.WHOLE) 120 else 60)
        load(course.id)?.let { c ->
            val done = c.consolidateDone + part
            save(c.copy(consolidateDone = done, partBegun = "", consolidated = StudyChunks.consolidateParts.all { it in done }))
        }
    }

    /** The guide written from the notes, a few chapters at a time, a few replays at a time, then tied together whole. */
    private suspend fun distil(course: Course) {
        closeBrowser()
        // No room limit (1.1.43, kai: mastery): the guide takes the plan whole; the playbook already holds the detail.
        val part = StudyChunks.nextDistil(course)
        if (part != null) {
            val key = "distil:$part"
            val begun = course.partBegun == key
            save(course.copy(partBegun = key))
            step(course, CourseTools.STEP_DISTIL, CourseBrief.distilPart(course, part, begun), steps = if (part == StudyChunks.WHOLE) 120 else 80)
            val after = load(course.id) ?: return
            val done = after.distilDone + part
            if (StudyChunks.nextDistil(after.copy(distilDone = done)) != null) return save(after.copy(distilDone = done, partBegun = ""))
        }
        val after = load(course.id) ?: return
        save(
            after.copy(
                distilled = true, distilDepth = CourseDepth.CURRENT, replaysDistilled = after.replays.any { it.state == Chapter.State.NOTED },
                note = "", distilDone = emptyList(), partBegun = "",
            ),
        )
    }

    // ---- the replays a course links to (1.1.41) ----------------------------------------

    /** Chapter [n]'s page, open now, looked over for DuelingBook replays: the course with them added, saved. */
    private suspend fun harvest(id: String, n: Int, text: String): Course? {
        val links = runCatching { browser?.links().orEmpty() }.getOrDefault(emptyList())
        val c = load(id) ?: return null
        return c.found(n, DbReplays.found(links, text)).also(::save)
    }

    /**
     * Chapter [n]'s page, open now, kept whole on this computer ([PageSnapshot], 1.1.51): each picture worth keeping saved
     * from the browser's own copy (nothing fetched again), its links and whether it holds a video written down. Null when
     * the browser is gone.
     */
    private suspend fun keepPage(id: String, n: Int): PageSnapshot? {
        val s = browser?.takeIf { it.alive } ?: return null
        val here = runCatching { s.here() }.getOrNull() ?: return null
        val images = PageSnapshots.worth(runCatching { s.pictures() }.getOrDefault(emptyList()))
        val dir = files.file(CoursePaths.pictures(id, n))
        dir.deleteRecursively()
        val kept = images.mapNotNull { img ->
            val bytes = runCatching { s.resource(img.src) }.getOrNull()?.takeIf { it.isNotEmpty() && it.size <= PageSnapshots.MAX_BYTES } ?: return@mapNotNull null
            val ext = PageSnapshots.extension(bytes) ?: return@mapNotNull null
            dir.mkdirs()
            File(dir, "${img.n}.$ext").writeBytes(bytes)
            SavedPicture(img.n, "${img.n}.$ext", img.alt, img.src, img.near, img.w, img.h)
        }
        kept.firstOrNull()?.let { p -> monitor.picture(File(dir, p.file).readBytes(), "Chapter $n: ${kept.size} picture${if (kept.size == 1) "" else "s"} kept") }
        val links = runCatching { s.links() }.getOrDefault(emptyList()).map { (text, href) -> SavedLink(text, href) }
        val snap = PageSnapshot(here.url, here.title, System.currentTimeMillis(), links, kept, video = runCatching { s.hasVideo() }.getOrDefault(false))
        files.write(CoursePaths.snapshot(id, n), PageSnapshots.write(snap))
        return snap
    }

    /**
     * A chapter read before pages were kept (1.1.51): opened once more and kept whole — its replays found and its video
     * noticed from what was kept — so no later step opens it again but to watch its video. Its words gain the pictures'
     * markers when no notes rest on their sections yet.
     */
    private suspend fun savePage(course: Course, n: Int) {
        val chapter = course.chapter(n) ?: return
        if (BrowseGuard.openRefusal(chapter.url, course) != null) return save(course.with(chapter.copy(saved = true)))
        paced { surface(course).open(chapter.url) }
        val snap = keepPage(course.id, n)
        val text = pageText()
        val before = files.read(CoursePaths.page(course.id, n)).orEmpty()
        var c = load(course.id) ?: return
        c = c.found(n, DbReplays.found(snap?.links?.map { it.text to it.href }.orEmpty(), text + "\n" + before))
        val ch = c.chapter(n) ?: return
        val rewrite = ch.kind != Chapter.Kind.VIDEO && !ch.hasVideo && ch.notedThrough == 0 && ch.notesMark < 0 &&
            (ch.state == Chapter.State.READ || (ch.state == Chapter.State.NOTED && ch.depth < CourseDepth.CURRENT)) &&
            snap?.pictures?.isNotEmpty() == true && CourseText.words(text) >= CourseText.words(before) * 8 / 10
        if (rewrite) files.write(CoursePaths.page(course.id, n), text)
        val video = snap?.video == true
        save(
            c.with(
                ch.copy(
                    saved = true, pictures = snap?.pictures?.size ?: 0, error = "",
                    words = if (rewrite) CourseText.words(text) else ch.words,
                    // What the page holds is known now: a video still to watch is watched next; none, and that is settled.
                    videoChecked = true,
                    hasVideo = ch.hasVideo || video,
                    watched = if (video && !ch.hasVideo) false else ch.watched || !video,
                ),
            ),
        )
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
        val words = DbReplays.render(parsed, heading)
        files.write(CoursePaths.replayText(course.id, n), words)
        // The exam's replays are read and kept, never shown — not even on the monitor.
        if (!r.exam) monitor.reading("Replay $n: ${parsed.players.joinToString(" vs ")}", words, "replay $n")
        save(after.with(r.copy(state = Chapter.State.READ, error = "", players = parsed.players.joinToString(" vs "), games = parsed.games.size)))
    }

    private suspend fun replayNotes(course: Course, n: Int) {
        val r = course.replay(n) ?: return
        if (r.exam) return
        val text = files.read(CoursePaths.replayText(course.id, n)).orEmpty()
        noteInParts(
            course, text, CoursePaths.replayNotes(course.id, n), "replay $n", r.notedThrough, r.notesMark, CourseTools.STEP_REPLAY_NOTES,
            mark = { c, through, sections, at -> c.with((c.replay(n) ?: r).copy(notedThrough = through, sections = sections, notesMark = at)) },
            brief = { c, part, begun -> CourseBrief.replayNotesPart(c, c.replay(n) ?: r, part, begun) },
            last = { c, left, begun -> CourseBrief.uncoveredAgain("replay $n", r.players, left, begun) },
            done = { c, written ->
                val now = c.replay(n) ?: r
                if (!written) StudyQueue.replayFailed(c, n, "No notes were written.").let { f -> f.with(f.replay(n)!!.copy(notedThrough = 0, notesMark = -1)) }
                else c.renoted().with(now.copy(state = Chapter.State.NOTED, error = "", depth = CourseDepth.CURRENT, notedThrough = 0, notesMark = -1))
            },
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
    private suspend fun step(course: Course, kind: String, brief: String, room: Triple<Int, Int, String>? = null, steps: Int = steps(kind)): String? {
        val names = CourseTools.forStep(kind)
        val out = ai.studyStep(
            course.id, course.deckId, course.deckName, CourseBrief.withSkill(CourseBrief.system(ai.name, files.soul(ai.name), course), kind), brief,
            ai.tools.filter { it.name in names }, EFFORT, steps, room, monitor,
        )
        load(course.id)?.let { c -> save(c.copy(spent = c.spent + out.usage.input + out.usage.output + out.usage.cacheRead + out.usage.cacheWrite)) }
        return out.filled
    }

    /** A step's rounds (1.1.43): mastering a chapter is reading it in parts, checking its cards and writing many entries. */
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
                "course_pictures" -> coursePictures(course, run, ToolArgs.int(i, "chapter") ?: 0, ToolArgs.ints(i, "pictures"), ToolArgs.int(i, "from") ?: 0)
                "course_read" -> courseRead(course, ToolArgs.int(i, "chapter") ?: 0, ToolArgs.string(i, "what") ?: "text", ToolArgs.int(i, "from") ?: 0, sections(i))
                "course_notes" -> courseNotes(course, ToolArgs.int(i, "chapter") ?: 0, ToolArgs.string(i, "notes").orEmpty(), ToolArgs.bool(i, "append") == true)
                "course_page_save" -> pageSave(course, ToolArgs.int(i, "chapter") ?: 0)
                "replay_read" -> replayRead(course, ToolArgs.int(i, "replay") ?: 0, ToolArgs.string(i, "what") ?: "text", ToolArgs.int(i, "from") ?: 0, sections(i))
                "replay_notes" -> replayNotesWrite(course, ToolArgs.int(i, "replay") ?: 0, ToolArgs.string(i, "notes").orEmpty(), ToolArgs.bool(i, "append") == true)
                "course_cards" -> courseCards(course, ToolArgs.int(i, "chapter"), ToolArgs.int(i, "replay"), sections(i))
                "notes_coverage" -> notesCoverage(course, ToolArgs.int(i, "chapter"), ToolArgs.int(i, "replay"), sections(i))
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
            if (!currentCoroutineContext().isActive) throw c
            fail("$name failed: ${c.message ?: "it was cut short"}")
        } catch (t: Throwable) {
            fail("$name failed: ${t.message ?: t::class.simpleName}")
        }
    }

    /** The sections a read is limited to (`section`, `through`), or null for the whole. */
    private fun sections(i: JsonObject): IntRange? {
        val first = ToolArgs.int(i, "section") ?: return null
        return first..maxOf(first, ToolArgs.int(i, "through") ?: first)
    }

    /** [text] numbered, or only [only]'s sections of it. */
    private fun shown(text: String, only: IntRange?): String =
        if (only == null) Sections.numbered(text) else Sections.only(text, only.first, only.last).ifBlank { "(No sections ${only.first}–${only.last}: the text has ${Sections.of(text).size}.)" }

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

    private fun replayRead(course: Course, n: Int, what: String, from: Int, only: IntRange? = null): MetaAnswer {
        val r = course.replay(n) ?: return fail("No replay $n: course_state lists them.")
        if (r.exam) return fail("Replay $n is held out: it is the exam, never read in the study.")
        val notes = what == "notes"
        val text = files.read(if (notes) CoursePaths.replayNotes(course.id, n) else CoursePaths.replayText(course.id, n))
            ?: return fail(if (notes) "No notes on replay $n yet." else "Replay $n is not read yet.")
        val source = "${course.label}, replay $n (${r.url})" + if (notes) " (notes)" else ""
        val shown = if (notes) text else shown(text, only)
        return ok(Untrusted.wrap(source, CourseText.part(shown, from)), if (notes) "Read the notes on replay $n" else "Read replay $n: ${r.players.ifBlank { "a duel" }}" + (only?.let { " §${it.first}–§${it.last}" } ?: ""))
    }

    private fun replayNotesWrite(course: Course, n: Int, notes: String, append: Boolean): MetaAnswer {
        val r = course.replay(n) ?: return fail("No replay $n.")
        if (r.exam) return fail("Replay $n is held out: it is the exam.")
        if (notes.isBlank()) return fail("The notes are empty.")
        val all = keepNotes(CoursePaths.replayNotes(course.id, n), notes, append || r.notesMark >= 0) ?: return fail(NOTES_FULL)
        val (cited, of) = Sections.coverage(files.read(CoursePaths.replayText(course.id, n)).orEmpty(), all, "replay $n")
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

    private fun courseRead(course: Course, n: Int, what: String, from: Int, only: IntRange? = null): MetaAnswer {
        val chapter = course.chapter(n) ?: return fail("No chapter $n: course_state lists them.")
        val notes = what == "notes"
        val text = files.read(if (notes) CoursePaths.notes(course.id, n) else CoursePaths.page(course.id, n))
            ?: return fail(if (notes) "No notes on chapter $n yet." else "Chapter $n's text is not kept yet.")
        val source = "${course.label}, ch. $n “${chapter.title}”" + if (notes) " (notes)" else ""
        // The chapter with its sections numbered, as its notes cite them (§N).
        val shown = if (notes) text else shown(text, only)
        return ok(Untrusted.wrap(source, CourseText.part(shown, from)), if (notes) "Read the notes on chapter $n" else "Read chapter $n: ${chapter.title}" + (only?.let { " §${it.first}–§${it.last}" } ?: ""))
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

    /** The pictures kept from chapter [n]'s page ([PageSnapshot]): those [wanted] by number, else a few from [from]. */
    private fun coursePictures(course: Course, run: StudyRun, n: Int, wanted: List<Int>, from: Int): MetaAnswer {
        val chapter = course.chapter(n) ?: return fail("No chapter $n.")
        val snap = PageSnapshots.read(files.read(CoursePaths.snapshot(course.id, n)))
        val all = snap?.pictures.orEmpty()
        if (all.isEmpty()) return fail("No pictures were kept from chapter $n's page.")
        if (ai.sight == Vision.Sight.NO) return fail("This model cannot see pictures: what the page says of them is in its text.")
        val page = if (wanted.isNotEmpty()) all.filter { it.n in wanted } else all.drop(from.coerceAtLeast(0)).take(FRAMES_AT_ONCE)
        if (page.isEmpty()) return fail("Chapter $n kept pictures " + all.joinToString { it.n.toString() } + ".")
        val dir = files.file(CoursePaths.pictures(course.id, n))
        val pictures = page.mapNotNull { p ->
            val f = File(dir, p.file).takeIf { it.isFile } ?: return@mapNotNull null
            val bytes = f.readBytes()
            val picture = com.kaiharimoto.neue.platform.decodePicture(bytes)
            files.putImage("course-" + run.courseId, bytes, PageSnapshots.mediaType(p.file), picture?.width ?: 0, picture?.height ?: 0)
                .copy(data = java.util.Base64.getEncoder().encodeToString(bytes))
        }
        val shown = page.joinToString("; ") { p -> "Picture ${p.n}" + (if (p.alt.isNotBlank()) ": ${p.alt}" else "") + (if (p.near.isNotBlank()) " (after “${p.near.takeLast(80)}”)" else "") }
        val rest = all.filter { p -> page.none { it.n == p.n } }
        val words = "From chapter $n “${chapter.title}”, in order — $shown." + if (rest.isNotEmpty() && wanted.isEmpty()) " More: from ${from + page.size}." else ""
        return MetaAnswer(Untrusted.wrap("${course.label}, ch. $n (pictures)", words), "Looked at ${pictures.size} pictures from chapter $n", pictures = pictures)
    }

    private fun courseNotes(course: Course, n: Int, notes: String, append: Boolean): MetaAnswer {
        val chapter = course.chapter(n) ?: return fail("No chapter $n.")
        if (notes.isBlank()) return fail("The notes are empty.")
        // A part going (1.1.46): its notes add to the parts before, whatever it asks — the runner set them back already.
        val all = keepNotes(CoursePaths.notes(course.id, n), notes, append || chapter.notesMark >= 0) ?: return fail(NOTES_FULL)
        val (cited, of) = Sections.coverage(files.read(CoursePaths.page(course.id, n)).orEmpty(), all, "ch. $n")
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
    private fun courseCards(course: Course, chapter: Int?, replay: Int?, only: IntRange? = null): MetaAnswer {
        val (whole, named) = unit(course, chapter, replay) ?: return fail("Name a chapter or a replay.")
        val text = if (only == null) whole else Sections.only(whole, only.first, only.last)
        val label = if (only == null) named else "$named §${only.first}–§${only.last}"
        if (text.isBlank()) return fail("Nothing is kept of $label yet.")
        val index = ai.h.builder.index
        val names = CardMentions.find(text, index.cards.map { it.name })
        if (names.isEmpty()) return ok("$label names no card the pool knows.", "Found no cards in $label")
        val body = names.take(CARDS_AT_ONCE).joinToString("\n\n") { name -> index.byName(name)?.let { "$name — ${it.type}\n${it.description}" } ?: name }
        val more = if (names.size > CARDS_AT_ONCE) "\n\n(${names.size - CARDS_AT_ONCE} more: " + names.drop(CARDS_AT_ONCE).joinToString() + " — card_info reads them.)" else ""
        return ok("$label names ${names.size} cards:\n\n$body$more", "Read the ${names.size} cards $label names")
    }

    private fun notesCoverage(course: Course, chapter: Int?, replay: Int?, only: IntRange? = null): MetaAnswer {
        val (text, named) = unit(course, chapter, replay) ?: return fail("Name a chapter or a replay.")
        val notes = files.read(if (chapter != null) CoursePaths.notes(course.id, chapter) else CoursePaths.replayNotes(course.id, replay!!)).orEmpty()
        val worth = Sections.of(text).filter { it.words >= Sections.MIN_WORDS && (only == null || it.n in only) }
        val left = Sections.uncovered(text, notes, if (chapter != null) "ch. $chapter" else "replay $replay").filter { only == null || it.n in only }
        val of = worth.size
        val cited = of - left.size
        val label = if (only == null) named else "$named §${only.first}–§${only.last}"
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
        if (heldOut(course, url)) return fail(HELD_OUT)
        val page = paced { surface(course).open(url) }
        return ok("Open: ${page.title} — ${page.url}. Read it with browser_read.", "Opened ${page.title.ifBlank { page.url }}")
    }

    /**
     * Whether [url] is a replay held out for an exam — this course's or any other course's for the same deck, since a
     * replay one course holds out another may link to as well (1.1.47: the browser could open them).
     */
    private fun heldOut(course: Course, url: String): Boolean {
        val id = DbReplays.id(url) ?: return false
        return (courses().filter { it.deckId == course.deckId && it.id != course.id } + course)
            .any { c -> c.replays.any { it.exam && DbReplays.id(it.url) == id } }
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
        if (e.href.isNotBlank() && heldOut(course, e.href)) return fail(HELD_OUT)
        paced { s.click(e.ref); s.here() }
        shown = emptyMap()
        val here = s.here()
        // A press that opened a held-out replay comes back too: the exam's duels are never seen (1.1.47).
        if (heldOut(course, here.url)) {
            paced { s.open(course.start) }
            return fail("$HELD_OUT Went back to the course's start.")
        }
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

    /**
     * A replay for the library (1.1.51): its page opened in the study's browser as the person would open it, and what
     * DuelingBook sends the page kept — never asked for by the app. Not while a study holds the browser; the browser
     * closes after, unless the person is logging in to a course in it.
     */
    internal suspend fun receiveReplay(url: String): String {
        if (running) error("A course is being studied in the browser: pause it, then add the replay.")
        WebSurfaces.missing(ai.prefs.courseBrowser)?.let { error(it) }
        if (!url.startsWith("https://", ignoreCase = true) || DbReplays.id(url) == null) error("That is not a DuelingBook replay's address.")
        val s = lock.withLock { browser?.takeIf { it.alive } ?: WebSurfaces.launch(profile, "about:blank", ai.prefs.courseBrowser, visible = true).also { browser = it } }
        try {
            val got = s.openReceiving(url, DbReplays.DATA, REPLAY_WAIT_MS)
            val body = got.body ?: error(NOT_SENT)
            DbReplays.error(body)?.let { error("DuelingBook: $it") }
            return body
        } finally {
            if (!running && !awaitingLogin) closeBrowser()
        }
    }

    private fun closeBrowser() {
        browser?.let { b -> runCatching { b.close() } }
        browser = null
    }

    /** The page's words, each picture kept marked where it stands ("[Picture 3: …]", 1.1.51). */
    private suspend fun pageText(): String {
        val b = browser ?: return ""
        return HtmlText.text(b.markedHtml(), PAGE_CAP)
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

        /** One chapter's or replay's notes, all told (1.1.43: mastery notes are long; it was 40,000, silently cut). */
        const val NOTES_CAP = 300_000
        const val NOTES_FULL = "The notes have reached their limit for this part: write what is left into the playbook instead."

        /** A part's rounds (1.1.46): a few sections read, their cards checked, noted and written into the playbook. */
        const val PART_STEPS = 48

        /** The thought each step of a study is given (1.1.43: it was medium). */
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

        const val HELD_OUT = "That replay is held out for the exam: it is never opened in the study."

        /** What the panel says of a finished course this build can study further. */
        fun moreToStudy(course: Course): String {
            val shallow = course.chapters.count { it.state == Chapter.State.NOTED && it.depth < CourseDepth.CURRENT }
            val left = course.replays.count { it.state != Chapter.State.NOTED && !it.gaveUp && !it.exam }
            val unsaved = course.chapters.count { !it.saved && (it.state == Chapter.State.READ || it.state == Chapter.State.NOTED || it.state == Chapter.State.WAITING) }
            return when {
                // 1.1.51: every page kept on this computer, opened once more for it and never again.
                unsaved > 0 && shallow == 0 -> "This version keeps every page of a course on this computer — its pictures and links too — so it is " +
                    "never opened again. It can keep the $unsaved page${if (unsaved == 1) "" else "s"} it read before" +
                    if (left > 0) ", and study $left replay${if (left == 1) "" else "s"}." else "."
                shallow > 0 -> "This version studies a course to mastery: section by section, every card checked, a playbook written as it goes. " +
                    "It can take $shallow chapter${if (shallow == 1) "" else "s"} again from what it kept, without loading a page" +
                    if (left > 0) ", and $left replay${if (left == 1) "" else "s"}." else "."
                left > 0 -> "It links to $left DuelingBook replay${if (left == 1) "" else "s"} not studied yet."
                else -> "This version can study the DuelingBook replays a course links to: it looks for them in each chapter first."
            }
        }
    }
}
