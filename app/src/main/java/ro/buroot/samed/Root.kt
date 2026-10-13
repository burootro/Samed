package ro.buroot.samed

import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

/**
 * Minimal root shell helper.
 *
 * Opens a single long-lived `su` process and feeds commands to it one at a
 * time. This is faster and more reliable than spawning a new `su` process for
 * every command, and it keeps a single root grant for the whole app session.
 */
object Root {

    data class Result(val exitCode: Int, val stdout: String, val stderr: String)

    private var process: Process? = null
    private var stdin: DataOutputStream? = null

    @Synchronized
    fun isAvailable(): Boolean {
        return try {
            val r = runOnce("id")
            r.exitCode == 0 && r.stdout.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Runs a single command in a fresh `su -c` invocation and returns its
     * full output. Used for one-shot checks.
     */
    @Synchronized
    fun runOnce(command: String): Result {
        return try {
            val p = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(false)
                .start()
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            val code = p.waitFor()
            Result(code, out.trim(), err.trim())
        } catch (e: Exception) {
            Result(-1, "", e.message ?: "exception")
        }
    }

    /**
     * Ensures the persistent root shell is running. Returns true on success.
     */
    @Synchronized
    private fun ensureShell(): Boolean {
        val existing = process
        if (existing != null) {
            try {
                existing.exitValue() // throws if still alive -> good
                // if no exception, process already exited -> recreate
            } catch (e: IllegalThreadStateException) {
                return true
            }
        }
        return try {
            val p = ProcessBuilder("su").redirectErrorStream(false).start()
            process = p
            stdin = DataOutputStream(p.outputStream)
            true
        } catch (e: Exception) {
            process = null
            stdin = null
            false
        }
    }

    /**
     * Runs a command in the persistent shell and captures its output using a
     * unique end marker. Returns stdout text (stderr is merged into the shell
     * but not captured separately here).
     */
    @Synchronized
    fun exec(command: String): String {
        if (!ensureShell()) return ""
        val marker = "___SAMED_DONE_${System.nanoTime()}___"
        val out = StringBuilder()
        try {
            val writer = stdin ?: return ""
            val reader = BufferedReader(InputStreamReader(process!!.inputStream))
            writer.writeBytes(command + "\n")
            writer.writeBytes("echo $marker\n")
            writer.flush()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.contains(marker)) break
                out.appendLine(line)
            }
        } catch (e: Exception) {
            // Shell died; drop it so it is recreated next time.
            process = null
            stdin = null
        }
        return out.toString().trim()
    }

    @Synchronized
    fun close() {
        try {
            stdin?.writeBytes("exit\n")
            stdin?.flush()
        } catch (_: Exception) {
        }
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
        process = null
        stdin = null
    }
}
