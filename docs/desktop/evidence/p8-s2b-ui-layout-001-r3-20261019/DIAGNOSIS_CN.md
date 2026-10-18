# 原生诊断及当前未决根因

## 修复前一次有界观察

阶段01使用真实Window／AWT输入和loopback MockWebServer，独立合成根与长MP3，没有业务Container或真实用户数据。Window身份来自明确的合成窗口创建事件和公开ComposeWindow；不使用Window.getWindows、反射、foreground猜测或截图。

1100×720请求的原生frame记录为1100×720，client1084×681、scale1、wide=true；720×720请求记录frame720×720、client704×681、scale1、compact=true。窄屏有两个语义root（主内容和Popup）。这些是该诊断记录到的实际尺寸，不是“约半屏”。

四次原始Escape观察均为Window KeyDown直接关闭Picker，接着KeyUp；Popup自己的Key处理／onDismiss没有先发生。诊断中的focusField标记表示尝试点击对应字段，不冒充已通过真实Focused断言；修复后测试明确定位SetText节点、实际鼠标点击并断言Focused，覆盖有／无输入框焦点。

长MP3为独立MPEG合成静音帧，原生输出isPlaying与runtime playing均为true；它不是用户能听见正确语音的证据。旧Stop节点1个，位于Window底部，boundsInWindow约y631..663，处于真实client范围内；因此不能说所有场景都“没有生成按钮”或“必定超出视口”。但在窄屏Popup打开后，其root仍是主内容root，与当前Popup root不同。位置远、层级及发现性问题需要与状态错误分别处理。

## 草稿机制与原生断言

Window和Content共用每个Picker自己的Escape协调器，Popup关闭系统默认back-dismiss，使用同一preview事件入口；面板dismiss注册随panel composition变化清除。KeyDown只关闭一层，重复KeyDown不继续关闭，KeyUp重置；没有固定延时或全局键盘拦截。文本、目标及缓存仍由原来的picker/session持有。

Stop在顶部预览状态区用现有StudioAction PRIMARY、Stop图标和完整文字；compact Popup打开时从下层移到该Popup顶层，只存在一个实际可操作按钮。真实窗口用例核对完整bounds在client视口内、同一Popup root、Role/Disabled/RequestFocus、原生mouse或Enter触发、busy/playing与实际Windows后端一致、停止后无假按钮或额外请求／误绑定。

阶段03和05 XML记录：普通宽屏Stop的boundsInWindow已在顶部约y58..90；窄屏Popup Stop约y259..292，并与Popup字段同root。操作成功后实际后端不再playing，缓存清理断言成立。完整数值按各次XML／native-geometry-state.jsonl为准，不能把某次root ID或坐标冒充所有显示器保证。

用户追加的分页草稿以显式Enter／跳转按钮调用既有controller.search，默认pageSize20不变；已知total时用Long计算页数，拒绝非法／越界输入；unknown total不虚构总页数，Int.MAX_VALUE处不允许“下一页”溢出。结果LazyColumn使用同一ListState和常驻VerticalScrollbar，换页回到本页顶部。没有添加HTTP自动重试、改变认证／sample来源或扩大生产业务。

## 新失败：只知道滚动位置未增加

阶段02及03的同名分页方法通过，阶段05在第309行等待range().value()增加超时10秒。没有page-input-and-scrollbar完成快照；按固定循环顺序，未完成首个1100请求宽度分支，也未到达720／unknown-total分支。该次失败拖动的实际frame、thumb、maxValue和pointer capture没有单独记录，保持UNKNOWN。

测试已核对计划点击和拖动终点位于该Window bounds内；这不足以证明点到了thumb、可滚动范围已经就绪，或原生pointer事件被目标控件接收。也没有证据证明是运行环境、用户输入干扰、产品逻辑或特定显示器问题。不可因一次聚焦PASS就判定无产品缺陷，不可随意增加等待或跳过失败。

按用户STOP规则，本轮没有第二次诊断、重试或代码修正。下一步候选只读定位是：在同一合成窗口内记录实际thumb/track与viewport、scroll range和ready时刻、own HWND命中、focus/capture及收到的指针事件；需要Project另行授权，不是本轮已完成结论。
