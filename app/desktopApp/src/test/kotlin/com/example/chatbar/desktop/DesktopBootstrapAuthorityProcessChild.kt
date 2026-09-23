package com.example.chatbar.desktop

import java.nio.file.Path

internal object DesktopBootstrapAuthorityProcessChild {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Expected bootstrap path" }
        val bootstrapPath = Path.of(args.single()).toAbsolutePath().normalize()
        when (val result = DesktopBootstrapAuthorityTransaction.acquireLock(bootstrapPath)) {
            is AuthorityLockAcquisition.Acquired -> result.lock.use {
                println(READY)
                System.out.flush()
                check(readlnOrNull() == CLOSE) { "Expected CLOSE command" }
                println(CLOSED)
                System.out.flush()
            }

            is AuthorityLockAcquisition.Busy -> {
                System.err.println("BUSY sameJvm=${result.sameJvm}")
                throw IllegalStateException("Child could not acquire bootstrap authority lock")
            }

            is AuthorityLockAcquisition.Failure -> {
                System.err.println("FAILURE kind=${result.kind} message=${result.message}")
                throw IllegalStateException("Child could not acquire bootstrap authority lock", result.cause)
            }
        }
    }

    const val READY = "READY"
    const val CLOSE = "CLOSE"
    const val CLOSED = "CLOSED"
}
