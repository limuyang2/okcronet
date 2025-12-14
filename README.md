# okcronet
A network request library similar to OKHTTP, implemented using Cronet

[中文](https://github.com/limuyang2/okcronet/blob/main/README_CN.md)

# Why
Google provides a bridge implementation from `okhttp` to `Cronet` [cronet-transport-for-okhttp](https://github.com/google/cronet-transport-for-okhttp/), but as in `README` As described in the `Incompatibilities` content, you cannot use the source code to improve its functionality.

Therefore, the goal of the okcronet project is to solve the problems of HTTP3/QUIC support provided by interceptors implemented by `Cronet`.

For answers about `okhttp`'s support for HTTP3/QUIC, you can view this [issues](https://github.com/square/okhttp/issues/907).

# Advantages of okcronet
* Easy to use, consistent with OkHttp usage
* Supports HTTP3/QUIC, which can provide better network performance
* Supports all features of Cronet, such as caching, thread pools, proxies, etc.

# Import
You need to reference both the OkCronet library and the Cronet library.
## okcronet Library:
```
implementation("io.github.limuyang2:okcronet:1.0.11")
```

## Cronet Library:
### Google Official Cronet:
#### Way 1
[Google Latest Version](https://maven.google.com/web/index.html?#org.chromium.net)
```
implementation("org.chromium.net:cronet-api:141.7340.3")
implementation("org.chromium.net:cronet-common:141.7340.3")
implementation("org.chromium.net:cronet-embedded:141.7340.3")
```
#### Way 2
The packages officially provided by Google are not the latest. If you want to use the latest version synchronized with `chromium`, you can visit the official storage bucket to obtain it.[google cloud](https://console.cloud.google.com/storage/browser/chromium-cronet/android;tab=objects?inv=1&invt=Abmz0w&prefix=&forceOnObjectsSortingFiltering=true)


### Google Play Cronet:
Reference link - [android develop](https://developer.android.com/develop/connectivity/cronet/start#kts)
```
implementation("com.google.android.gms:play-services-cronet:18.1.1")
```

# Using
The usage is consistent with the usage of okhttp. There is just one more `CronetEngine` creation work.
## Build a globally unique CronetEngine
```kotlin
    // cronetEngine needs to maintain global uniqueness and cannot be created repeatedly
    private val cronetEngine: CronetEngine by lazy(LazyThreadSafetyMode.NONE) {
        val httpCacheDir =
            File(
                this.applicationContext.externalCacheDir ?: this.applicationContext.cacheDir,
                "http"
            )

        if (!httpCacheDir.exists()) {
            httpCacheDir.mkdir()
        }

        CronetEngine.Builder(
            NativeCronetEngineBuilderImpl(this.applicationContext)
        )
            .setStoragePath(httpCacheDir.absolutePath)
            .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK, 1048576)
            .enableHttp2(true)
            .enableQuic(true)
            .setThreadPriority(-1)
            .enableBrotli(true)
            .build()
    }
```

## GET
```kotlin
    // Create CronetClient
    val cronetClient = CronetClient.Builder(cronetEngine).build()

    // Build Request
    val request = Request.Builder()
        .url("https://www.fastly.com/quic-http-3")
        .get()
        .build()

    // Initiate network requests synchronously
    cronetClient.newCall(request).execute()

```

## POST
```kotlin
    // Create CronetClient
    val cronetClient = CronetClient.Builder(cronetEngine).build()

    // Build RequestBody
    val body: RequestBody = "jsonString".toRequestBody()

    // Build Request
    val request = Request.Builder()
        .url("https://www.fastly.com/quic-http-3")
        .post(body)
        .build()

    // Initiate network requests synchronously
    cronetClient.newCall(request).execute()
```

# Proguard
You need to add obfuscation rules for `cronet`. If you are using the version officially provided by Google, it will be included automatically.


# Thanks
[okhttp](https://github.com/square/okhttp)

[cronet-transport-for-okhttp](https://github.com/google/cronet-transport-for-okhttp)
