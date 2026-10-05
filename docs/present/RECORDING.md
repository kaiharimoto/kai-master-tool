# Present: recording a take

Neue 1.1.13, the audit's track A (`docs/present/AUDIT.md`). kai: "a slideshow presentation creator that's animated
and interactive … record in app using a webcam", for YouTube deck profiles.

## The design: record what happened, draw the video afterwards

While the creator presents, nothing is drawn for the video. Three things are kept:

- **The events** (`TakeEvent`, written by `TakeLog` from what the presenter shows): each move to a slide and build,
  the whole deck on and off, a blank screen, the laser's points, the pen's points, chapters marked. Stamped on the
  take's own clock (`TakeClock`), which a pause stops, so a take has no gaps.
- **The camera**, as MJPEG in `camera.mkv`, each frame placed at its moment on the take's clock.
- **The microphone**, as 16-bit PCM in `audio.wav`; nothing is written while paused.

Then **Render** draws the video frame by frame with the presenter's own drawing (`StageView`, drawn out of
`PresentStage`), the presenter's state set from the events (`RenderPlan.walk`), the camera's recorded frame cropped
into its zone (`CameraFit`), and encodes it with the sound. So recording costs the show nothing, the video is the
slides to the pixel, and a take can be rendered again.

The presentation is **frozen** into the take's folder as recording begins (`presentation.json`): editing the
presentation later never changes a take.

## Where it is

| Part | Where |
|---|---|
| Events, clock, replay, chapters, encoder choice, crop, WAV, camera names, paths, settings | `core/present/record/` (commonTest `RecordTest`, `TakeTest`) |
| The holder: takes, the camera for the preview, a render | `neue/present/record/TakeLibrary.kt` (`Presentations.takes`) |
| Recording: count-in, pause, mark, stop | `neue/present/record/Recording.kt` |
| The bar, the light, the count-in, the camera picture, the host | `neue/present/record/RecordViews.kt` |
| Takes and Camera-and-microphone dialogs | `neue/present/record/TakesDialog.kt` |
| The seam | `neue/platform/Capture.kt` (`Capture`, `LiveCamera`, `LiveMic`, `TakeVideo`, `deliverCopy`) |
| The desk | `jvmMain/.../platform/Capture.jvm.kt`, `present/record/DeskMedia.kt`, `TakeRenderer.kt` |
| Android | `androidMain/.../platform/Capture.android.kt`: not yet (below) |

## Using it

- **Present ▾ › Record a take** (Ctrl Shift R in the editor) presents from this slide and counts in (3 s; Settings
  in the camera dialog: none, 3 or 5).
- While presenting: **R** records, pauses and carries on; **M** marks a chapter; **Shift R** stops and keeps the take
  (the show goes on); **Esc** ends the show and keeps the take. The recording bar (top left, the presenter's only — the
  audience's window never draws it, and the video never has it) does the same by mouse and finger: the light (click or
  tap pauses, right-click or a held finger marks), Pause, Mark, Stop. `PresentMouse`/`PresentTouch` hold the rows.
- **The live camera** stands in each slide's camera zone while presenting, recording or not, when the presentation's
  webcam is on and "Show my camera while presenting" is (the camera dialog). Mirrored by the Theme tab's switch
  (`WebcamZone.mirror`). Cropped to fill the zone's shape; the zone's own outline and border clip it.
- **Takes** (Present ▾ › Takes, Ctrl Alt R): each take's length, date, camera, sound, clicks and size; Render (again),
  Play, Save a copy, Show in folder, **Copy chapters** (YouTube's rules: `Chapters`, from slide titles and sections and
  marks), Rename, Delete. A render shows its last frame, frames done and the time left, and stops on Stop.
- **Camera and microphone** (Present ▾, the Theme tab, the Takes dialog and the start step `RECORD`, offered on the desk
  from Neue 1.1.13 / APK v1.3.91): which camera with its picture live, which microphone with a level meter, no sound,
  the count-in, 30 or 60 frames a second, the camera while presenting. On a Mac the system asks once.

## The desk: JavaCV and the LGPL FFmpeg

- `org.bytedeco:javacv:1.5.14` taken **without its transitives** (its POM pulls OpenCV, OpenBLAS, librealsense and
  more, about 1 GB), `org.bytedeco:javacpp:1.5.14` and `org.bytedeco:ffmpeg:8.1.2-1.5.14` named alone, each with the
  natives of the system the build runs on (`javacppPlatform` in `neue/build.gradle.kts`; `release-neue.yml` packages
  each installer on its own system). **Never an `-gpl` classifier.**
- Licences: JavaCV and JavaCPP Apache 2.0; FFmpeg LGPL v3 (built `--enable-version3`, without `--enable-gpl`). The LGPL,
  the GPL it builds on, and how to replace the libraries go with every installer: `neue/packaged/common/licenses/`
  (`appResourcesRootDir`). The libraries are separate shared objects JavaCPP unpacks at run time, so they can be
  replaced.
