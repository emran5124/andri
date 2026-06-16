package com.example.export

import com.example.api.JsonSerializer

class HtmlExporter {

    class ChapParsed(val title: String, val rawLines: MutableList<String> = mutableListOf())
    class SecParsed(val title: String, val chapters: MutableList<ChapParsed> = mutableListOf(), val _buf: MutableList<String> = mutableListOf())

    private fun escapeHtml(str: String): String {
        return str
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun parseStructure(rawText: String): List<SecParsed> {
        val lines = rawText.split(Regex("\\r?\\n"))
        val sectionSep = Regex("^={3,}\\s*$")
        val chapterPat = Regex("^🚩\\s*\\[(.+?)\\]\\s*🚩\\s*$")
        val chapterPat2 = Regex("^🚩\\s*(.+?)\\s*🚩\\s*$")

        val sections = mutableListOf<SecParsed>()
        var currentSection: SecParsed? = null
        var currentChapter: ChapParsed? = null
        var pendingTitle: String? = null
        var inSep = false

        fun flushChapter() {
            if (currentChapter != null && currentSection != null) {
                currentSection!!.chapters.add(currentChapter!!)
                currentChapter = null
            }
        }

        fun flushSection() {
            if (currentSection != null) {
                flushChapter()
                if (currentSection!!.chapters.isEmpty()) {
                    val secBuf = currentSection!!._buf
                    currentSection!!.chapters.add(ChapParsed(title = currentSection!!.title, rawLines = secBuf))
                }
                currentSection!!._buf.clear()
                sections.add(currentSection!!)
                currentSection = null
            }
        }

        for (line in lines) {
            if (sectionSep.matches(line)) {
                if (!inSep) {
                    inSep = true
                    pendingTitle = null
                } else {
                    inSep = false
                    if (pendingTitle != null) {
                        flushSection()
                        currentSection = SecParsed(title = pendingTitle!!.trim())
                        currentChapter = null
                        pendingTitle = null
                    }
                }
                continue
            }

            if (inSep) {
                pendingTitle = if (pendingTitle == null) line else pendingTitle + "\n" + line
                continue
            }

            var chapterTitle: String? = null
            val m1 = chapterPat.find(line)
            val m2 = if (m1 == null) chapterPat2.find(line) else null

            if (m1 != null) {
                chapterTitle = m1.groupValues[1].trim()
            } else if (m2 != null) {
                chapterTitle = m2.groupValues[1].trim()
            }

            if (chapterTitle != null) {
                if (currentSection == null) {
                    currentSection = SecParsed(title = chapterTitle)
                }
                flushChapter()
                currentChapter = ChapParsed(title = chapterTitle)
                continue
            }

            if (currentChapter != null) {
                currentChapter!!.rawLines.add(line)
            } else if (currentSection != null) {
                currentSection!!._buf.add(line)
            }
        }

        flushSection()
        return sections
    }

    private fun parseContent(rawLines: List<String>): String {
        val raw = rawLines.joinToString("\n")

        val escapes = mutableListOf<String>()
        val text1 = Regex("\\\\([*_`\\[\\]()~<>«»!#|\\\\])").replace(raw) { match ->
            val ch = match.groupValues[1]
            escapes.add(ch)
            "\u0000ESC${escapes.size - 1}\u0000"
        }

        val codeBlocks = mutableListOf<String>()
        val text2 = Regex("```([\\w]*)\\n?([\\s\\S]*?)```").replace(text1) { match ->
            val lang = match.groupValues[1]
            val code = match.groupValues[2]
            val block = "<pre class=\"code-block\" data-lang=\"${escapeHtml(lang)}\"><code>${escapeHtml(code.trim())}</code></pre>"
            codeBlocks.add(block)
            "\u0000CB${codeBlocks.size - 1}\u0000"
        }

        val inlineCodes = mutableListOf<String>()
        val text3 = Regex("`([^`\\n]+?)`").replace(text2) { match ->
            val code = match.groupValues[1]
            val block = "<code class=\"inline-code\">${escapeHtml(code)}</code>"
            inlineCodes.add(block)
            "\u0000IC${inlineCodes.size - 1}\u0000"
        }

        val lines = text3.split('\n')
        val outputBlocks = mutableListOf<String>()

        class ListItem(val depth: Int, val content: String)
        val listBuffer = mutableListOf<ListItem>()
        var listOrdered = false

        fun flushList() {
            if (listBuffer.isEmpty()) return
            val tag = if (listOrdered) "ol" else "ul"
            val html = java.lang.StringBuilder("<$tag class=\"md-list\">")
            for (item in listBuffer) {
                html.append("<li>").append(item.content).append("</li>")
            }
            html.append("</$tag>")
            outputBlocks.add(html.toString())
            listBuffer.clear()
        }

        val tableBuffer = mutableListOf<String>()

        fun flushTable() {
            if (tableBuffer.size < 2) {
                for (l in tableBuffer) {
                    outputBlocks.add(applyInline(l))
                }
                tableBuffer.clear()
                return
            }
            val html = java.lang.StringBuilder("<div class=\"table-wrap\"><table class=\"md-table\"><thead><tr>")
            val headers = tableBuffer[0].split('|').map { it.trim() }.filter { it.isNotEmpty() }
            for (h in headers) {
                html.append("<th>").append(applyInline(h)).append("</th>")
            }
            html.append("</tr></thead><tbody>")
            for (i in 2 until tableBuffer.size) {
                html.append("<tr>")
                val cols = tableBuffer[i].split('|').map { it.trim() }.filter { it.isNotEmpty() }
                for (c in cols) {
                    html.append("<td>").append(applyInline(c)).append("</td>")
                }
                html.append("</tr>")
            }
            html.append("</tbody></table></div>")
            outputBlocks.add(html.toString())
            tableBuffer.clear()
        }

        for (i in lines.indices) {
            val line = lines[i]
            val trimmed = line.trim()

            if (trimmed.startsWith("\u0000CB") && trimmed.endsWith("\u0000")) {
                flushList()
                flushTable()
                outputBlocks.add(trimmed)
                continue
            }

            if (Regex("^[-*_]{3,}\\s*$").matches(trimmed)) {
                flushList()
                flushTable()
                outputBlocks.add("<hr class=\"md-hr\">")
                continue
            }

            val hMatch = Regex("^(#{1,6})\\s+(.+)$").find(trimmed)
            if (hMatch != null) {
                flushList()
                flushTable()
                val level = hMatch.groupValues[1].length
                val content = applyInline(hMatch.groupValues[2])
                outputBlocks.add("<h$level class=\"md-h$level\">$content</h$level>")
                continue
            }

            if (trimmed.startsWith(">")) {
                flushList()
                flushTable()
                val inner = applyInline(trimmed.substring(1).trim())
                outputBlocks.add("<blockquote class=\"md-blockquote\">$inner</blockquote>")
                continue
            }

            if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                flushList()
                tableBuffer.add(trimmed.substring(1, trimmed.length - 1))
                continue
            } else if (tableBuffer.isNotEmpty()) {
                flushTable()
            }

            val olMatch = Regex("^(\\s*)(\\d+)\\.\\s+(.+)$").find(line)
            if (olMatch != null) {
                if (listBuffer.isNotEmpty() && !listOrdered) flushList()
                listOrdered = true
                listBuffer.add(ListItem(olMatch.groupValues[1].length / 2, applyInline(olMatch.groupValues[3])))
                continue
            }

            val ulMatch = Regex("^(\\s*)[-*+]\\s+(.+)$").find(line)
            if (ulMatch != null) {
                if (listBuffer.isNotEmpty() && listOrdered) flushList()
                listOrdered = false
                listBuffer.add(ListItem(ulMatch.groupValues[1].length / 2, applyInline(ulMatch.groupValues[2])))
                continue
            }

            if (listBuffer.isNotEmpty()) {
                flushList()
            }

            if (trimmed.isEmpty()) {
                outputBlocks.add("<p-break/>")
                continue
            }

            outputBlocks.add(applyInline(trimmed))
        }

        flushList()
        flushTable()

        val html = java.lang.StringBuilder()
        val paraLines = mutableListOf<String>()

        fun flushPara() {
            if (paraLines.isNotEmpty()) {
                html.append("<p class=\"md-p\">").append(paraLines.joinToString("<br>")).append("</p>")
                paraLines.clear()
            }
        }

        for (block in outputBlocks) {
            val isBlockElement = block.startsWith("<h") ||
                    block.startsWith("<pre") ||
                    block.startsWith("<blockquote") ||
                    block.startsWith("<ul") ||
                    block.startsWith("<ol") ||
                    block.startsWith("<hr") ||
                    block.startsWith("<div") ||
                    block == "<p-break/>" ||
                    (block.startsWith("\u0000CB") && block.endsWith("\u0000"))

            if (isBlockElement) {
                flushPara()
                if (block != "<p-break/>") {
                    html.append(block)
                }
            } else {
                paraLines.add(block)
            }
        }
        flushPara()

        var finalHtml = html.toString()

        finalHtml = Regex("\\x00CB(\\d+)\\x00").replace(finalHtml) { match ->
            val idx = match.groupValues[1].toInt()
            codeBlocks[idx]
        }
        finalHtml = Regex("\\x00IC(\\d+)\\x00").replace(finalHtml) { match ->
            val idx = match.groupValues[1].toInt()
            inlineCodes[idx]
        }
        finalHtml = Regex("\\x00ESC(\\d+)\\x00").replace(finalHtml) { match ->
            val idx = match.groupValues[1].toInt()
            escapeHtml(escapes[idx])
        }

        return finalHtml
    }

