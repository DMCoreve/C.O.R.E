package com.dmcore.core.network

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

data class InteractResult(
    val transcript: String,
    val responseText: String,
    val audioBase64: String,
    val action: JSONObject?,
)

data class NoteResult(
    val transcript: String,
    val noteId: String,
)

/**
 * Cliente del backend de C.O.R.E. ("cerebro" compartido). Cambia BASE_URL por la IP
 * local del backend (ver backend/README.md) mientras no haya deploy remoto.
 */
object BackendClient {

    // IP local de la PC donde corre backend/ (ver README del backend). Cambia si tu PC
    // cambia de red o de IP: en Windows, `ipconfig` -> "Dirección IPv4" del adaptador LAN.
    private const val BASE_URL = "http://192.168.1.185:8787"

    private val client = OkHttpClient()

    fun interactWithText(text: String, onResult: (Result<InteractResult>) -> Unit) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("text", text)
            .build()

        val request = Request.Builder()
            .url("$BASE_URL/interact")
            .post(body)
            .build()

        client.newCall(request).enqueue(resultCallback(onResult))
    }

    fun interactWithAudio(audioFile: java.io.File, onResult: (Result<InteractResult>) -> Unit) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "audio",
                audioFile.name,
                audioFile.asRequestBody("audio/wav".toMediaType()),
            )
            .build()

        val request = Request.Builder()
            .url("$BASE_URL/interact")
            .post(body)
            .build()

        client.newCall(request).enqueue(resultCallback(onResult))
    }

    fun uploadNote(audioFile: java.io.File, onResult: (Result<NoteResult>) -> Unit) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "audio",
                audioFile.name,
                audioFile.asRequestBody("audio/mp4".toMediaType()),
            )
            .build()

        val request = Request.Builder()
            .url("$BASE_URL/note")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val bodyString = it.body?.string()
                    if (!it.isSuccessful || bodyString == null) {
                        onResult(Result.failure(IOException("Backend respondió ${it.code}")))
                        return
                    }
                    val json = JSONObject(bodyString)
                    onResult(
                        Result.success(
                            NoteResult(
                                transcript = json.getString("transcript"),
                                noteId = json.getString("note_id"),
                            ),
                        ),
                    )
                }
            }
        })
    }

    private fun resultCallback(onResult: (Result<InteractResult>) -> Unit) = object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            onResult(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            response.use {
                val bodyString = it.body?.string()
                if (!it.isSuccessful || bodyString == null) {
                    onResult(Result.failure(IOException("Backend respondió ${it.code}")))
                    return
                }
                val json = JSONObject(bodyString)
                onResult(
                    Result.success(
                        InteractResult(
                            transcript = json.getString("transcript"),
                            responseText = json.getString("response_text"),
                            audioBase64 = json.getString("audio_base64"),
                            action = json.optJSONObject("action"),
                        ),
                    ),
                )
            }
        }
    }
}

private fun java.io.File.asRequestBody(mediaType: okhttp3.MediaType) =
    this.readBytes().toRequestBody(mediaType)
