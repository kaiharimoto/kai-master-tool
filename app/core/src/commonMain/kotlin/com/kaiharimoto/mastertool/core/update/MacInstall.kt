package com.kaiharimoto.mastertool.core.update

/**
 * Neue installing its own update on a Mac (Phase 4), instead of opening the
 * `.dmg` and asking for a drag.
 *
 * The app cannot replace its own bundle while it runs, so it writes this
 * script, starts it, and quits. The script waits for the process to be gone,
 * mounts the image, copies the new bundle beside the old one with `ditto`
 * (which keeps what a Mac bundle needs: symlinks, modes, extended attributes),
 * swaps the two — putting the old one back if the swap fails half-way — clears
 * the download quarantine flag, detaches the image and opens the app again.
 *
 * Which bundle is "the app" is read off the running launcher's path, and when
 * that is not inside a `.app` (a development run) there is nothing to replace:
 * [bundleOf] answers null and the caller falls back to opening the image.
 */
object MacInstall {

    /**
     * `/Applications/Neue Master Tool.app/Contents/MacOS/Neue Master Tool` →
     * `/Applications/Neue Master Tool.app`; anything not inside a bundle → null.
     */
    fun bundleOf(command: String): String? {
        val marker = ".app/Contents/"
        val at = command.indexOf(marker)
        if (at <= 0) return null
        return command.substring(0, at + ".app".length)
    }

    /** [value] as one `sh` word, whatever it contains. */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun script(dmg: String, bundle: String, pid: Long, log: String): String = """
        |#!/bin/sh
        |# Neue Master Tool's update, written by the build that is quitting.
        |DMG=${quote(dmg)}
        |APP=${quote(bundle)}
        |PID=$pid
        |exec >>${quote(log)} 2>&1
        |while kill -0 "${'$'}PID" 2>/dev/null; do sleep 0.2; done
        |MNT="${'$'}(mktemp -d /tmp/neue-update.XXXXXX)"
        |if hdiutil attach -nobrowse -noautoopen -readonly -mountpoint "${'$'}MNT" "${'$'}DMG"; then
        |  NEW="${'$'}(find "${'$'}MNT" -maxdepth 1 -name '*.app' | head -n 1)"
        |  rm -rf "${'$'}APP.new"
        |  if [ -n "${'$'}NEW" ] && ditto "${'$'}NEW" "${'$'}APP.new"; then
        |    rm -rf "${'$'}APP.old"
        |    if mv "${'$'}APP" "${'$'}APP.old"; then
        |      if mv "${'$'}APP.new" "${'$'}APP"; then rm -rf "${'$'}APP.old"; else mv "${'$'}APP.old" "${'$'}APP"; fi
        |    fi
        |    xattr -dr com.apple.quarantine "${'$'}APP" 2>/dev/null
        |  fi
        |  rm -rf "${'$'}APP.new"
        |  hdiutil detach "${'$'}MNT" -quiet
        |fi
        |rmdir "${'$'}MNT" 2>/dev/null
        |open "${'$'}APP"
        |""".trimMargin()
}