    private fun applyInline(text: String): String {
        var res = text

        res = Regex("!\\[([^\\]]*?)\\]\\(([^)]+?)\\)").replace(res) { match ->
            val alt = match.groupValues[1]
            val src = match.groupValues[2]
            "<img class=\"md-img\" src=\"${escapeHtml(src)}\" alt=\"${escapeHtml(alt)}\" loading=\"lazy\">"
        }

        res = Regex("\\[([^\\]]+?)\\]\\(([^)]+?)\\)").replace(res) { match ->
            val label = match.groupValues[1]
            val href = match.groupValues[2]
            "<a class=\"md-link\" href=\"${escapeHtml(href)}\" target=\"_blank\" rel=\"noopener\">$label</a>"
        }

        res = Regex("\\*{3}(.+?)\\*{3}").replace(res, "<strong><em>$1</em></strong>")
        res = Regex("_{3}(.+?)_{3}").replace(res, "<strong><em>$1</em></strong>")

        res = Regex("\\*{2}(.+?)\\*{2}").replace(res, "<strong>$1</strong>")
        res = Regex("_{2}(.+?)_{2}").replace(res, "<strong>$1</strong>")

        res = Regex("\\*(.+?)\\*").replace(res, "<em>$1</em>")
        res = Regex("_(.+?)_").replace(res, "<em>$1</em>")

        res = Regex("««([\\s\\S]+?)»»").replace(res, "<mark class=\"hl-4\">$1</mark>")
        res = Regex("<<([\\s\\S]+?)>>").replace(res, "<mark class=\"hl-3\">$1</mark>")
        res = Regex("\\[\\[([\\s\\S]+?)\\]\\]").replace(res, "<mark class=\"hl-2\">$1</mark>")
        res = Regex("\\(\\(([\\s\\S]+?)\\)\\)").replace(res, "<mark class=\"hl-1\">$1</mark>")

        res = Regex("«([\\s\\S]+?)»").replace(res, "<span class=\"cl-4\">$1</span>")
        res = Regex("<([^<>]+?)>").replace(res) { match ->
            val inner = match.groupValues[1]
            val isHtmlTag = Regex("^/?\\w[\\w-]*(\\s|/?>|$)").containsMatchIn(inner)
            if (isHtmlTag) {
                match.value
            } else {
                "<span class=\"cl-3\">$inner</span>"
            }
        }
        res = Regex("\\[([^\\[\\]]+?)\\]").replace(res, "<span class=\"cl-2\">$1</span>")
        res = Regex("¥¥([^¥]+?)¥¥").replace(res, "<span class=\"cl-1\">$1</span>")

        return res
    }

    private fun normaliseArabic(str: String): String {
        return str
            .replace("ي", "ی")
            .replace("ك", "ک")
            .replace("ـ", "")
            .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
            .replace(Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u206F\\uFEFF]"), "")
            .lowercase()
            .trim()
    }

