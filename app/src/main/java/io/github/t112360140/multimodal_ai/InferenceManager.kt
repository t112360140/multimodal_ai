package io.github.t112360140.multimodal_ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig

import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.SamplerConfig

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object InferenceManager {
    private var engine: Engine? = null
    private var conversation : Conversation? = null
    private var modelLoaded = false;


    suspend fun loadModel(context: Context, modelPath: String, gpu_backend: Boolean=false): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (engine != null) return@withContext true

                val engineConfig = EngineConfig(
                    modelPath = modelPath, // Replace with your model path
                    backend = if(gpu_backend) Backend.GPU else Backend.CPU,
                    visionBackend = Backend.GPU,
                    audioBackend = Backend.CPU,
                    cacheDir = context.cacheDir.path
                )

                engine = Engine(engineConfig)
                engine?.initialize()
                modelLoaded = true
                true
            } catch (e: Exception) {
                e.printStackTrace()
                modelLoaded = false
                false
            }
        }
    }

    fun newConversation(systemMessage: String="You are a helpful assistant.", topK: Int=64, topP: Double = 0.95, temperature: Double = 1.0, seed: Int = 42): Boolean{
        if(engine != null && modelLoaded){
            conversation?.close()

            val conversationConfig = ConversationConfig(
                systemMessage = Message.of(systemMessage),
                samplerConfig = SamplerConfig(topK = topK, topP = topP, temperature = temperature, seed = seed),

            )

            conversation = engine?.createConversation(conversationConfig)
            return true
        }
        return false
    }

    fun sendMessage(text: String?, image: ByteArray?, audio: ByteArray?,
                    onMessage: (Message) -> Unit,
                    onDone: () -> Unit,
                    onError: (Throwable) ->Unit): Boolean{
        if(conversation!=null){
            val contents = mutableListOf<Content>()
            image?.let { contents.add(Content.ImageBytes(image)) }
            audio?.let { contents.add(Content.AudioBytes(audio)) }
            text?.let { contents.add(Content.Text(text)) }

            if(contents.isEmpty()) return false

            val message = Message.of(contents)

            val callback = object : MessageCallback {
                override fun onMessage(message: Message) {
                    onMessage(message)
                }

                override fun onDone() {
                    onDone()
                }

                override fun onError(throwable: Throwable) {
                    onError(throwable)
                }
            }

            conversation?.sendMessageAsync(message, callback)

            return true
        }
        return false
    }

    fun stop(){
        conversation?.cancelProcess()
    }

    // 釋放資源 (通常在 App 關閉時)
    fun close() {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null

        modelLoaded = false
    }
}