package com.phone.mirror.core

/**
 * 通用 Result<T> 包装类型。
 *
 * 设计上是 [kotlin.Result] 的领域增强版：携带可读的错误消息、可选的 Throwable，
 * 同时提供与业务层更契合的 [successOrNull] / [errorOrThrow] 等扩展。
 */
sealed interface Result<out T> {
    val isSuccess: Boolean
    val isFailure: Boolean get() = !isSuccess

    data class Success<out T>(val value: T) : Result<T> {
        override val isSuccess: Boolean get() = true
    }

    data class Failure(
        val message: String,
        val cause: Throwable? = null,
    ) : Result<Nothing> {
        override val isSuccess: Boolean get() = false
    }

    companion object {
        fun <T> success(value: T): Result<T> = Success(value)
        fun <T> failure(message: String, cause: Throwable? = null): Result<T> =
            Failure(message, cause)
    }
}

inline fun <T> runResult(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (t: Throwable) {
    Result.failure(t.message ?: t.javaClass.simpleName, t)
}

inline fun <T> Result<T>.onFailure(block: (Result.Failure) -> Unit): Result<T> {
    if (this is Result.Failure) block(this)
    return this
}

inline fun <T, R> Result<T>.map(transform: (T) -> R): Result<R> = when (this) {
    is Result.Success -> Result.success(transform(value))
    is Result.Failure -> this
}

fun <T> Result<T>.successOrNull(): T? = when (this) {
    is Result.Success -> value
    is Result.Failure -> null
}

fun <T> Result<T>.errorOrThrow(): T = when (this) {
    is Result.Success -> value
    is Result.Failure -> throw IllegalStateException(message, cause)
}
