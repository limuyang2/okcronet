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

import java.util.concurrent.CancellationException
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * @author 李沐阳
 * @date 2023/5/17
 * @description 'CompletableFuture' 的向后移植，可与旧版本 Android 配合使用。
 */
internal class CompletableFutureCompat<V> : Future<V> {
    private sealed class Result<out V> {
        abstract val value: V
        class Ok<V>(override val value: V) : Result<V>()
        class Error(val e: Throwable) : Result<Nothing>() {
            override val value: Nothing
                get() = throw e
        }
        data object Cancel : Result<Nothing>() {
            override val value: Nothing
                get() = throw CancellationException()
        }
    }

    /**
     * Offers the completion result for [result].
     *
     * If this queue is not empty, the future is completed.
     */
    private val completion = LinkedBlockingQueue<Result<V>>(1)
    /**
     * Holds the result of the computation. Takes the item from [completion] upon running and provides it as a result.
     */
    private val result = FutureTask { completion.peek()!!.value }
    /**
     * If not already completed, causes invocations of [get]
     * and related methods to throw the given exception.
     *
     * @param ex the exception
     * @return `true` if this invocation caused this CompletableFuture
     * to transition to a completed state, else `false`
     */
    fun completeExceptionally(ex: Throwable): Boolean {
        val offered = completion.offer(Result.Error(ex))
        if (offered) {
            result.run()
        }
        return offered
    }

    /**
     * If not already completed, completes this CompletableFuture with
     * a [CancellationException].
     *
     * @param mayInterruptIfRunning this value has no effect in this
     * implementation because interrupts are not used to control
     * processing.
     *
     * @return `true` if this task is now cancelled
     */
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        val offered = completion.offer(Result.Cancel)
        if (offered) {
            result.cancel(mayInterruptIfRunning)
        }
        return offered
    }

    /**
     * If not already completed, sets the value returned by [get] and related methods to the given value.
     *
     * @param value the result value
     * @return `true` if this invocation caused this CompletableFuture
     * to transition to a completed state, else `false`
     */
    fun complete(value: V): Boolean {
        val offered = completion.offer(Result.Ok(value))
        if (offered) {
            result.run()
        }
        return offered
    }

    override fun isDone(): Boolean = completion.isNotEmpty()

    override fun get(): V = result.get()

    override fun get(timeout: Long, unit: TimeUnit): V = result.get(timeout, unit)

    override fun isCancelled(): Boolean = completion.peek() == Result.Cancel
}