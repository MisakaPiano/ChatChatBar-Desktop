package com.example.chatbar.data.operation

/**
 * 平台中立的 app-data normal-operation 参与边界。
 *
 * sharedCore 只声明普通操作需要登记，不包含 Desktop maintenance、lifecycle 或 process ownership
 * 语义。未提供平台协调器时使用 [NoOpAppDataOperationGate]，保持 Android 与既有调用行为不变。
 */
interface AppDataOperationGate {
    suspend fun <T> withNormalOperation(operation: suspend () -> T): T
}

object NoOpAppDataOperationGate : AppDataOperationGate {
    override suspend fun <T> withNormalOperation(operation: suspend () -> T): T = operation()
}
