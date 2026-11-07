package com.example.receiverflasher

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File

/** تنفيذ أوامر shell عبر Shizuku (صلاحية ADB shell). */
object Shell {
    // newProcess صار private في Shizuku 13+، فنستدعيه بالـ reflection
    private val newProcess by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java, Array<String>::class.java, String::class.java
        ).also { it.isAccessible = true }
    }

    fun alive() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    fun granted() = alive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    fun run(cmd: String): String {
        val p = newProcess.invoke(null, arrayOf("sh", "-c", "($cmd) 2>&1"), null, null) as Process
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out.trim()
    }

    private val LUN_PATHS = listOf(
        "/sys/class/android_usb/android0/f_mass_storage/lun/file",
        "/sys/class/android_usb/android0/f_mass_storage/lun0/file",
        "/sys/devices/virtual/android_usb/android0/f_mass_storage/lun/file",
        "/config/usb_gadget/g1/functions/mass_storage.0/lun.0/file"
    )

    /** يبحث عن مسار LUN يستطيع shell الكتابة فيه. null = الجهاز غير مدعوم. */
    fun findLun(): String? {
        val direct = run(LUN_PATHS.joinToString("; ") { "[ -w $it ] && echo $it" })
            .lines().firstOrNull { it.startsWith("/") && !it.contains(' ') }
        if (direct != null) return direct
        // بحث ديناميكي (بعض أجهزة MediaTek تستخدم مسارات مختلفة)
        return run(
            "for f in \$(find /sys/class/android_usb /sys/devices/virtual/android_usb " +
                    "/config/usb_gadget -path '*mass_storage*' -name file 2>/dev/null | head -20); " +
                    "do [ -w \$f ] && echo \$f; done"
        ).lines().firstOrNull { it.startsWith("/") && !it.contains(' ') }
    }

    /** تقرير تشخيصي لمعرفة ما يعرضه الهاتف (مفيد لأوبو رينو 3 وغيره). */
    fun report(): String = run(
        """
        echo "== الجهاز =="
        echo model: ${'$'}(getprop ro.product.model)
        echo android: ${'$'}(getprop ro.build.version.release)
        echo platform: ${'$'}(getprop ro.board.platform)
        echo build: ${'$'}(getprop ro.build.display.id)
        echo selinux: ${'$'}(getenforce)
        echo "== USB =="
        echo sys.usb.config: ${'$'}(getprop sys.usb.config)
        echo sys.usb.state: ${'$'}(getprop sys.usb.state)
        echo "== configfs =="
        ls /config/usb_gadget/g1/functions
        ls -l /config/usb_gadget/g1/configs/b.1
        echo "== android_usb =="
        ls /sys/class/android_usb/android0
        echo "== mass_storage =="
        find /sys/class/android_usb /sys/devices/virtual/android_usb /config/usb_gadget -path '*mass_storage*' 2>/dev/null | head -20
        echo "== svc usb =="
        svc usb | head -12
        """.trimIndent()
    )

    private const val DEST = "/data/local/tmp/flash.img"

    /** نقل الصورة إلى /data/local/tmp عبر stdin (بديل إذا فشل cp بسبب قيود التخزين). */
    private fun pushImage(img: File): String {
        val p = newProcess.invoke(
            null,
            arrayOf("sh", "-c", "cat > $DEST && chmod 666 $DEST && echo PUSH_OK 2>&1"),
            null, null
        ) as Process
        p.outputStream.use { o -> img.inputStream().use { it.copyTo(o, 256 * 1024) } }
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out.trim()
    }

    fun enable(lun: String, img: File): String {
        val log = StringBuilder()
        run("cp '${img.absolutePath}' $DEST; chmod 666 $DEST")
        val size = run("stat -c %s $DEST").toLongOrNull() ?: 0L
        if (size != img.length()) {
            log.appendLine("النسخ المباشر لم يكتمل، أنقل الصورة عبر Shizuku (قد يستغرق دقيقة)...")
            log.appendLine(pushImage(img))
        }
        log.appendLine(
            run(
                """
                svc usb setFunctions mass_storage || setprop sys.usb.config mass_storage,adb
                sleep 1
                echo '' > $lun
                echo $DEST > $lun && echo LUN_OK
                cat $lun
                """.trimIndent()
            )
        )
        return log.toString()
    }

    fun disable(lun: String?): String = run(
        """
        ${if (lun != null) "echo '' > $lun" else ""}
        svc usb setFunctions mtp || setprop sys.usb.config mtp,adb
        rm -f $DEST
        echo DONE
        """.trimIndent()
    )
}

class ShizukuFlasherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { FlasherScreen2() } }
    }
}

private fun queryName(ctx: Context, uri: Uri): String =
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (c.moveToFirst() && i >= 0) c.getString(i) else null
    } ?: "update.bin"

@Composable
fun FlasherScreen2() {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val dir = remember { File(ctx.filesDir, "software").apply { mkdirs() } }
    val img = remember { File(ctx.getExternalFilesDir(null), "flash.img") }

    var files by remember { mutableStateOf(dir.listFiles()?.toList() ?: emptyList()) }
    var sizeMb by remember { mutableStateOf(512) }
    var lun by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var log by remember { mutableStateOf("1) شغّل Shizuku  2) اضغط فحص الجهاز") }
    var busy by remember { mutableStateOf(false) }

    fun task(block: () -> String) = scope.launch {
        busy = true
        log = try { withContext(Dispatchers.IO) { block() } }
        catch (e: Throwable) { "خطأ: ${e.message}" }
        busy = false
    }

    val addFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) task {
            val name = queryName(ctx, uri)
            ctx.contentResolver.openInputStream(uri)!!.use { i ->
                File(dir, name).outputStream().use { o -> i.copyTo(o) }
            }
            files = dir.listFiles()?.toList() ?: emptyList()
            "تمت إضافة $name"
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("فلاشة افتراضية (Shizuku)", style = MaterialTheme.typography.headlineSmall)

        Button(enabled = !busy, onClick = {
            task {
                when {
                    !Shell.alive() -> "Shizuku غير شغّال. افتح تطبيق Shizuku وشغّله أولاً"
                    !Shell.granted() -> { Shizuku.requestPermission(100); "امنح الإذن ثم أعد الفحص" }
                    else -> {
                        lun = Shell.findLun()
                        if (lun == null)
                            "❌ جهازك لا يدعم هذه الطريقة (لا يوجد مسار LUN قابل للكتابة).\n" +
                                    "اضغط «تقرير تشخيصي» وأرسله لي، أو استخدم فلاشة OTG."
                        else "✔ الجهاز مدعوم\n$lun"
                    }
                }
            }
        }) { Text("فحص الجهاز") }

        OutlinedButton(enabled = !busy, onClick = {
            task { if (Shell.granted()) Shell.report() else "شغّل Shizuku وامنح الإذن أولاً" }
        }) { Text("تقرير تشخيصي (أرسله لي)") }

        Text("الملفات داخل الفلاشة:", style = MaterialTheme.typography.titleMedium)
        files.forEach { f ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${f.name} (${f.length() / 1024 / 1024}MB)", Modifier.weight(1f))
                TextButton(enabled = !busy, onClick = {
                    f.delete(); files = dir.listFiles()?.toList() ?: emptyList()
                }) { Text("حذف") }
            }
        }
        OutlinedButton(enabled = !busy, onClick = { addFile.launch(arrayOf("*/*")) }) {
            Text("إضافة ملف سوفت")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(512, 1024, 2048).forEach { s ->
                FilterChip(selected = sizeMb == s, onClick = { sizeMb = s },
                    label = { Text(if (s >= 1024) "${s / 1024}GB" else "${s}MB") })
            }
        }

        Button(enabled = !busy && files.isNotEmpty(), onClick = {
            task {
                progress = 0f
                Fat32Image.build(img, sizeMb, files.map { SoftFile(it.name, it) }) { progress = it }
                "تم بناء صورة FAT32 (${sizeMb}MB) بـ ${files.size} ملف"
            }
        }) { Text("بناء الفلاشة (FAT32)") }

        Button(enabled = !busy && lun != null && img.exists(), onClick = {
            task {
                val r = Shell.enable(lun!!, img)
                if (r.contains("LUN_OK")) "✔ الهاتف الآن يعمل كفلاشة. وصّله بالرسيفر.\n$r"
                else "فشل التفعيل:\n$r"
            }
        }) { Text("تشغيل وضع الفلاشة") }

        OutlinedButton(enabled = !busy && Shell.granted(), onClick = {
            task { Shell.disable(lun) }
        }) { Text("إيقاف والرجوع للوضع العادي") }

        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        SelectionContainer { Text(log) }
        TextButton(onClick = { clipboard.setText(AnnotatedString(log)) }) { Text("نسخ النتيجة") }
    }
}
