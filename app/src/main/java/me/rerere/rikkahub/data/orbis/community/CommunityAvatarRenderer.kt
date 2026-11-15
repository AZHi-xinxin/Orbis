package me.rerere.rikkahub.data.orbis.community

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.market.*

/** Only signed, bundled wardrobe assets are rendered. No caller-supplied image path. */
internal object CommunityAvatarRenderer {
    private var data:JsonObject?=null
    private val images=object:LruCache<String,Bitmap>(24*1024*1024) {override fun sizeOf(key:String,value:Bitmap)=value.byteCount}
    private val renders=object:LruCache<String,Bitmap>(12*1024*1024) {override fun sizeOf(key:String,value:Bitmap)=value.byteCount}
    @Synchronized fun catalog(context:Context):JsonObject = data ?: context.assets.open("orbis-avatar/wardrobe.json").bufferedReader().use {Json.parseToJsonElement(it.readText()).jsonObject}.also {data=it}
    private fun image(context:Context,name:String):Bitmap {
        require(name in catalog(context)["images"]!!.jsonObject && name.matches(Regex("[a-z0-9-]+\\.png")))
        return images.get(name) ?: context.assets.open("orbis-avatar/$name").use {BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply {inSampleSize=2}) ?: error("头像素材无法读取")}.also {images.put(name,it)}
    }
    private fun layers(context:Context,id:String):List<JsonObject> = (catalog(context)["draw"]!!.jsonObject[id] as? JsonArray).orEmpty().map {it.jsonObject}
    private fun coords(row:JsonObject,key:String)=row[key]!!.jsonArray.map {it.jsonPrimitive.float}.toFloatArray()
    private fun blit(context:Context,canvas:Canvas,row:JsonObject) {
        val name=row.ms("file");val bitmap=image(context,name)
        val original=catalog(context)["images"]!!.jsonObject[name]!!.jsonObject["size"]!!.jsonArray
        val sx=bitmap.width/original[0].jsonPrimitive.float;val sy=bitmap.height/original[1].jsonPrimitive.float
        val b=coords(row,"source");val t=coords(row,"target")
        canvas.drawBitmap(bitmap,Rect((b[0]*sx).toInt(),(b[1]*sy).toInt(),(b[2]*sx).toInt(),(b[3]*sy).toInt()),RectF(t[0],t[1],t[0]+t[2],t[1]+t[3]),Paint().apply {isFilterBitmap=false})
    }
    @Synchronized fun render(context:Context,outfit:JsonObject,portrait:Boolean=false):Bitmap {
        val key="$portrait:$outfit";renders.get(key)?.let {return it}
        val defaults=catalog(context)["defaults"]!!.jsonObject
        val config=(defaults+outfit).toMutableMap();if("skirts" in outfit)config.remove("pants")
        val ids=listOf("skin","pants","skirts","shoes","tops","faces","hair").mapNotNull {(config[it] as? JsonPrimitive)?.contentOrNull}+((config["accessories"] as? JsonArray).orEmpty().take(4).mapNotNull {(it as? JsonPrimitive)?.contentOrNull})
        val bitmap=Bitmap.createBitmap(if(portrait)256 else 384,if(portrait)256 else 512,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap)
        if(portrait){canvas.scale(256f/68,256f/68);canvas.translate(-14f,0f)}else canvas.scale(4f,4f)
        // Stable depth order lets rear hair/collars pass behind the body and
        // shoes under trouser/skirt hems, regardless of accessory selection order.
        val masks=catalog(context)["hair_masks"] as? JsonObject
        for(row in ids.flatMap {layers(context,it)}.sortedBy {it["z"]?.jsonPrimitive?.intOrNull ?: 0}) {
            canvas.save()
            if(row.ms("file").startsWith("hair-"))for(id in ids) {
                val mask=(masks?.get(id) as? JsonArray)?.map {it.jsonPrimitive.float} ?: continue
                canvas.clipOutRect(mask[0],mask[1],mask[0]+mask[2],mask[1]+mask[3])
            }
            blit(context,canvas,row);canvas.restore()
        }
        renders.put(key,bitmap);return bitmap
    }
    @Synchronized fun thumbnail(context:Context,id:String):Bitmap {
        val key="item:$id";renders.get(key)?.let {return it}
        val layers=layers(context,id).sortedBy {it["z"]?.jsonPrimitive?.intOrNull ?: 0};require(layers.isNotEmpty())
        val boxes=layers.map {coords(it,"target")};val left=boxes.minOf {it[0]};val top=boxes.minOf {it[1]}
        val width=boxes.maxOf {it[0]+it[2]}-left;val height=boxes.maxOf {it[1]+it[3]}-top
        val bitmap=Bitmap.createBitmap(160,160,Bitmap.Config.ARGB_8888);val c=Canvas(bitmap);val scale=138f/maxOf(width,height)
        c.translate((160-width*scale)/2,(160-height*scale)/2);c.scale(scale,scale);c.translate(-left,-top)
        for(row in layers)blit(context,c,row)
        renders.put(key,bitmap);return bitmap
    }
    suspend fun sheet(context:Context,items:List<JsonObject>):File=withContext(Dispatchers.Default) {
        require(items.size in 1..50)
        val bitmap=Bitmap.createBitmap(1024,((items.size+3)/4)*220,Bitmap.Config.ARGB_8888)
        val c=Canvas(bitmap);c.drawColor(0xfffaf4e7.toInt());val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=0xff35483b.toInt();textSize=17f;textAlign=Paint.Align.CENTER}
        items.forEachIndexed {i,item->val x=(i%4)*256f;val y=(i/4)*220f;c.drawBitmap(thumbnail(context,item.ms("id")),x+48,y+4,null);c.drawText(item.ms("id"),x+128,y+184,paint);c.drawText(item.ms("name"),x+128,y+207,paint)}
        save(context,bitmap).also {bitmap.recycle()}
    }
    suspend fun preview(context:Context,outfit:JsonObject):File=withContext(Dispatchers.Default) {save(context,render(context,outfit))}
    private fun save(context:Context,bitmap:Bitmap):File {
        val dir=File(context.filesDir,"orbis-avatar-previews").apply {mkdirs()}
        return File(dir,UUID.randomUUID().toString()+".png").also {f->f.outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}
    }
}
