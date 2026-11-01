# 外部文件打开与顺序导入 / 2026-11-02

原216 P15外部入口的独立源码切片。上一个已执行检查为9040fb64／CI89成功；本文新增源码、测试和原生入口尚待下一精确源云端编译运行，不能写为功能验收通过。公开仍1229／签名13，私有1230冻结064／本地14，不混入新源码，不操作日常H安装。

## 实际入口

Main(args)在业务构造前接DesktopExternalActivation。每个安装身份在当前用户辅助目录持有一个receiver锁，以认证loopback传递文件和聚焦请求；只有owner构造业务与资料根。队列只记录有界路径、ID和attempted，不记录正文、密码或冲突选择。确认丢失不自动重送；损坏辅助记录保留原件、暂停外部投递，普通窗口仍可进入。正常重开保留待处理项，attempted项恢复为待核对而非重新导入；这不是断电或所有文件系统原子性证明。

新增OpenChatBarFile.exe为受管根的第三个入口资源，旧稳定入口协议保持。管理器→Main传参保留Unicode、空格与顺序，运行中直接投给receiver、不重新运行整个image。冷启动仍校验所有image字节。HKCU仅登记按安装根区分的“打开方式”，不抢默认或改UserChoice；卸载只移除精确归属注册。资源回执接受旧两项或新三项，外来helper拒绝，不静默收编。原生ready、forwarded和queue确认分别证明就绪、已有owner与排队，不互相冒充。

## 复用已有导入能力

Inspector按复制后的内容识别。小型卡片复用共享严格解码；整备份走原有流式校验；SaveSlot走原有结构检查；大文件不会整份readBytes。未知内容要求选类型，失败留队头。

- 角色／格式／世界书：明确导入，原冲突覆盖／新建／取消；模型仍建新配置且不带模板密钥。
- 整备份：原密码、冲突预览、目标位置、凭据同意与提交UI；外部入口不自动提交。重启门禁保留后续队列。
- SaveSlot：明确接收会话后导入其存档库，不自动恢复；保留现有会话正文与草稿。
- 图片：实际Studio忙时等待，不停止生成；沿现有目标检查并复制guidance，不自动付费。动画只读预览与静态guidance准入区分。

每次实际修改前先发布attempted标记。完成只认本item/attempt的真实导入器回执，不用全局revision或“别的导入成功”替代；不确定提交只用原捕获unresolved对象核对，重新启动后无法重建该证据就继续待核对。取消与关闭join自己的具体委托Job后再释放源副本，不取消其他手动操作或提前删仍在读取的文件。所有独立选择经既有root／restart门禁。

全局Notice和modal位于应用shell，不因离开聊天页销毁。隐藏窗口不消费队列。成功后用户可进入匹配管理页或手动下一项；不会为导航强制关闭未保存编辑。

## 云端检查设计（尚未执行）

DesktopExternalActivationTest包含真实子JVM调用生产Main ingress helper、唯一资料writer、暖启动原始路径、正常重开、损坏记录、发布失败、TCP送达后丢ACK不重放。Inspector覆盖原件／副本、超限图片元数据与取消清理。Controller使用真实AppContainer与既有typed/model/save/merge/Studio owners，覆盖FIFO冲突、手动B不能确认取消A、同原证据核对、恢复项不重放、真实合成加密包密码及预览、显式SaveSlot接收、实际Studio忙、restart和attempt发布失败。Host在420／1100宽实际Scene验证隐藏／导入／下一项，并输出合成审图。

原生test_lifecycle.py追加旧公开1229稳定入口源字节固定编译、新三资源冷暖委托、Unicode传参、OpenWith/default保全、外来helper拒绝以及实际C# socket送达／丢ACK无重放／冷image损坏拒绝。小型fake不等于完整安装包或真人验收，后者仍需分层记录。冻结源码fixture仅测试，不进入产物。云端第一轮失败按真实栈修，不把静态检查写成PASS。

首轮ee382445／CI90（41342985050）原FAIL保留：Windows生产编译在新预业务提示框调用`User32.MessageBox`失败（现有JNA未声明此方法），Desktop主用例及production JAR代理验证均未运行。原生33、更新器8、退出交接4实际通过，Windows共享7及Linux作业成功。后续仅改用项目已有StartupMessageBoxApi.MessageBoxW接口，再送新精确源；不以这些已过旁路代替Desktop新用例。
