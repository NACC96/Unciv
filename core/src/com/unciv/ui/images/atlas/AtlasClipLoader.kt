package com.unciv.ui.images.atlas

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.TextureData
import com.badlogic.gdx.graphics.glutils.PixmapTextureData
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One bounded CPU worker. No file read/decode/upload runs inline in a cache request. */
class AtlasClipLoader(
    private val directory: FileHandle = Gdx.files.internal("atlas-animation"),
    internal val application: Application = Gdx.app,
    queueCapacity: Int = 8
) : AutoCloseable {
    private val closed = AtomicBoolean()
    @Volatile private var contextAvailable = true
    @Volatile private var glThread: Thread? = null
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(queueCapacity.also { require(it in 1..32) }),
        { job -> Thread(job, "Atlas clip decoder").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())

    internal fun loadManifest(callback: (Result<AtlasArtManifest>) -> Unit): Boolean = submit {
        val result = runCatching {
            val file = directory.child("manifest.json")
            val bytes = readBounded(file, MAX_MANIFEST_BYTES)
            check(!closed.get()) { "Atlas loader closed" }
            AtlasArtManifest.parse(bytes.toString(Charsets.UTF_8))
        }
        callback(result)
    }

    /** Caller reserves RGBA decoded bytes before invoking this method; completion is on the worker. */
    internal fun decode(page: PageSpec, callback: (Result<DecodedPage>) -> Unit): Boolean = submit {
        val result = runCatching {
            check(!closed.get()) { "Atlas loader closed" }
            require(page.fileName == "${page.name}.png" && page.width in 1..AtlasArtManifest.MAX_PAGE_SIZE &&
                page.height in 1..AtlasArtManifest.MAX_PAGE_SIZE && page.decodedBytes == page.width.toLong() * page.height * 4) {
                "Invalid Atlas decode allocation"
            }
            val file = directory.child(page.fileName)
            val bytes = readBounded(file, MAX_ENCODED_PAGE_BYTES)
            // Verify the allocation before the native decoder can allocate it, including for corrupt files.
            ByteArrayInputStream(bytes).use { input ->
                val header = DataInputStream(input)
                require(header.readLong() == PNG_SIGNATURE && header.readInt() == 13 && header.readInt() == IHDR) { "Invalid PNG header" }
                require(header.readInt() == page.width && header.readInt() == page.height &&
                    header.readUnsignedByte() == 8 && header.readUnsignedByte() == 6 &&
                    header.readUnsignedByte() == 0 && header.readUnsignedByte() == 0 && header.readUnsignedByte() in 0..1) {
                    "Page must be a matching RGBA8888 PNG"
                }
            }
            check(!closed.get()) { "Atlas loader closed" }
            val pixmap = Pixmap(bytes, 0, bytes.size)
            try {
                require(pixmap.width == page.width && pixmap.height == page.height &&
                    pixmap.format == Pixmap.Format.RGBA8888 && pixmap.pixels.capacity().toLong() == page.decodedBytes) {
                    "Decoded page differs from its declared allocation"
                }
                check(!closed.get()) { "Atlas loader closed" }
                DecodedPage(pixmap)
            } catch (ex: Throwable) { pixmap.dispose(); throw ex }
        }
        callback(result)
    }

    private fun submit(action: () -> Unit): Boolean {
        if (closed.get()) return false
        return try { worker.execute(action); true } catch (_: RejectedExecutionException) { false }
    }

    /** Always queued: allocation/disposal never occurs within a request or an actor draw callback. */
    internal fun postGl(action: () -> Unit): Boolean {
        if (!contextAvailable || Gdx.app !== application) return false
        return try {
            application.postRunnable {
                glThread = Thread.currentThread()
                action()
            }
            true
        } catch (_: RuntimeException) { false }
    }

    /** Only invoked by libGDX's GL-thread lifecycle callbacks, outside rendering. */
    internal fun lifecycleGl(action: () -> Unit) {
        glThread = Thread.currentThread()
        action()
    }

    internal fun upload(page: PageSpec, decoded: DecodedPage): Texture {
        check(Thread.currentThread() === glThread && contextAvailable && Gdx.app === application && Gdx.gl20 != null) { "Atlas upload requires its live GL thread" }
        return decoded.withPixmap { pixmap ->
            // Supply already prepared RGBA data. Hardware mip generation avoids CPU mipmap Pixmaps.
            val data = PixmapTextureData(pixmap, Pixmap.Format.RGBA8888, false, false, false)
            val handle = Gdx.gl.glGenTexture()
            var texture: AtlasPageTexture? = null
            try {
                texture = AtlasPageTexture(handle, data)
                if (page.mipmaps) { texture.bind(); Gdx.gl.glGenerateMipmap(GL20.GL_TEXTURE_2D) }
                texture.setFilter(if (page.mipmaps) Texture.TextureFilter.MipMapLinearLinear else Texture.TextureFilter.Linear,
                    Texture.TextureFilter.Linear)
                texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge)
                val error = Gdx.gl.glGetError()
                check(error == GL20.GL_NO_ERROR) { "Atlas page upload failed with GL error $error" }
                texture
            } catch (ex: Throwable) {
                if (texture != null) texture.dispose() else Gdx.gl.glDeleteTexture(handle)
                throw ex
            }
        }
    }

    internal fun disposeTexture(texture: Texture) {
        check(Thread.currentThread() === glThread && contextAvailable && Gdx.app === application) { "Atlas disposal requires its live GL thread" }
        texture.dispose()
    }

    /** A handle from an earlier/lost context must never delete a newly reused GL texture id. */
    internal fun abandonTexture(texture: Texture) { (texture as AtlasPageTexture).abandon() }
    internal fun contextGone() { contextAvailable = false }
    internal fun hasContext() = contextAvailable && Gdx.app === application
    internal fun queuedJobs() = worker.queue.size

    /** Let admitted jobs finish/callback; never lose native staging allocations through queue cancellation. */
    override fun close() { if (closed.compareAndSet(false, true)) worker.shutdown() }

    internal class DecodedPage(private val pixmap: Pixmap) : AutoCloseable {
        private var closed = false
        @Synchronized fun <T> withPixmap(action: (Pixmap) -> T): T {
            check(!closed) { "Decoded Atlas page closed" }
            return action(pixmap)
        }
        @Synchronized override fun close() {
            if (closed) return
            closed = true
            pixmap.dispose()
        }
    }

    private class AtlasPageTexture(handle: Int, data: TextureData) : Texture(GL20.GL_TEXTURE_2D, handle, data) {
        fun abandon() { glHandle = 0 }
    }

    private fun readBounded(file: FileHandle, limit: Int): ByteArray {
        val length = file.length()
        require(length <= limit) { "Atlas input exceeds bounded read limit" }
        return file.read().use { input ->
            val output = ByteArrayOutputStream(minOf(maxOf(length, 1024L), limit.toLong()).toInt())
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                require(count <= limit - total) { "Atlas input exceeds bounded read limit" }
                output.write(buffer, 0, count); total += count
            }
            output.toByteArray()
        }
    }

    companion object {
        private const val PNG_SIGNATURE = -8552249625308161526L
        private const val IHDR = 0x49484452
        private const val MAX_ENCODED_PAGE_BYTES = 5 * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 64 * 1024 * 1024
    }
}