- Sizes per installer (Maven Central): natives macOS arm64 20.6 MB, macOS x64 24.0 MB, Linux x64 26.9 MB, Windows x64
  30.4 MB; JavaCV + JavaCPP + FFmpeg's Java classes about 1.3 MB. Measured on Linux: the distributable grew 222.1 → 250.7 MB (+28.6 MB), the .deb 125.5 → 153.8 MB (+28.3 MB).
- The camera: FFmpeg's device input — DirectShow (`dshow`, `video=<name>`) on Windows, AVFoundation (`<index>:none`) on a
  Mac, Video4Linux (`/dev/videoN`) on Linux — opened at 1280 × 720 at 30, else the camera's own size, else whatever it
  gives; frames as BGRA. DirectShow and AVFoundation list their cameras only by printing them, so the list is read off
  FFmpeg's log (`FfmpegLog`, `CameraNames`, tested on what each prints). Linux reads `/sys/class/video4linux`.
- The microphone: Java Sound, as the voice feature opens it, 48 kHz (else 44.1 kHz) mono 16-bit. Ai's listening stops
  as a take begins.
- The Mac: `NSCameraUsageDescription` and `NSMicrophoneUsageDescription` in `Info.plist`, and the
  `com.apple.security.device.camera` and `audio-input` entitlements for a signed build.

### Encoding

`EncoderPick`: the machine's own H.264 first — `h264_videotoolbox` on a Mac; `h264_mf`, then NVIDIA's, Intel's and
AMD's on Windows; NVIDIA's on Linux — with FFmpeg's AAC; else **VP9** (`libvpx-vp9`, BSD) with **Opus**, both
royalty-free, set for speed (`deadline=realtime`, `cpu-used=8`, `row-mt`). Always an MP4, which YouTube asks for.
**Never the bundled OpenH264**: it is compiled from source, so Cisco's patent licence (which covers only Cisco's own
binaries) does not cover it; `EncoderPick.NEVER` and `TakeTest` hold it out. Not WebM: JavaCV hands the muxer sound
packets' lengths in 1/48000 s, which WebM reads as milliseconds, and the file said its sound lasted almost a second
longer than it did; an MP4 keeps sound in 1/48000 s.

### Rendering

`TakeRenderer` (jvmMain) runs on a thread of its own: an `ImageComposeScene` at 1920 × 1080 holding `StageView` under the
window's own composition locals (handed over by `TakesHost`, with the frozen presentation's `SlideContext`, so cards,
fonts and pictures are the window's), each frame's pixels read as BGRA and handed to FFmpeg. Every slide the take shows
is first drawn until its art and pictures have arrived (`ArtWaits`, at most 10 s a slide). Cancelling stops it and
deletes the half-written file. The take's `rendered`, `renderedCodec` and `renderedAt` record the result; a take keeps
one video, named after it.

## Stored

`<data>/present/<presentation>/takes/<take>/`: `take.json` (`TakeCodec`, unknown keys skipped; `finished` false while
recording, so a take the app closed on says so — written every five seconds while recording, so such a take still renders up
to then), `presentation.json`, `camera.mkv`, `audio.wav`, `<name>.mp4`.

- **Device-only.** A minute of camera is about 60 MB, so takes are never synced and never backed up: `TakePaths.syncs`
  says no to any path in a `takes` folder, and `NeueSyncLocal` (out and in) and `BackupCenter` (backup and restore) ask
  it. Deleting a presentation deletes its takes.
- **Settings**: `NeuePreferences.record` (`RecordPrefs`: camera, microphone, muted, count-in, live camera, frames a
  second, chosen) — `SyncedPrefs.DEVICE`, `AiSettings.INTERNAL` (Ai never opens a camera by changing a setting).
- `OldDataTest` holds 1.1.13's `take.json` and settings without `record`.

## Android: the seam

`Capture.canRecord` and `TakeVideo.canRender` are false on Android, and `Capture.whyNot` says so wherever recording is
offered; the camera zone shows its fill, as before. The plan: CameraX (Apache 2.0) for the camera and its preview,
`MediaRecorder` for camera and microphone, and a renderer that draws `StageView` through a `GraphicsLayer` into a
`MediaCodec` input surface muxed by `MediaMuxer` — the device's own H.264, never FFmpeg (whose Android natives alone are
23 MB). `CAMERA` is already declared for the scanner. Takes made on the desk are never synced, so a phone never sees
one.

## Proof without a camera

- `-Dneue.camera=synthetic` (or `NEUE_CAMERA=synthetic`) puts `SyntheticCamera` (moving bands, a square, a frame counter
  in binary) and a silent microphone in place of the devices.
- `TakeRenderTest` (neue jvmTest) writes three seconds of synthetic camera as the live camera does, silent sound, a take
  with two slides, the laser and the pen; renders it at 640 × 360; reads it back (90 pictures, sound) and with ffprobe
  when present. Here: VP9 + Opus MP4, 3.000 s both streams. It also records 1.3 s through the live camera and the
  silent microphone, with a pause, and reads both files back.
- `tools/shoot.sh --page=present --present=demo --present-record=setup|countdown|bar|paused|takes|rendering|render`.
