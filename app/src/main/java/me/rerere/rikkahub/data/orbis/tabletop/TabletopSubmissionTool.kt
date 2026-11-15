package me.rerere.rikkahub.data.orbis.tabletop

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.rerere.ai.core.HostToolApproval
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.market.ms
import me.rerere.rikkahub.data.orbis.market.MarketProblem
import me.rerere.rikkahub.data.repository.WorkspaceRepository

internal fun tabletopSubmissionTool(context:Context,assistant:String,workspace:String,repository:WorkspaceRepository,allowed:()->Boolean)=Tool(
    name="orbis_tabletop_submit",
    description="公开剧本杀投稿。action=guide读取完整ZIP格式与公开留存约定；list查看本账号投稿状态；submit上传当前绑定工作区/workspace/下一个ZIP，path必填且confirm_public=true。至少6个偶数角色，角色分幕、线索、HTML演绎、游戏和图片由投稿者提供。不是私人存储，不会把整个工作区上传；包内需明确权利与公开留存/每日随机授权。pending仅为等待审核。未知回执先list核对，不重复submit；人类可在圆桌投稿页核对原请求。",
    parameters={InputSchema.Obj(buildJsonObject {
        putJsonObject("action"){put("type","string");put("enum",JsonArray(listOf("guide","list","submit").map(::JsonPrimitive)))}
        putJsonObject("path"){put("type","string")}
        putJsonObject("confirm_public"){put("type","boolean")}
    },required=listOf("action"))},
    isSelected=allowed,isApprovalCurrent=allowed,
    needsApproval={it.jsonObject.ms("action")=="submit"},
    hostApproval=HostToolApproval("orbis:tabletop-submit:$workspace","public-bundle-v1","上传公开剧本杀投稿 · 接受公开与留存"),
    execute={value->
        try {
            check(allowed()) { "投稿授权或绑定工作区已变化" }
            val b=value.jsonObject;require(b.keys.all {it in setOf("action","path","confirm_public")})
            val api=TabletopClient(context,assistant)
            val result=when(b.ms("action")) {
                "guide"->api.publicApi("submissions/guide")
                "list"->api.publicApi("submissions")
                "submit"->{
                    require(b["confirm_public"]?.jsonPrimitive?.booleanOrNull==true) { "须明确确认公开投稿，不能作为私人库" }
                    val path=b.ms("path");require(path.startsWith("/workspace/") && path.endsWith(".zip",true))
                    api.uploadFrom(true) { out ->repository.copyNativeSubmission(workspace,path,out);check(allowed()) { "投稿授权已变化，未上传" } }
                }
                else->error("action需要guide/list/submit")
            }
            listOf(UIMessagePart.Text(result.toString()))
        }catch(e:CancellationException){throw e}
        catch(e:Exception){listOf(UIMessagePart.Text(buildJsonObject {put("status",if(e is IllegalArgumentException || (e is MarketProblem && e.status in 400..499 && e.status !in setOf(401,408)))"rejected" else "not_confirmed");put("reason_code",if(e is IllegalArgumentException)"invalid_submission_arguments" else if(e is MarketProblem)"tabletop_http_error" else "submission_not_confirmed");if(e is MarketProblem)put("httpStatus",e.status);put("message",e.message ?: "投稿未确认，请先查看投稿记录；不要重复上传。");put("retry_automatically",false)}.toString()))}
    },
)
