# HDUHelper

基于 Jetpack Compose 与 [MIUIX](https://github.com/compose-miuix-ui/miuix) 的 Android 校园应用。最低支持 Android 13（API 33），编译 SDK 37。

## 当前功能

底栏依次为 **日程、课表、一码通、应用、我的**，按导航顺序提供左右切换动画。课表和一码通已接入学校服务，日程与应用页保留占位内容。

“我的”页支持点击未登录账号卡片进入原生登录页，并提供外观设置、关于应用与退出登录。外观设置为独立二级页面，支持跟随系统、浅色、深色；设置立即生效并持久保存。二级页面从右侧滑入，返回时向右滑出。

“我的”中的“课表设置”同样使用独立二级页面，可配置非本周课程、已结课课程、周末、教师、地点和作息校区。

原生登录页包含账号、密码、图标式密码显隐和自动登录选项。“我的”页不展示自动登录开关。官方网页登录使用手机 UA、手机视口及 HTTPS，并允许用户手动完成学校要求的额外验证。

## 认证与安全

- 登录通过 `sso.hdu.edu.cn` 的 CAS 表单获取数字杭电会话。每次获取新的 execution 与密码加密参数；密码按学校协议进行 AES/ECB 加密后通过 HTTPS 提交。
- 官方验证码检查接口即使使用 GET，也要求动态 `Csrf-Key` / `Csrf-Value` 请求头。缺少时 HTTP 仍可能是 200，但 JSON 业务码为 401；实现与官网的生成规则保持一致，并有回归测试。
- 最终使用数字杭电 `loginInfo.rst` 的成功标识和有效账号验证身份，支持 JSON / JSONP。跳转到 SSO 或 HTTP 200 的登录 HTML 均不视为成功。
- 密码、Cookie、必要个人资料及失败计数使用 Android Keystore 的 AES-GCM 加密，保存于 `noBackupFilesDir`。官方 WebView 的数据也从云备份与设备迁移中排除。测试账号、密码不写入项目文件或日志。
- 启动、回到前台及受保护请求失效时静默检查，优先使用 SSO 会话，其次使用保存的密码；不添加定时后台任务，也不调用未经证实的 refresh-token 接口。
- 并发请求共享一次恢复，受保护 GET 最多重试一次。连续三次明确的凭据错误后清理会话并提示重新登录；断网、超时、服务异常不计入。额外验证会暂停自动提交，转交用户完成。
- 取消、退出或切换账号会使旧操作失效，迟到的网络与 WebView 回调不能恢复已经退出的会话。退出仅影响本应用，不触发学校全局 SSO 注销。
- 官方 WebView 不读取、注入或保存网页中输入的密码。若没有同账号已验证的密码，网页登录成功后只保存会话，并关闭密码自动登录；下次在原生登录页重新验证即可开启。

## 开发与测试

课表页面实现前的协议调研见 [教务系统 SSO 登录与课表解析分析](docs/jw-sso-timetable-analysis.md)，包含实际跳转链、`N2151` 来源、JSON 接口、校历作息与解析边界。

使用 Android Studio 打开项目，SDK 路径由本机 `local.properties` 配置。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebugAndroidTest
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。主要代码按 `data/auth`（协议、加密存储、认证仓库）、`data/settings`（设置）、`ui/screens`（页面）组织。`ui/HDUHelperApp.kt` 管理导航，`ui/AppViewModel.kt` 持有界面状态；密码不使用可保存的页面状态。

设备界面测试可运行：

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class moe.nepnep.hduhelper.ProfileUiTest \
  moe.nepnep.hduhelper.test/androidx.test.runner.AndroidJUnitRunner
```

真实认证测试需要显式启动，并会在结束时退出测试账号。脚本通过交互终端读取密码，经临时 ADB 转发连接传入设备；不会将凭据放入环境变量、命令行参数、Gradle 配置或文件。

```sh
python3 tools/live-auth-smoke.py --serial DEVICE_SERIAL
```

`LiveAuthTest` 默认跳过。错误密码、连续认证失败、并发和异常场景使用 MockWebServer / 内存模拟测试，避免真实账号被锁定。官方短信、验证码等交互场景依赖学校触发，未进行完整实测。

官方页面的公开布局回归测试（不输入账号密码）：

```sh
adb shell am instrument -w -e class moe.nepnep.hduhelper.OfficialWebViewTest \
  -e officialWebViewTest true \
  moe.nepnep.hduhelper.test/androidx.test.runner.AndroidJUnitRunner
```

WebView 必须设置原生 `MATCH_PARENT` 宽高，并启用官网的 viewport 标签。仅在 Compose 中使用 `fillMaxSize()` 会保留默认的 `WRAP_CONTENT`，导致 CSS 视口高度异常：官网误判为横屏电脑版，背景高度不足并在底部留白。测试检查手机布局、页面填满视口、无横向溢出，以及弹出键盘后视口尺寸保持不变。

## 一码通

“一码通”页面使用已有 SSO 会话为学校一码通授权，动态查找“电子凭证”应用并换取应用 token。原生页面展示姓名、身份、学院与二维码，不包含余额或充值等其他业务。二维码以白底黑色显示，由 ZXing 原样编码服务端字符串；请求签名兼容学校网页的 MD5、SM3 与原始 SM2 协议，使用的固定协议参数来自学校公开前端脚本，不是用户凭据。

一码通页面位于前台时将当前窗口亮度设为最高，切换页面或进入后台时恢复原亮度，不修改系统亮度设置。

自动刷新间隔读取学校 `getFreshTime` 接口，缺失或非正数时使用网页默认值 300 秒。手动刷新不重置自动周期，并发刷新合并。离开页面或进入后台时停止刷新并清除显示，返回后立即获取新码；刷新失败隐藏旧码，联网后自动重试。业务 token 和二维码只在内存中保存，退出或切换账号会清除并拒绝迟到响应。

新增测试覆盖 SSO 服务授权、门户仍有效时的 SSO 恢复、业务授权重试、官方签名测试向量、二维码解码比对及刷新生命周期。可在连接设备上运行 `CampusCodeUiTest` 验证页面状态和深色主题扫码。完整真实取码验证需要先安装 Debug APK 和测试 APK，然后运行：

```sh
python3 tools/live-auth-smoke.py --serial DEVICE_SERIAL --campus-code
```

该测试使用独立的内存认证存储，检查 SSO 授权不会再次提交密码、页面二维码解码与服务端原文一致、手动刷新、一个完整自动刷新周期、页面隐藏/返回及退出清理。等待时间由学校刷新间隔决定（2026-09-06 实测为 180 秒）。真实账号仅测试成功路径，错误密码及故障恢复用模拟服务验证。

## 课表

原生周课表支持左右滑周、右上角切换学期和下拉刷新。每次进入默认显示当前学期的当前周；未开学时显示假期倒计时，向左滑进入第一周；查看已经结束的学期默认打开第一周。校历和学期编码由学校提供，支持跨年，不按自然月份猜测开学日期。

打开课表先显示本地缓存，再在后台静默更新，不显示下拉刷新动画；更新期间可以继续浏览，完成后保留正在查看的周次。后台网络失败保留缓存，不弹出错误；手动下拉刷新仍显示进度及失败提示。首次没有缓存时显示加载状态，离线时使用最近成功保存的数据，重启应用后仍可读取。

本周课程使用稳定配色，非本周和已结课课程使用灰色。同一课程/教学班的全部排课合并判断结课；总览重叠时优先显示本周课，其次显示距离浏览周最近的未结课安排，最后才显示已结课安排。部分重叠只遮挡交集，不扩大课程的真实时间范围。详情右下角可切换同时间段的其他排课，即使它们在总览中被隐藏。

显示非本周课程、教师和地点默认开启，已结课课程和周末默认关闭。课程详情总是显示完整周次、节次、校区、地点、教师及学分，不受这五个开关影响。无法定位到网格的课程与实践安排显示在“其他安排”。独立实验模块、调休公告整合、手动编辑、导出及提醒尚未实现。

按课程校区分别获取学校作息。时间轴默认选择当前学期课程最多的校区，可在课表设置中手动调整，并按账号、学期记忆。调整时间轴不筛选课程；详情始终使用课程所属校区的作息。作息缺失时显示“时间暂不可用”，不会套用其他校区时间。

教务授权复用现有 SSO，保留学校注册的 HTTP `service` 标识，实际回调通过 HTTPS 传输。教务业务 Cookie 只保存在内存；最近成功的课表、校历和作息使用独立 Android Keystore AES-GCM 密钥保存在 `noBackupFilesDir`，按账号与学期隔离。失败保留缓存，退出/换号清理并拒绝迟到响应。HTTP 901、权限拒绝、未开放、真正空课表分别处理。

设备测试：

```sh
adb shell am instrument -w -e class moe.nepnep.hduhelper.TimetableUiTest \
  moe.nepnep.hduhelper.test/androidx.test.runner.AndroidJUnitRunner
```

真实验证（先安装 Debug APK 和测试 APK）：

```sh
python3 tools/live-auth-smoke.py --serial DEVICE_SERIAL --timetable
```

该测试用独立内存会话读取真实课表，检查 SSO 不重复提交密码、当前与往期学期、校历作息、课程详情、浅深主题、刷新、离线恢复及退出清理。2026-09-07 已通过真实账号验证；本次真实数据涉及一个校区，另一套不同作息通过模拟接口、规则测试和设备详情测试验证。测试不会覆盖手机中的原有登录资料。合成画面测试会在应用缓存目录生成 `timetable-*.png` 供本地布局核对，不含真实课程信息。
