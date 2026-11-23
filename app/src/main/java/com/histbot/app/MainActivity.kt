package com.histbot.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class ChatMessage(
    val isUser: Boolean,
    var text: String,
    var source: String = ""
)

data class ChatSession(
    val id: String,
    var title: String,
    var updatedAt: Long,
    var lastTopic: String = "",
    var lastContext: String = "",
    val messages: ArrayList<ChatMessage> = ArrayList()
)

class ChatAdapter(
    private val items: List<ChatMessage>,
    private val onCopy: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

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
            val showCopy = msg.text.isNotBlank() && msg.text != "Думаю..."
            holder.copyBtn.visibility = if (showCopy) View.VISIBLE else View.GONE
            holder.copyBtn.setOnClickListener { onCopy(msg.text) }
        }
    }

    class UserVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.msgText)
    }

    class BotVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.msgText)
        val source: TextView = v.findViewById(R.id.sourceText)
        val copyBtn: TextView = v.findViewById(R.id.copyBtn)
    }
}

class MainActivity : AppCompatActivity() {
    private val welcomeText =
        "Привет! Я твоя локальная историческая нейросеть на 30M параметров. Спрашивай про события, правителей и эпохи."

    private val followUpMarkers = setOf(
        "он", "она", "оно", "они", "его", "её", "ее", "их", "ему", "ей", "им",
        "ним", "ней", "нём", "нем", "этом", "этой", "этого", "эту", "этот", "эти",
        "там", "тогда", "подробнее", "дальше", "ещё", "еще", "почему", "зачем"
    )

    private val messages = ArrayList<ChatMessage>()
    private val sessions = ArrayList<ChatSession>()
    private lateinit var currentSession: ChatSession
    private lateinit var historyFile: File

    private lateinit var adapter: ChatAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var inputEdit: EditText
    private lateinit var sendBtn: TextView
    private lateinit var statusText: TextView
    private lateinit var historyBtn: TextView
    private lateinit var clearBtn: TextView

    private var ragEngine: RagEngine? = null
    private var histModel: HistModel? = null
    private var isReady = false
    private var isGenerating = false
    private val stopFlag = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        historyFile = File(filesDir, "chat_history.json")
        recycler = findViewById(R.id.chatRecycler)
        inputEdit = findViewById(R.id.inputEdit)
        sendBtn = findViewById(R.id.sendBtn)
        statusText = findViewById(R.id.statusText)
        historyBtn = findViewById(R.id.historyBtn)
        clearBtn = findViewById(R.id.clearBtn)

        adapter = ChatAdapter(messages) { text -> copyToClipboard(text) }
        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        recycler.adapter = adapter

        loadSessionsFromDisk()
        if (sessions.isNotEmpty()) {
            openSession(sessions.first())
        } else {
            startNewChat()
        }

