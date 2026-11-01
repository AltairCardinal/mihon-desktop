package tachiyomi.domain.feedback

enum class FeedbackLanguage { ENGLISH, CHINESE }

enum class FeedbackCategory(val id: String, val chinese: String, val english: String) {
    STARTUP_CRASH("startup_crash", "崩溃或无法启动", "Crash or unable to start"),
    PERFORMANCE("performance", "卡顿、无响应或资源占用过高", "Slow, unresponsive or excessive resource use"),
    SOURCE_NETWORK("source_network", "漫画源、扩展或网络连接", "Sources, extensions or network"),
    BROWSE_DETAILS("browse_details", "搜索、浏览或漫画详情", "Search, browse or manga details"),
    READER("reader", "阅读器、图片或翻页", "Reader, images or page turns"),
    LIBRARY_PROGRESS("library_progress", "书架、分类或阅读进度", "Library, categories or reading progress"),
    DOWNLOAD_LOCAL("download_local", "下载、离线阅读或本地文件", "Downloads, offline reading or local files"),
    BACKUP_SYNC("backup_sync", "备份、恢复或多设备同步", "Backup, restore or multi-device sync"),
    UPDATE_INSTALL("update_install", "应用更新或安装", "App updates or installation"),
    INTERFACE("interface", "界面显示或操作异常", "Appearance or interaction"),
    FEATURE_REQUEST("feature_request", "功能建议", "Feature request"),
    OTHER("other", "其他／不确定", "Other / not sure"),
    ;

    val publicTitle: String get() = "【问题反馈】$chinese"
    fun label(language: FeedbackLanguage): String = if (language == FeedbackLanguage.CHINESE) chinese else english
    fun template(language: FeedbackLanguage): String {
        val chinese = language == FeedbackLanguage.CHINESE
        if (this == FEATURE_REQUEST) return if (chinese) FEATURE_ZH else FEATURE_EN
        val common = if (chinese) COMMON_ZH else COMMON_EN
        if (this != READER) return common
        val before = if (chinese) "【如何再次遇到】" else "[How to reproduce]"
        return common.replace(before, (if (chinese) READER_ZH else READER_EN) + "\n\n" + before)
    }

    companion object {
        fun hasActualDescription(value: String): Boolean {
            val prompts = entries.flatMap { category ->
                FeedbackLanguage.entries.flatMap { category.template(it).lines() }
            }.map { it.trim() }.toSet()
            return value.lines().any { line ->
                val text = line.trim()
                text !in prompts && !text.matches(Regex("[【\\[].*[】\\]]")) &&
                    text.count { it.isLetter() } >= 4
            }
        }
    }
}

private val COMMON_ZH = """
【遇到了什么问题】
请把这句话改成你的情况：我在______页面，进行______操作时，出现了______。

【如何再次遇到】
1. 打开……
2. 点击／操作……
3. 出现……

【期望的结果】
我原本希望……

【实际的结果】
实际发生了……
如果有错误提示，请填写原文或附上截屏

【出现频率与影响范围】
出现频率：每次都会／偶尔出现／只出现过一次／不清楚
影响范围：所有内容／部分内容／不清楚
相关漫画、章节或漫画源（如适用）：

【补充信息（可选）】
大约从什么时候开始？之前是否正常？
已经尝试过哪些解决方法？结果如何？
""".trimIndent()

private val READER_ZH = """
【问题发生位置】
漫画源：
作品名称：
章节名称或编号：
页码：第______页／不清楚
如果是长条阅读，请描述大致位置：
""".trimIndent()

private val FEATURE_ZH = """
【希望解决的困难】
我在______场景下，需要______，但目前只能______。

【希望增加或改进什么】
我希望能够……

【使用示例】
例如……

【目前的替代方法（可选）】
我现在通过______完成，但不方便的地方是______。
""".trimIndent()

private val COMMON_EN = """
[What went wrong]
Replace this sentence: On the ______ page, I performed ______ and ______ happened.

[How to reproduce]
1. Open …
2. Tap / do …
3. Observe …

[Expected result]
I expected …

[Actual result]
What actually happened …
If there is an error, copy the exact message or attach a screenshot.

[Frequency and scope]
Frequency: Every time / Sometimes / Once / Not sure
Scope: All content / Some content / Not sure
Related manga, chapter or source (if applicable):

[Additional information (optional)]
When did this start? Did it work before?
What have you tried, and what happened?
""".trimIndent()

private val READER_EN = """
[Where it happened]
Source:
Manga title:
Chapter name or number:
Page: ______ / Not sure
For continuous scrolling, describe the approximate position:
""".trimIndent()

private val FEATURE_EN = """
[Difficulty to solve]
In the ______ situation I need ______, but currently I can only ______.

[Suggested change]
I would like …

[Example]
For example …

[Current workaround (optional)]
I currently use ______, which is inconvenient because ______.
""".trimIndent()
