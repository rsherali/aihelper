package com.curiosityengine.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Random

class Card(val topic: String, val kind: String, val via: String, val pillar: String, val json: JSONObject) {
    var deeper: String? = null
    var revealed = false
    var guess = ""
    var feedback = ""
    var minutes = 5
    var level = 0
    var actionState: String? = null          // "done" or "skip"
    val flags = HashSet<String>()            // once-per-card events already logged
}

class Week(val pts: Map<String, Int>, val reveals: Int, val done: Int, val skipped: Int, val days: Int)

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("ce_prefs", Context.MODE_PRIVATE) }
    private val rnd = Random()

    private val CATS = linkedMapOf(
        "Science & Nature" to listOf("Space", "Animals", "Oceans", "Weather", "Human body", "Plants", "Physics", "Chemistry"),
        "Maths" to listOf("Puzzles", "Numbers", "Geometry", "Probability", "Logic", "Famous mathematicians"),
        "Technology" to listOf("AI", "Computers", "Inventions", "Internet", "Robotics", "Gadgets"),
        "Food" to listOf("Cooking science", "World cuisines", "Ingredients", "Drinks", "Food history", "Nutrition")
    )
    private val WILD = listOf("Music", "History", "Art", "Languages", "Psychology", "Architecture", "Geography", "Sport")
    private val PILLARS = listOf("THINK", "LEARN", "MOVE", "ENGAGE", "DISCOVER", "CONFIDENCE")
    private val LIVE = setOf("LEARN", "THINK", "DISCOVER")
    private val PURPOSE = mapOf(
        "THINK" to "Reason, estimate, remember",
        "LEARN" to "Understand something new",
        "MOVE" to "Stretch, walk, move",
        "ENGAGE" to "Talk to someone",
        "DISCOVER" to "Try the unexpected",
        "CONFIDENCE" to "Step outside your comfort zone"
    )
    private val HINT = mapOf(
        "THINK" to "a mental exercise: a memory, logic or estimation challenge to do without a screen",
        "LEARN" to "a way to learn one more thing, e.g. look something up or explain it to someone",
        "DISCOVER" to "trying something new or unfamiliar related to the topic"
    )
    private val LEVELS = listOf("Beginner", "Intermediate", "Advanced")
    private val WEEKLY_TARGET = 30   // minutes per pillar per week

    // ---- state ----
    private var screenName = "home"
    private var mode = "LEARN"
    private var current: Card? = null
    @Volatile private var next: Card? = null
    @Volatile private var fetching = false
    @Volatile private var loading = false
    @Volatile private var generation = 0   // bumps when the section/interests change; stale fetches are discarded
    private val recentHooks = ArrayList<String>()

    private lateinit var statusView: TextView
    private lateinit var topicView: TextView
    private lateinit var hookView: TextView
    private lateinit var questionView: TextView
    private lateinit var answerView: TextView
    private lateinit var deeperView: TextView
    private lateinit var revealBtn: Button
    private lateinit var guessField: EditText
    private lateinit var actionView: TextView
    private lateinit var actionRow: LinearLayout
    private lateinit var explainerView: TextView
    private lateinit var hintBtn: Button
    private lateinit var hintView: TextView
    private lateinit var discoverRow: LinearLayout

    // ---------- backup: survives uninstall (shared storage, not app storage) ----------
    private fun backupFile(): java.io.File =
        java.io.File(java.io.File(android.os.Environment.getExternalStorageDirectory(), "CuriosityEngine"), "backup.json")

    private fun exportPrefs() {
        try {
            if (prefs.all.isEmpty()) return
            val o = JSONObject()
            for ((k, v) in prefs.all) {
                if (v is String) o.put(k, "s:$v") else if (v is Int) o.put(k, "i:$v")
            }
            val f = backupFile()
            f.parentFile?.mkdirs()
            f.writeText(o.toString())
        } catch (e: Exception) { /* best-effort */ }
    }

    private fun importPrefsIfEmpty() {
        try {
            if (prefs.all.isNotEmpty()) return
            val f = backupFile()
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            val ed = prefs.edit()
            for (k in o.keys()) {
                val v = o.getString(k)
                if (v.startsWith("s:")) ed.putString(k, v.substring(2))
                else if (v.startsWith("i:")) ed.putInt(k, v.substring(2).toInt())
            }
            ed.commit()
        } catch (e: Exception) { /* ignore */ }
    }

    override fun onPause() { super.onPause(); exportPrefs() }

    override fun onBackPressed() {
        if (screenName != "home" && selected().isNotEmpty()) showHome() else super.onBackPressed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importPrefsIfEmpty()
        if (selected().isEmpty()) showInterests() else showHome()
    }

    // ---------- helpers ----------
    private fun dp(i: Int) = (i * resources.displayMetrics.density).toInt()
    private fun version() = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }
    private fun keyFor(p: String): String = prefs.getString("key_$p", "")?.trim() ?: ""
    private fun selected(): List<String> = (prefs.getString("tags", "") ?: "").split("|").filter { it.isNotEmpty() }
    private fun weight(tag: String) = prefs.getInt("w_$tag", 3)
    private fun lvl(tag: String) = prefs.getInt("lvl_$tag", 0).coerceIn(0, 2)
    private fun blockedList(): List<String> = (prefs.getString("blocked", "") ?: "").split("|").filter { it.isNotEmpty() }
    private fun wildTopics(): List<String> = WILD.filter { !blockedList().contains(it) }.ifEmpty { WILD }

    private fun text(t: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; minHeight = dp(48); setOnClickListener { onClick() }
    }

    private fun row(vararg buttons: Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (b in buttons) addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun screen(name: String, content: LinearLayout) {
        screenName = name
        content.setPadding(dp(16), dp(16), dp(16), dp(16))
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun openUrl(u: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (e: Exception) { }
    }

    // ---------- event log (minutes invested) ----------
    private fun logEvent(type: String, pillar: String, mins: Int) {
        var arr = try { JSONArray(prefs.getString("log", "[]")) } catch (x: Exception) { JSONArray() }
        if (arr.length() > 700) {
            val trimmed = JSONArray()
            for (i in arr.length() - 500 until arr.length()) trimmed.put(arr.get(i))
            arr = trimmed
        }
        arr.put(JSONObject().put("t", System.currentTimeMillis()).put("type", type).put("p", pillar).put("m", mins))
        prefs.edit().putString("log", arr.toString()).apply()
    }

    private fun weekly(): Week {
        val arr = try { JSONArray(prefs.getString("log", "[]")) } catch (x: Exception) { JSONArray() }
        val since = System.currentTimeMillis() - 7L * 86400000L
        val pts = HashMap<String, Int>()
        for (p in PILLARS) pts[p] = 0
        var reveals = 0; var done = 0; var skipped = 0
        val days = HashSet<Long>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val t = o.optLong("t")
            if (t < since) continue
            days.add(t / 86400000L)
            val p = o.optString("p")
            val m = o.optInt("m", o.optInt("pts"))   // older entries stored points; treat as minutes
            if (pts.containsKey(p)) pts[p] = (pts[p] ?: 0) + m
            when (o.optString("type")) {
                "reveal" -> reveals++
                "action_done" -> done++
                "action_skip" -> skipped++
            }
        }
        return Week(pts, reveals, done, skipped, days.size)
    }

    private fun focusPillar(w: Week): String {
        val min = PILLARS.minOf { w.pts[it] ?: 0 }
        val ties = PILLARS.filter { (w.pts[it] ?: 0) == min }
        return ties[((System.currentTimeMillis() / 86400000L) % ties.size).toInt()]
    }

    private fun statusLabel(m: Int) = when {
        m == 0 -> "Nothing this week"
        m < WEEKLY_TARGET / 2 -> "Not enough"
        m < WEEKLY_TARGET -> "Getting there"
        else -> "On track"
    }

    private fun statusColor(m: Int) = when {
        m == 0 -> "#F8D7DA"
        m < WEEKLY_TARGET / 2 -> "#FFE8B3"
        m < WEEKLY_TARGET -> "#E2F0CB"
        else -> "#C8E6C9"
    }

    private fun bar(m: Int): LinearLayout {
        val b = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.parseColor("#CCCCCC")) }
        val f = minOf(m, WEEKLY_TARGET)
        val fill = View(this).apply { setBackgroundColor(Color.parseColor(if (m >= WEEKLY_TARGET) "#2E7D32" else "#3F51B5")) }
        b.addView(fill, LinearLayout.LayoutParams(0, dp(10), f.toFloat()))
        b.addView(View(this), LinearLayout.LayoutParams(0, dp(10), (WEEKLY_TARGET - f).toFloat()))
        return b
    }

    /** Slim six-pillar balance strip, shown on section screens. Tap = home. */
    private fun strip(): LinearLayout {
        val w = weekly()
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (p in PILLARS) {
            val m = w.pts[p] ?: 0
            val cell = TextView(this).apply {
                text = "$p\n${m} min"; textSize = 10f; gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#222222"))
                setBackgroundColor(Color.parseColor(statusColor(m)))
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener { showHome() }
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(1), 0, dp(1), 0)
            r.addView(cell, lp)
        }
        return r
    }

    // ---------- HOME (pillar dashboard) ----------
    private fun showHome() {
        val root = vertical()
        val w = weekly()
        val focus = focusPillar(w)
        val total = PILLARS.sumOf { w.pts[it] ?: 0 }
        root.addView(text("Curiosity Engine", 26f, true))
        root.addView(text("Minutes invested this week. Target: $WEEKLY_TARGET min per pillar.", 13f))
        root.addView(text(if (total == 0) "Nothing logged yet. Start with $focus." else "Focus: $focus (least time this week).", 16f, true))

        var i = 0
        while (i < PILLARS.size) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (p in listOf(PILLARS[i], PILLARS[i + 1])) {
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins(dp(4), dp(4), dp(4), dp(4))
                r.addView(tile(p, w, focus), lp)
            }
            root.addView(r)
            i += 2
        }
        root.addView(row(btn("Weekly review") { showReview() }, btn("Interests") { showInterests() }))
        root.addView(row(btn("Favourites") { showFavourites() }, btn("Settings") { showSettings() }))
        root.addView(text("Build v${version()}", 11f))
        screen("home", root)
    }

    private fun tile(p: String, w: Week, focus: String): LinearLayout {
        val m = w.pts[p] ?: 0
        val t = vertical()
        t.setPadding(dp(12), dp(12), dp(12), dp(12))
        t.minimumHeight = dp(150)
        t.setBackgroundColor(Color.parseColor(if (p == focus && (w.pts.values.sum() > 0 || true)) "#FFF3CD" else "#EEF1F8"))
        t.addView(text(p, 18f, true))
        t.addView(text(PURPOSE[p] ?: "", 12f))
        t.addView(text("$m / $WEEKLY_TARGET min", 15f, true))
        t.addView(bar(m), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)))
        t.addView(text(statusLabel(m), 12f))
        val tag = (if (p == focus) "FOCUS THIS WEEK  " else "") + (if (p in LIVE) "" else "Coming soon")
        if (tag.isNotBlank()) t.addView(text(tag.trim(), 11f, true))
        t.isClickable = true
        t.setOnClickListener { openSection(p) }
        return t
    }

    private fun openSection(p: String) {
        if (p !in LIVE) { showPlaceholder(p); return }
        if (mode != p) {
            mode = p; current = null; next = null; generation++
            recentHooks.clear(); loading = false; fetching = false
        }
        showFeed()
    }

    // ---------- placeholder sections (built one at a time) ----------
    private fun showPlaceholder(p: String, msg: String = "") {
        val root = vertical()
        root.addView(btn("Back to home") { showHome() })
        root.addView(strip())
        root.addView(text(p, 24f, true))
        root.addView(text(PURPOSE[p] ?: "", 14f))
        val plan = when (p) {
            "MOVE" -> "Planned: gentle movement reminders, plus links to short stretching videos and step suggestions."
            "ENGAGE" -> "Planned: tiny nudges to talk to someone about anything. Little interactions count."
            else -> "Planned: a ladder of small comfort-zone challenges, with a self-rating."
        }
        root.addView(text("This section is not built yet.\n$plan", 15f))
        if (p == "MOVE") root.addView(btn("Find short stretching videos (opens browser)") {
            openUrl("https://www.youtube.com/results?search_query=5+minute+stretching+for+beginners")
        })
        val m = weekly().pts[p] ?: 0
        root.addView(text("This week: $m min (${statusLabel(m)})", 16f, true))
        root.addView(text("Until it is built, log time you actually spent (self-reported):", 13f))
        root.addView(row(
            btn("+5 min") { logEvent("manual", p, 5); showPlaceholder(p, "Logged 5 min of $p.") },
            btn("+10 min") { logEvent("manual", p, 10); showPlaceholder(p, "Logged 10 min of $p.") },
            btn("+20 min") { logEvent("manual", p, 20); showPlaceholder(p, "Logged 20 min of $p.") }
        ))
        if (msg.isNotEmpty()) root.addView(text(msg, 14f))
        screen("placeholder", root)
    }

    // ---------- Interests (with a level per interest) ----------
    private class IRow(val tag: String, val cb: CheckBox, val sp: Spinner)

    private fun showInterests() {
        val root = vertical()
        root.addView(text("Choose your interests", 24f, true))
        root.addView(text("Tick what interests you and set your level for each. Too hard or too easy? You can change it any time, or tap Too hard / Too easy on a card.", 14f))
        val rows = ArrayList<IRow>()
        val chosen = selected()
        val known = CATS.values.flatten()
        val groups = LinkedHashMap<String, List<String>>()
        for ((cat, tags) in CATS) groups[cat] = tags
        val extra = chosen.filter { !known.contains(it) }
        if (extra.isNotEmpty()) groups["Discovered"] = extra
        for ((cat, tags) in groups) {
            root.addView(text(cat, 18f, true))
            for (t in tags) {
                val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val cb = CheckBox(this).apply { text = t; isChecked = chosen.contains(t) }
                val sp = Spinner(this)
                sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, LEVELS)
                sp.setSelection(lvl(t))
                sp.isEnabled = cb.isChecked
                cb.setOnCheckedChangeListener { _, checked -> sp.isEnabled = checked }
                line.addView(cb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                line.addView(sp, LinearLayout.LayoutParams(dp(170), ViewGroup.LayoutParams.WRAP_CONTENT))
                rows.add(IRow(t, cb, sp)); root.addView(line)
            }
        }
        val msg = text("", 14f)
        root.addView(msg)
        root.addView(btn("Save & continue") {
            val picked = rows.filter { it.cb.isChecked }
            if (picked.isEmpty()) { msg.text = "Pick at least one interest." } else {
                val names = picked.map { it.tag }
                val old = selected()
                val blocked = (blockedList() + old.filter { !names.contains(it) }).filter { !names.contains(it) }.distinct()
                val ed = prefs.edit()
                ed.putString("tags", names.joinToString("|")).putString("blocked", blocked.joinToString("|"))
                for (r in picked) ed.putInt("lvl_${r.tag}", r.sp.selectedItemPosition)
                ed.apply()
                exportPrefs()
                generation++
                current = null; next = null; loading = false; fetching = false
                recentHooks.clear()
                showHome()
            }
        })
        screen("interests", root)
    }

    // ---------- Section feed (LEARN / THINK / DISCOVER) ----------
    private fun showFeed() {
        val root = vertical()
        root.addView(row(
            btn("Home") { showHome() },
            btn("Favourites") { showFavourites() }
        ))
        root.addView(strip())
        root.addView(text("$mode: ${PURPOSE[mode]}", 18f, true))
        topicView = text("", 13f)
        statusView = text("", 13f)
        hookView = text("", 22f, true)
        questionView = text("", 18f)
        answerView = text("", 16f).apply { visibility = View.GONE }
        deeperView = text("", 15f).apply { visibility = View.GONE }
        guessField = EditText(this).apply {
            hint = when (mode) {
                "THINK" -> "Your answer (needed to earn THINK minutes)"
                "LEARN" -> "Your answer to the check question (optional)"
                else -> "Your guess (optional)"
            }
        }
        explainerView = text("", 16f).apply { visibility = View.GONE }
        hintView = text("", 15f).apply { visibility = View.GONE }
        hintBtn = btn("Give me a hint") {
            val c = current
            if (c != null) { c.flags.add("hint"); applyState(c) }
        }.apply { visibility = View.GONE }
        discoverRow = row(
            btn("Look it up") { val c = current; if (c != null) openUrl("https://www.google.com/search?q=" + Uri.encode(c.json.optString("search", c.topic))) },
            btn("Add to my interests") { adoptTopic() },
            btn("Not for me") { notForMe() }
        )
        revealBtn = btn("Show answer") { reveal() }
        actionView = text("", 16f, true)
        actionRow = row(
            btn("I did it") { actionResult("done") },
            btn("Not today") { actionResult("skip") }
        )

        root.addView(topicView); root.addView(statusView)
        root.addView(hookView); root.addView(explainerView); root.addView(questionView)
        root.addView(hintBtn); root.addView(hintView)
        root.addView(guessField); root.addView(revealBtn); root.addView(answerView)
        root.addView(discoverRow)
        root.addView(actionView); root.addView(actionRow)
        root.addView(row(
            btn("More like this") { signal("more") },
            btn("Less like this") { signal("less") },
            btn("Favourite") { signal("favourite") }
        ))
        root.addView(row(
            btn("Go deeper") { goDeeper() },
            btn("Surprised me") { signal("surprised") }
        ))
        root.addView(row(
            btn("Too hard") { signal("toohard") },
            btn("Too easy") { signal("tooeasy") }
        ))
        root.addView(deeperView)
        root.addView(btn("Next card") { loadNext() })
        screen("feed", root)

        val c = current
        if (c != null) show(c) else loadNext()
    }

    private fun adjacentTags(): List<String> {
        val sel = selected()
        val blocked = (prefs.getString("blocked", "") ?: "").split("|")
        return CATS.values.filter { v -> v.any { sel.contains(it) } }.flatten()
            .filter { !sel.contains(it) && !blocked.contains(it) }
    }

    private fun pickTopic(m: String): Pair<String, String> {
        val sel = selected()
        val adj = adjacentTags()
        if (sel.isEmpty()) return Pair(wildTopics()[rnd.nextInt(wildTopics().size)], "unexpected")
        if (m == "DISCOVER") {
            return if (adj.isNotEmpty() && rnd.nextInt(100) < 55) Pair(adj[rnd.nextInt(adj.size)], "adjacent")
            else Pair(wildTopics()[rnd.nextInt(wildTopics().size)], "unexpected")
        }
        val r = rnd.nextInt(100)
        if (r >= 90) return Pair(wildTopics()[rnd.nextInt(wildTopics().size)], "unexpected")
        if (r >= 70 && adj.isNotEmpty()) return Pair(adj[rnd.nextInt(adj.size)], "adjacent")
        val pool = sel.filter { weight(it) > 0 }.ifEmpty { sel }
        var total = 0
        for (t in pool) total += maxOf(weight(t), 1)
        var pick = rnd.nextInt(total)
        for (t in pool) { pick -= maxOf(weight(t), 1); if (pick < 0) return Pair(t, "preferred") }
        return Pair(pool[0], "preferred")
    }

    private fun fetchText(prompt: String): Pair<String, String> {
        val choice = prefs.getString("provider", "Auto") ?: "Auto"
        if (choice == "Auto") return Net.askAuto(Net.PROVIDERS.associate { it to keyFor(it) }, prompt)
        if (keyFor(choice).isEmpty()) throw RuntimeException("No $choice key saved. Open Settings.")
        return Pair(choice, Net.ask(choice, keyFor(choice), prompt))
    }

    private fun levelText(level: Int) = when (level) {
        0 -> "BEGINNER: assume no prior knowledge, use plain everyday words, no jargon; something a curious 12-year-old could attempt."
        1 -> "INTERMEDIATE: assume some familiarity; basic terms are fine."
        else -> "ADVANCED: assume a solid background; technical and nuanced is fine."
    }

    private fun fetchCard(): Card {
        val m = mode
        val (topic, kind) = pickTopic(m)
        val level = lvl(topic)
        val avoid = recentHooks.takeLast(6).joinToString(" | ")
        val extra = when (m) {
            "LEARN" -> "This is a TEACHING card. 'explainer' is 4-5 clear sentences that teach the idea; the learner reads it first. " +
                "'question' is a short check question answerable from the explainer; 'answer' is its correct answer (1-2 sentences). "
            "THINK" -> "This is a BRAIN CHALLENGE card: a logic puzzle, estimation, pattern, or reasoning problem related to the topic, " +
                "solvable by reasoning without obscure facts. 'question' states the whole challenge; 'answer' gives the solution with brief reasoning; " +
                "'hint' is a nudge that does not give the answer away. "
            else -> "This is a DISCOVERY card that tempts the learner toward something new. 'hook' is a vivid teaser; 'answer' is a 2-sentence taste of the topic; " +
                "'question' is a wondering question; 'search' is a 3-6 word web search to explore it. "
        }
        val keys = when (m) {
            "LEARN" -> "hook, explainer, question, answer, fact, action, minutes"
            "THINK" -> "hook, question, hint, answer, fact, action, minutes"
            else -> "hook, question, answer, fact, search, action, minutes"
        }
        val prompt = "You are a curiosity coach. Create one card about: $topic. Difficulty level: ${levelText(level)} $extra" +
            "Make it surprising and specific. Avoid repeating these earlier hooks: $avoid. " +
            "Reply with ONLY a JSON object with these keys: $keys. All values are strings except minutes. " +
            "'fact' is one surprising related fact. 'hook' is one intriguing sentence. " +
            "'action' is one small real-world action under 20 words that exercises the $m pillar: ${HINT[m]}; " +
            "link it to the topic where natural; doable today without special equipment. " +
            "'minutes' is an integer from 1 to 15: realistic minutes the action takes."
        val (via, raw) = fetchText(prompt)
        val j = Net.extractJson(raw)
        val c = Card(topic, kind, via, m, j)
        c.level = level
        val mins = j.optInt("minutes", 5)
        c.minutes = if (mins < 1) 5 else minOf(mins, 30)
        return c
    }

    private fun prefetch() {
        if (next != null || fetching) return
        fetching = true
        val gen = generation
        Thread {
            try {
                val c = fetchCard()
                if (gen == generation) next = c
            } catch (e: Exception) { /* next tap retries */ }
            if (gen == generation) fetching = false
        }.start()
    }

    private fun loadNext() {
        if (loading) return
        val n = next
        if (n != null) { next = null; show(n); prefetch(); return }
        loading = true
        val gen = generation
        statusView.text = "Fetching a card..."
        Thread {
            try {
                val c = fetchCard()
                runOnUiThread { if (gen == generation) { loading = false; show(c); prefetch() } }
            } catch (e: Exception) {
                runOnUiThread { loading = false; statusView.text = "FAIL: ${e.javaClass.simpleName}: ${e.message}\nTap Next card to retry." }
            }
        }.start()
    }

    private fun show(c: Card) {
        current = c
        val j = c.json
        recentHooks.add(j.optString("hook"))
        topicView.text = "${c.topic} (${c.kind}) | ${LEVELS[c.level]} | via ${c.via}"
        statusView.text = ""
        hookView.text = j.optString("hook")
        explainerView.text = "EXPLAINER\n" + j.optString("explainer")
        questionView.text = when (c.pillar) {
            "LEARN" -> "CHECK YOURSELF\n"
            "THINK" -> "CHALLENGE\n"
            else -> "WONDER\n"
        } + j.optString("question")
        guessField.setText("")
        if (c.pillar == "DISCOVER" && !c.revealed) {
            c.revealed = true
            if (c.flags.add("viewed")) logEvent("view", "DISCOVER", 1)
        }
        applyState(c)
        val d = c.deeper
        if (d != null) { deeperView.text = d; deeperView.visibility = View.VISIBLE } else deeperView.visibility = View.GONE
    }

    private fun applyState(c: Card) {
        val j = c.json
        val p = c.pillar
        explainerView.visibility = if (p == "LEARN") View.VISIBLE else View.GONE
        discoverRow.visibility = if (p == "DISCOVER") View.VISIBLE else View.GONE
        val hinted = c.flags.contains("hint")
        hintView.text = "HINT\n" + j.optString("hint")
        hintView.visibility = if (p == "THINK" && hinted) View.VISIBLE else View.GONE
        hintBtn.visibility = if (p == "THINK" && !c.revealed && !hinted) View.VISIBLE else View.GONE
        if (!c.revealed) {
            guessField.visibility = View.VISIBLE; revealBtn.visibility = View.VISIBLE
            answerView.visibility = View.GONE; actionView.visibility = View.GONE; actionRow.visibility = View.GONE
            return
        }
        guessField.visibility = View.GONE; revealBtn.visibility = View.GONE
        val label = when (p) { "DISCOVER" -> "A TASTE"; "THINK" -> "SOLUTION"; else -> "ANSWER" }
        val g = if (c.guess.isNotEmpty()) "YOUR ANSWER\n${c.guess}\n${c.feedback}\n\n" else ""
        answerView.text = g + "$label\n${j.optString("answer")}\n\nFACT\n${j.optString("fact")}"
        answerView.visibility = View.VISIBLE
        val st = c.actionState
        actionView.text = "TRY TODAY (${c.pillar}, about ${c.minutes} min)\n${j.optString("action")}" +
            (if (st == "done") "\n[Done - logged]" else if (st == "skip") "\n[Skipped - logged]" else "")
        actionView.visibility = View.VISIBLE
        actionRow.visibility = if (st == null) View.VISIBLE else View.GONE
    }

    private fun reveal() {
        val c = current ?: return
        if (c.revealed) return
        c.guess = guessField.text.toString().trim()
        c.revealed = true
        val think = c.pillar == "THINK"
        if (!think) logEvent("reveal", c.pillar, 1)       // THINK minutes come only from real attempts
        if (think && c.guess.isEmpty()) statusView.text = "No attempt, no THINK minutes. Try an answer next time."
        if (c.kind != "preferred" && c.pillar != "DISCOVER") logEvent("explore", "DISCOVER", 1)
        if (c.guess.isNotEmpty()) { c.feedback = "Checking your guess..."; judgeGuess(c) }
        applyState(c)
    }

    /** Asks the model to judge the typed guess and shows feedback. A real attempt earns 1 THINK minute. */
    private fun judgeGuess(c: Card) {
        val j = c.json
        val prompt = "A learner was asked a question and typed a guess before seeing the answer. Judge the guess fairly. " +
            "Question: ${j.optString("question")}. Correct answer: ${j.optString("answer")}. Learner's guess: ${c.guess}. " +
            "Reply with ONLY a JSON object with string keys: verdict (exactly one of: spot_on, close, off, no_attempt; " +
            "use no_attempt only for gibberish or a non-answer) and feedback (1-2 plain sentences, warm but honest: " +
            "say what they got right, and what to adjust if anything)."
        Thread {
            var verdict = "error"
            var fb = "Could not check your guess (no connection), but the effort counts."
            try {
                val (_, raw) = fetchText(prompt)
                val o = Net.extractJson(raw)
                verdict = o.optString("verdict")
                fb = o.optString("feedback")
            } catch (e: Exception) { /* keep fallback */ }
            val label = when (verdict) {
                "spot_on" -> "Spot on"; "close" -> "Close"; "off" -> "Not quite"
                "no_attempt" -> "No real attempt"; else -> "Unchecked"
            }
            runOnUiThread {
                c.feedback = "[$label] $fb"
                if (c.flags.add("guessLogged") && verdict != "no_attempt") logEvent("guess", "THINK", if (c.pillar == "THINK") 2 else 1)
                if (current === c) applyState(c)
            }
        }.start()
    }

    private fun actionResult(state: String) {
        val c = current ?: return
        if (c.actionState != null) return
        c.actionState = state
        if (state == "done") {
            logEvent("action_done", c.pillar, c.minutes)
            if (c.pillar != "CONFIDENCE") logEvent("action_conf", "CONFIDENCE", 1)
            statusView.text = "Logged: ${c.minutes} min of ${c.pillar}."
        } else {
            logEvent("action_skip", c.pillar, 0)
            statusView.text = "Skip logged. The weekly review will remember."
        }
        applyState(c)
    }

    private fun signal(kind: String) {
        val c = current ?: return
        val e = prefs.edit()
        e.putInt("n_$kind", prefs.getInt("n_$kind", 0) + 1)
        when (kind) {
            "more" -> e.putInt("w_${c.topic}", weight(c.topic) + 2)
            "less" -> e.putInt("w_${c.topic}", maxOf(0, weight(c.topic) - 2))
            "surprised" -> e.putInt("w_${c.topic}", weight(c.topic) + 1)
            "toohard" -> e.putInt("lvl_${c.topic}", maxOf(0, lvl(c.topic) - 1))
            "tooeasy" -> e.putInt("lvl_${c.topic}", minOf(2, lvl(c.topic) + 1))
            "favourite" -> {
                val arr = try { JSONArray(prefs.getString("favs", "[]")) } catch (x: Exception) { JSONArray() }
                arr.put(JSONObject().put("topic", c.topic).put("card", c.json))
                e.putString("favs", arr.toString())
            }
        }
        e.apply()
        statusView.text = when (kind) {
            "more" -> "Noted: more ${c.topic}."
            "less" -> "Noted: less ${c.topic}."
            "surprised" -> "Noted: that surprised you."
            "toohard" -> "Noted: ${c.topic} cards will be easier (now ${LEVELS[lvl(c.topic)]})."
            "tooeasy" -> "Noted: ${c.topic} cards will be harder (now ${LEVELS[lvl(c.topic)]})."
            else -> "Saved to favourites."
        }
    }

    private fun adoptTopic() {
        val c = current ?: return
        val sel = selected()
        if (sel.contains(c.topic)) { statusView.text = "${c.topic} is already in your interests."; return }
        prefs.edit().putString("tags", (sel + c.topic).joinToString("|"))
            .putString("blocked", blockedList().filter { it != c.topic }.joinToString("|"))
            .putInt("lvl_${c.topic}", c.level).apply()
        if (c.flags.add("adopt")) logEvent("adopt", "DISCOVER", 2)
        exportPrefs()
        statusView.text = "Added ${c.topic} to your interests (${LEVELS[c.level]})."
    }

    private fun notForMe() {
        val c = current ?: return
        prefs.edit().putString("blocked", (blockedList() + c.topic).distinct().joinToString("|")).apply()
        statusView.text = "Noted: no more ${c.topic}."
    }

    private fun goDeeper() {
        val c = current ?: return
        if (c.deeper != null) return
        statusView.text = "Going deeper..."
        val j = c.json
        val prompt = "Explain more about this for a curious adult in about 200 words of plain text (no markdown, no lists). " +
            "Topic: ${c.topic}. Question: ${j.optString("question")}. Short answer so far: ${j.optString("answer")}. " +
            "End with one suggestion for what to explore next."
        Thread {
            try {
                val (_, raw) = fetchText(prompt)
                runOnUiThread {
                    c.deeper = "GO DEEPER\n" + raw.trim()
                    if (c.flags.add("deeper")) logEvent("deeper", c.pillar, 3)
                    if (current === c) { deeperView.text = c.deeper; deeperView.visibility = View.VISIBLE; statusView.text = "" }
                }
            } catch (e: Exception) {
                runOnUiThread { statusView.text = "FAIL: ${e.javaClass.simpleName}: ${e.message}" }
            }
        }.start()
    }

    // ---------- Weekly review ----------
    private fun showReview() {
        val root = vertical()
        root.addView(btn("Back to home") { showHome() })
        root.addView(text("Weekly review: last 7 days", 24f, true))
        val w = weekly()
        root.addView(text("Target: $WEEKLY_TARGET minutes per pillar per week.", 13f))
        for (p in PILLARS) {
            val m = w.pts[p] ?: 0
            root.addView(text("$p: $m min - ${statusLabel(m)}", 16f, true))
            root.addView(bar(m), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)))
        }
        root.addView(text("Honest take", 18f, true))
        root.addView(text(honestTake(w), 16f))
        root.addView(text("Minutes logged: reveal 1; real guess 1 (THINK); non-preferred topic 1 (DISCOVER); " +
            "go deeper 3; action done = its estimated minutes (+1 CONFIDENCE); manual logs as entered.", 12f))
        var armed = false
        val reset = Button(this)
        reset.text = "Reset stats"
        reset.setOnClickListener {
            if (!armed) { armed = true; reset.text = "Tap again to confirm reset" } else {
                prefs.edit().remove("log").apply(); showReview()
            }
        }
        root.addView(reset)
        screen("review", root)
    }

    private fun honestTake(w: Week): String {
        if (w.reveals + w.done + w.skipped + w.pts.values.sum() == 0) return "Nothing logged in the last 7 days. That is not enough to say anything yet. Open a pillar."
        val lines = ArrayList<String>()
        lines.add("Cards explored: ${w.reveals}. Actions done: ${w.done}. Actions skipped: ${w.skipped}. Active ${w.days} of 7 days.")
        if (w.reveals >= 5 && w.done == 0) lines.add("You have read a lot and done nothing. Knowing is not doing.")
        else if (w.skipped > w.done && w.skipped >= 3) lines.add("You have skipped more actions than you have done.")
        else if (w.done >= 3) lines.add("Good: you are actually doing things, not just reading.")
        val low = PILLARS.filter { (w.pts[it] ?: 0) < WEEKLY_TARGET / 2 }
        if (low.size >= 4) lines.add("Not enough across ${low.size} of 6 pillars: ${low.joinToString(", ")}.")
        else if (low.isNotEmpty()) lines.add("Weak spots: ${low.joinToString(", ")}.")
        else lines.add("Balanced week. No pillar is below half target.")
        return lines.joinToString("\n\n")
    }

    // ---------- Favourites ----------
    private fun showFavourites() {
        val root = vertical()
        root.addView(btn("Back to home") { showHome() })
        root.addView(text("Favourites", 24f, true))
        val arr = try { JSONArray(prefs.getString("favs", "[]")) } catch (x: Exception) { JSONArray() }
        if (arr.length() == 0) root.addView(text("Nothing saved yet. Tap Favourite on a card.", 16f))
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.getJSONObject(i); val c = o.getJSONObject("card")
            root.addView(text("${o.optString("topic")}: ${c.optString("hook")}", 16f, true))
            root.addView(text("${c.optString("question")}\n${c.optString("answer")}", 14f))
        }
        screen("favourites", root)
    }

    // ---------- Settings ----------
    private fun showSettings() {
        val root = vertical()
        root.addView(btn("Back to home") { showHome() })
        root.addView(text("Settings", 24f, true))
        root.addView(text("Build v${version()} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", 13f))
        val choices = listOf("Auto") + Net.PROVIDERS
        root.addView(text("Provider (Auto = try every saved key)", 14f))
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, choices)
        spinner.setSelection(choices.indexOf(prefs.getString("provider", "Auto") ?: "Auto").coerceAtLeast(0))
        root.addView(spinner)
        val keyField = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        root.addView(keyField)
        val out = text("", 14f)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val name = choices[pos]
                prefs.edit().putString("provider", name).apply()
                keyField.isEnabled = name != "Auto"
                keyField.hint = if (name == "Auto") "Auto uses all saved keys" else "$name API key (this device only)"
                keyField.setText(if (name == "Auto") "" else keyFor(name))
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        root.addView(btn("Save key") {
            val name = choices[spinner.selectedItemPosition]
            if (name == "Auto") out.text = "Pick a provider to save its key." else {
                prefs.edit().putString("key_$name", keyField.text.toString().trim()).apply()
                exportPrefs()
                out.text = "$name key saved. Providers with keys: ${Net.PROVIDERS.filter { keyFor(it).isNotEmpty() }}"
            }
        })
        root.addView(btn("Connection test") {
            out.text = "Testing..."
            Thread {
                val t0 = System.currentTimeMillis()
                val msg = try {
                    val (via, r) = fetchText("Reply with exactly: CONNECTION OK")
                    "PASS via $via (${System.currentTimeMillis() - t0} ms): ${r.take(60)}"
                } catch (e: Exception) { "FAIL: ${e.javaClass.simpleName}: ${e.message}" }
                runOnUiThread { out.text = msg }
            }.start()
        })
        root.addView(out)
        val stats = "Signals so far: more ${prefs.getInt("n_more", 0)}, less ${prefs.getInt("n_less", 0)}, " +
            "favourite ${prefs.getInt("n_favourite", 0)}, surprised ${prefs.getInt("n_surprised", 0)}"
        root.addView(text(stats, 13f))
        root.addView(text("Data and keys are also backed up to the tablet folder CuriosityEngine/backup.json so they survive reinstalling. Anyone with file access to the tablet can read that file.", 12f))
        var armed2 = false
        val erase = Button(this)
        erase.text = "Erase ALL data and backup"
        erase.setOnClickListener {
            if (!armed2) { armed2 = true; erase.text = "Tap again to confirm erase" } else {
                prefs.edit().clear().commit()
                try { backupFile().delete() } catch (e: Exception) { }
                current = null; next = null
                showInterests()
            }
        }
        root.addView(erase)
        screen("settings", root)
    }
}
