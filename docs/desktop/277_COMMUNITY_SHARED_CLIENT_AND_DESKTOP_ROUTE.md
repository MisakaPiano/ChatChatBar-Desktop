# 277 — 社区原客户端共享与桌面接线

本片属于216最后功能域P14，不新建社区后端。2026-11-02只读重读官方`community_runtime_config`实际返回`enabled:false`、`Community disabled by emergency switch`；该时点事实不是永久关闭结论，客户端仍须动态读取并遵守开关。

第一片将原CommunityModels/PackagePolicy原字节移入sharedCore，原CommunityService的HTTP、Discord身份/刷新、metadata分页、只读详情、计数下载、Storage/Edge发布/覆盖/删除与状态缓存进入CommunityClient。Android facade保留原prefs、repository/transfer导出和160px/80KB JPEG预览；不改DTO、表、bucket、Edge载荷或Prompt。共享平台缝仅为本地条目导出与session保管。

原20MB限制也约束HTTP读取，取消会关闭原Call；取消refresh保留原session，不误当失效登出。覆盖前核同owner/类型/本地原名。下载次数失败走明确warning，不冒充发布或导入成功。回调先核对配置的scheme/host/port/path，再沿原user endpoint核实身份；没有服务开通或真实OAuth成功声明。

十个共享loopback用例覆盖关闭/仅缺表legacy、metadata与详情/下载分离、回调/刷新、Storage/Edge原载荷、owner/同名拒绝、超长响应、取消保全、二进制预览以及暂时刷新失败后原session重试/明确拒绝才清除。HTTP错误仅投影固定action/status，服务body和任意error字符串不进可展示异常；合成字母数字token反例也保留。原Android PackagePolicy测试继续使用共享owner。

DesktopCommunityPorts已有真实export/受保护key、160px/80KB JPEG复制；Controller已有状态/metadata分页/预览/导入/登录/本人快照和远端更新owner，但尚未接生产root UI。原typed conflict/evidence/commit路径新增明确来源参数，provenance在首次原子提交而非后补save；pending普通file或远端更新在真正Core overwrite前重核最新来源，A→B/普通→readonly拒绝且无资源写入，合法更新保ID、duplicate清来源。新增Core2、Desktop ports2/实际controller1/typed1（后者含源变化反例）源码待自己的云验，不归128或129，不涉及真实社区内容、登录、服务端、日常H或公开。

下一片继续原顶层开关/导航、UI、实际Desktop OAuth返回等位和受影响Scene验证。实际服务关闭只挂对应联调，其他离线客户端继续；R7普通链既有通过不因这些卡字段重建三包。
