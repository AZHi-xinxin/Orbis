package com.lover.connect

/** Separate destinations, not anchors into one long screen. */
enum class BridgeSection(val title: String, val description: String) {
    CONNECTION("连接与权限", "本机服务 · 运行状态 · 系统授权"),
    VISION("屏幕观察", "视觉模型 · 定时截屏 · 观察人格"),
    REST("休息提醒", "连续使用门槛 · 聊天豁免 · 冷却"),
    LOCATION("位置围栏", "多个终点 · 独立半径 · 报备与追踪"),
    CONTEXT("通知与情境", "设备事实 · 通知摘要 · 内容隐私"),
    CONTROLS("设备控制", "应用限制 · 专注回聊 · 一键解除"),
    SENTINEL("哨兵与自我唤醒", "事件连接 · 外部服务 · 验证边界"),
    LOCAL("本地资料", "天气城市 · 纪念日 · 本地记忆"),
}
