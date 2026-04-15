package com.example.tinkletmjpeg

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class MjpegServer(port: Int = 8080) : NanoHTTPD(port) {
    private val frameQueue = ArrayBlockingQueue<ByteArray>(1)
    @Volatile
    private var fallbackFrame: ByteArray = createPlaceholderFrame()
    @Volatile
    private var running = false

    override fun start(timeout: Int, daemon: Boolean) {
        super.start(timeout, daemon)
        running = true
    }

    override fun stop() {
        running = false
        super.stop()
    }

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/stream" -> streamResponse()
            "/health" -> newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        }
    }

    fun pushFrame(jpegBytes: ByteArray) {
        frameQueue.poll()
        fallbackFrame = jpegBytes
        frameQueue.offer(jpegBytes)
    }

    private fun streamResponse(): Response {
        val bodyStream = MjpegInputStream()
        val response = newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=frame",
            bodyStream
        )
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("Pragma", "no-cache")
        response.addHeader("Connection", "close")
        return response
    }

    private inner class MjpegInputStream : InputStream() {
        private var chunk = ByteArray(0)
        private var index = 0

        override fun read(): Int {
            if (!ensureChunk()) {
                return -1
            }
            return chunk[index++].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (!ensureChunk()) {
                return -1
            }
            val readable = minOf(len, chunk.size - index)
            System.arraycopy(chunk, index, buffer, off, readable)
            index += readable
            return readable
        }

        private fun ensureChunk(): Boolean {
            while (index >= chunk.size) {
                if (!running) {
                    return false
                }

                val frame = try {
                    frameQueue.poll(2, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }

                val nextFrame = frame ?: fallbackFrame
                chunk = composePart(nextFrame)
                index = 0
            }
            return true
        }

        private fun composePart(frame: ByteArray): ByteArray {
            val header = (
                "--frame\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: ${frame.size}\r\n\r\n"
                ).toByteArray(StandardCharsets.US_ASCII)
            val tail = "\r\n".toByteArray(StandardCharsets.US_ASCII)

            val output = ByteArrayOutputStream(header.size + frame.size + tail.size)
            output.write(header)
            output.write(frame)
            output.write(tail)
            return output.toByteArray()
        }

        override fun close() {
            try {
                super.close()
            } catch (_: IOException) {
                // Ignore close exceptions for streaming response.
            }
        }
    }

    private fun createPlaceholderFrame(): ByteArray {
        val width = 640
        val height = 480
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(24, 24, 24))

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 34f
            isFakeBoldText = true
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(180, 180, 180)
            textSize = 24f
        }

        canvas.drawText("TINKLET STREAM", 28f, 80f, titlePaint)
        canvas.drawText("Waiting for camera frames...", 28f, 130f, bodyPaint)

        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, output)
        return output.toByteArray()
    }
}
