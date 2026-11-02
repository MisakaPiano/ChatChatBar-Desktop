# 261 外部导入空闲发布与原动作交接

CI111／b6bc309b主2513=2493PASS+19SKIP+1FAIL；唯一原用例在成功解密、进入PREVIEW、原settings／文件字节／未提交断言已通过后，调用skip并于原5秒等不到next且idle。精确XML为DesktopExternalImportControllerTest旧120行，不是KDF／进入预览慢。新图片五行为、custody／Panel及相关人物等实际选中20例通过，但整轮仍FAIL。proxy5／NTFS7／shared1138／Android582／Python3／native33／updater8／exit4独立通过。

## 机制与最小修正

原action finally先发布busy=false，再unlock。跨线程或StateFlow即时观察者可在锁仍持有时看到idle，随后调用skip/import被tryLock拒绝。生产UI主要EDT串行，此报告不证明每次GUI点击都发生该窗口；控制器对外发布的“已可操作”仍应与准入一致。原inspectHead也在其caller持锁期间发布idle，一并由原owner收尾。

现在释放mutex后才为原item ID发布idle，若unlock已让下一队列头开始检查，不把下一项busy清掉。保留原准备、密码擦除、冲突选择、delegate.join、清理、attempt receipt、队列确认、根锁／重启拒绝和不自动提交。

同时把原动作引用放进ActionRun，在发布busy前注册；UNDISPATCHED同步动作可能在launch返回前结束并被即时观察者开启下一动作，旧尾声仅补自己的Job，不覆盖新owner。提前stop intent保留至原Job可用，备份／存档仍停止原delegate，不另建业务任务。

## 受影响验证（新源待云端）

- 原失败加密包case保留原5秒、20秒预览及全部不提交／原数据／文件／队列断言，仅超时时补固定phase／busy／next-head／merge-ready状态，不能把等待增加当修复。
- 新即时idle观察者在production EDT默认dispatcher和Unconfined两种情况下，连续显式import→complete→next，验证首项真实写入、第二项保持READY未导入、原件不变。
- 新同步retryInspection尾声用受控precommit导入等待，随后stop必须取消那个原动作，进入UNCERTAIN且无Entity／receipt发布，不能被已完成旧Job替代。

这项后补不在冻结1233／2ba39c里；1233的110和普通R18证据不被改成111通过或真人验收。无本机编译／测试、真实资料写入或新发布授权。
