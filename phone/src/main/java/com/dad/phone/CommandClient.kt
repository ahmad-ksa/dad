package com.dad.phone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Sends a single command to the tablet and returns its JSON reply (or an error). */
class CommandClient(private val token: String) {

    data class Result(val ok: Boolean, val body: String)

    suspend fun send(host: String, port: Int, command: String): Result =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = URL("http://$host:$port/cmd/$command")
                (url.openConnection() as HttpURLConnection).run {
                    requestMethod = "POST"
                    connectTimeout = 3000
                    readTimeout = 3000
                    setRequestProperty(Protocol.HEADER_TOKEN, token)
                    doOutput = true
                    outputStream.use { it.write(ByteArray(0)) }
                    val code = responseCode
                    val text = (if (code in 200..299) inputStream else errorStream)
                        ?.bufferedReader()?.use { it.readText() } ?: ""
                    disconnect()
                    Result(code in 200..299, text)
                }
            }.getOrElse { Result(false, it.message ?: "network_error") }
        }
}
