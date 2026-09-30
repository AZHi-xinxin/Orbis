package me.rerere.rikkahub.data.orbis.soup

/** Public examples, verbatim content; adapted only from JSON into native data. No private bank. */
internal object SoupCatalogue {
    const val SOURCE = "https://github.com/AZHi-xinxin/haiguitang-coop"
    const val CONTENT_LICENSE = "https://creativecommons.org/licenses/by/4.0/"
    const val ATTRIBUTION = "示例题：AZHi-xinxin / Haiguitang contributors · CC BY 4.0。原题文字保留，仅转换为本机数据格式。规则实现改编自原项目 MIT 代码。"
    val MIT_NOTICE = """
        MIT License

        Copyright (c) 2026 Haiguitang contributors

        Permission is hereby granted, free of charge, to any person obtaining a copy
        of this software and associated documentation files (the "Software"), to deal
        in the Software without restriction, including without limitation the rights
        to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
        copies of the Software, and to permit persons to whom the Software is
        furnished to do so, subject to the following conditions:

        The above copyright notice and this permission notice shall be included in all
        copies or substantial portions of the Software.

        THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
        IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
        FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
        AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
        LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
        OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
        SOFTWARE.
    """.trimIndent()
    val puzzles: List<SoupPuzzle> = listOf(
        SoupPuzzle("soup_sample_001", "闹钟", "简单",
            "我定了早上7点的闹钟。早上它响了，我按掉，继续睡。10点我自然醒过来，一看手机，闹钟设置明明还是7点，可它再也没有响过第二次。",
            "我设的是'单次闹钟'，响过这一次就结束了。我以为闹钟每天早上都会响，按掉之后安心继续睡，结果再也不会响第二遍——迟到是我自己的认知问题。",
            listOf("我设的是单次闹钟，不是重复闹钟", "按掉之后就彻底结束了", "我以为闹钟会天天响", "迟到是因为我自己的错误认知"),
            listOf("注意闹钟的种类：单次和重复有什么区别？", "按掉之后，这个闹钟还会存在吗？", "问题出在闹钟身上还是我身上？")),
        SoupPuzzle("soup_sample_002", "鱼缸", "简单",
            "我养了一缸热带鱼。昨天我给鱼缸换了水，用的是刚买的矿泉水。今天早上，鱼全死了。",
            "我把鱼缸里的水一次性全换成了矿泉水。新水没有困过、没有调温，水温跟鱼缸差很多，水质也突变，鱼承受不住直接应激死了。问题不在矿泉水干不干净，在于换水方式太粗暴。",
            listOf("一次性全换水而不是换一部分", "矿泉水没困水、没调温", "水温水质骤变", "鱼死于应激"),
            listOf("换水的方式有问题吗？全换和换一部分有什么区别？", "新水直接倒进去之前，一般要做什么？", "鱼是突然死的，最可能是什么原因？")),
        SoupPuzzle("soup_sample_003", "备用钥匙", "简单",
            "我把钥匙忘在家里，想起家门口的脚垫下有一把备用钥匙，于是我用它开了门。第二天我发现备用钥匙也不见了，但我还是进了门。",
            "其实我出门时门根本没锁——只是随手带上了，我推门就进去了。备用钥匙我压根没碰过。第二天它不见了是因为被我记错了位置，或者被家里人收走了，但门依旧没锁，所以我照样进得去。",
            listOf("出门时门没锁，只是带上了", "我根本没用到备用钥匙", "备用钥匙不见跟进门无关", "我是推门进去的"),
            listOf("我真的是用备用钥匙开的门吗？", "有没有可能门根本就没锁？", "备用钥匙的消失和进门这件事有关吗？")),
        SoupPuzzle("soup_sample_004", "雨伞", "简单",
            "下雨天，我撑着一把伞出门。回来的时候伞不见了，可我却浑身干爽。",
            "我出门时没下雨，没带伞。半路下雨了，我躲进便利店买了一把新伞。回来时我把伞落在了便利店。而我浑身干爽，是因为我回来的路上雨已经停了。",
            listOf("出门时没下雨，没带伞", "伞是半路在便利店买的", "伞落在便利店没带回来", "回来时雨已经停了"),
            listOf("我是从家里带伞出门的吗？", "伞是哪里来的？", "我回来时还下着雨吗？")),
        SoupPuzzle("soup_sample_005", "送信", "中等",
            "邮递员以前每天都会往我家门缝里塞一封信。有一天起，他不再塞信了，我反而特别高兴。",
            "那些信是银行寄来的催款单。我欠的钱终于还清了，催款单自然就停了。所以邮递员不再塞信，对我而言是债务清空的好消息。",
            listOf("信是催款单", "我欠了钱", "不再塞信是因为我还清了", "高兴是因为债务清空"),
            listOf("每天寄来的信是什么信？", "为什么会有信，后来又为什么停了？", "信停了为什么反而高兴？")),
    )
    fun get(id: String): SoupPuzzle = puzzles.singleOrNull { it.id == id } ?: error("soup_unknown_puzzle")
    fun note(id: String): String? = if (id in setOf("soup_sample_003", "soup_sample_004"))
        "原作汤面与汤底存在叙述歧义，已保留原文；可优先选其他题。" else null
}
