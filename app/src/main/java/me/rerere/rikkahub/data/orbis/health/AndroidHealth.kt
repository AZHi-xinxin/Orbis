package me.rerere.rikkahub.data.orbis.health

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.data.orbis.activities.bounded
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal val healthChanges = kotlinx.coroutines.flow.MutableStateFlow(0L)

/** noBackup is also excluded from Orbis' explicit ordinary/cloud/emergency file allowlists. */
internal class AndroidHealthStorage(context:Context):HealthStorage {
    private val target=File(context.noBackupFilesDir.canonicalFile,"orbis-health/records-v1.json")
    private val file:AtomicFile
    private var loaded=false
    private var expected:String?=null
    init {check(target.canonicalFile==target.absoluteFile);file=AtomicFile(target)}
    private fun committed():String? = if (!target.exists()&&!File(target.path+".bak").exists()) null
        else file.openRead().use{it.bounded(8*1024*1024).toString(Charsets.UTF_8)}
    private fun digest(text:String?)=text?.let{MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString(""){b->"%02x".format(b)}}
    override fun read():String?=synchronized(lock){committed().also{expected=digest(it);loaded=true}}
    override fun write(value:String):Unit=synchronized(lock){
        check(loaded&&digest(committed())==expected){"health_storage_changed_reload"}
        val bytes=value.toByteArray();require(bytes.size<=8*1024*1024){"health_capacity"}
        val out=file.startWrite()
        try{out.write(bytes);out.fd.sync();file.finishWrite(out)}catch(e:Exception){file.failWrite(out);throw e}
        check(committed()==value){"health_write_unverified"};expected=digest(value);healthChanges.value++
    }
    companion object{private val lock=Any()}
}
internal fun openHealth(context:Context)=HealthRepository(AndroidHealthStorage(context.applicationContext))

/** Explicit picker input, bounded decode, bundled on-device model. No model-provider/API call. */
internal suspend fun recognizeHealthScreenshot(context:Context,uri:Uri):String=withContext(Dispatchers.IO){
    val bytes=context.contentResolver.openInputStream(uri)?.use{it.bounded(12*1024*1024)}?:error("health_image_unreadable")
    val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
    require(bounds.outWidth in 1..16000 && bounds.outHeight in 1..16000 && bounds.outWidth.toLong()*bounds.outHeight<=64_000_000){"health_image_invalid"}
    var sample=1
    while(maxOf(bounds.outWidth,bounds.outHeight)/sample>2400)sample*=2
    val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample})?:error("health_image_invalid")
    val recognizer=TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    withTimeout(20_000) { suspendCancellableCoroutine { continuation ->
        try {
            recognizer.process(InputImage.fromBitmap(bitmap,0)).addOnCompleteListener { task ->
                try {
                    if(continuation.isActive) {
                        if(task.isSuccessful) continuation.resume(task.result.text.take(100_000))
                        else continuation.resumeWithException(IllegalStateException("health_ocr_failed"))
                    }
                } finally {recognizer.close();bitmap.recycle()}
            }
        } catch(e:Exception){recognizer.close();bitmap.recycle();if(continuation.isActive)continuation.resumeWithException(e)}
    } }
}
