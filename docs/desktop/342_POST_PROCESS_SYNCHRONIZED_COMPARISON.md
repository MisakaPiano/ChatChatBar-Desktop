# 342 后处理结果同步对比

固定148／12e的ImageComparison要求原图与结果共享fit边界及scale／offset，分界裁剪留在viewport坐标，放大后增加解码分辨率。旧Desktop只有两图各自Fit及拖动分界，不能用独立图片窗缩放或R67“大图可以显示”代替同步细节比较。

新增 `DesktopPostProcessComparison` 复用既有锚点缩放数学：滚轮锚点缩放1–5倍；分界附近拖动只改分界，其他区域在放大后拖动平移；平移按共同原图边界限位；适应／重置同时恢复fit及中间分界。原图、结果使用共同原图fit尺寸及明确FillBounds，含结果圆整后略异比例；分界在外层viewport裁剪，提供SetProgress语义。ceil(scale)稳定档位解码提高细节分辨率，不只拉伸默认缩略图。普通OwnedImage的Fit／Crop默认保持。

只有记录sourceHash匹配当前输入时双图比较，其他来源独立显示结果；任务、费用、持久化、API、显式用作／保存／复制及原数据机制保留。异步结果发布携带recordId，仅当前记录匹配时显示及提供动作，避免切记录首帧把旧bytes标为新身份。

定向用例用不同像素尺寸／略异比例的合成色块与条纹，实际渲染采样检查双层对齐，实际pointer拖分界、滚轮、平移、点击重置及上下限，保留原bytes，并提供fit与zoom/pan图。待原件，不提前声明通过；不重复大噪声R67、真实Provider或整个后处理生命周期。原大结果测试只更新实际producer identity定位，其余断言保留，新组件不由旧R67代验。

同区Strength／Noise改接已有StudioNumericInput，保留标签及finite／0–1／busy约束、默认值和费用算法。数值等价echo保留“0”等继续输入态，失焦按既有settle恢复最后合法值；使用当前来源的局部历史scope。只作既有组件的接线，随下次必要编译，不为简单范围条件写镜像测试或重跑Studio。R72源0437不含此后继，不冒称该run覆盖后继编译。
