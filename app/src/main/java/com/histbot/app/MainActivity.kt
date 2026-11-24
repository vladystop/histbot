package com.histbot.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
    var isPinned: Boolean = false,
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

class SessionAdapter(
    private val items: List<ChatSession>,
    private val getActiveId: () -> String,
    private val onSelect: (ChatSession) -> Unit,
    private val onMore: (ChatSession) -> Unit
) : RecyclerView.Adapter<SessionAdapter.SessionVH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionVH {
        val density = parent.context.resources.displayMetrics.density
        val row = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (44 * density).toInt()
            ).apply {
                bottomMargin = (2 * density).toInt()
            }
            setPadding((14 * density).toInt(), 0, (6 * density).toInt(), 0)
        }

        val titleView = TextView(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val moreBtn = TextView(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams((34 * density).toInt(), (34 * density).toInt())
            gravity = Gravity.CENTER
            text = "⋮"
            textSize = 17f
            setTextColor(Color.parseColor("#9B9B9B"))
        }

        row.addView(titleView)
        row.addView(moreBtn)
        return SessionVH(row, titleView, moreBtn)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: SessionVH, position: Int) {
        val session = items[position]
        val isActive = session.id == getActiveId()
        val density = holder.row.context.resources.displayMetrics.density

        val prefix = if (session.isPinned) "📌 " else ""
        holder.title.text = prefix + session.title

        if (isActive) {
            val pill = GradientDrawable().apply {
                setColor(Color.parseColor("#2A2B2E"))
                cornerRadius = 22 * density
            }
            holder.row.background = pill
            holder.title.setTextColor(Color.parseColor("#FFFFFF"))
            holder.title.setTypeface(null, Typeface.BOLD)
        } else {
            holder.row.background = null
            holder.title.setTextColor(Color.parseColor("#D4D4D4"))
            holder.title.setTypeface(null, Typeface.NORMAL)
        }

        holder.row.setOnClickListener { onSelect(session) }
        holder.moreBtn.setOnClickListener { onMore(session) }
    }

    class SessionVH(
        val row: LinearLayout,
        val title: TextView,
        val moreBtn: TextView
    ) : RecyclerView.ViewHolder(row)
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
    private val filteredSessions = ArrayList<ChatSession>()
    private lateinit var currentSession: ChatSession
    private lateinit var historyFile: File

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var adapter: ChatAdapter
    private lateinit var sessionAdapter: SessionAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var sessionsRecycler: RecyclerView
    private lateinit var inputEdit: EditText
    private lateinit var searchChatsEdit: EditText
    private lateinit var sendBtn: TextView
    private lateinit var statusText: TextView
    private lateinit var menuBtn: TextView
    private lateinit var powerBtn: TextView
    private lateinit var quickNewChatBtn: TextView
    private lateinit var newChatDrawerBtn: TextView
    private lateinit var resetCacheBtn: View
    private lateinit var cacheInfoText: TextView

    private var currentPower = PowerMode.MEDIUM
    private var ragEngine: RagEngine? = null
    private var histModel: HistModel? = null
    private var isReady = false
    private var isGenerating = false
    private val stopFlag = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        historyFile = File(filesDir, "chat_history.json")
        drawerLayout = findViewById(R.id.drawerLayout)
        recycler = findViewById(R.id.chatRecycler)
        sessionsRecycler = findViewById(R.id.sessionsRecycler)
        inputEdit = findViewById(R.id.inputEdit)
        searchChatsEdit = findViewById(R.id.searchChatsEdit)
        sendBtn = findViewById(R.id.sendBtn)
        statusText = findViewById(R.id.statusText)
        menuBtn = findViewById(R.id.menuBtn)
        powerBtn = findViewById(R.id.powerBtn)
        quickNewChatBtn = findViewById(R.id.quickNewChatBtn)
        newChatDrawerBtn = findViewById(R.id.newChatDrawerBtn)
        resetCacheBtn = findViewById(R.id.resetCacheBtn)
        cacheInfoText = findViewById(R.id.cacheInfoText)

        val savedPower = getSharedPreferences("histbot_prefs", MODE_PRIVATE)
            .getString("power_mode", PowerMode.MEDIUM.name)
        currentPower = PowerMode.values().firstOrNull { it.name == savedPower } ?: PowerMode.MEDIUM
        powerBtn.text = currentPower.shortLabel
        powerBtn.setOnClickListener {
            if (isGenerating) {
                Toast.makeText(this, "Дождитесь окончания ответа", Toast.LENGTH_SHORT).show()
            } else {
                showPowerDialog()
            }
        }

        adapter = ChatAdapter(messages) { text -> copyToClipboard(text) }
        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        recycler.adapter = adapter
        recycler.itemAnimator = null

        sessionAdapter = SessionAdapter(
            items = filteredSessions,
            getActiveId = { currentSession.id },
            onSelect = { session ->
                if (isGenerating) {
                    Toast.makeText(this, "Дождитесь окончания ответа", Toast.LENGTH_SHORT).show()
                } else {
                    openSession(session)
                    drawerLayout.closeDrawer(GravityCompat.START)
                }
            },
            onMore = { session -> showSessionActions(session) }
        )
        sessionsRecycler.layoutManager = LinearLayoutManager(this)
        sessionsRecycler.adapter = sessionAdapter

        loadSessionsFromDisk()
        if (sessions.isNotEmpty()) {
            openSession(sessions.first())
        } else {
            startNewChat()
        }

        menuBtn.setOnClickListener {
            refreshSessionList()
            drawerLayout.openDrawer(GravityCompat.START)
        }

        val createNewChatAction = {
            if (isGenerating) {
                Toast.makeText(this, "Дождитесь окончания ответа", Toast.LENGTH_SHORT).show()
            } else {
                startNewChat()
                drawerLayout.closeDrawer(GravityCompat.START)
            }
        }
        quickNewChatBtn.setOnClickListener { createNewChatAction() }
        newChatDrawerBtn.setOnClickListener { createNewChatAction() }

        searchChatsEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshSessionList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        resetCacheBtn.setOnClickListener {
            val rag = ragEngine ?: return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("Сбросить кэш Википедии?")
                .setMessage("Скачанные из интернета статьи будут удалены, останутся исходные 2456 статей.")
                .setPositiveButton("Сбросить") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        val baseCount = rag.resetCache()
                        withContext(Dispatchers.Main) {
                            updateStatusBar(baseCount)
                            Toast.makeText(
                                this@MainActivity,
                                "Кэш очищен ($baseCount базовых статей)",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
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
                    updateStatusBar(rag.articleCount())
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
                currentSession.title = q.take(48)
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

                val modelQuestion = if (followUp) "${currentSession.lastTopic}: $q" else q

                withContext(Dispatchers.Main) {
                    botMsg.source = sourceLabel
                    botMsg.text = ""
                    adapter.notifyItemChanged(botIndex)
                }

                val finalAns = if (rag.isGreeting(q) && q.lowercase().startsWith("привет")) {
                    "Привет! Я локальная историческая нейросеть на 30M параметров с базой знаний (RAG). Задавай вопросы по истории!"
                } else {
                    model.generate(baseCtx, modelQuestion, stopFlag, currentPower) { partial ->
                        runOnUiThread {
                            val atBottom = !recycler.canScrollVertically(1)
                            botMsg.text = partial
                            adapter.notifyItemChanged(botIndex)
                            if (atBottom) recycler.scrollToPosition(botIndex)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    botMsg.text = finalAns.ifEmpty { "Не удалось сформировать ответ." }
                    currentSession.updatedAt = System.currentTimeMillis()
                    saveSessionsToDisk()
                    refreshSessionList()
                    adapter.notifyItemChanged(botIndex)
                    updateStatusBar(rag.articleCount())
                    isGenerating = false
                    sendBtn.text = "↑"
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    private fun updateStatusBar(count: Int) {
        statusText.text = "Готов • $count статей + Википедия"
        val extra = (count - 2456).coerceAtLeast(0)
        cacheInfoText.text = "Сбросить кэш Википедии (+$extra)"
    }

    private fun showPowerDialog() {
        val modes = PowerMode.values()
        val labels = modes.map { it.title }.toTypedArray()
        val checked = modes.indexOf(currentPower)

        AlertDialog.Builder(this)
            .setTitle("Мощность ответа")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                currentPower = modes[which]
                powerBtn.text = currentPower.shortLabel
                getSharedPreferences("histbot_prefs", MODE_PRIVATE)
                    .edit()
                    .putString("power_mode", currentPower.name)
                    .apply()
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showSessionActions(session: ChatSession) {
        val pinLabel = if (session.isPinned) "Открепить" else "Закрепить"
        val options = arrayOf(pinLabel, "Переименовать", "Удалить")

        AlertDialog.Builder(this)
            .setTitle(session.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        session.isPinned = !session.isPinned
                        saveSessionsToDisk()
                        refreshSessionList()
                    }
                    1 -> {
                        val edit = EditText(this).apply {
                            setText(session.title)
                            setSelection(text.length)
                        }
                        AlertDialog.Builder(this)
                            .setTitle("Переименовать чат")
                            .setView(edit)
                            .setPositiveButton("Сохранить") { _, _ ->
                                val newTitle = edit.text.toString().trim()
                                if (newTitle.isNotEmpty()) {
                                    session.title = newTitle
                                    saveSessionsToDisk()
                                    refreshSessionList()
                                }
                            }
                            .setNegativeButton("Отмена", null)
                            .show()
                    }
                    2 -> {
                        sessions.removeAll { it.id == session.id }
                        saveSessionsToDisk()
                        if (currentSession.id == session.id) {
                            if (sessions.isNotEmpty()) {
                                openSession(sessions.first())
                            } else {
                                startNewChat()
                            }
                        } else {
                            refreshSessionList()
                        }
                        Toast.makeText(this, "Чат удалён", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
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
        refreshSessionList()
    }

    private fun openSession(session: ChatSession) {
        currentSession = session
        messages.clear()
        messages.addAll(session.messages)
        adapter.notifyDataSetChanged()
        refreshSessionList()
        if (messages.isNotEmpty()) {
            recycler.scrollToPosition(messages.size - 1)
        }
    }

    private fun upsertCurrentSession() {
        sessions.removeAll { it.id == currentSession.id }
        sessions.add(0, currentSession)
        refreshSessionList()
    }

    private fun refreshSessionList() {
        val query = searchChatsEdit.text.toString().trim().lowercase()
        val sorted = sessions.sortedWith(
            compareByDescending<ChatSession> { it.isPinned }.thenByDescending { it.updatedAt }
        )
        filteredSessions.clear()
        if (query.isEmpty()) {
            filteredSessions.addAll(sorted)
        } else {
            filteredSessions.addAll(
                sorted.filter { s ->
                    s.title.lowercase().contains(query) ||
                        s.messages.any { it.text.lowercase().contains(query) }
                }
            )
        }
        sessionAdapter.notifyDataSetChanged()
    }

    private fun saveSessionsToDisk() {
        try {
            val arr = JSONArray()
            for (s in sessions.take(50)) {
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
                        .put("isPinned", s.isPinned)
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
                        isPinned = obj.optBoolean("isPinned", false),
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
