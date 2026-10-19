        fun armHumanMouse() {
            val h = checkNotNull(human)
            check(!h.armed && !h.started)
            val bar = node("音色列表滚动条")
            h.thumb = Rectangle(bar.positionOnScreen.x.roundToInt(), bar.positionOnScreen.y.roundToInt(),
                thumbSize.width.roundToInt(), thumbSize.height.roundToInt())
            h.beforeRange = node("Fish 音色列表").config[SemanticsProperties.VerticalScrollAxisRange].value()
            h.beforeFirst = firstVisiblePageItem()
            check(h.beforeRange == 0f && h.thumb.width > 0 && h.thumb.height > 0)
            scrollbarSnapshot("R6I-human-armed")
            h.armed = true
            println("FISH_R6I_ARMED thumb=${h.thumb} beforeRange=${h.beforeRange} beforeFirst=${h.beforeFirst} " +
                "activationOutsideListExcluded=true maxControlledGestures=1")
            System.out.flush()
        }

