/*
 * MIT License
 *
 * Copyright (c) 2023 LiMuYang
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package okcronet

import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * @author 李沐阳
 * @date 2023/3/3
 * @description
 */
@Throws(IOException::class)
internal fun <T> Future<T>.getValue(): T {
    return try {
        this.getUninterruptibly()
    } catch (e: ExecutionException) {
        throw IOException(e.cause ?: e)
    }
}

@Throws(ExecutionException::class)
internal fun  <T> Future<T>.getUninterruptibly(): T {
    var interrupted = false
    try {
        while (true) {
            try {
                return this.get()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
    } finally {
        if (interrupted) {
            Thread.currentThread().interrupt()
        }
    }
}

@Throws(ExecutionException::class, TimeoutException::class)
internal fun <V> Future<V>.getUninterruptibly(timeout: Long, unit: TimeUnit): V {
    var interrupted = false
    try {
        var remainingNanos = unit.toNanos(timeout)
        val end = System.nanoTime() + remainingNanos
        while (true) {
            try {
                // Future treats negative timeouts just like zero.
                return this.get(remainingNanos, TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
                remainingNanos = end - System.nanoTime()
            }
        }
    } finally {
        if (interrupted) {
            Thread.currentThread().interrupt()
        }
    }
}


internal enum class DirectExecutor : Executor {
    INSTANCE;

    override fun execute(command: Runnable) {
        command.run()
    }

    override fun toString(): String {
        return "DirectExecutor.directExecutor()"
    }
}