    private fun bookToJson(sections: List<SecParsed>): String {
        val sb = java.lang.StringBuilder()
        sb.append("{\"sections\":[")
        for (i in sections.indices) {
            val sec = sections[i]
            sb.append("{")
            sb.append("\"title\":").append(kotlinJsonEscape(sec.title)).append(",")
            sb.append("\"chapters\":[")
            for (j in sec.chapters.indices) {
                val ch = sec.chapters[j]
                val htmlContent = parseContent(ch.rawLines)
                val plainText = ch.rawLines.joinToString(" ")

                sb.append("{")
                sb.append("\"title\":").append(kotlinJsonEscape(ch.title)).append(",")
                sb.append("\"html\":").append(kotlinJsonEscape(htmlContent)).append(",")
                sb.append("\"search\":").append(kotlinJsonEscape(normaliseArabic(plainText))).append(",")
                sb.append("\"plain\":").append(kotlinJsonEscape(if (plainText.length > 300) plainText.substring(0, 300) else plainText))
                sb.append("}")
                if (j < sec.chapters.size - 1) sb.append(",")
            }
            sb.append("]")
            sb.append("}")
            if (i < sections.size - 1) sb.append(",")
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun kotlinJsonEscape(str: String): String {
        val out = java.lang.StringBuilder()
        out.append("\"")
        for (element in str) {
            when (element) {
                '\\' -> out.append("\\\\")
                '\"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> {
                    if (element.code < 32) {
                        out.append(String.format("\\u%04x", element.code))
                    } else {
                        out.append(element)
                    }
                }
            }
        }
        out.append("\"")
        return out.toString()
    }

    fun generateHtmlFromSummaries(fileName: String, summaries: List<String>): String {
        val total = summaries.size
        val sb = java.lang.StringBuilder()
        for (i in summaries.indices) {
            val idx = i + 1
            sb.append("============\n")
            sb.append("بخش $idx از $total \n")
            sb.append("============\n")
            // sb.append("🚩 [خلاصه بخش $idx] 🚩\n")
            sb.append(summaries[i])
            if (i < summaries.size - 1) {
                sb.append("\n\n")
            }
        }
        val rawText = sb.toString()
        val parsed = parseStructure(rawText)
        val bookJson = bookToJson(parsed)
        val randSuffix = java.util.UUID.randomUUID().toString().take(6)
        val lsPrefix = "\"perBook_$randSuffix\""

        var html = """<!DOCTYPE html>
<html lang="fa" dir="rtl">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover">
<meta name="theme-color" content="#1a1a2e">
<title>__FILE_NAME__</title>
<style>
/* ═══════════════════════════════════════════════════════════════
   FONT
═══════════════════════════════════════════════════════════════ */
:root { --font-main: 'Vazir', 'Tahoma', 'Arial Unicode MS', sans-serif; }

/* ═══════════════════════════════════════════════════════════════
   THEMES
═══════════════════════════════════════════════════════════════ */
:root {
  --font-size: 17px;
  --line-height: 2;
  --content-width: 780px;

  /* Light (default) */
  --bg         : #f7f4ef;
  --surface    : #surface;
  --surface    : #ffffff;
  --surface2   : #f0ece4;
  --text       : #2c2416;
  --text-muted : #7a6e5f;
  --border     : #d8cfc4;
  --accent     : #7c4d2e;
  --accent2    : #b06030;
  --nav-bg     : #2c2416;
  --nav-text   : #e8ddd0;
  --nav-active : #c87941;
  --progress   : #c87941;
  --scrollbar  : #c4b9aa;

  /* highlights */
  --hl1-bg:#fff3b0;--hl1-fg:#5a4000;
  --hl2-bg:#c8f0c8;--hl2-fg:#1a4a1a;
  --hl3-bg:#ffd6c8;--hl3-fg:#5a2010;
  --hl4-bg:#d4c8f8;--hl4-fg:#2a1060;

  /* colors */
  --cl1:#c06000;
  --cl2:#1a6a1a;
  --cl3:#7a1070;
  --cl4:#1040a0;

  /* search */
  --search-hl:#ffd700;
  --search-fg:#2c2416;
}

[data-theme="dark"] {
  --bg         : #0e0e14;
  --surface    : #1a1a26;
  --surface2   : #23232f;
  --text       : #ddd8f0;
  --text-muted : #7070a0;
  --border     : #33334a;
  --accent     : #8888ff;
  --accent2    : #b0a0ff;
  --nav-bg     : #0a0a12;
  --nav-text   : #c8c8e8;
  --nav-active : #9090ff;
  --progress   : #8080ff;
  --scrollbar  : #33334a;
  --hl1-bg:#3a3000;--hl1-fg:#ffd060;
  --hl2-bg:#003a00;--hl2-fg:#80ff80;
  --hl3-bg:#3a1000;--hl3-fg:#ff9060;
  --hl4-bg:#200060;--hl4-fg:#c0a0ff;
  --cl1:#ffb060; --cl2:#60ff80; --cl3:#e060e0; --cl4:#60b0ff;
  --search-hl:#886600; --search-fg:#fff;
}

[data-theme="sepia"] {
  --bg         : #f4ecd8;
  --surface    : #fdf6e6;
  --surface2   : #ede0c8;
  --text       : #3c2e1a;
  --text-muted : #8a7258;
  --border     : #c8b898;
  --accent     : #8b5e3c;
  --accent2    : #a0714d;
  --nav-bg     : #3c2e1a;
  --nav-text   : #ede0c8;
  --nav-active : #c8945a;
  --progress   : #c8945a;
  --scrollbar  : #c0a880;
  --hl1-bg:#f0e090;--hl1-fg:#3c2e00;
  --hl2-bg:#c0e8c0;--hl2-fg:#1a3c1a;
  --hl3-bg:#f8c8b8;--hl3-fg:#4a1800;
  --hl4-bg:#d8c8f0;--hl4-fg:#200840;
  --cl1:#9a5020; --cl2:#2a6a2a; --cl3:#8a0880; --cl4:#1050b0;
  --search-hl:#e8c840; --search-fg:#3c2e1a;
}

[data-theme="analogous"] {
  --bg:#e8f4f0; --surface:#f4fbf8; --surface2:#d8ecea;
  --text:#163028; --text-muted:#4a7868;
  --border:#a8d4c8; --accent:#2a8060; --accent2:#3aaa80;
  --nav-bg:#163028; --nav-text:#d8f0e8; --nav-active:#3aaa80;
  --progress:#3aaa80; --scrollbar:#90c8b8;
  --hl1-bg:#d0f8c0;--hl1-fg:#184010; --hl2-bg:#b8e8f8;--hl2-fg:#0a2840;
  --hl3-bg:#f8e8b0;--hl3-fg:#403010; --hl4-bg:#e8c8f8;--hl4-fg:#280048;
  --cl1:#208060; --cl2:#0060a0; --cl3:#806020; --cl4:#600080;
  --search-hl:#90e060; --search-fg:#163028;
}

[data-theme="complementary"] {
  --bg:#f0eef8; --surface:#faf9ff; --surface2:#e4e0f4;
  --text:#1a1640; --text-muted:#5850a0;
  --border:#b8b0e8; --accent:#4040d0; --accent2:#6060e0;
  --nav-bg:#1a1640; --nav-text:#d8d4f8; --nav-active:#f0a000;
  --progress:#f0a000; --scrollbar:#9090d0;
  --hl1-bg:#fff0b0;--hl1-fg:#402800; --hl2-bg:#b8d8ff;--hl2-fg:#001840;
  --hl3-bg:#ffd0b0;--hl3-fg:#402000; --hl4-bg:#d0f0b0;--hl4-fg:#103010;
  --cl1:#c07000; --cl2:#0050c0; --cl3:#c00050; --cl4:#008050;
  --search-hl:#f0c000; --search-fg:#1a1640;
}

[data-theme="triadic"] {
  --bg:#fff8f0; --surface:#fffcf8; --surface2:#f8eee0;
  --text:#3a1800; --text-muted:#906030;
  --border:#e8c898; --accent:#c04000; --accent2:#e06000;
  --nav-bg:#3a1800; --nav-text:#ffe8c8; --nav-active:#0090e0;
  --progress:#0090e0; --scrollbar:#d0a870;
  --hl1-bg:#ffe0b0;--hl1-fg:#401000; --hl2-bg:#b0e8ff;--hl2-fg:#002040;
  --hl3-bg:#b0ffd0;--hl3-fg:#003020; --hl4-bg:#f0b0ff;--hl4-fg:#300040;
  --cl1:#c03000; --cl2:#0080d0; --cl3:#008040; --cl4:#9000c0;
  --search-hl:#ffe040; --search-fg:#3a1800;
}

[data-theme="monochromatic"] {
  --bg:#f0f0f0; --surface:#f8f8f8; --surface2:#e4e4e4;
  --text:#181818; --text-muted:#606060;
  --border:#c0c0c0; --accent:#4040d0; --accent2:#606060;
  --nav-bg:#181818; --nav-text:#e0e0e0; --nav-active:#a0a0a0;
  --progress:#707070; --scrollbar:#b0b0b0;
  --hl1-bg:#d8d8d8;--hl1-fg:#101010; --hl2-bg:#c8c8c8;--hl2-fg:#101010;
  --hl3-bg:#b8b8b8;--hl3-fg:#000000; --hl4-bg:#e8e8e8;--hl4-fg:#101010;
  --cl1:#404040; --cl2:#606060; --cl3:#282828; --cl4:#505050;
  --search-hl:#909090; --search-fg:#000;
}

/* ═══════════════════════════════════════════════════════════════
   RESET & BASE
═══════════════════════════════════════════════════════════════ */
*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

html {
  scroll-behavior: smooth;
  overflow-x: hidden;
}

body {
  font-family: 'Vazirmatn', sans-serif;
  font-size: var(--font-size);
  line-height: var(--line-height);
  background: var(--bg);
  color: var(--text);
  min-height: 100dvh;
  direction: rtl;
  -webkit-font-smoothing: antialiased;
  transition: background .3s, color .3s;
}

::-webkit-scrollbar { width: 6px; height: 6px; }
::-webkit-scrollbar-track { background: transparent; }
::-webkit-scrollbar-thumb { background: var(--scrollbar); border-radius: 3px; }

/* ═══════════════════════════════════════════════════════════════
   PROGRESS BAR
═══════════════════════════════════════════════════════════════ */
#progress-bar {
  position: fixed;
  top: 0; inset-inline-start: 0;
  height: 3px;
  width: 0%;
  background: var(--progress);
  z-index: 1000;
  transition: width .15s linear;
}

/* ═══════════════════════════════════════════════════════════════
   NAVIGATION SIDEBAR
═══════════════════════════════════════════════════════════════ */
#sidebar {
  position: fixed;
  top: 0;
  right: 0;
  width: min(320px, 88vw);
  height: 100dvh;
  height: 100vh;
  background: var(--nav-bg);
  color: var(--nav-text);
  z-index: 500;
  display: flex;
  flex-direction: column;
  transform: translateX(100%);
  transition: transform .3s cubic-bezier(.4,0,.2,1);
  overflow: hidden;
  will-change: transform;
}

#sidebar.open { transform: translateX(0); }

#sidebar-header {
  padding: 20px 18px 14px;
  border-bottom: 1px solid rgba(255,255,255,.08);
  display: flex;
  align-items: center;
  gap: 10px;
}

#sidebar-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--nav-active);
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

#sidebar-close {
  background: none; border: none; cursor: pointer;
  color: var(--nav-text); font-size: 20px; padding: 4px;
  opacity: .7; transition: opacity .2s;
}
#sidebar-close:hover { opacity: 1; }

#nav-tree {
  flex: 1;
  overflow-y: auto;
  padding: 10px 0 20px;
}

.nav-section-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 10px 16px;
  background: none; border: none; cursor: pointer;
  color: var(--nav-text);
  font-family: 'Vazirmatn';
  font-size: 13.5px;
  text-align: right;
  direction: rtl;
  transition: background .15s;
  border-right: 3px solid transparent;
}
.nav-section-btn:hover { background: rgba(255,255,255,.06); }
.nav-section-btn.active { border-right-color: var(--nav-active); color: var(--nav-active); }

.nav-section-arrow {
  font-size: 11px;
  transition: transform .2s;
  flex-shrink: 0;
  margin-inline-start: auto;
}
.nav-section-btn.collapsed .nav-section-arrow { transform: rotate(-90deg); }

.nav-chapters {
  overflow: hidden;
  transition: max-height .25s ease;
}
.nav-chapters.collapsed { max-height: 0 !important; }

.nav-chapter-btn {
  display: block;
  width: 100%;
  padding: 8px 32px 8px 16px;
  background: none; border: none; cursor: pointer;
  color: rgba(255,255,255,.6);
  font-family: 'Vazirmatn';
  font-size: 12.5px;
  text-align: right;
  direction: rtl;
  transition: color .15s, background .15s;
  border-right: 2px solid transparent;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.nav-chapter-btn:hover { color: var(--nav-text); background: rgba(255,255,255,.04); }
.nav-chapter-btn.active {
  color: var(--nav-active);
  border-right-color: var(--nav-active);
  background: rgba(255,255,255,.05);
}

#sidebar-progress {
  padding: 12px 16px;
  border-top: 1px solid rgba(255,255,255,.08);
  font-size: 11px;
  color: rgba(255,255,255,.45);
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.progress-bar-mini {
  height: 3px;
  background: rgba(255,255,255,.15);
  border-radius: 2px;
  overflow: hidden;
}
.progress-bar-mini-fill {
  height: 100%;
  background: var(--nav-active);
  transition: width .3s;
  border-radius: 2px;
}

/* ═══════════════════════════════════════════════════════════════
   OVERLAY
═══════════════════════════════════════════════════════════════ */
#overlay {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,.5);
  z-index: 490;
  opacity: 0;
  pointer-events: none;
  transition: opacity .3s;
}
#overlay.active { opacity: 1; pointer-events: all; }

/* Settings overlay */
#settings-overlay {
  position: fixed;
  inset: 0;
  z-index: 340;
  display: none;
}
#settings-overlay.active { display: block; }

/* ═══════════════════════════════════════════════════════════════
   TOP BAR
═══════════════════════════════════════════════════════════════ */
#topbar {
  position: fixed;
  top: 0; inset-inline-start: 0; inset-inline-end: 0;
  height: 52px;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 12px;
  z-index: 300;
  transition: background .3s, border-color .3s;
}

.topbar-btn {
  background: none; border: none; cursor: pointer;
  color: var(--text);
  font-size: 20px;
  padding: 8px;
  border-radius: 8px;
  display: flex; align-items: center; justify-content: center;
  transition: background .15s;
  flex-shrink: 0;
}
.topbar-btn:hover { background: var(--surface2); }

#search-wrap {
  flex: 1;
  position: relative;
}

#search-input {
  width: 100%;
  padding: 7px 14px;
  border: 1.5px solid var(--border);
  border-radius: 22px;
  background: var(--surface2);
  color: var(--text);
  font-family: 'Vazirmatn';
  font-size: 14px;
  direction: rtl;
  outline: none;
  transition: border-color .2s, background .3s;
}
#search-input:focus { border-color: var(--accent); }
#search-input::placeholder { color: var(--text-muted); }

#search-dropdown {
  position: absolute;
  top: calc(100% + 6px);
  inset-inline-start: 0; inset-inline-end: 0;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 12px;
  box-shadow: 0 8px 32px rgba(0,0,0,.18);
  max-height: 400px;
  overflow-y: auto;
  z-index: 600;
  display: none;
}
#search-dropdown.visible { display: block; }

.search-result-item {
  padding: 10px 14px;
  cursor: pointer;
  border-bottom: 1px solid var(--border);
  transition: background .12s;
}
.search-result-item:last-child { border-bottom: none; }
.search-result-item:hover { background: var(--surface2); }

.sri-section { font-size: 11px; color: var(--accent); margin-bottom: 2px; }
.sri-chapter { font-size: 13px; font-weight: 700; margin-bottom: 3px; color: var(--text); }
.sri-snippet { font-size: 12px; color: var(--text-muted); line-height: 1.5; }
.sri-snippet mark { background: var(--search-hl); color: var(--search-fg); border-radius: 2px; }

.search-view-all {
  padding: 10px 14px;
  text-align: center;
  font-size: 13px;
  color: var(--accent);
  cursor: pointer;
  font-weight: 600;
}
.search-view-all:hover { background: var(--surface2); }
.search-no-results { padding: 14px; text-align: center; color: var(--text-muted); font-size: 13px; }

/* ═══════════════════════════════════════════════════════════════
   MAIN CONTENT
═══════════════════════════════════════════════════════════════ */
#main {
  padding-top: 60px;
  padding-bottom: 80px;
  min-height: 100dvh;
}

#reader {
  max-width: var(--content-width);
  margin: 0 auto;
  padding: 24px 0px;
}

/* Chapter container */
.chapter-block {
  margin-bottom: 10px;
  padding: 32px 28px;
  background: var(--surface);
  border-radius: 0px;
  border: 1px solid var(--border);
  min-height: 50dvh;
  transition: background .3s, border-color .3s;
}

.chapter-title {
  font-size: 1.5em;
  font-weight: 700;
  color: var(--accent);
  margin-bottom: 24px;
  padding-bottom: 14px;
  border-bottom: 2px solid var(--border);
  line-height: 1.5;
}

.section-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-muted);
  letter-spacing: .05em;
  margin-bottom: 6px;
  display: block;
}

/* ═══════════════════════════════════════════════════════════════
   MARKDOWN STYLES
═══════════════════════════════════════════════════════════════ */
.md-h1,.md-h2,.md-h3,.md-h4,.md-h5,.md-h6 { font-weight: 700; margin: 1.2em 0 .5em; color: var(--text); line-height:1.4; }
.md-h1 { font-size:1.9em; color:var(--accent); }
.md-h2 { font-size:1.55em; color:var(--accent2); }
.md-h3 { font-size:1.3em; }
.md-h4 { font-size:1.15em; }
.md-h5 { font-size:1.05em; }
.md-h6 { font-size:.95em; font-style:italic; }

.md-p { margin: .5em 0 .8em; }
.md-hr { border: none; border-top: 1px solid var(--border); margin: 1.5em 0; }
.md-img { max-width: 100%; border-radius: 8px; margin: .5em 0; }
.md-link { color: var(--accent); text-decoration: underline; }

.md-blockquote {
  border-right: 4px solid var(--accent);
  margin: 1em 0;
  padding: .6em 14px;
  background: var(--surface2);
  border-radius: 0 6px 6px 0;
  color: var(--text-muted);
  font-style: italic;
}

.code-block {
  background: var(--surface2);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 14px 16px;
  overflow-x: auto;
  margin: .8em 0;
  direction: ltr;
  text-align: left;
}
.code-block code, .inline-code {
  font-family: 'Courier New', monospace;
  font-size: .88em;
}
.inline-code {
  background: var(--surface2);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 1px 5px;
}

.md-list { margin: .5em 0 .8em 0; padding-right: 1.6em; padding-left: 0; }
.md-list li { margin-bottom: .4em; }

.table-wrap { overflow-x: auto; margin: 1em 0; }
.md-table { width: 100%; border-collapse: collapse; font-size: .9em; }
.md-table th, .md-table td {
  border: 1px solid var(--border);
  padding: 8px 12px;
  text-align: right;
}
.md-table th {
  background: var(--surface2);
  font-weight: 700;
  color: var(--accent);
}
.md-table tr:nth-child(even) td { background: var(--surface2); }

/* ── Custom highlights ─────────────────────────────────────────────── */
mark.hl-1 { background:var(--hl1-bg); color:var(--hl1-fg); padding:1px 3px; border-radius:3px; }
mark.hl-2 { background:var(--hl2-bg); color:var(--hl2-fg); padding:1px 3px; border-radius:3px; }
mark.hl-3 { background:var(--hl3-bg); color:var(--hl3-fg); padding:1px 3px; border-radius:3px; }
mark.hl-4 { background:var(--hl4-bg); color:var(--hl4-fg); padding:1px 3px; border-radius:3px; }

/* ── Custom colors ─────────────────────────────────────────────────── */
.cl-1 { color: var(--cl1); }
.cl-2 { color: var(--cl2); }
.cl-3 { color: var(--cl3); }
.cl-4 { color: var(--cl4); }

/* Search highlight inside chapter */
.search-mark { background: var(--search-hl); color: var(--search-fg); border-radius: 2px; }

/* LTR island */
.ltr { direction: ltr; unicode-bidi: isolate; display: inline-block; }

/* ═══════════════════════════════════════════════════════════════
   CHAPTER PLACEHOLDER (always in DOM, zero height)
═══════════════════════════════════════════════════════════════ */
.chapter-placeholder { height: 0; margin: 0; padding: 0; overflow: hidden; }

/* ═══════════════════════════════════════════════════════════════
   SETTINGS PANEL
═══════════════════════════════════════════════════════════════ */
#settings-panel {
  position: fixed;
  bottom: 66px;
  left: 12px;
  right: 12px;
  max-width: 360px;
  max-height: 70dvh;
  max-height: 70vh;
  overflow-y: auto;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 16px;
  box-shadow: 0 8px 40px rgba(0,0,0,.25);
  z-index: 350;
  padding: 0;
  display: none;
  direction: rtl;
  transition: background .3s;
}
#settings-panel.visible { display: flex; flex-direction: column; }

#settings-inner {
  padding: 14px 16px 18px;
  overflow-y: auto;
}

#settings-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px 10px;
  border-bottom: 1px solid var(--border);
  position: sticky;
  top: 0;
  background: var(--surface);
  z-index: 1;
  border-radius: 16px 16px 0 0;
}
#settings-header-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--text);
}
#settings-close-btn {
  background: var(--surface2);
  border: 1px solid var(--border);
  border-radius: 50%;
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  font-size: 14px;
  color: var(--text);
  flex-shrink: 0;
  transition: background .15s;
}
#settings-close-btn:hover { background: var(--border); }

.settings-row {
  margin-bottom: 14px;
}
.settings-label { font-size: 13px; color: var(--text-muted); margin-bottom: 5px; display: block; }
.settings-range {
  width: 100%;
  accent-color: var(--accent);
}
.settings-select {
  width: 100%;
  padding: 6px 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--surface2);
  color: var(--text);
  font-family: 'Vazirmatn';
  font-size: 13px;
  direction: rtl;
}

.settings-section-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--accent);
  margin: 12px 0 8px;
  border-bottom: 1px solid var(--border);
  padding-bottom: 4px;
}

/* Theme colour picker */
#theme-color-input { width: 40px; height: 30px; border: none; cursor: pointer; background: none; }
.theme-gen-btn {
  background: var(--accent);
  color: var(--surface);
  border: none;
  border-radius: 8px;
  padding: 6px 14px;
  cursor: pointer;
  font-family: 'Vazirmatn';
  font-size: 12px;
  transition: opacity .2s;
}
.theme-gen-btn:hover { opacity: .85; }
.saved-themes { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; }
.saved-theme-chip {
  padding: 4px 10px;
  border-radius: 20px;
  font-size: 11px;
  cursor: pointer;
  border: 1.5px solid transparent;
  transition: border-color .15s;
}
.saved-theme-chip:hover { border-color: var(--accent); }

/* ═══════════════════════════════════════════════════════════════
   BOTTOM BAR (mobile)
═══════════════════════════════════════════════════════════════ */
#bottombar {
  position: fixed;
  bottom: 0; left: 0; right: 0;
  height: 52px;
  background: var(--surface);
  border-top: 1px solid var(--border);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 300;
  transition: background .3s;
}

/* ═══════════════════════════════════════════════════════════════
   FOCUS MODE
═══════════════════════════════════════════════════════════════ */
body.focus-mode #topbar,
body.focus-mode #bottombar,
body.focus-mode #sidebar { opacity: 0; pointer-events: none; }
body.focus-mode #reader { max-width: 660px; }
body.focus-mode { cursor: none; }
body.focus-mode:hover { cursor: default; }
body.focus-mode #topbar:hover,
body.focus-mode #bottombar:hover { opacity: 1; pointer-events: all; }

/* ═══════════════════════════════════════════════════════════════
   RESPONSIVE
═══════════════════════════════════════════════════════════════ */
@media (max-width: 600px) {
  .chapter-block { padding: 18px 14px; }
  :root { --font-size: 16px; --content-width: 100%; }
}
</style>
</head>
<body data-theme="light">

<div id="progress-bar"></div>
<div id="overlay"></div>

<!-- TOP BAR -->
<div id="topbar">
  <button class="topbar-btn" id="menu-btn" title="فهرست" aria-label="باز کردن فهرست">☰</button>
  <div id="search-wrap">
    <input type="search" id="search-input" placeholder="جستجو در کتاب…" autocomplete="off" spellcheck="false">
    <div id="search-dropdown"></div>
  </div>
  <button class="topbar-btn" id="settings-btn" title="تنظیمات" aria-label="تنظیمات">⚙</button>
  <button class="topbar-btn" id="focus-btn" title="حالت تمرکز" aria-label="حالت تمرکز">◎</button>
</div>

<!-- SIDEBAR -->
<div id="sidebar" role="navigation" aria-label="فهرست کتاب">
  <div id="sidebar-header">
    <div id="sidebar-title">فهرست</div>
    <button id="sidebar-close" aria-label="بستن">✕</button>
  </div>
  <nav id="nav-tree"></nav>
  <div id="sidebar-progress">
    <div>پیشرفت کل</div>
    <div class="progress-bar-mini"><div class="progress-bar-mini-fill" id="overall-progress-fill" style="width:0%"></div></div>
    <div id="overall-progress-text">۰٪</div>
  </div>
</div>

<!-- SETTINGS OVERLAY (transparent, closes panel on outside click) -->
<div id="settings-overlay"></div>

<!-- SETTINGS PANEL -->
<div id="settings-panel" role="dialog" aria-label="تنظیمات">
  <div id="settings-header">
    <span id="settings-header-title">تنظیمات</span>
    <button id="settings-close-btn" aria-label="بستن تنظیمات">✕</button>
  </div>
  <div id="settings-inner">
    <div class="settings-section-title">پوسته</div>
    <div class="settings-row">
      <select class="settings-select" id="theme-select">
        <option value="light">روشن</option>
        <option value="dark">تاریک</option>
        <option value="sepia">سپیا</option>
        <option value="analogous">آنالوگ</option>
        <option value="complementary">مکمل</option>
        <option value="triadic">سه‌گانه</option>
        <option value="monochromatic">تک‌رنگ</option>
      </select>
    </div>

    <div class="settings-section-title">تولید پوسته</div>
    <div class="settings-row" style="display:flex;align-items:center;gap:8px;">
      <input type="color" id="theme-color-input" value="#7c4d2e">
      <select class="settings-select" id="theme-harmony-select" style="flex:1">
        <option value="analogous">آنالوگ</option>
        <option value="complementary">مکمل</option>
        <option value="triadic">سه‌گانه</option>
        <option value="monochromatic">تک‌رنگ</option>
      </select>
      <button class="theme-gen-btn" id="theme-gen-btn">ساخت</button>
    </div>
    <div id="saved-themes-container">
      <div class="saved-themes" id="saved-themes"></div>
    </div>

    <div class="settings-section-title">خواندن</div>
    <div class="settings-row">
      <span class="settings-label">اندازه قلم: <span id="font-size-val">17</span>px</span>
      <input type="range" class="settings-range" id="font-size-range" min="13" max="26" value="17" step="1">
    </div>
    <div class="settings-row">
      <span class="settings-label">فاصله خطوط: <span id="line-height-val">2</span></span>
      <input type="range" class="settings-range" id="line-height-range" min="1.4" max="3" value="2" step="0.1">
    </div>
    <div class="settings-row">
      <span class="settings-label">عرض محتوا: <span id="content-width-val">780</span>px</span>
      <input type="range" class="settings-range" id="content-width-range" min="400" max="1200" value="780" step="20">
    </div>
  </div>
</div>

<!-- MAIN READER -->
<main id="main">
  <div id="reader"></div>
</main>

<!-- BOTTOM BAR -->
<div id="bottombar">
  <button class="topbar-btn" id="menu-btn-bottom" title="فهرست" aria-label="فهرست">☰</button>
</div>

<script>
// ════════════════════════════════════════════════════════════════
//  BOOK DATA — embedded at build time
// ════════════════════════════════════════════════════════════════
const BOOK = __BOOK_JSON__;

// ════════════════════════════════════════════════════════════════
//  BUILD FLAT INDEX
// ════════════════════════════════════════════════════════════════
const FLAT = []; // { si, ci, secTitle, chapTitle, norm, plain }
BOOK.sections.forEach((sec, si) => {
  sec.chapters.forEach((ch, ci) => {
    FLAT.push({
      si, ci,
      secTitle : sec.title,
      chapTitle: ch.title,
      norm     : normalise(ch.search || ''),
      plain    : ch.plain || '',
    });
  });
});

function normalise(str) {
  return str
    .replace(/[\u064B-\u065F\u0670]/g, '') // diacritics
    .replace(/[\uFEFF\u200B-\u200F]/g, '')  // zero-width
    .replace(/\u0649/g, '\u06CC')             // ي → ی
    .replace(/\u0643/g, '\u06A9')             // ك → ک
    .replace(/\u0640/g, '')                    // tatweel
    .toLowerCase()
    .trim();
}

// ════════════════════════════════════════════════════════════════
//  STATE
// ════════════════════════════════════════════════════════════════
let currentSi  = 0;
let currentCi  = 0;
let searchTerm = '';

// Virtualised render: which chapter blocks are mounted
const mounted = new Set(); // key = "si-ci"

const LS_PREFIX = __LS_PREFIX__;

function lsGet(k, def = null) {
  try { const v = localStorage.getItem(LS_PREFIX + k); return v !== null ? JSON.parse(v) : def; } catch { return def; }
}
function lsSet(k, v) { try { localStorage.setItem(LS_PREFIX + k, JSON.stringify(v)); } catch {} }

// ════════════════════════════════════════════════════════════════
//  SETTINGS RESTORE
// ════════════════════════════════════════════════════════════════
const savedTheme   = lsGet('theme', 'light');
const savedFS      = lsGet('fontSize', 17);
const savedLH      = lsGet('lineHeight', 2);
const savedCW      = lsGet('contentWidth', 780);
const savedThemes  = lsGet('customThemes', []);

document.body.dataset.theme = savedTheme;
document.documentElement.style.setProperty('--font-size', savedFS + 'px');
document.documentElement.style.setProperty('--line-height', savedLH);
document.documentElement.style.setProperty('--content-width', savedCW + 'px');

// ════════════════════════════════════════════════════════════════
//  NAVIGATION TREE
// ════════════════════════════════════════════════════════════════
function buildNavTree() {
  const tree = document.getElementById('nav-tree');
  tree.innerHTML = '';
  BOOK.sections.forEach((sec, si) => {
    // Section row
    const secBtn = document.createElement('button');
    secBtn.className = 'nav-section-btn';
    secBtn.innerHTML = `<span>${'$'}{esc(sec.title)}</span><span class="nav-section-arrow">▾</span>`;
    secBtn.dataset.si = si;

    const chDiv = document.createElement('div');
    chDiv.className = 'nav-chapters';
    const totalH = sec.chapters.length * 38;
    chDiv.style.maxHeight = totalH + 'px';

    secBtn.addEventListener('click', () => {
      const collapsed = secBtn.classList.toggle('collapsed');
      chDiv.classList.toggle('collapsed', collapsed);
      if (!collapsed) chDiv.style.maxHeight = totalH + 'px';
    });

    sec.chapters.forEach((ch, ci) => {
      const chBtn = document.createElement('button');
      chBtn.className = 'nav-chapter-btn';
      chBtn.textContent = ch.title;
      chBtn.dataset.si = si;
      chBtn.dataset.ci = ci;
      chBtn.addEventListener('click', () => jumpTo(si, ci));
      chDiv.appendChild(chBtn);
    });

    tree.appendChild(secBtn);
    tree.appendChild(chDiv);
  });
}

function updateNavActive(si, ci) {
  document.querySelectorAll('.nav-section-btn').forEach(btn => {
    btn.classList.toggle('active', +btn.dataset.si === si);
  });
  document.querySelectorAll('.nav-chapter-btn').forEach(btn => {
    const active = +btn.dataset.si === si && +btn.dataset.ci === ci;
    btn.classList.toggle('active', active);
    if (active) btn.scrollIntoView({ block: 'nearest' });
  });
}

// ════════════════════════════════════════════════════════════════
//  VIRTUALISED RENDERER
// ════════════════════════════════════════════════════════════════
const reader   = document.getElementById('reader');
const placeholders = {}; // key → placeholder div (always in DOM, never removed)

function chKey(si, ci) { return si + '-' + ci; }

/**
 * Called once at init. Inserts a 0-height placeholder for every chapter
 * in correct book order. Chapter blocks are inserted after their placeholder.
 */
function initPlaceholders() {
  BOOK.sections.forEach((sec, si) => {
    sec.chapters.forEach((_, ci) => {
      const key = chKey(si, ci);
      const ph = document.createElement('div');
      ph.className = 'chapter-placeholder';
      ph.id = 'ph-' + key;
      reader.appendChild(ph);
      placeholders[key] = ph;
    });
  });
}

/** Mount a chapter block into the DOM right after its placeholder */
function mountChapter(si, ci) {
  const key = chKey(si, ci);
  if (mounted.has(key)) return;
  const sec = BOOK.sections[si];
  if (!sec) return;
  const ch = sec.chapters[ci];
  if (!ch) return;

  const ph = placeholders[key];
  if (!ph) return;

  const div = document.createElement('div');
  div.className = 'chapter-block';
  div.id = 'chapter-' + key;
  div.innerHTML =
    `<span class="section-label">${'$'}{esc(sec.title)}</span>` +
    `<div class="chapter-title">${'$'}{esc(ch.title)}</div>` +
    `<div class="chapter-content">${'$'}{ch.html}</div>`;

  ph.after(div);
  mounted.add(key);
}

/** Unmount a chapter block to free DOM memory */
function unmountChapter(si, ci) {
  const key = chKey(si, ci);
  if (!mounted.has(key)) return;
  const el = document.getElementById('chapter-' + key);
  if (el) el.remove();
  mounted.delete(key);
}

/** Ensure a window of ±1 chapters around current is mounted */
function ensureWindow(si, ci) {
  const allChapters = [];
  BOOK.sections.forEach((sec, s) => {
    sec.chapters.forEach((_, c) => allChapters.push([s, c]));
  });
  const flatIdx = allChapters.findIndex(([s,c]) => s === si && c === ci);
  if (flatIdx < 0) return;

  const windowKeys = new Set();
  for (let d = -1; d <= 1; d++) {
    const idx = flatIdx + d;
    if (idx < 0 || idx >= allChapters.length) continue;
    const [s, c] = allChapters[idx];
    windowKeys.add(chKey(s, c));
    mountChapter(s, c);
  }

  // Unmount chapters outside window + 2 buffer
  for (const key of [...mounted]) {
    const [ks, kc] = key.split('-').map(Number);
    const fi = allChapters.findIndex(([s,c]) => s === ks && c === kc);
    if (Math.abs(fi - flatIdx) > 2) {
      unmountChapter(ks, kc);
    }
  }

  // Re-apply search highlights
  if (searchTerm) highlightSearchInView(searchTerm);
}

// ════════════════════════════════════════════════════════════════
//  JUMP TO CHAPTER
// ════════════════════════════════════════════════════════════════
function jumpTo(si, ci, scrollIntoView = true) {
  currentSi = si;
  currentCi = ci;
  ensureWindow(si, ci);

  if (scrollIntoView) {
    // Chapter may have just been mounted — wait one frame for layout
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        const el = document.getElementById('chapter-' + chKey(si, ci));
        if (el) {
          const top = el.getBoundingClientRect().top + window.scrollY - 64;
          window.scrollTo({ top: Math.max(0, top), behavior: 'smooth' });
        }
      });
    });
  }

  updateNavActive(si, ci);
  updateProgress(si, ci);
  updateURL(si, ci);
  lsSet('lastSi', si);
  lsSet('lastCi', ci);
  closeSidebar();
}

// ════════════════════════════════════════════════════════════════
//  SCROLL OBSERVER — detect which chapter is in view
// ════════════════════════════════════════════════════════════════
let observerIO = null;

function setupScrollObserver() {
  if (observerIO) observerIO.disconnect();
  observerIO = new IntersectionObserver(entries => {
    for (const entry of entries) {
      if (!entry.isIntersecting) continue;
      const el = entry.target;
      if (!el.classList.contains('chapter-block')) continue;
      const [si, ci] = el.id.replace('chapter-', '').split('-').map(Number);
      if (si !== currentSi || ci !== currentCi) {
        currentSi = si;
        currentCi = ci;
        updateNavActive(si, ci);
        updateProgress(si, ci);
        updateURL(si, ci);
        lsSet('lastSi', si);
        lsSet('lastCi', ci);
        ensureWindow(si, ci);
      }
    }
  }, { rootMargin: '-30% 0px -50% 0px' });

  document.querySelectorAll('.chapter-block').forEach(el => {
    observerIO.observe(el);
  });
}

// Re-observe after DOM changes
const domMutObserver = new MutationObserver(() => {
  document.querySelectorAll('.chapter-block:not([data-observed])').forEach(el => {
    el.dataset.observed = '1';
    observerIO && observerIO.observe(el);
  });
});
domMutObserver.observe(reader, { childList: true, subtree: false });

// ════════════════════════════════════════════════════════════════
//  PROGRESS
// ════════════════════════════════════════════════════════════════
const allChaptersFlat = [];
BOOK.sections.forEach((sec, si) => {
  sec.chapters.forEach((_, ci) => allChaptersFlat.push({ si, ci }));
});

function updateProgress(si, ci) {
  const idx = allChaptersFlat.findIndex(c => c.si === si && c.ci === ci);
  const pct = allChaptersFlat.length > 1
    ? Math.round((idx / (allChaptersFlat.length - 1)) * 100)
    : 100;
  document.getElementById('progress-bar').style.width = pct + '%';
  document.getElementById('overall-progress-fill').style.width = pct + '%';
  document.getElementById('overall-progress-text').textContent = toPersianNum(pct) + '٪';
}

function toPersianNum(n) {
  return String(n).replace(/\d/g, d => '۰۱۲۳۴۵۶۷۸۹'[d]);
}

// ════════════════════════════════════════════════════════════════
//  URL HASH NAV
// ════════════════════════════════════════════════════════════════
function updateURL(si, ci) {
  const hash = '#s' + si + 'c' + ci;
  history.replaceState(null, '', hash);
}

function parseHashNav() {
  const m = location.hash.match(/^#s(\d+)c(\d+)$/);
  if (m) return { si: +m[1], ci: +m[2] };
  return null;
}

// ════════════════════════════════════════════════════════════════
//  SEARCH ENGINE
// ════════════════════════════════════════════════════════════════
let searchDebounceTimer = null;

document.getElementById('search-input').addEventListener('input', e => {
  clearTimeout(searchDebounceTimer);
  searchDebounceTimer = setTimeout(() => doSearch(e.target.value.trim()), 200);
});

document.getElementById('search-input').addEventListener('keydown', e => {
  if (e.key === 'Escape') {
    closeSearch();
  }
});

document.addEventListener('click', e => {
  if (!e.target.closest('#search-wrap')) closeSearch();
});

function closeSearch() {
  document.getElementById('search-dropdown').classList.remove('visible');
  clearSearchHighlights();
  searchTerm = '';
}

function doSearch(q) {
  if (!q || q.length < 2) { closeSearch(); return; }
  searchTerm = q;
  const norm = normalise(q);
  const results = [];

  for (const item of FLAT) {
    if (item.norm.includes(norm)) {
      // Extract snippet
      const idx = item.plain.indexOf(q.slice(0,4));
      const snipStart = Math.max(0, idx - 20);
      const snip = item.plain.slice(snipStart, snipStart + 100);
      results.push({ ...item, snip });
    }
    if (results.length >= 200) break;
  }

  renderSearchDropdown(results, q);
}

function renderSearchDropdown(results, q) {
  const dropdown = document.getElementById('search-dropdown');
  const MAX = 10;
  dropdown.innerHTML = '';

  if (!results.length) {
    dropdown.innerHTML = '<div class="search-no-results">نتیجه‌ای یافت نشد</div>';
    dropdown.classList.add('visible');
    return;
  }

  const shown = results.slice(0, MAX);
  shown.forEach(r => {
    const item = document.createElement('div');
    item.className = 'search-result-item';
    item.innerHTML = `
      <div class="sri-section">${'$'}{esc(r.secTitle)}</div>
      <div class="sri-chapter">${'$'}{esc(r.chapTitle)}</div>
      <div class="sri-snippet">${'$'}{highlightSnippet(r.snip, q)}</div>
    `;
    item.addEventListener('click', () => {
      jumpTo(r.si, r.ci);
      setTimeout(() => highlightSearchInView(q), 400);
      dropdown.classList.remove('visible');
    });
    dropdown.appendChild(item);
  });

  if (results.length > MAX) {
    const more = document.createElement('div');
    more.className = 'search-view-all';
    more.textContent = `مشاهده همه ${'$'}{toPersianNum(results.length)} نتیجه`;
    more.addEventListener('click', () => showAllResults(results, q));
    dropdown.appendChild(more);
  }

  dropdown.classList.add('visible');
}

function highlightSnippet(text, q) {
  const safe = esc(text);
  const safeQ = esc(q).replace(/[.*+?^${'$'}()|\[\]\\]/g, '\\$&');
  return safe.replace(new RegExp(safeQ, 'gi'), m => `<mark>${'$'}{m}</mark>`);
}

function showAllResults(results, q) {
  document.getElementById('search-dropdown').classList.remove('visible');
  const win = document.createElement('div');
  win.style.cssText = 'position:fixed;inset:0;background:var(--surface);z-index:900;overflow-y:auto;padding:70px 16px 20px;direction:rtl;';
  win.innerHTML = `
    <div style="display:flex;align-items:center;gap:10px;margin-bottom:16px;">
      <button onclick="this.closest('[style]').remove()" style="background:var(--surface2);border:1px solid var(--border);padding:6px 14px;border-radius:8px;cursor:pointer;font-family:'Vazirmatn';color:var(--text);">بازگشت</button>
      <span style="font-size:14px;color:var(--text-muted)">${'$'}{toPersianNum(results.length)} نتیجه برای «${'$'}{esc(q)}»</span>
    </div>
  `;
  // Virtualised list for large results
  const list = document.createElement('div');
  const CHUNK = 50;
  let rendered = 0;
  function renderChunk() {
    const frag = document.createDocumentFragment();
    const end = Math.min(rendered + CHUNK, results.length);
    for (let i = rendered; i < end; i++) {
      const r = results[i];
      const div = document.createElement('div');
      div.className = 'search-result-item';
      div.style.cssText = 'border:1px solid var(--border);border-radius:8px;margin-bottom:8px;';
      div.innerHTML = `
        <div class="sri-section">${'$'}{esc(r.secTitle)}</div>
        <div class="sri-chapter">${'$'}{esc(r.chapTitle)}</div>
        <div class="sri-snippet">${'$'}{highlightSnippet(r.snip, q)}</div>
      `;
      div.addEventListener('click', () => {
        win.remove();
        jumpTo(r.si, r.ci);
        setTimeout(() => highlightSearchInView(q), 400);
      });
      frag.appendChild(div);
    }
    list.appendChild(frag);
    rendered = end;
  }
  renderChunk();
  // Infinite scroll
  const sentinel = document.createElement('div');
  sentinel.style.height = '1px';
  list.appendChild(sentinel);
  const io = new IntersectionObserver(([entry]) => {
    if (entry.isIntersecting && rendered < results.length) renderChunk();
  });
  io.observe(sentinel);
  win.appendChild(list);
  document.body.appendChild(win);
}

function highlightSearchInView(q) {
  if (!q) return;
  clearSearchHighlights();
  const norm = normalise(q);
  document.querySelectorAll('.chapter-content').forEach(el => {
    highlightTextInEl(el, q, norm);
  });
}

function clearSearchHighlights() {
  document.querySelectorAll('.search-mark').forEach(m => {
    m.replaceWith(document.createTextNode(m.textContent));
  });
}

function highlightTextInEl(el, q, normQ) {
  const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
  const nodes = [];
  let n;
  while ((n = walker.nextNode())) nodes.push(n);
  for (const node of nodes) {
    const txt = node.textContent;
    if (!normalise(txt).includes(normQ)) continue;
    // Simple highlight
    const re = new RegExp(q.replace(/[.*+?^${'$'}()|\[\]\\]/g, '\\$&'), 'gi');
    const html = txt.replace(re, m => `<mark class="search-mark">${'$'}{m}</mark>`);
    const wrap = document.createElement('span');
    wrap.innerHTML = html;
    node.replaceWith(wrap);
  }
}

// ════════════════════════════════════════════════════════════════
//  THEME GENERATOR
// ════════════════════════════════════════════════════════════════
function hexToHsl(hex) {
  let r = parseInt(hex.slice(1,3),16)/255;
  let g = parseInt(hex.slice(3,5),16)/255;
  let b = parseInt(hex.slice(5,7),16)/255;
  const max = Math.max(r,g,b), min = Math.min(r,g,b);
  let h,s,l=(max+min)/2;
  if(max===min){ h=s=0; }
  else {
    const d=max-min; s=l>.5?d/(2-max-min):d/(max+min);
    switch(max){ case r:h=((g-b)/d+(g<b?6:0))/6;break; case g:h=((b-r)/d+2)/6;break; default:h=((r-g)/d+4)/6; }
  }
  return [h*360, s*100, l*100];
}

function hslToHex(h,s,l) {
  h/=360; s/=100; l/=100;
  let r,g,b;
  if(s===0){r=g=b=l;}
  else {
    const hue2rgb=(p,q,t)=>{if(t<0)t+=1;if(t>1)t-=1;if(t<1/6)return p+(q-p)*6*t;if(t<1/2)return q;if(t<2/3)return p+(q-p)*(2/3-t)*6;return p;};
    const q=l<.5?l*(1+s):l+s-l*s, p=2*l-q;
    r=hue2rgb(p,q,h+1/3); g=hue2rgb(p,q,h); b=hue2rgb(p,q,h-1/3);
  }
  return '#'+[r,g,b].map(x=>Math.round(x*255).toString(16).padStart(2,'0')).join('');
}

function generateTheme(baseHex, harmony) {
  const [h,s,l] = hexToHsl(baseHex);
  let accent, accent2;

  if (harmony === 'complementary') {
    accent = hslToHex((h+180)%360, s, 45);
    accent2 = hslToHex((h+180)%360, s, 60);
  } else if (harmony === 'triadic') {
    accent = hslToHex((h+120)%360, s, 45);
    accent2 = hslToHex((h+240)%360, s, 55);
  } else if (harmony === 'monochromatic') {
    accent = hslToHex(h, s, 40);
    accent2 = hslToHex(h, s*0.7, 55);
  } else { // analogous
    accent = hslToHex((h+30)%360, s, 45);
    accent2 = hslToHex((h-30+360)%360, s, 55);
  }

  const bg     = hslToHex(h, Math.min(s*0.15, 10), 96);
  const surface= hslToHex(h, Math.min(s*0.1, 8), 99);
  const text   = hslToHex(h, Math.min(s*0.4, 25), 12);
  const navBg  = hslToHex(h, Math.min(s*0.4, 30), 14);

  const id = 'gen_' + Date.now();
  return {
    id, name: harmony + '_' + baseHex.slice(1),
    vars: { '--bg':bg, '--surface':surface, '--surface2':hslToHex(h,10,93),
            '--text':text, '--text-muted':hslToHex(h,20,50),
            '--border':hslToHex(h,10,82), '--accent':accent, '--accent2':accent2,
            '--nav-bg':navBg, '--nav-text':hslToHex(h,10,88), '--nav-active':accent2,
            '--progress':accent, '--scrollbar':hslToHex(h,10,72) }
  };
}

function applyCustomTheme(theme) {
  const root = document.documentElement;
  // Clear generated theme vars first
  ['--bg','--surface','--surface2','--text','--text-muted','--border',
   '--accent','--accent2','--nav-bg','--nav-text','--nav-active','--progress','--scrollbar'
  ].forEach(v => root.style.removeProperty(v));
  if (theme && theme.vars) {
    document.body.dataset.theme = 'custom';
    Object.entries(theme.vars).forEach(([k,v]) => root.style.setProperty(k,v));
  }
}

let savedCustomThemes = lsGet('customThemes', []);

function renderSavedThemes() {
  const container = document.getElementById('saved-themes');
  container.innerHTML = '';
  savedCustomThemes.forEach((t, idx) => {
    const chip = document.createElement('div');
    chip.className = 'saved-theme-chip';
    chip.textContent = t.name.slice(0,16);
    chip.style.background = t.vars['--bg'];
    chip.style.color = t.vars['--text'];
    chip.style.borderColor = t.vars['--accent'];
    chip.addEventListener('click', () => {
      applyCustomTheme(t);
      lsSet('theme', '__custom__' + idx);
    });
    container.appendChild(chip);
  });
}

document.getElementById('theme-gen-btn').addEventListener('click', () => {
  const color   = document.getElementById('theme-color-input').value;
  const harmony = document.getElementById('theme-harmony-select').value;
  const theme   = generateTheme(color, harmony);
  savedCustomThemes.push(theme);
  if (savedCustomThemes.length > 12) savedCustomThemes.shift();
  lsSet('customThemes', savedCustomThemes);
  applyCustomTheme(theme);
  renderSavedThemes();
});

// ════════════════════════════════════════════════════════════════
//  SETTINGS CONTROLS
// ════════════════════════════════════════════════════════════════
document.getElementById('theme-select').value = savedTheme.startsWith('gen') ? 'light' : savedTheme;

document.getElementById('theme-select').addEventListener('change', e => {
  document.body.dataset.theme = e.target.value;
  // Clear any custom CSS vars
  ['--bg','--surface','--surface2','--text','--text-muted','--border',
   '--accent','--accent2','--nav-bg','--nav-text','--nav-active','--progress','--scrollbar'
  ].forEach(v => document.documentElement.style.removeProperty(v));
  lsSet('theme', e.target.value);
});

function rangeSetup(rangeId, valId, cssProp, unit, parseFunc) {
  const range = document.getElementById(rangeId);
  const val   = document.getElementById(valId);
  const saved = lsGet(rangeId.replace('-range','').replace('-','.'), null);
  if (saved !== null) range.value = saved;
  val.textContent = range.value;
  document.documentElement.style.setProperty(cssProp, range.value + unit);
  range.addEventListener('input', () => {
    val.textContent = range.value;
    document.documentElement.style.setProperty(cssProp, range.value + unit);
    lsSet(rangeId.replace('-range','').replace('-','.'), parseFloat(range.value));
  });
}

document.getElementById('font-size-range').value = savedFS;
document.getElementById('font-size-val').textContent = savedFS;
document.getElementById('font-size-range').addEventListener('input', e => {
  document.documentElement.style.setProperty('--font-size', e.target.value + 'px');
  document.getElementById('font-size-val').textContent = e.target.value;
  lsSet('fontSize', +e.target.value);
});

document.getElementById('line-height-range').value = savedLH;
document.getElementById('line-height-val').textContent = savedLH;
document.getElementById('line-height-range').addEventListener('input', e => {
  document.documentElement.style.setProperty('--line-height', e.target.value);
  document.getElementById('line-height-val').textContent = (+e.target.value).toFixed(1);
  lsSet('lineHeight', +e.target.value);
});

document.getElementById('content-width-range').value = savedCW;
document.getElementById('content-width-val').textContent = savedCW;
document.getElementById('content-width-range').addEventListener('input', e => {
  document.documentElement.style.setProperty('--content-width', e.target.value + 'px');
  document.getElementById('content-width-val').textContent = e.target.value;
  lsSet('contentWidth', +e.target.value);
});

// ════════════════════════════════════════════════════════════════
//  UI CONTROLS
// ════════════════════════════════════════════════════════════════
function openSidebar() {
  document.getElementById('sidebar').classList.add('open');
  document.getElementById('overlay').classList.add('active');
}
function closeSidebar() {
  document.getElementById('sidebar').classList.remove('open');
  document.getElementById('overlay').classList.remove('active');
}

function openSettings() {
  document.getElementById('settings-panel').classList.add('visible');
  document.getElementById('settings-overlay').classList.add('active');
}
function closeSettings() {
  document.getElementById('settings-panel').classList.remove('visible');
  document.getElementById('settings-overlay').classList.remove('active');
}

document.getElementById('menu-btn').addEventListener('click', openSidebar);
document.getElementById('menu-btn-bottom').addEventListener('click', openSidebar);
document.getElementById('sidebar-close').addEventListener('click', closeSidebar);
document.getElementById('overlay').addEventListener('click', () => { closeSidebar(); });

document.getElementById('settings-btn').addEventListener('click', () => {
  const isOpen = document.getElementById('settings-panel').classList.contains('visible');
  if (isOpen) closeSettings(); else openSettings();
});
document.getElementById('settings-close-btn').addEventListener('click', closeSettings);
document.getElementById('settings-overlay').addEventListener('click', closeSettings);

document.getElementById('focus-btn').addEventListener('click', () => {
  document.body.classList.toggle('focus-mode');
});

// Keyboard shortcuts
document.addEventListener('keydown', e => {
  if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;
  if (e.key === 'f' || e.key === 'F') document.getElementById('focus-btn').click();
  if (e.key === '/') { e.preventDefault(); document.getElementById('search-input').focus(); }
  if (e.key === 'Escape') { closeSidebar(); closeSettings(); closeSearch(); }
});

// ════════════════════════════════════════════════════════════════
//  SCROLL POSITION SAVE
// ════════════════════════════════════════════════════════════════
window.addEventListener('scroll', () => {
  lsSet('scrollY', window.scrollY);
}, { passive: true });

// ════════════════════════════════════════════════════════════════
//  UTILITY
// ════════════════════════════════════════════════════════════════
// Escaping HTML characters safely:
function esc(str) {
  return String(str)
    .replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')
    .replace(/"/g,'&quot;').replace(/'/g,'&#39;');
}

// ════════════════════════════════════════════════════════════════
//  INIT
// ════════════════════════════════════════════════════════════════
(function init() {
  // Build nav
  buildNavTree();
  renderSavedThemes();

  // Restore custom theme if any
  const savedThemeKey = lsGet('theme', 'light');
  if (savedThemeKey && savedThemeKey.startsWith('__custom__')) {
    const idx = parseInt(savedThemeKey.replace('__custom__',''));
    if (savedCustomThemes[idx]) applyCustomTheme(savedCustomThemes[idx]);
  }

  // Create all placeholders in book order (foundation of virtualised layout)
  initPlaceholders();

  // Determine starting position
  let startSi = 0, startCi = 0;
  const hashNav = parseHashNav();
  if (hashNav) {
    startSi = hashNav.si;
    startCi = hashNav.ci;
  } else {
    startSi = lsGet('lastSi', 0);
    startCi = lsGet('lastCi', 0);
  }

  // Clamp to valid range
  startSi = Math.max(0, Math.min(startSi, BOOK.sections.length - 1));
  startCi = Math.max(0, Math.min(startCi, (BOOK.sections[startSi]?.chapters.length || 1) - 1));

  currentSi = startSi;
  currentCi = startCi;

  ensureWindow(startSi, startCi);
  setupScrollObserver();

  // Scroll to position after layout settles
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      const el = document.getElementById('chapter-' + chKey(startSi, startCi));
      const savedScrollY = lsGet('scrollY', 0);
      if (savedScrollY > 0) {
        window.scrollTo(0, savedScrollY);
      } else if (el) {
        const top = el.getBoundingClientRect().top + window.scrollY - 64;
        window.scrollTo(0, Math.max(0, top));
      }
      updateNavActive(startSi, startCi);
      updateProgress(startSi, startCi);
    });
  });

  // Deep link support
  window.addEventListener('hashchange', () => {
    const nav = parseHashNav();
    if (nav) jumpTo(nav.si, nav.ci);
  });
})();
</script>
</body>
</html>"""

        html = html.replace("__BOOK_JSON__", bookJson)
        html = html.replace("__LS_PREFIX__", lsPrefix)
        html = html.replace("__FILE_NAME__", escapeHtml(fileName))

        return html
    }
}
