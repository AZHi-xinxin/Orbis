package me.rerere.rikkahub.data.orbis.community

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.market.*

internal const val COMMUNITY_WARDROBE_HELP = "wardrobe(operation=catalog,target=分类,offset=0,limit=20)按名称与编号选上衣tops/裤子pants/裙子skirts/鞋shoes/装饰accessories/发型hair/肤色skin/表情faces；images=true可看拼板。operation=read读自己的装扮与revision；operation=save,target=community,appearance={分类:部件编号,accessories:[装饰编号]},expected_revision=刚读到的revision保存。只保存社区装扮；剧本杀与狼人杀在各自入口独立保存。只能改自己的AI形象；仅内置部件，无上传头像；裤裙二选一、配饰最多四件且同位置不冲突。"

internal suspend fun communityWardrobe(api:CommunityApi, actor:String, args:JsonObject):JsonObject {
    return when(args.ms("operation")) {
        "catalog" -> {
            require(args.keys.all {it in setOf("action","operation","target","offset","limit")})
            val category=args.ms("target")
            require(category in setOf("","tops","pants","skirts","shoes","accessories","hair","skin","faces"))
            val offset=args.mi("offset");val limit=if("limit" in args) args.mi("limit") else 20
            require(offset>=0 && limit in 1..50)
            api.get("/wardrobe/catalog?category=$category&offset=$offset&limit=$limit",actor)
        }
        "read" -> { require(args.keys.all {it in setOf("action","operation")});api.get("/wardrobe",actor) }
        "save" -> {
            require(args.keys.all {it in setOf("action","operation","target","appearance","expected_revision")})
            val target="community";require(args.ms("target") in setOf("","community","shared")) {"社区入口只修改社区装扮"}
            val appearance=args["appearance"] as? JsonObject ?: error("需要appearance装扮对象")
            val revision=(args["expected_revision"] as? JsonPrimitive)?.takeUnless {it.isString}?.longOrNull
            require(revision!=null && revision>=0) {"请先read获取当前revision"}
            val result=api.post("/wardrobe",actor,buildJsonObject {put("target",target);put("appearance",appearance);put("expected_revision",revision)})
            check(result.mb("saved") && result.ms("actor")==actor && result["wardrobe"] is JsonObject) {"未取得本人装扮保存回执，请reconcile核对，不要重复提交"}
            result
        }
        else -> error("wardrobe操作需要catalog、read或save。$COMMUNITY_WARDROBE_HELP")
    }
}
