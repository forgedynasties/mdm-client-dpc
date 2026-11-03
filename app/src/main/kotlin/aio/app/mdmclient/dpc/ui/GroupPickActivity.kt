package aio.app.mdmclient.dpc.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import aio.app.mdmclient.dpc.AgentConfig
import aio.app.mdmclient.dpc.DeviceIdentity
import aio.app.mdmclient.dpc.R
import aio.app.mdmclient.dpc.net.ApiClient
import org.json.JSONObject

/**
 * Pick this Lab device's group (G3, picked 3 Nov 2026). Every group the server offers (not
 * hidden, no member at a venue) is a big tile, the current one marked; a tap, or OK on a remote,
 * moves the device there (out of any other group) through POST /api/v1/device-group. Opened by
 * the status screen's Group card, and by the status screen itself while a Lab device has no
 * group ([EXTRA_ASK]: "Which team is this device for?" with Not now).
 */
class GroupPickActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var body: LinearLayout
    private lateinit var note: TextView
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ask = intent.getBooleanExtra(EXTRA_ASK, false)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
            setBackgroundColor(color(R.color.ui_bg))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text(if (ask) "Which team is this device for?" else "Which group is this device in?", 22f, R.color.ui_text, 600),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(button(if (ask) "Not now" else "Back", primary = false) { finish() })
        page.addView(head)
        note = text(if (ask) "It's in the Lab with no group yet. Pick one so the right people can manage it." else "Loading…",
            13.5f, R.color.ui_muted, 400).apply { setPadding(0, dp(6), 0, dp(16)) }
        page.addView(note)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(ScrollView(this).apply { addView(body); isFillViewport = true },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(page)
        load(null)
    }

    /** Reads the list (and, with [set], moves the device first) off the main thread. */
    private fun load(set: Pick?) {
        busy = true
        val ctx = applicationContext
        Thread {
            val payload = JSONObject().put("serial", DeviceIdentity.serial(ctx))
            if (set != null) payload.put("set", set.id)
            val answer = ApiClient(AgentConfig.get(ctx)).postForAnswer("/api/v1/device-group", payload, 20)
            main.post {
                busy = false
                if (isFinishing || isDestroyed) return@post
                val b = answer.body
                when {
                    b == null -> note.text = "Couldn't reach the MDM (${answer.error}). Check the network and try again."
                    set != null -> {
                        Toast.makeText(this, if (set.id.isEmpty()) "Taken out of its group" else "Moved to ${set.name}", Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        finish()
                    }
                    else -> draw(b)
                }
            }
        }.start()
    }

    private class Pick(val id: String, val name: String)

    private fun draw(b: JSONObject) {
        body.removeAllViews()
        if (!b.optBoolean("lab")) {
            note.text = b.optString("venue").takeIf { it.isNotBlank() }?.let { "This device is at $it. Its group is set on the dashboard." }
                ?: "This device isn't in the Lab. Its group is set on the dashboard."
            return
        }
        val current = b.optJSONObject("current")
        val curId = current?.optString("id").orEmpty()
        if (!intent.getBooleanExtra(EXTRA_ASK, false)) {
            note.text = if (current != null) "Now in ${current.optString("name")}. Pick another to move it there." else "It has no group yet."
        }
        val picks = mutableListOf<Triple<Pick, String, Boolean>>()
        val arr = b.optJSONArray("groups")
        for (i in 0 until (arr?.length() ?: 0)) {
            val g = arr!!.getJSONObject(i)
            val id = g.optString("id")
            val n = g.optInt("count")
            picks += Triple(Pick(id, g.optString("name")), if (id == curId) "now" else "$n device${if (n == 1) "" else "s"}", id == curId)
        }
        if (current != null) picks += Triple(Pick("", "No group"), "take it out", false)
        if (picks.isEmpty()) { note.text = "There are no groups to pick from yet. Ask for one on the dashboard."; return }
        val cols = if (resources.displayMetrics.widthPixels / resources.displayMetrics.density >= 720) 3 else 2
        var row: LinearLayout? = null
        var first: View? = null
        picks.forEachIndexed { i, (p, sub, on) ->
            if (i % cols == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                body.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
            }
            val tile = tile(p.name, sub, on) { if (!busy && !on) { note.text = "Moving to ${p.name}…"; load(p) } }
            row!!.addView(tile, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i % cols != cols - 1) marginEnd = dp(10)
            })
            if (first == null || on) first = tile
        }
        // Fill the last row so tiles keep one width.
        val left = (cols - picks.size % cols) % cols
        repeat(left) { row!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { if (it < left - 1) marginEnd = dp(10) }) }
        first?.requestFocus()
    }

    private fun tile(name: String, sub: String, on: Boolean, click: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        isFocusable = true
        isClickable = true
        background = tileBg(on)
        addView(text(name, 16f, if (on) R.color.ui_accent else R.color.ui_text, 600))
        addView(text(sub, 12.5f, R.color.ui_muted, 400).apply { setPadding(0, dp(4), 0, 0) })
        setOnClickListener { click() }
    }

    private fun tileBg(on: Boolean): StateListDrawable {
        fun shape(fill: Int, stroke: Int, w: Int) = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat(); setColor(color(fill)); setStroke(dp(w), color(stroke))
        }
        val base = if (on) shape(R.color.ui_warn_bg, R.color.ui_accent, 2) else shape(R.color.ui_surface, R.color.ui_border, 1)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(R.color.ui_surface2, R.color.ui_border, 1))
            addState(intArrayOf(android.R.attr.state_focused), shape(if (on) R.color.ui_warn_bg else R.color.ui_surface2, R.color.ui_ring, 3))
            addState(intArrayOf(), base)
        }
    }

    private fun button(label: String, primary: Boolean, click: () -> Unit) =
        TextView(this, null, 0, if (primary) R.style.Ui_Button_Primary else R.style.Ui_Button_Secondary).apply {
            text = label
            setOnClickListener { click() }
        }

    private fun text(s: String, sp: Float, colorRes: Int, weight: Int) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color(colorRes))
        typeface = ResourcesCompat.getFont(this@GroupPickActivity, R.font.poppins)?.let {
            if (android.os.Build.VERSION.SDK_INT >= 28) android.graphics.Typeface.create(it, weight, false) else it
        }
        includeFontPadding = false
    }

    private fun color(res: Int) = ContextCompat.getColor(this, res)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_ASK = "ask"

        fun open(ctx: Context, ask: Boolean) {
            ctx.startActivity(Intent(ctx, GroupPickActivity::class.java).putExtra(EXTRA_ASK, ask))
        }
    }
}
