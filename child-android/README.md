# 安心伴 · 孩子端（Android 骨架）

## 运行
1. 用 Android Studio 打开本文件夹，等待 Gradle 同步（会自动生成 wrapper）。
2. 手机开启开发者选项与 USB 调试，点 Run 安装。
3. 打开 App：先点「无服务器测试：载入演示规则」，再点「开启无障碍服务」，在系统里打开「安心伴孩子端」。
4. 打开微信用满 1 分钟，会被拦截并弹出锁屏页。

## 已实现
- GuardService：无障碍识别前台应用，累计每个应用当天用时，按规则拦截（临时锁定、睡眠时段、单应用限时、每日总时长、学习白名单）。
- 拦截页：回到桌面、申请再用 15 分钟（发给家长）。
- SyncService：前台服务，每 30 秒上报心跳（电量、用时）并拉取规则；开机自启。
- 规则字段与家长端网页原型一致。

## 后端接口约定（你需要自己实现）
- POST /bind  {code, model} -> {token}
- POST /heartbeat  {battery, usedMinutes:{pkg:min}, ts}   请求头 Authorization: Bearer token
- GET /rules -> {locked, timed, sleepStart, sleepEnd, daily, limits:{pkg:min}, whitelist:[pkg]}
- POST /requests  {pkg, minutes}   家长端同意后，服务器把该应用 limits 加 15 并返回新规则

## 还没做（建议顺序）
1. 定位上报与电子围栏（FusedLocationProviderClient + 前台服务 location 类型 + 后台定位权限）。
2. 防卸载（DeviceAdminReceiver），并考虑拦截设置里的无障碍和卸载页面。
3. 华为、荣耀、vivo 的自启动和后台保护引导页（各厂商设置路径不同）。
4. 使用记录时间线上报（现在只上报用时汇总）。
5. 孩子端界面接入家长端「已开启管控」的透明提示。
