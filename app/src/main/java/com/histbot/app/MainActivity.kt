package com.histbot.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

data class ChatMessage(
    val isUser: Boolean,
    var text: String,
    var source: String = ""
)

class ChatAdapter(private val items: List<ChatMessage>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun getItemViewType(position: Int): Int = if (items[position].isUser) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 1) {
            UserVH(inflater.inflate(R.layout.item_user, parent, false))
        } else {
            BotVH(inflater.inflate(R.layout.item_bot, parent, false))
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = items[position]
        if (holder is UserVH) {
            holder.text.text = msg.text
        } else if (holder is BotVH) {
            holder.text.text = msg.text
            if (msg.source.isNotEmpty()) {
                holder.source.visibility = View.VISIBLE
                holder.source.text = msg.source
            } else {
                holder.source.visibility = View.GONE
            }
        }
    }

    class UserVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.msgText)
    }

    class BotVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.msgText)
        val source: TextView = v.findViewById(R.id.sourceText)
    }
}

class MainActivity : AppCompatActivity() {
    private val messages = ArrayList<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var inputEdit: EditText
    private lateinit var sendBtn: TextView
    private lateinit var statusText: TextView

    private var ragEngine: RagEngine? = null
    private var histModel: HistModel? = null
    private var isReady = false
    private var isGenerating = false
    private val stopFlag = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recycler = findViewById(R.id.chatRecycler)
        inputEdit = findViewById(R.id.inputEdit)
        sendBtn = findViewById(R.id.sendBtn)
        statusText = findViewById(R.id.statusText)

        adapter = ChatAdapter(messages)
        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        recycler.adapter = adapter

        messages.add(ChatMessage(false, "Привет! Я твоя локальная историческая нейросеть на 30M параметров. Спрашивай про события, правителей и эпохи."))
        adapter.notifyItemInserted(0)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tok = BpeTokenizer(applicationContext)
                val rag = RagEngine(applicationContext)
                val model = HistModel(applicationContext, tok)
                ragEngine = rag
                histModel = model
                isReady = true
                withContext(Dispatchers.Main) {
                    statusText.text = "Готов • ${rag.articleCount()} статей + Википедия"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "Ошибка загрузки: ${e.message}"
                }
            }
        }

        sendBtn.setOnClickListener {
            if (isGenerating) {
                stopFlag.set(true)
                return@setOnClickListener
            }
            if (!isReady) return@setOnClickListener

            val q = inputEdit.text.toString().trim()
            if (q.isEmpty()) return@setOnClickListener
            inputEdit.setText("")

            messages.add(ChatMessage(true, q))
            adapter.notifyItemInserted(messages.size - 1)

            val botMsg = ChatMessage(false, "Думаю...")
            messages.add(botMsg)
            val botIndex = messages.size - 1
            adapter.notifyItemInserted(botIndex)
            recycler.scrollToPosition(botIndex)

            isGenerating = true
            stopFlag.set(false)
            sendBtn.text = "■"

            lifecycleScope.launch(Dispatchers.IO) {
                val rag = ragEngine!!
                val model = histModel!!

                val hit = rag.search(q) { st ->
                    runOnUiThread {
                        botMsg.source = st
                        adapter.notifyItemChanged(botIndex)
                    }
                }

                val ctx = if (hit.item != null) "${hit.item.title}. ${hit.item.text}" else "нет данных."
                withContext(Dispatchers.Main) {
                    botMsg.source = hit.sourceLabel
                    botMsg.text = ""
                    adapter.notifyItemChanged(botIndex)
                }

                val finalAns = if (rag.isGreeting(q) && q.lowercase().startsWith("привет")) {
                    "Привет! Я локальная историческая нейросеть на 30M параметров с базой знаний (RAG). Задавай вопросы по истории!"
                } else {
                    model.generate(ctx, q, stopFlag) { partial ->
                        runOnUiThread {
                            botMsg.text = partial
                            adapter.notifyItemChanged(botIndex)
                            recycler.scrollToPosition(botIndex)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    botMsg.text = finalAns.ifEmpty { "Не удалось сформировать ответ." }
                    adapter.notifyItemChanged(botIndex)
                    statusText.text = "Готов • ${rag.articleCount()} статей + Википедия"
                    isGenerating = false
                    sendBtn.text = "↑"
                }
            }
        }
    }
}
