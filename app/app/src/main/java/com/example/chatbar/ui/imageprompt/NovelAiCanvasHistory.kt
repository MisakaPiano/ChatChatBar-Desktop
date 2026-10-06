package com.example.chatbar.ui.imageprompt

internal fun <T> undoCanvasState(current: T, undo: MutableList<T>, redo: MutableList<T>): T? =
    com.example.chatbar.domain.image.undoCanvasState(current, undo, redo)

internal fun <T> redoCanvasState(current: T, undo: MutableList<T>, redo: MutableList<T>): T? =
    com.example.chatbar.domain.image.redoCanvasState(current, undo, redo)
