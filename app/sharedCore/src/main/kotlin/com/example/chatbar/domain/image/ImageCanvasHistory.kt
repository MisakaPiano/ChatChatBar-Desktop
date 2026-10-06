package com.example.chatbar.domain.image

fun <T> undoCanvasState(current: T, undo: MutableList<T>, redo: MutableList<T>): T? {
    val previous = undo.removeLastOrNull() ?: return null
    redo += current
    return previous
}

fun <T> redoCanvasState(current: T, undo: MutableList<T>, redo: MutableList<T>): T? {
    val next = redo.removeLastOrNull() ?: return null
    undo += current
    return next
}
