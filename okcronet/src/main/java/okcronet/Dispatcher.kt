package okcronet

import java.util.ArrayDeque

/**
 * @author 李沐阳
 * @date 2025/3/14
 * @description
 */
class Dispatcher() {

    private val calls = ArrayDeque<RealCall>()

    @Synchronized internal fun add(call: RealCall) {
        calls.add(call)
    }

    /**
     * Cancel all calls
     */
    @Synchronized fun cancelAll() {
        while (calls.isNotEmpty()) {
            calls.pollFirst()?.cancel()
        }
    }

    @Synchronized internal fun finished(call: RealCall) {
        synchronized(this) {
            calls.remove(call)
        }
    }


}