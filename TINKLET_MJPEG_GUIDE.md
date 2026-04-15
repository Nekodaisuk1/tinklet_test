# TINKLET 側: HTTP MJPEG 配信アプリ実装ガイド

このガイドは、TINKLET上で HTTP MJPEG形式でカメラ映像を配信するAndroidアプリの最小実装を示します。

## 前提条件

- TINKLET App SDK がセットアップ済み  
  https://fairydevicesrd.github.io/thinklet.app.developer/
- Android Studio 導入済み
- Kotlin/Gradle の基本知識

## 実装方針

- CameraX を使用してフレーム取得
- nanohttpd ライブラリでHTTPサーバーを実装
- MJPEG形式（multipart/x-mixed-replace）でストリーム配信

## ステップ1: 依存関係の追加

`build.gradle` (Module: app) に以下を追加：

```gradle
dependencies {
    // CameraX (TINKLET フォーク推奨)
    implementation "androidx.camera:camera-core:1.1.0"
    implementation "androidx.camera:camera-camera2:1.1.0"
    implementation "androidx.camera:camera-lifecycle:1.1.0"

    // nanohttpd
    implementation "org.nanohttpd:nanohttpd:2.3.1"

    // 他の依存関係...
}
```

## ステップ2: AndroidManifest.xml 権限設定

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## ステップ3: MJPEG配信ロジック実装例（Kotlin）

## TINKLET 実機で最初にやること

実機で詰まりやすいので、まずは次の順番で進めるのが安全です。

1. Android Studio で新規プロジェクトを作る
2. TINKLET 用の SDK / 端末設定が有効か確認する
3. CameraX でカメラプレビューが出ることを先に確認する
4. MJPEG サーバーを追加して `/stream` が返ることを確認する
5. そのあとで PC 側の `app.py --source "http://<TINKLET_IP>:8080/stream"` をつなぐ

ポイント:

- いきなり HTTP 配信から入るより、まずカメラ表示だけ通すほうが切り分けしやすい
- TINKLET の IP は端末側の Wi-Fi 設定か `adb shell ip addr` / `adb shell ifconfig` で確認する
- まずは 640x480 前後、JPEG 60〜70% 程度で十分

### MJPEGServer.kt

```kotlin
import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Status
import java.io.ByteArrayOutputStream
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

class MJPEGServer(port: Int = 8080) : NanoHTTPD(port) {
    private val frameQueue: BlockingQueue<ByteArray> = LinkedBlockingQueue(2)

    override fun serve(session: IHTTPSession?): Response {
        if (session?.uri == "/stream") {
            return serveMJPEGStream()
        }
        return Response(Status.NOT_FOUND, "text/plain", "Not found")
    }

    private fun serveMJPEGStream(): Response {
        val protocol = arrayOf(
            "HTTP/1.1 200 OK\r\n",
            "Content-Type: multipart/x-mixed-replace; boundary=--frame\r\n",
            "Connection: close\r\n",
            "\r\n"
        ).joinToString("")

        return object : Response(Status.OK, "multipart/x-mixed-replace") {
            override fun closeConnection() {}

            override fun toString(): String {
                return protocol
            }
        }.apply {
            setGzipEncoding(false)
            addHeader("Content-Type", "multipart/x-mixed-replace; boundary=--frame")
            mimeType = "multipart/x-mixed-replace"
            try {
                while (true) {
                    val frameData = frameQueue.take()
                    getBody().write("--frame\r\n".toByteArray())
                    getBody().write("Content-Type: image/jpeg\r\n".toByteArray())
                    getBody().write("Content-Length: ${frameData.size}\r\n\r\n".toByteArray())
                    getBody().write(frameData)
                    getBody().write("\r\n".toByteArray())
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun pushFrame(jpegData: ByteArray) {
        frameQueue.poll()  // 古いフレームを削除
        frameQueue.offer(jpegData)
    }
}
```

### CameraActivity.kt（メイン）

```kotlin
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class CameraActivity : AppCompatActivity() {
    private lateinit var mjpegServer: MJPEGServer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // サーバー起動
        mjpegServer = MJPEGServer(8080)
        mjpegServer.start()
        
        // ロギング（adb logcat で確認可能）
        android.util.Log.i("MJPEG", "Server started on http://<TINKLET_IP>:8080/stream")
        
        requestPermissionsIfNeeded()
        setupCamera()
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = arrayOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.INTERNET
        )
        permissions.forEach { permission ->
            if (ContextCompat.checkSelfPermission(this, permission)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(permissions, 1001)
            }
        }
    }

    private fun setupCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                        processImage(imageProxy)
                        imageProxy.close()
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    imageAnalysis
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImage(imageProxy: ImageProxy) {
        val bitmap = imageProxy.image?.let { image ->
            // YUV → JPEG への変換ロジック（簡略版）
            // 実装は複雑なため、CameraX-Vision ライブラリ使用を推奨
            convertYUVtoJPEG(image)
        } ?: return

        val jpegData = bitmapToJPEG(bitmap)
        mjpegServer.pushFrame(jpegData)
    }

    private fun convertYUVtoJPEG(image: android.media.Image): Bitmap {
        // YUV → Bitmap 変換（詳細実装は省略）
        return Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
    }

    private fun bitmapToJPEG(bitmap: Bitmap): ByteArray {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos)
        return baos.toByteArray()
    }

    override fun onDestroy() {
        mjpegServer.stop()
        super.onDestroy()
    }
}
```

## ステップ4: ビルド・インストール

```bash
# TINKLET App SDK をセットアップした上で

# ビルド
./gradlew assembleDebug

# インストール
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 実行（デバイスのボタンで起動、または adb shell）
adb shell am start -n com.example.mjpeg/.CameraActivity
```

## ステップ5: PC側で接続

```bash
python app.py --source "http://<TINKLET_IP>:8080/stream"
```

`<TINKLET_IP>` は TINKLET のWi-Fi IP アドレスに置き換えてください。

adb コマンドで確認：
```bash
adb shell ifconfig | grep inet
```

## トラブルシューティング

### "Permission denied" エラー
→ `adb install -g` で権限をグラント、または AndroidManifest.xml を再確認

### カメラが起動しない
→ CameraX との互換性を確認。TINKLET フォーク版を使用推奨

### フレームレートが低い
→ ImageAnalysis の `setBackpressureStrategy` を STRATEGY_KEEP_ONLY_LATEST に変更

### HTTP接続失敗
→ ファイアウォール設定、Wi-Fi接続状況を確認。`adb logcat` で詳細ログ確認
