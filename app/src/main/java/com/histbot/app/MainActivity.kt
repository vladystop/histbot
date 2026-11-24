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
        val text: TextView = v.findViewById
