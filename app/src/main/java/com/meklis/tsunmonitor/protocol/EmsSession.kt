package com.meklis.tsunmonitor.protocol

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * One TCP session to an AECC/SXD EMS device (JSON over raw TCP, default port 8080).
 *
 * The device accepts a single client at a time, so sessions are short:
 * open, send a few requests, close.
 */
class EmsSession(
    host: String,
    port: Int = DEFAULT_PORT,
    connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 10_000,
) : Closeable {

    private val socket = Socket()
    private var serial = 0

    init {
        try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
        } catch (e: IOException) {
            socket.close()
            throw e
        }
    }

    fun request(method: String, verb: String = "Get", params: Map<String, Any> = emptyMap()): JSONObject {
        serial = serial % 65535 + 1
        val msg = JSONObject()
            .put(verb, method)
            .put("SerialNumber", serial)
            .put("CommandSource", "HA")
        params.forEach { (k, v) -> msg.put(k, v) }
        socket.getOutputStream().apply {
            write((msg.toString() + "\n").toByteArray())
            flush()
        }
        val deadline = System.currentTimeMillis() + readTimeoutMs
        while (true) {
            val resp = readJson(deadline)
            // A late reply to another client's request (e.g. a Modbus pass-through
            // that outlived its connection) can arrive first: skip it.
            if (resp.optString("Response") == method) {
                // The device ignores requests that arrive too quickly back to back.
                Thread.sleep(REQUEST_GAP_MS)
                return resp
            }
        }
    }

    private val pending = ByteArrayOutputStream()

    /** Reads one JSON object; bytes after it stay buffered for the next call. */
    private fun readJson(deadline: Long): JSONObject {
        val input = socket.getInputStream()
        val chunk = ByteArray(8192)
        while (true) {
            extractObject()?.let { return it }
            if (System.currentTimeMillis() > deadline) throw SocketTimeoutException("Response timeout")
            val n = input.read(chunk)
            if (n < 0) throw IOException("Connection closed by device")
            pending.write(chunk, 0, n)
        }
    }

    /** Takes the first complete top-level JSON object out of [pending], if any. */
    private fun extractObject(): JSONObject? {
        val text = pending.toString(Charsets.UTF_8.name())
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> {}
                c == '{' -> depth++
                c == '}' -> if (--depth == 0) {
                    val rest = text.substring(i + 1)
                    pending.reset()
                    pending.write(rest.toByteArray(Charsets.UTF_8))
                    return try {
                        JSONObject(text.substring(start, i + 1))
                    } catch (_: JSONException) {
                        null
                    }
                }
            }
        }
        return null
    }

    fun energyParameter(): JSONObject = request("EnergyParameter")

    /** Numbered control registers, read in chunks of 10. */
    fun registers(addrs: List<Int>): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        addrs.chunked(10).forEach { chunk ->
            val info = request("Energycontrolparameters", params = mapOf("RegControlAddr" to JSONArray(chunk)))
                .optJSONObject("ControlInfo") ?: return@forEach
            info.keys().forEach { k -> k.toIntOrNull()?.let { result[it] = info.getString(k) } }
        }
        return result
    }

    fun setRegisters(values: Map<Int, String>): JSONObject {
        val info = JSONObject()
        values.forEach { (k, v) -> info.put(k.toString(), v) }
        return request("Energycontrolparameters", verb = "Set", params = mapOf("SetControlInfo" to info))
    }

    /**
     * Modbus RTU read (function 3) through the DataTransmission pass-through.
     * Returns register values by address, or null if the device did not answer properly.
     */
    fun modbusRead(slave: Int, start: Int, count: Int): Map<Int, Int>? {
        val frame = byteArrayOf(
            slave.toByte(), 3, (start shr 8).toByte(), start.toByte(), (count shr 8).toByte(), count.toByte(),
        )
        val crc = crc16(frame)
        val full = frame + byteArrayOf(crc.toByte(), (crc shr 8).toByte())
        val cmd = JSONObject()
            .put("FunctionCode", 3)
            .put("TransmittedData", full.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) })
        val resp = request("DataTransmission", verb = "Set", params = mapOf("SetCommand" to cmd))
        val hex = resp.optJSONObject("CommandResponse")?.optString("TransmittedData") ?: return null
        val data = hex.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16) }
        if (data.size < 3 + 2 * count || data[1] != 3 || data[2] != 2 * count) return null
        return (0 until count).associate { i -> start + i to (data[3 + 2 * i] shl 8 or data[4 + 2 * i]) }
    }

    override fun close() = socket.close()

    companion object {
        const val DEFAULT_PORT = 8080
        const val REQUEST_GAP_MS = 500L

        fun crc16(data: ByteArray): Int {
            var crc = 0xFFFF
            for (b in data) {
                crc = crc xor (b.toInt() and 0xFF)
                repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
            }
            return crc
        }
    }
}
