package io.kungfury.coworker.internal

import com.jsoniter.JsonIterator

import io.kungfury.coworker.BackgroundKotlinWork
import io.kungfury.coworker.dbs.ConnectionManager
import io.kungfury.coworker.internal.states.HandleAsyncFunctorState

import java.io.ObjectInputStream
import java.util.function.Function

class AsyncFunctorRunner(
    private val connectionManager: ConnectionManager,
    id: Long,
    stage: Int,
    strand: String,
    priority: Int
) : BackgroundKotlinWork(id, stage, strand, priority) {
    private var state = ""

    override fun serializeState(): String = state

    inline fun <reified T : Any> ByteArray.deserialize(): T =
        ObjectInputStream(inputStream()).readObject() as T

    override suspend fun Work(stat: String) {
        state = stat
        try {
            val parsed: HandleAsyncFunctorState = JsonIterator.parse(state).read(HandleAsyncFunctorState::class.java)
            val serializedFunctor = parsed.methodState.serializedClosure

            if (!parsed.isJava) {
                val func: (Array<com.jsoniter.any.Any>) -> Unit = serializedFunctor.deserialize()
                func(parsed.args)
            } else {
                val func: Function<Array<com.jsoniter.any.Any>, Void> = serializedFunctor.deserialize()
                // If we don't do this we get: `Error: func.apply(parsed.args) must not be null`
                var _res: Any? = func.apply(parsed.args)
            }

            finishWork()
        } catch (err: Exception) {
            failWork(
                connectionManager,
                "io.kungfury.coworker.internal.AsyncFunctorRunner",
                "Error: ${err.message}\n  ${err.stackTrace.joinToString("\n  ")}"
            )
        }
    }
}