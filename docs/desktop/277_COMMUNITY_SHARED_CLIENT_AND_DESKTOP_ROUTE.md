# 277 — 社区原客户端共享与桌面接线

本片属于216最后功能域P14，不新建社区后端。2026-11-02只读重读官方`community_runtime_config`实际返回`enabled:false`、`Community disabled by emergency switch`；该时点事实不是永久关闭结论，客户端仍须动态读取并遵守开关。

第一片将原CommunityModels/PackagePolicy原字节移入sharedCore，原CommunityService的HTTP、Discord身份/刷新、metadata分页、只读详情、计数下载、Storage/Edge发布/覆盖/删除与状态缓存进入CommunityClient。Android facade保留原prefs、repository/transfer导出和160px/80KB JPEG预览；不改DTO、表、bucket、Edge载荷或Prompt。共享平台缝仅为本地条目导出与session保管。

原20MB限制也约束HTTP读取，取消会关闭原Call；取消refresh保留原session，不误当失效登出。覆盖前核同owner/类型/本地原名。下载次数失败走明确warning，不冒充发布或导入成功。回调先核对配置的scheme/host/port/path，再沿原user endpoint核实身份；没有服务开通或真实OAuth成功声明。

七个新合成loopback测试覆盖关闭/仅缺表legacy分支、metadata与详情/下载分离、回调与刷新、Storage/Edge原载荷、owner/同名拒绝、超长响应和取消refresh保全。原Android PackagePolicy测试继续使用共享owner。当前为源码，尚待自己的云端验证；不在128/bcacc或129/ec086中，也不涉及真实社区内容、真实登录、服务端配置、日常H或公开发行。

后续沿原typed transfer、受保护SecretStore、现有代理、顶层开关与正常草稿导航接桌面UI；浏览搜索/详情预览、导入冲突、本人快照管理、远端更新及Desktop OAuth返回依次实装。实际服务关闭/真实OAuth返回只挂对应联调，其他离线客户端继续。
