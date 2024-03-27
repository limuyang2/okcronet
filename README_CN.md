# okcronet
类似 okhttp 的网络请求库，使用 Cronet 实现

# Why
Google 提供了一个 `okhttp` 到 `Cronet` 的桥接实现 [cronet-transport-for-okhttp](https://github.com/google/cronet-transport-for-okhttp/)，但是正如 `README` 中 `Incompatibilities`(不兼容性)内容中所描述的，你无法使用该源码来完善其功能。

因此，okcronet 项目的目标是，解决使用 `Cronet` 实现的拦截器来提供的 HTTP3/QUIC 支持中存在的问题。

关于 `okhttp` 在对 HTTP3/QUIC 的支持性方面的解答，可以查看此[issues](https://github.com/square/okhttp/issues/907)。

## 引用
你需要同时引用本库以及 Cronet 库。
关于 Cronet 的引用，你可以使用任何 Cronet 的实现库，只要它遵守 cronet-api 

### 引入本库
```
    // 引入本库
    implementation("io.github.limuyang2:okcronet:1.0.0")

```

### 引入 Cronet 库
```
    // 示例，这是直接引入 Google 官方提供的 Cronet，并且包含本地 so 库的 lib
    implementation("org.chromium.net:cronet-api:119.6045.31")
    implementation("org.chromium.net:cronet-common:119.6045.31")
    implementation("org.chromium.net:cronet-embedded:119.6045.31")


    // 如果你是直接使用 Google Play 的海外app，不需要考虑中国大陆的情况，可以直接使用 Google Play 提供的 so，不需要在APK中打包 so 文件
    // 参考链接 https://developer.android.com/develop/connectivity/cronet/start#kts
    //
    implementation("com.google.android.gms:play-services-cronet:18.0.1")
```

# 如何使用
整体使用方式与 okhttp 使用方式保持一致。只是多了一个 `CronetEngine` 的创建工作。

## 构建唯一的 CronetEngine
具体的 api 说明请参考 [Cronet](https://developer.android.com/develop/connectivity/cronet)
```kotlin
    // cronetEngine 需要要保持全局唯一性，不可重复创建
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
            .setThreadPriority(-1)
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

# 混淆
你需要增加 cronet 的混淆规则。如果你是用 Google 官方提供的版本，则会自动包含。


# Thanks
[okhttp](https://github.com/square/okhttp)

[cronet-transport-for-okhttp](https://github.com/google/cronet-transport-for-okhttp)
