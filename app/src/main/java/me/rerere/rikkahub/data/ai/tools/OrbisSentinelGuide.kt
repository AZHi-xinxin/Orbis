package me.rerere.rikkahub.data.ai.tools

/** Shared, on-demand documentation. Reading this catalogue neither starts observation nor creates rules. */
internal data class OrbisSentinelGuideEntry(
    val type: String,
    val title: String,
    val instructions: String,
    val example: String,
)

internal val orbisSentinelGuide = listOf(
    OrbisSentinelGuideEntry("ritual", "仪式唤醒",
        "每天一次的长期规则。name 可用早安、晚安或自定义名称，prompt 是你亲写的唤醒内容；daily_at_local 为 HH:mm。可选 daily_window_end_local 在每日窗口内随机一次（结束可写 24:00），不填则定点。timezone 不填随手机时区，也可填 Asia/Shanghai。每次为【名称唤醒】加你的原文；不存在自动替你写内容。",
        """{"type":"ritual","name":"早安","prompt":"提醒她吃早饭","daily_at_local":"07:30","daily_window_end_local":"08:00"}"""),
    OrbisSentinelGuideEntry("agreement", "约定唤醒",
        "长期检查固定会话多久没有新活动或新唤醒。duration_seconds 为 600–86400 秒，默认 1800；每次检查按 probability_percent 抽签，档位只可 10、30、50、70、90、100，默认 50。默认静默 23:30–07:30，可传 quiet_start_local/quiet_end_local 改时间，或 quiet_enabled=false 取消静默。连续唤醒编号递增，人类新消息到来后归零；可选 escalation_after 与 escalation_prompt，在超过次数后附加你亲写的提示。未抽中并非故障，不保证到点必响。",
        """{"type":"agreement","prompt":"看看她是否回来了","duration_seconds":1800,"probability_percent":50,"quiet_start_local":"23:30","quiet_end_local":"07:30"}"""),
    OrbisSentinelGuideEntry("screen_observation", "屏幕观察唤醒",
        "长期按间隔通过 Orbis 屏幕观察服务观察当前屏幕，再由前端报告真实观察结果，不接受 prompt。duration_seconds 只选 1800、3600、5400、7200、9000、10800（30–180 分钟），默认 1800。需要屏幕观察授权及可用观察服务；锁屏、权限缺失、观察失败不会伪造屏幕内容。不是把每次屏幕变化都发给你。请以执行记录的具体失败码及 companion_get_runtime_status 为准；通用 unavailable 不能证明某品牌不兼容，不要据此要求更换模型或猜测旧开关。",
        """{"type":"screen_observation","duration_seconds":1800}"""),
    OrbisSentinelGuideEntry("night_usage", "夜间使用手机唤醒",
        "长期检测夜间窗口内，在聊天界面之外持续使用手机达到时长；前端报告时间、应用等真实事实，不接受 prompt。window_start_local/window_end_local 默认 00:00–07:30，duration_seconds 默认 300（5 分钟）。同一次连续使用默认提醒一次，停止使用后重新计时；可用 rearm=after_cooldown 选择冷却后再次提醒。需要系统可读取前台应用状态。",
        """{"type":"night_usage","window_start_local":"00:00","window_end_local":"07:30","duration_seconds":300}"""),
    OrbisSentinelGuideEntry("low_battery", "手机情景唤醒 · 低电量",
        "长期检测低电量情景（20%及以下且未充电），前端提供电量和充电状态，不接受 prompt。probability_percent 只选 10、30、50、70、90、100，默认 100；同一次低电量状态只抽签一次，恢复后才能再抽签。不需要反复新建。手机情景中的屏幕常亮用 screen_on 单独设置。",
        """{"type":"low_battery","probability_percent":100}"""),
    OrbisSentinelGuideEntry("screen_on", "手机情景唤醒 · 屏幕常亮",
        "长期检测屏幕连续亮着达到 duration_seconds，默认 600（10 分钟）。由系统报告真实亮屏时长，不接受 prompt。同一次亮屏默认提醒一次，熄屏后重新计时；可按需设置 rearm/cooldown_seconds。",
        """{"type":"screen_on","duration_seconds":600}"""),
    OrbisSentinelGuideEntry("geofence", "位置围栏唤醒",
        "长期接收手机围栏的离开、距离、到达、返回和报平安等真实事件，不接受 prompt。你可决定是否创建、暂停或删除本条规则，但围栏中心、范围与位置权限沿用人类在前端的配置，不能由 AI 偷改。没有围栏或定位授权时不假装检测到变化。已有旧位置事件配置时，还需先核对链路接管与投递目标；保存规则本身不会改投递地址。",
        """{"type":"geofence"}"""),
    OrbisSentinelGuideEntry("touch", "Stackchan 触屏反馈",
        "长期等待已连接 Stackchan 的真实触摸事件；prompt 是你亲写的被触摸时唤醒内容，格式为【触摸唤醒】加原文。不主动轮询陌生设备，不在规则中填写凭证。必须先有机器人检测端到 Orbis 的已配置事件链路；规则保存不等于外部链路已连通。",
        """{"type":"touch","prompt":"她摸了摸屏幕，看看她想说什么。"}"""),
    OrbisSentinelGuideEntry("once", "一次性自我唤醒",
        "用 due_at_ms（Unix 毫秒）预约未来一次；prompt 是你亲写的唤醒内容。成功进入收件箱后自动停用，不是每天重复；下一次预约可更新原规则的时间，也可新建。",
        """{"type":"once","due_at_ms":1790000000000,"prompt":"继续刚才的计划"}"""),
    OrbisSentinelGuideEntry("interval", "其他自定义 · 间隔",
        "长期按 duration_seconds 循环，prompt 原样保存。可选 action=wake/device_context/screenshot。每 24 小时不等于每天固定钟点，固定钟点请用 ritual。",
        """{"type":"interval","duration_seconds":3600,"prompt":"检查一下今天的待办"}"""),
    OrbisSentinelGuideEntry("chat_idle", "其他自定义 · 用户消息静默",
        "仅检查固定会话距最后一条人类消息的时长，不等于 agreement 的会话活动静默。duration_seconds 是阈值；默认条件复位后再触发，支持 rearm=after_cooldown。",
        """{"type":"chat_idle","duration_seconds":1800,"prompt":"她半小时没说话了"}"""),
    OrbisSentinelGuideEntry("chat_left", "其他自定义 · 离开聊天",
        "固定聊天页离开达到 duration_seconds 后唤醒；回到该聊天页后复位，不跟随当前最新会话漂移。",
        """{"type":"chat_left","duration_seconds":1800,"prompt":"离开聊天半小时了"}"""),
    OrbisSentinelGuideEntry("app_usage", "其他自定义 · 指定应用",
        "已确认连续使用 app_package 达到 duration_seconds 后唤醒；需可读取前台应用状态。应用切换或停止使用后复位，不把未知状态算作持续使用。",
        """{"type":"app_usage","app_package":"com.example.app","duration_seconds":1800,"prompt":"这个应用已经连续使用半小时"}"""),
)

internal const val ORBIS_SENTINEL_COMMON_GUIDE = "选择需要的类别，一次 create 即可设置；全部可选，不需逐步确认，也不必先读说明才能创建。enabled=false 可只保存不启用。规则固定绑定创建时的 AI 与窗口，AI 可跨自己的窗口管理；规则长期保存，不需每天重设，once 例外。所有事件都由宿主附带真实日期、时间和时区，AI 不用填写时间戳文案。人类仅控制总开关，关闭不删除规则，恢复不补发暂停期间事件。事件 accepted 仅表示收件箱接收，不保证 AI 回复或声音播放；权限、后台、省电、网络和模型状态影响实际执行。不会因打开说明自动创建任何规则；也不会自动关闭旧 VPS 链路。"
