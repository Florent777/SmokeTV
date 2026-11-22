package io.github.florent777.smoketv

import android.graphics.BitmapFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Tiny temporary HTTP server for uploading a wallpaper from a phone on the same Wi-Fi.
 * GET / serves an upload page; POST /upload takes the raw image bytes (the page downsizes
 * the photo in the browser first). Runs only while the upload panel is open.
 */
class WallpaperServer(
    private val dest: File,
    private val onStatus: (String) -> Unit,
    private val onWallpaper: () -> Unit,
) {
    private var socket: ServerSocket? = null

    /** Binds 8080 (or any free port if taken) and returns the port. */
    fun start(): Int {
        val s = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(8080)) }
        } catch (_: IOException) {
            ServerSocket(0)
        }
        socket = s
        thread(name = "wallpaper-server", isDaemon = true) {
            while (!s.isClosed) {
                val client = try { s.accept() } catch (_: IOException) { break }
                // One thread per connection: browsers open idle pre-connections.
                thread(isDaemon = true) {
                    try { handle(client) } catch (_: Exception) { }
                }
            }
        }
        return s.localPort
    }

    fun stop() {
        try { socket?.close() } catch (_: IOException) { }
        socket = null
    }

    private fun handle(client: Socket) = client.use { c ->
        c.soTimeout = 20_000
        val input = BufferedInputStream(c.getInputStream())
        val out = BufferedOutputStream(c.getOutputStream())
        val request = readLine(input) ?: return
        val parts = request.split(' ')
        if (parts.size < 2) return
        var length = -1L
        while (true) {
            val h = readLine(input) ?: return
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0 && h.substring(0, i).trim().equals("content-length", ignoreCase = true)) {
                length = h.substring(i + 1).trim().toLongOrNull() ?: -1L
            }
        }
        val path = parts[1].substringBefore('?')
        when {
            parts[0] == "GET" && (path == "/" || path == "/index.html") ->
                respond(out, 200, "text/html; charset=utf-8", PAGE.toByteArray())
            parts[0] == "POST" && path == "/upload" -> upload(input, out, length)
            else -> respond(out, 404, "text/plain", "Not found".toByteArray())
        }
    }

    private fun upload(input: InputStream, out: OutputStream, length: Long) {
        if (length <= 0 || length > MAX_BYTES) {
            respond(out, 413, "text/plain", "Image missing or larger than 40 MB".toByteArray())
            return
        }
        onStatus("Receiving image (${length / 1024} KB)…")
        val tmp = File(dest.parentFile, dest.name + ".part")
        FileOutputStream(tmp).use { fo ->
            val buf = ByteArray(64 * 1024)
            var left = length
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) { tmp.delete(); throw IOException("upload cut off") }
                fo.write(buf, 0, n)
                left -= n
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(tmp.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            tmp.delete()
            onStatus("That file is not an image the TV can read.")
            respond(out, 415, "text/plain", "Not a supported image".toByteArray())
            return
        }
        if (!tmp.renameTo(dest)) {
            dest.delete()
            tmp.renameTo(dest)
        }
        respond(out, 200, "text/plain", "ok".toByteArray())
        onWallpaper()
    }

    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.size() == 0) null else line.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) line.write(b)
            if (line.size() > 8192) throw IOException("header too long")
        }
        return line.toString("ISO-8859-1")
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = when (code) { 200 -> "OK"; 404 -> "Not Found"; 413 -> "Payload Too Large"; else -> "Unsupported Media Type" }
        out.write(
            ("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()
        )
        out.write(body)
        out.flush()
    }

    private companion object {
        const val MAX_BYTES = 40L * 1024 * 1024

        val PAGE = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>SmokeTV wallpaper</title>
<style>body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#111;color:#eee;font-family:system-ui,sans-serif}
main{max-width:340px;padding:24px;text-align:center}label{display:block;margin:24px 0;padding:18px;border:1px solid #555;border-radius:14px;font-size:18px}
input{display:none}#s{min-height:1.4em;color:#aaa}</style></head><body><main>
<h2>Upload wallpaper</h2><p>Pick a photo. It goes straight to your Fire TV over your Wi-Fi.</p>
<label>Choose image<input id="f" type="file" accept="image/*"></label><p id="s"></p></main>
<script>
const s=document.getElementById('s');
document.getElementById('f').onchange=async e=>{
 const file=e.target.files[0]; if(!file) return;
 s.textContent='Preparing…'; let body=file;
 try{ const b=await createImageBitmap(file);
  const k=Math.min(1,Math.max(1920/b.width,1080/b.height));
  const c=document.createElement('canvas'); c.width=Math.round(b.width*k); c.height=Math.round(b.height*k);
  c.getContext('2d').drawImage(b,0,0,c.width,c.height);
  body=(await new Promise(r=>c.toBlob(r,'image/jpeg',0.9)))||file;
 }catch(_){}
 s.textContent='Uploading…';
 try{ const r=await fetch('/upload',{method:'POST',body,headers:{'Content-Type':'application/octet-stream'}});
  s.textContent=r.ok?'Done. Look at your TV.':'Upload failed: '+await r.text();
 }catch(err){ s.textContent='Upload failed: '+err; }
 e.target.value='';
};
</script></body></html>"""
    }
}