        historyBtn.setOnClickListener {
            if (isGenerating) {
                Toast.makeText(this, "Дождитесь окончания ответа", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showHistoryDialog()
        }

        clearBtn.setOnClickListener {
            if (isGenerating) {
                Toast.makeText(this, "Дождитесь окончания ответа", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startNewChat()
            Toast.makeText(this, "Начат новый чат", Toast.LENGTH_SHORT).show()
        }

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

            if (currentSession.title == "Новый чат") {
                currentSession.title = q.take(42)
            }
            currentSession.updatedAt = System.currentTimeMillis()
            upsertCurrentSession()

            val userMsg = ChatMessage(true, q)
            messages.add(userMsg)
            currentSession.messages.add(userMsg)
            adapter.notifyItemInserted(messages.size - 1)

            val botMsg = ChatMessage(false, "Думаю...")
            messages.add(botMsg)
            currentSession.messages.add(botMsg)
            val botIndex = messages.size - 1
            adapter.notifyItemInserted(botIndex)
            recycler.scrollToPosition(botIndex)

            isGenerating = true
            stopFlag.set(false)
            sendBtn.text = "■"

            lifecycleScope.launch(Dispatchers.IO) {
                val rag = ragEngine!!
                val model = histModel!!

                val followUp = isFollowUp(q) && currentSession.lastTopic.isNotEmpty()
                val searchQuery = if (followUp) "${currentSession.lastTopic} $q" else q

                val hit = rag.search(searchQuery) { st ->
                    runOnUiThread {
                        botMsg.source = st
                        adapter.notifyItemChanged(botIndex)
                    }
                }

                val baseCtx: String
                val sourceLabel: String
                if (hit.item != null) {
                    baseCtx = "${hit.item.title}. ${hit.item.text}"
                    sourceLabel = hit.sourceLabel
                    currentSession.lastTopic = hit.item.title
                    currentSession.lastContext = baseCtx
                } else if (followUp && currentSession.lastContext.isNotEmpty()) {
                    baseCtx = currentSession.lastContext
                    sourceLabel = "Память чата: ${currentSession.lastTopic}"
                } else {
                    baseCtx = "нет данных."
                    sourceLabel = hit.sourceLabel
                }

                val historySnippet = buildHistoryContext()
                val fullCtx = if (historySnippet.isNotEmpty()) "$historySnippet $baseCtx" else baseCtx
                val modelQuestion = if (followUp) "${currentSession.lastTopic}: $q" else q

                withContext(Dispatchers.Main) {
                    botMsg.source = sourceLabel
                    botMsg.text = ""
                    adapter.notifyItemChanged(botIndex)
                }

                val finalAns = if (rag.isGreeting(q) && q.lowercase().startsWith("привет")) {
                    "Привет! Я локальная историческая нейросеть на 30M параметров с базой знаний (RAG). Задавай вопросы по истории!"
                } else {
                    model.generate(fullCtx, modelQuestion, stopFlag) { partial ->
                        runOnUiThread {
                            botMsg.text = partial
                            adapter.notifyItemChanged(botIndex)
                            recycler.scrollToPosition(botIndex)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    botMsg.text = finalAns.ifEmpty { "Не удалось сформировать ответ." }
                    currentSession.updatedAt = System.currentTimeMillis()
                    saveSessionsToDisk()
                    adapter.notifyItemChanged(botIndex)
                    statusText.text = "Готов • ${rag.articleCount()} статей + Википедия"
                    isGenerating = false
                    sendBtn.text = "↑"
                }
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("HistBot", text))
        Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
    }

    private fun isFollowUp(q: String): Boolean {
        val words = Regex("""[а-яА-ЯёЁa-zA-Z0-9]+""").findAll(q.lowercase()).map { it.value }.toList()
        return words.any { it in followUpMarkers }
    }

    private fun buildHistoryContext(): String {
        val prev = currentSession.messages.dropLast(2).filter { it.text != welcomeText }
        if (prev.isEmpty()) return ""
        val recent = prev.takeLast(4)
        val sb = StringBuilder("Ранее в чате: ")
        for (m in recent) {
            val prefix = if (m.isUser) "В: " else "О: "
            sb.append(prefix).append(m.text.take(90)).append(" ")
            if (sb.length > 220) break
        }
        return sb.toString().trim().take(240)
    }

    private fun startNewChat() {
        currentSession = ChatSession(
            id = System.currentTimeMillis().toString(),
            title = "Новый чат",
            updatedAt = System.currentTimeMillis(),
            messages = arrayListOf(ChatMessage(false, welcomeText))
        )
        messages.clear()
        messages.addAll(currentSession.messages)
        adapter.notifyDataSetChanged()
    }

    private fun openSession(session: ChatSession) {
        currentSession = session
        messages.clear()
        messages.addAll(session.messages)
        adapter.notifyDataSetChanged()
        if (messages.isNotEmpty()) {
            recycler.scrollToPosition(messages.size - 1)
        }
    }

    private fun upsertCurrentSession() {
        sessions.removeAll { it.id == currentSession.id }
        sessions.add(0, currentSession)
    }

    private fun showHistoryDialog() {
        if (sessions.isEmpty()) {
            Toast.makeText(this, "История чатов пока пуста", Toast.LENGTH_SHORT).show()
            return
        }
        val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        val labels = sessions.map { s ->
            "${s.title}\n${fmt.format(Date(s.updatedAt))}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("История чатов")
            .setItems(labels) { _, which ->
                openSession(sessions[which])
            }
            .setPositiveButton("Новый чат") { _, _ ->
                startNewChat()
            }
            .setNeutralButton("Удалить все") { _, _ ->
                sessions.clear()
                saveSessionsToDisk()
                startNewChat()
                Toast.makeText(this, "История очищена", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun saveSessionsToDisk() {
        try {
            val arr = JSONArray()
            for (s in sessions.take(30)) {
                val msgArr = JSONArray()
                for (m in s.messages) {
                    msgArr.put(
                        JSONObject()
                            .put("isUser", m.isUser)
                            .put("text", m.text)
                            .put("source", m.source)
                    )
                }
                arr.put(
                    JSONObject()
                        .put("id", s.id)
                        .put("title", s.title)
                        .put("updatedAt", s.updatedAt)
                        .put("lastTopic", s.lastTopic)
                        .put("lastContext", s.lastContext)
                        .put("messages", msgArr)
                )
            }
            historyFile.writeText(arr.toString(), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    private fun loadSessionsFromDisk() {
        sessions.clear()
        if (!historyFile.exists()) return
        try {
            val arr = JSONArray(historyFile.readText(Charsets.UTF_8))
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val msgArr = obj.getJSONArray("messages")
                val msgs = ArrayList<ChatMessage>()
                for (j in 0 until msgArr.length()) {
                    val m = msgArr.getJSONObject(j)
                    msgs.add(
                        ChatMessage(
                            isUser = m.getBoolean("isUser"),
                            text = m.getString("text"),
                            source = m.optString("source", "")
                        )
                    )
                }
                sessions.add(
                    ChatSession(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        updatedAt = obj.getLong("updatedAt"),
                        lastTopic = obj.optString("lastTopic", ""),
                        lastContext = obj.optString("lastContext", ""),
                        messages = msgs
                    )
                )
            }
        } catch (_: Exception) {
        }
    }
}
