# HDUHelper

基于 Jetpack Compose 与 [MIUIX](https://github.com/compose-miuix-ui/miuix) 的 Android 校园应用。最低支持 Android 13（API 33），编译 SDK 37。

## 当前功能

底栏依次为 **日程、课表、一码通、应用、我的**，按导航顺序提供左右切换动画。前四个页面保留占位内容。

“我的”页支持点击未登录账号卡片进入原生登录页，并提供外观设置、关于应用与退出登录。外观设置为独立二级页面，支持跟随系统、浅色、深色；设置立即生效并持久保存。二级页面从右侧滑入，返回时向右滑出。

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
