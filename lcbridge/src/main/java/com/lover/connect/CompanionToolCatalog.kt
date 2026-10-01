package com.lover.connect

import org.json.JSONArray
import org.json.JSONObject

/** Single unchanged tool schema source for the legacy MCP and native host. No runtime access. */
object CompanionToolCatalog {
    fun json(): JSONArray = JSONArray().apply {
        put(JSONObject().apply {
            put("name", "get_battery")
            put("description", "获取电池状态")
            put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
        })
        put(JSONObject().apply {
            put("name", "get_anniversary")
            put("description", "获取纪念日信息")
            put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
        })
        put(JSONObject().apply {
            put("name", "get_weather")
            put("description", "获取天气信息")
            put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
        })
        put(JSONObject().apply {
            put("name", "get_steps")
            put("description", "获取今日步数")
            put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
        })
        put(JSONObject().apply {
            put("name", "send_notification")
            put("description", "推送通知")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("message", JSONObject().apply { put("type", "string"); put("description", "消息内容") })
                })
                put("required", JSONArray().apply { put("message") })
            })
        })
        put(JSONObject().apply {
            put("name", "save_memory")
            put("description", "保存一条记忆到本地记忆库")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("key", JSONObject().apply { put("type", "string"); put("description", "记忆的键名") })
                    put("value", JSONObject().apply { put("type", "string"); put("description", "记忆的内容") })
                })
                put("required", JSONArray().apply { put("key"); put("value") })
            })
        })
        put(JSONObject().apply {
            put("name", "read_memory")
            put("description", "读取本地记忆库，不传key返回全部")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("key", JSONObject().apply { put("type", "string"); put("description", "要查询的键名，不传则返回全部") })
                })
            })
        })
        put(JSONObject().apply {
            put("name", "set_alarm")
            put("description", "设置本应用的一次性闹钟；当天该时间已过则设到次日，不是每天重复。同一 HH:mm 再次设置会替换原闹钟。查看返回的完整日期和调度回执；系统接受调度不保证一定响铃或人已听到，核验请用 get_alarms。")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("hour", JSONObject().apply { put("type", "integer"); put("description", "小时（0-23）") })
                    put("minute", JSONObject().apply { put("type", "integer"); put("description", "分钟（0-59）") })
                    put("message", JSONObject().apply { put("type", "string"); put("maxLength", 4096); put("description", "闹钟备注（可选，最多 4096 字符）") })
                })
                put("required", JSONArray().apply { put("hour"); put("minute") })
            })
        })
        put(JSONObject().apply {
            put("name", "cancel_alarm")
            put("description", "按 HH:mm 取消本应用对应的一次性闹钟。读取返回的完整日期和取消回执；旧记录缺失时不能补造日期或响铃历史。只影响本应用闹钟，不操作系统时钟 App 的其它闹钟。")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("hour", JSONObject().apply { put("type", "integer"); put("description", "小时（0-23）") })
                    put("minute", JSONObject().apply { put("type", "integer"); put("description", "分钟（0-59）") })
                })
                put("required", JSONArray().apply { put("hour"); put("minute") })
            })
        })
        put(JSONObject().apply {
            put("name", "get_alarms")
            put("description", "只读查询本应用闹钟台账及最近触发、停止回执，不是系统时钟 App 的全部闹钟。旧版记录可能不齐，未查到不能证明从未设置；系统接受调度不等于一定响铃或人已听到。服务关闭也可查询，不设置、取消、补设闹钟，不触发真实铃声。")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("include_history", JSONObject().apply {
                        put("type", "boolean"); put("default", true)
                        put("description", "是否包含最近触发、停止、取消等历史回执，默认 true")
                    })
                    put("limit", JSONObject().apply {
                        put("type", "integer"); put("minimum", 1); put("maximum", 100); put("default", 30)
                        put("description", "返回条数上限，默认 30，最多 100")
                    })
                    put("offset", JSONObject().apply {
                        put("type", "integer"); put("minimum", 0); put("maximum", 10000); put("default", 0)
                        put("description", "分页起点，默认 0；用返回的 next_offset 继续读取，null 表示末页")
                    })
                })
            })
        })
        put(JSONObject().apply {
            put("name", "lock_screen")
            put("description", "强制锁屏")
            put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
        })
        put(JSONObject().apply {
            put("name", "play_music")
            put("description", "播放音乐（通过QQ音乐或网易云）")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply { put("type", "string"); put("description", "歌曲名或歌手名") })
                    put("platform", JSONObject().apply { put("type", "string"); put("description", "平台：qq/netease/auto（默认auto）") })
                })
                put("required", JSONArray().apply { put("query") })
            })
        })
        put(JSONObject().apply {
            put("name", "get_now_playing")
            put("description", "获取当前正在播放的音乐")
            put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
        })

        put(JSONObject().apply {
            put("name", "take_screenshot")
            put("description", "立刻截屏并分析当前屏幕内容")
            put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
        })
        put(JSONObject().apply {
            put("name", "lock_app")
            put("description", "Lock an entertainment app on supported Android devices, including OPPO. Vivo stays passive and writes nothing. Configure this tool to require manual approval in Orbis.")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("package_name", JSONObject().apply { put("type", "string") })
                    put("duration_minutes", JSONObject().apply { put("type", "integer"); put("minimum", 0); put("maximum", 10080) })
                    put("lock_message", JSONObject().apply { put("type", "string"); put("maxLength", 80) })
                    put("show_overlay", JSONObject().apply { put("type", "boolean"); put("default", true) })
                })
                put("required", JSONArray().apply { put("package_name") })
            })
        })
        put(JSONObject().apply {
            put("name", "unlock_app")
            put("description", "Unlock one app by package name. On runtime-confirmed Vivo devices this only clears legacy locked-list entries because passive compatibility mode performs no interception.")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply { put("package_name", JSONObject().apply { put("type", "string") }) })
                put("required", JSONArray().apply { put("package_name") })
            })
        })
        put(JSONObject().apply {
            put("name", "focus_rikka")
            put("description", "Redirect only explicitly listed entertainment apps to the current Orbis host. Legacy tool name retained for compatibility. Vivo reports unsupported and writes nothing. Requires manual approval.")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("enabled", JSONObject().apply { put("type", "boolean") })
                    put("package_names", JSONObject().apply { put("type", "array"); put("items", JSONObject().apply { put("type", "string") }) })
                })
                put("required", JSONArray().apply { put("enabled"); put("package_names") })
            })
        })
        put(JSONObject().apply {
            put("name", "redirect_to_rikka")
            put("description", "Redirect selected safe apps to the current Orbis host during an HH:mm-HH:mm window. Legacy tool name retained. Vivo reports unsupported and writes nothing. Empty list disables it. Requires manual approval.")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("package_names", JSONObject().apply { put("type", "array"); put("items", JSONObject().apply { put("type", "string") }) })
                    put("time_window", JSONObject().apply { put("type", "string") })
                })
                put("required", JSONArray().apply { put("package_names"); put("time_window") })
            })
        })
        put(JSONObject().apply {
            put("name", "list_locked_apps")
            put("description", "List apps currently locked by Orbis companion features. On runtime-confirmed Vivo devices entries are legacy configuration only because passive compatibility mode performs no interception.")
            put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
        })
        put(JSONObject().apply {
            put("name", "read_eyes_log")
            put("description", "读取 Orbis 本机观察日记")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("lines", JSONObject().apply { put("type", "integer"); put("description", "读取行数，默认20") })
                })
            })
        })
        put(JSONObject().apply {
            put("name", "get_l_service_status")
            put("description", "Read Little L and accessibility lifecycle diagnostics without exposing secrets")
            put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
        })
        put(JSONObject().apply {
            put("name", "configure_sentinel")
            put("description", "Configure the private Orbis companion sentinel endpoint. Token is stored locally and never returned.")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("url", JSONObject().apply { put("type", "string") })
                    put("token", JSONObject().apply { put("type", "string") })
                    put("enabled", JSONObject().apply { put("type", "boolean") })
                })
                put("required", JSONArray().apply { put("url"); put("token"); put("enabled") })
            })
        })
        put(JSONObject().apply {
            put("name", "test_sentinel")
            put("description", "Send one manual test event through the configured Orbis companion sentinel.")
            put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
        })
        put(JSONObject().apply {
            put("name", "get_location_safety_status")
            put("description", "Read coarse Orbis companion safety status. Never returns coordinates or secrets.")
            put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
        })
        put(JSONObject().apply {
            put("name", "get_device_context")
            put("description", "Read a structured, short-lived device-context snapshot. Device facts and uncertain inferences are separated; human posture, sleep, identity, and raw coordinates are never inferred or returned.")
            put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
        })
        put(JSONObject().apply {
            put("name", "get_recent_context_events")
            put("description", "Read recent local device-context transitions (maximum 50, retained up to 24 hours).")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("limit", JSONObject().apply {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", 50)
                        put("default", 20)
                    })
                })
            })
        })
        put(JSONObject().apply {
            put("name", "get_context_capabilities")
            put("description", "Read device-context sensors, privacy toggles, retention, and delivery-channel capabilities.")
            put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
        })
    }
}
