package com.dmcore.core.network

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class InteractResult(
    val transcript: String,
    val responseText: String,
    val action: JSONObject?,
)

data class NoteResult(
    val transcript: String,
    val noteId: String,
)

/** El servidor respondió, pero con un código HTTP de error (p. ej. 429 = cuota de Gemini). */
class BackendHttpException(val code: Int) : IOException("El servidor respondió $code")

/** Cliente del backend de C.O.R.E. ("cerebro" compartido), desplegado en Render. */
object BackendClient {

    private const val TAG = "BackendClient"

    // Backend en Render (ver backend/README.md → "Deploy en Render"). Plan Free: se
    // "duerme" tras 15 min sin tráfico y la primera llamada tarda 30-50s en despertarlo.
    // CoreForegroundService lo mantiene despierto con warmUp() mientras escucha.
    private const val BASE_URL = "https://c-o-r-e-d6g3.onrender.com"

    // Los timeouts por defecto de OkHttp (10s) cortaban la llamada mientras Render
    // arrancaba; el overlay quedaba en "Pensando" y después mostraba error.
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build()

    /** Despierta el servidor (o lo mantiene despierto). Fire-and-forget. */
    fun warmUp() {
        val request = Request.Builder().url("$BASE_URL/health").get().build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "warmUp falló: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    /**
     * El texto ya viene transcrito on-device (SpeechRecognizer) y la respuesta se
     * habla con el TTS del teléfono, así que se pide speak=false: el servidor solo
     * llama a Gemini. tz hace que "a las 5" sea las 5 de acá, no de UTC.
     */
    fun interactWithText(text: String, onResult: (Result<InteractResult>) -> Unit) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("text", text)
            .addFormDataPart("tz", TimeZone.getDefault().id)
            .addFormDataPart("speak", "false")
            .build()

        val request = Request.Builder()
            .url("$BASE_URL/interact")
            .post(body)
            .build()

        val startedAt = System.currentTimeMillis()
        client.newCall(request).enqueue(jsonCallback(onResult) { json ->
            Log.i(TAG, "interact respondió en ${System.currentTimeMillis() - startedAt}ms")
            InteractResult(
                transcript = json.getString("transcript"),
                responseText = json.getString("response_text"),
                action = json.optJSONObject("action"),
            )
        })
    }

    fun uploadNote(audioFile: java.io.File, onResult: (Result<NoteResult>) -> Unit) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "audio",
                audioFile.name,
                audioFile.readBytes().toRequestBody("audio/mp4".toMediaType()),
            )
            .build()

        val request = Request.Builder()
            .url("$BASE_URL/note")
            .post(body)
            .build()

        client.newCall(request).enqueue(jsonCallback(onResult) { json ->
            NoteResult(
                transcript = json.getString("transcript"),
                noteId = json.getString("note_id"),
            )
        })
    }

    /**
     * Toda falla (red, HTTP no-2xx, JSON inesperado) llega como Result.failure: una
     * excepción sin atrapar dentro del callback de OkHttp tumbaría la app entera.
     */
    private fun <T> jsonCallback(
        onResult: (Result<T>) -> Unit,
        parse: (JSONObject) -> T,
    ) = object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Log.w(TAG, "${call.request().url.encodedPath} falló", e)
            onResult(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            val result = response.use {
                val bodyString = it.body?.string()
                if (!it.isSuccessful || bodyString == null) {
                    Log.w(TAG, "${call.request().url.encodedPath} → ${it.code}: ${bodyString?.take(300)}")
                    Result.failure(BackendHttpException(it.code))
                } else {
                    try {
                        Result.success(parse(JSONObject(bodyString)))
                    } catch (e: JSONException) {
                        Log.w(TAG, "Respuesta inesperada: ${bodyString.take(300)}", e)
                        Result.failure(IOException("Respuesta inesperada del servidor", e))
                    }
                }
            }
            onResult(result)
        }
    }
}
