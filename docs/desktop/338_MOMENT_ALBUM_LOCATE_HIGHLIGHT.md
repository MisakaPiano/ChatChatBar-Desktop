# 338 相册定位后的短暂高亮

固定07eb的 `MomentsScreen` 除相册入口与原post ID定位，还给定位目标加3.5秒主题边框。Desktop已有按当前完整信息流计算索引、滚动及不存在ID的可见错误，但缺少短暂视觉提示；335两项筛选检查不覆盖这部分，不能据此宣称整个Screen差异完成。

本次仅在成功定位后按原post ID高亮对应 `DesktopMomentFeedCard`，使用现主题primary的2dp圆角边框；3.5秒后移除。计时effect放在相册early return之前，切回相册仍继续计时；不同新目标取消旧计时，不让旧目标的到期事件清掉新目标。Feed的选择语义与实际边框共享同一布尔值。已有找不到动态的可见错误继续复用，不添加第二条通知。

新增 `DesktopMomentAlbumLocateHighlightTest` 在实际父Panel检查定位、不同目标替换、原计时到期后新目标仍有效、最终到期及原动态字节不变；另捕获真实旧tile回调，删除动态后调用，确认只出现原错误、不高亮其他行。必要PNG归档供视觉核对。R65／42357093152原41605B／SHA519e31a1…ab1e2：失效tile方法完整PASS，timer方法首次高亮已出现，第二次打开保留card组后，helper误点不存在的folder失败；原件保留，不记业务timer失败。7139只校正helper层级，R68／42357638338原105309B／SHA371940bd…c637a，只跑该timer方法完整PASS、0FAIL/ERROR/SKIP。两原件CRC/source/全部XML与空stderr已核，R68三PNG实际查看：原p0→新p1边框只在对应原ID，最终无边框，文字与操作完整。失效tile旧一方法分源复用，不计一次新两方法；不重跑已通过的筛选两项与无关生图检查。不是整进程／真人验收或新交付。


最终证据 `OWNER_ALBUM_LOCATE_HIGHLIGHT_FINAL_REVIEW.json`。原固定3.5秒源码及实际替换／到期状态分别核对；不冒称纳秒精度的时间边界。
