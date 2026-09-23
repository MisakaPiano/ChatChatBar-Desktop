package com.example.chatbar.desktop

import java.nio.file.Path

internal object DesktopDataRootOwnershipProcessChild {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Expected selected data-root path" }
        val root = Path.of(args.single()).toAbsolutePath().normalize()
        val selectedRoot = DesktopDataRootResolution.Resolved(
            appDataRoot = root,
            provenance = DesktopDataRootProvenance.BOOTSTRAP_CUSTOM,
            bootstrapPath = root.resolveSibling("child-bootstrap.json"),
        )
        when (val result = DesktopDataRootOwnership.acquire(selectedRoot)) {
            is DesktopDataRootOwnershipResult.Acquired -> result.ownership.use {
                println(READY)
                System.out.flush()
                check(readlnOrNull() == CLOSE) { "Expected CLOSE command" }
                println(CLOSED)
                System.out.flush()
            }

            is DesktopDataRootOwnershipResult.AlreadyInUse -> {
                System.err.println("ALREADY_IN_USE sameJvm=${result.sameJvm}")
                throw IllegalStateException("Child could not acquire selected root")
            }

            is DesktopDataRootOwnershipResult.Failure -> {
                System.err.println("FAILURE kind=${result.kind} message=${result.message}")
                throw IllegalStateException("Child could not acquire selected root", result.cause)
            }
        }
    }

    const val READY = "READY"
    const val CLOSE = "CLOSE"
    const val CLOSED = "CLOSED"
}
