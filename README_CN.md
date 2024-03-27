# okcronet
类似 okhttp 的网络请求库，使用 Cronet 实现

# Why
Google 提供了一个`okhttp`到`Cronet`的桥接实现[cronet-transport-for-okhttp](https://github.com/google/cronet-transport-for-okhttp/)，但是如果你阅读过源码，并尝试完善其功能时，会发现诸多的问题无法解决，正如 Google 在`README`
中所提到的`Incompatibilities`(不兼容性)内容所描述的。

关于 okhhtp 在对 HTTP3/QUIC 的支持性方面的解答，可以查看此[issues](https://github.com/square/okhttp/issues/907)。总结来说就是，okhttp不会再去实现 HTTP3/QUIC 的内容，应该使用拦截器的方式交由 Cronet 实现。可是正如前面提到的，拦截器方式有诸多不兼容问题存在。

## 引用


# 如何使用
整体使用方式与 okhttp 使用方式保持一致。只是多了一个 `CronetEngine` 的创建工作。

## 构建唯一的 CronetEngine
具体的 api 说明请参考 [Cronet](https://developer.android.com/develop/connectivity/cronet)
```kotlin
    // cronetEngine需要要保持全局唯一性，不可重复创建
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
            .setThreadPriority(-19)
            .enableBrotli(true)
            .build()
```

## GET
```kotlin
    // 创建 CronetClient
    val cronetClient = CronetClient.Builder(cronetEngine).build()

    // 构建 Request
    val request = Request.Builder()
        .url("https://www.fastly.com/quic-http-3")
        .get()
        .build()

    // 同步方式发起网络请求
    cronetClient.newCall(request).execute()

```

## POST
```kotlin
    // 创建 CronetClient
    val cronetClient = CronetClient.Builder(cronetEngine).build()

    // 构建 RequestBody
    val body: RequestBody = "jsonString".toRequestBody()

    // 构建 Request
    val request = Request.Builder()
        .url("https://www.fastly.com/quic-http-3")
        .post(body)
        .build()

    // 同步方式发起网络请求
    cronetClient.newCall(request).execute()
```

# Thanks
[okhttp](https://github.com/square/okhttp)
[cronet-transport-for-okhttp](https://github.com/google/cronet-transport-for-okhttp)
