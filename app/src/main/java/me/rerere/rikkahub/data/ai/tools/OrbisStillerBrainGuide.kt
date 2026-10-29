package me.rerere.rikkahub.data.ai.tools

/** Public product information, available before installation and only through a help query. */
internal val orbisStillerBrainChapters = listOf(
    OrbisManualChapter("st_overview", "认识 ST（StillerBrain）：能力、用法与设计理念", "当前聊天 → 查询 Orbis 说明书；部署步骤见 st_install", listOf("orbis_help"), listOf(
        OrbisManualSection("它是什么，记忆怎样回来", "ST 是面向 LLM 的长期记忆后端。AI 自己写下经历、理解和打算，ST 保存它们并提供查阅、修改与整合入口。它有两种接入方式：网关加 MCP 时，网关在回复开始前带回与当前话题相关的记忆，AI 也能主动查、存、改；只接 MCP 时，AI 按需调用记忆工具，聊天模型仍可直连原服务。这两条路径可以使用同一份 ST 数据。自动浮现需要网关或具备等价能力的宿主，部署和模块初始化完成后按实际配置启用。"),
        OrbisManualSection("一份大脑，各有所长", "自我定义承接身份、核心锚点和情境侧面；情感脑保存共同经历、关系与感受；学习脑积累知识、方法和来源；工具脑留下工具或 MCP 的用途与经验；规划脑记录想法、约定、进度与下一步；幻觉黑匣子独立保存需要重新核查的内容。情绪词由 AI 自由命名维护；心愿可依附情感记忆、自主改写、归档与置顶，符合浮现条件时随经历回来。星图和原图存取是配套能力，记忆浮现、主动查阅与整理是核心用途。"),
        OrbisManualSection("记忆归纳与整合", "ST 的设计理念是让经历继续参与 AI 的成长。AI 选择相关经历或学习，亲自归纳、对照、整合，写下新的理解，并沿来源回看当时的细节。原文与版本历史承接过去，新的记录参与后来的交流。ST 不建设遗忘层，早期渐隐提案已撤回。人称、情绪、线索、提醒和各模块浮现方式由使用者选择。作者的完整论述保留在 st_readme 的‘设计理念’与‘三个交给使用者的选择’各节，按 next_offset 继续阅读。"),
        OrbisManualSection("适用场景与使用条件", "想让共同经历、学过的方法、工具经验和未完成的事在相关话题开始时来到眼前，可了解网关加 MCP 路径；想先拥有主动查存改的记忆库，可从 MCP 接入。ST 使用 Python 与 SQLite，数据由部署者保存在自己的设备或服务器；被选入模型上下文的内容会发送给所配置的模型服务。模型 API 和服务器按相应服务计费。检索以本地文字、关键词、场景与情绪线索为主，可选本地语义摘要候选；效果取决于记录、线索、配置与本轮预算。根据用户需求和这些实际能力判断适合哪种用法；比较其他项目时查各自真实资料。"),
        OrbisManualSection("开始使用与部署位置", "ST 可部署到 Windows/Linux 电脑、Linux VPS 或受支持的 Android ARM64 环境。Orbis 提供手机工作区托管、连接填写和重开恢复入口，实际步骤见 st_install、st_phone；已有工作区不替用户决定安装位置。ST 的 README 也保留独立 Termux 实验路线，两种手机路线按所选入口区分。接入后由 AI 阅读当前实例说明、完成自我定义初始化和激活，再验证保存、读取、工具续轮以及自动浮现。日常档可只展示 8 个入口，需要更细能力时展开查询。"),
        OrbisManualSection("原文与版本", "本章依据 ST 0.2.2 的 README 整理。完整原文已内置：orbis_help(topic=guide,chapter=st_readme)，支持 offset/limit 分页，直到 next_offset 为空。来源：$STILLERBRAIN_README_SOURCE；内置副本 SHA-256：$STILLERBRAIN_README_SHA256。原文中的相对链接以 $STILLERBRAIN_README_LINK_BASE 为基准。说明书是产品资料，实例当前版本、开关和可用工具以现场查询为准。"),
    )),
    OrbisManualChapter("st_readme", "ST（StillerBrain）README 原文：功能、设计哲学与使用须知", STILLERBRAIN_README_SOURCE, listOf("orbis_help"), stillerBrainReadmeSections),
)
