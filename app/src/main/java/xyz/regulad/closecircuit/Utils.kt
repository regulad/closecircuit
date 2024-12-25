package xyz.regulad.closecircuit

import android.net.wifi.WifiManager
import android.os.Parcel
import android.os.Parcelable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

suspend inline fun <T> simpleRetryUntilNotNull(
    times: Int = Int.MAX_VALUE,
    initialDelay: Long = 100, // 0.1 second
    maxDelay: Long = 1000,    // 1 second
    factor: Double = 2.0,
    block: () -> T?
): T {
    var currentDelay = initialDelay
    repeat(times - 1) {
        val value = block()
        if (value != null) return value
        delay(currentDelay)
        currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
    }
    return block()!!
}

inline fun WifiManager.MulticastLock.use(block: () -> Unit) {
    acquire()
    try {
        block()
    } finally {
        release()
    }
}

suspend inline fun WifiManager.MulticastLock.suspendUntilReleased() {
    while (isHeld) {
        yield()
    }
}

/**
 * Runs a blocking operation in a cancellable way using coroutines.
 * The blocking operation runs in a separate dispatcher to avoid blocking the caller's thread.
 * The operation can be cancelled via coroutine cancellation.
 *
 * @param context Additional coroutine context elements. Defaults to EmptyCoroutineContext.
 * @param block The blocking operation to run. Returns T or throws an exception.
 * @return The result of the blocking operation
 * @throws CancellationException if the coroutine is cancelled
 * @throws Exception if the blocking operation throws an exception
 */
suspend fun <T> runBlockingCancellable(
    context: CoroutineContext = EmptyCoroutineContext,
    block: () -> T
): T = withContext(Dispatchers.IO + context) {
    val threadRef = AtomicReference<Thread>()
    val resultHolder = AtomicReference<Result<T>>()

    val job = launch(CoroutineExceptionHandler { _, _ -> }) {
        try {
            threadRef.set(Thread.currentThread())
            val result = block()
            resultHolder.set(Result.success(result))
        } catch (e: InterruptedException) {
            resultHolder.set(Result.failure(CancellationException("Operation cancelled", e)))
        } catch (e: Exception) {
            resultHolder.set(Result.failure(e))
        } finally {
            threadRef.set(null)
        }
    }

    try {
        while (isActive) {
            if (job.isCompleted) {
                val result = resultHolder.get() ?: throw IllegalStateException("No result available")
                return@withContext result.getOrThrow()
            }
            yield()
        }

        // We were cancelled - interrupt the blocking thread if it exists
        threadRef.get()?.interrupt()
        throw CancellationException("Operation cancelled")
    } finally {
        // Ensure cleanup on any exit path
        job.cancel()
        threadRef.get()?.interrupt()
    }
}

fun NetworkInterface.supportsMulticast4(): Boolean {
    return try {
        if (!supportsMulticast() || !isUp) {
            return false
        }

        inetAddresses.asSequence()
            .filterIsInstance<Inet4Address>()
            .any()
    } catch (e: Exception) {
        false
    }
}

fun NetworkInterface.supportsMulticast6(): Boolean {
    return try {
        if (!supportsMulticast() || !isUp) {
            return false
        }

        inetAddresses.asSequence()
            .filterIsInstance<Inet6Address>()
            .any()
    } catch (e: Exception) {
        false
    }
}

fun <T : Parcelable> T.isParcelableEqual(other: T?): Boolean {
    if (other == null) return false
    if (this === other) return true

    val parcel1 = Parcel.obtain()
    val parcel2 = Parcel.obtain()

    try {
        // Write both objects to separate parcels
        this.writeToParcel(parcel1, 0)
        other.writeToParcel(parcel2, 0)

        // Reset the parcels for reading
        parcel1.setDataPosition(0)
        parcel2.setDataPosition(0)

        // Compare the marshalled bytes
        return parcel1.marshall().contentEquals(parcel2.marshall())
    } finally {
        // Always recycle the parcels
        parcel1.recycle()
        parcel2.recycle()
    }
}

/**
 * Collects only new values from the StateFlow, using the provided equalityCheck function to determine if a value is new. Using it with the default equalityCheck is rather useless, as StateFlows automatically check for simple equality.
 */
suspend fun <T> StateFlow<T>.collectOnlyNew(
    equalityCheck: T.(T?) -> Boolean = { other: T? -> this == other },
    collector: FlowCollector<T>
): Nothing {
    var previousValue: T? = null
    collect { value ->
        if ((previousValue == null && value != null) || value == null || !value.equalityCheck(previousValue)) {
            collector.emit(value)
            previousValue = value
        }
    }
}

/**
 * Returns a new state flow that only emits new values. Using it with the default equalityCheck is rather useless, as StateFlows automatically check for simple equality.
 */
inline fun <I, O> StateFlow<I>.transformOnlyNewState(
    noinline equalityCheck: I.(I?) -> Boolean = { other: I? -> this == other },
    scope: CoroutineScope,
    crossinline transformer: (I) -> O
): StateFlow<O> {
    val newFlow = MutableStateFlow(transformer(value))

    scope.launch {
        collectOnlyNew(equalityCheck) { value ->
            newFlow.value = transformer(value)
        }
    }

    return newFlow
}

/**
 * Returns a new state flow that only emits new values. Using it with the default equalityCheck is rather useless, as StateFlows automatically check for simple equality.
 */
inline fun <T> StateFlow<T>.onlyNewState(
    noinline equalityCheck: T.(T?) -> Boolean,
    scope: CoroutineScope
): StateFlow<T> {
    return transformOnlyNewState(equalityCheck = equalityCheck, scope = scope) { it }
}

inline fun <I, O> StateFlow<I>.transformOnlyNewStateSuspendable(
    noinline equalityCheck: I.(I?) -> Boolean = { other: I? -> this == other },
    scope: CoroutineScope,
    crossinline transformer: suspend (I) -> O
): StateFlow<O?> {
    val newFlow = MutableStateFlow<O?>(null)

    scope.launch {
        newFlow.value = transformer(value)
        collectOnlyNew(equalityCheck) { value ->
            newFlow.value = transformer(value)
        }
    }

    return newFlow
}

suspend inline fun <T : Parcelable?> StateFlow<T>.collectOnlyNewParcelable(
    collector: FlowCollector<T>
): Nothing {
    collectOnlyNew({ other: T? -> this?.isParcelableEqual(other) ?: throw RuntimeException("unreachable") }, collector)
}

inline fun <I : Parcelable?, O> StateFlow<I>.transformOnlyNewParcelable(
    scope: CoroutineScope,
    crossinline transformer: (I) -> O
): StateFlow<O> {
    return transformOnlyNewState({ other: I? ->
        this?.isParcelableEqual(other) ?: throw RuntimeException("unreachable")
    }, scope, transformer)
}

inline fun <T : Parcelable?> StateFlow<T>.onlyNewParcelable(
    scope: CoroutineScope
): StateFlow<T> {
    return onlyNewState({ other: T? -> this?.isParcelableEqual(other) ?: throw RuntimeException("unreachable") }, scope)
}

inline fun <I : Parcelable?, O> StateFlow<I>.transformOnlyNewParcelableSuspendable(
    scope: CoroutineScope,
    crossinline transformer: suspend (I) -> O
): StateFlow<O?> {
    return transformOnlyNewStateSuspendable({ other: I? ->
        this?.isParcelableEqual(other) ?: throw RuntimeException("unreachable")
    }, scope, transformer)
}
