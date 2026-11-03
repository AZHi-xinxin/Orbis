# 天气查询与本机工具排查

天气入口是 **北斗导航 → 手机与陪伴 → 本地资料 → 天气城市**。填写后点保存。该城市是人类选择的固定查询地点，不会自动替换为手机 GPS 或 VPN 出口城市。

- 可以填写城市名，重名时加省份或国家，例如 `Beijing, Beijing Municipality`。也可以填写 `39.9075,116.39723`（纬度在前、经度在后）。
- 首先请求 wttr.in。未取得有效数据时，最多尝试一次 Open-Meteo 备用流程（城市解析和天气查询）。找到多个同名地点时不会任意取第一项；先按指定地区和完整城市名称筛选，仍无法确定时提示修改城市。
- 返回中包含查询地点、匹配地点、来源及数据时间。备用来源的当前天气标明为模型估算。天气不是从聊天模型记忆中编造的。
- 仅发送已配置的地点；不会为天气读取围栏、聊天、密钥或后台定位。两个公开天气源的可用性独立于模型接口。

## 失败提示

| 错误码 | 含义与处理 |
| --- | --- |
| `weather_city_missing` | 未保存城市，先到上述入口填写并保存。 |
| `weather_city_invalid` | 城市为空白控制字符、过长或经纬度越界等，修正输入。 |
| `weather_city_not_found` | 备用来源没有匹配到地点，核对名称或改用经纬度。 |
| `weather_city_ambiguous` | 同名地点仍有多个，补充省份或经纬度。 |
| `weather_sources_unavailable` | 主备来源都失败；回执另列两边的封闭原因码。 |
| `weather_network_failed` / `weather_timeout` | 该天气源连接失败或超时。开着 VPN 不保证该站点能访问。 |
| `weather_rate_limited` | 天气服务限流，稍后再查。 |
| `weather_upstream_failed` / `weather_response_invalid` | 返回异常 HTTP 状态、错误页面或缺失必要数据，未当作成功天气。 |

自检中的天气配置项只报告有没有保存城市，不公开城市名称，也不发网络请求；“已配置”不等于“天气站点已验证可用”。新失败摘要可以读取嵌套的 `error.code`，不保存原始异常、聊天正文或密钥。旧版 `operation_unavailable` / `unclassified_failure` 记录无法事后补出丢失的原因。

纪念日数据解析失败现在明确报告 `anniversary_data_invalid`，不再当作成功内容；没有纪念日仍是正常的空列表状态，不会自动删除或重写原记录。

## ST 工具续轮

`tool_continuation_catalog_mismatch` 与天气不可用是不同故障。局部重生成曾少带三项上下文整理工具，审批恢复后又补入，从而改变目录。现在这两条路径保持同样的三项声明；局部重生成仍没有整段上下文整理的执行控制器，不能因此改写历史。

这一修复不取消 ST 的目录、来源或执行回执校验，不重跑旧工具。原来已经失败的旧链应从新消息继续；其他 MCP 开关、远端目录变化或进程恢复引起的目录变化仍需按现场证据核查。

天气接口说明：[wttr.in](https://github.com/chubin/wttr.in)、[Open-Meteo 天气 API](https://open-meteo.com/en/docs)、[地点搜索 API](https://open-meteo.com/en/docs/geocoding-api)。
