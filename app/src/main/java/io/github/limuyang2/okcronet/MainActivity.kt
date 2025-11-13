package io.github.limuyang2.okcronet

import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import io.github.limuyang2.okcronet.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import okcronet.CronetClient
import okcronet.http.Request
import org.chromium.net.CronetEngine
import org.chromium.net.RequestFinishedInfo
import org.chromium.net.impl.NativeCronetEngineBuilderImpl
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var viewBinding: ActivityMainBinding

    private val cronetEngine: CronetEngine by lazy(LazyThreadSafetyMode.NONE) {
        val httpCacheDir =
            File(this.applicationContext.externalCacheDir ?: this.applicationContext.cacheDir, "http")

        if (!httpCacheDir.exists()) {
            httpCacheDir.mkdir()
        }

        CronetEngine.Builder(
            NativeCronetEngineBuilderImpl(this.applicationContext)
        )
            .setStoragePath(httpCacheDir.absolutePath)
            .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISABLED, 1048576)
            .enableHttp2(true)
            .enableQuic(true)
            .enableBrotli(true)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        viewBinding.btnTest.setOnClickListener {
            request()
        }


    }

    private val cacheExecutor = Executors.newCachedThreadPool()

    private fun request() {
        lifecycleScope.launch {
            // 创建 CronetClient
            val cronetClient = CronetClient.Builder(cronetEngine)
                .setCallTimeoutMillis(5_000)
                .addInterceptor(LogInterceptor()) // 添加日志拦截器
                // （可选）请求完毕的回调，包含请求阶段各个时间的数据。一般用作性能监控使用
                .setRequestFinishedInfoListener(object : RequestFinishedInfo.Listener(cacheExecutor) {
                    override fun onRequestFinished(requestInfo: RequestFinishedInfo?) {
                        if (requestInfo == null) return

                        val metrics = requestInfo.metrics
                        val requestEnd = metrics.requestEnd ?: return
                        val requestStart = metrics.requestStart ?: return

                        val urlResponseInfo = requestInfo.responseInfo ?: return
                        urlResponseInfo.negotiatedProtocol

                        val duration = requestEnd.time - requestStart.time

                        val s = "RequestFinishedInfo : \n" +
                                "url: ${requestInfo.url} \n" +
                                "negotiatedProtocol: ${requestInfo.responseInfo?.negotiatedProtocol} \n" +
                                "ttfb: ${metrics.ttfbMs} \n" +
                                "duration: $duration  \n" +
                                "httpStatusCode: ${urlResponseInfo.httpStatusCode} \n" +
                                "proxyServer: ${urlResponseInfo.proxyServer}"

                        Log.d("log", s)

                        runOnUiThread {
                            viewBinding.tvInfo.text = s
                        }
                    }
                })
                .build()

            // 构建 Request
            val request = Request.Builder()
                .url("https://www.github.com")
                .get()
                .build()

            // 发起网络请求
            cronetClient.newCall(request).execute()
        }

    }
}