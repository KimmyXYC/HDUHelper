# 开发、测试与发布

## 环境与构建

使用 Android Studio 打开项目，在本机 `local.properties` 配置 SDK 路径。项目使用 JDK 25、Gradle Wrapper、Android SDK 37（SDK 包名 `platforms;android-37.0`）和 Build Tools 36.0.0；Java 源码兼容级别为 11，最低 Android API 为 33。依赖版本集中在 `gradle/libs.versions.toml`。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebugAndroidTest
```

产物位于 `app/build/outputs/apk/debug/` 和 `app/build/outputs/apk/androidTest/debug/`。

## 测试

JVM 测试使用 JUnit 4、MockWebServer 和协程测试工具，覆盖认证、并发恢复、加密存储、一码通签名及课表解析等场景。设备测试使用 AndroidX、Compose 和 Espresso。

```sh
./gradlew :app:connectedDebugAndroidTest
python3 tools/test-build-version.py
```

`RuntimeCompatibilityTest` 使用独立临时目录和 Keystore 密钥，离线检查加密存储、序列化及一码通算法，不接触原有账号。

`CampusCodeViewModelTest` 验证一码通的二维码缓存有效期与自动刷新倒计时共用同一个截止时间：离开页面后停止刷新，返回时剩余有效期大于 30 秒则复用缓存，否则重新获取并开始新周期。手动刷新只替换二维码内容，不延长有效期或重置倒计时，切换页面后仍沿用原截止时间。测试还覆盖账号、会话、网络、验证状态变化后的缓存失效；二维码不持久化到磁盘。

可通过 instrumentation 参数选择 `ProfileUiTest`、`CampusCodeUiTest`、`TimetableUiTest` 或 `TimetableNavigationTest`：

```sh
adb shell am instrument -w -e class moe.nepnep.hduhelper.ProfileUiTest \
  moe.nepnep.hduhelper.test/androidx.test.runner.AndroidJUnitRunner
```

### 日程回归

`ScheduleUiTest` 用模拟状态验证左右翻日、回到今天按钮、紧凑表单、独立重复/提醒页面及统一时间面板；`AppPullToRefreshTest` 验证日程与课表共用的下拉阻尼和仅在顶部刷新；`ScheduleNavigationTest` 验证真实页面导航和加密存储的增删改。后者只创建带唯一标识的测试日程，并在结束后删除这些记录，不清除账号或其他日程。

```sh
adb shell am instrument -w \
  -e class moe.nepnep.hduhelper.ScheduleUiTest,moe.nepnep.hduhelper.ScheduleNavigationTest \
  moe.nepnep.hduhelper.test/androidx.test.runner.AndroidJUnitRunner
```

`ScheduleReminderDeviceTest` 默认跳过。实体机上先在系统设置中允许通知与精确闹钟，再以 `-e scheduleReminders true` 单独运行该类。测试等待真实闹钟触发，验证通知去重、恢复调度，以及通知的冷启动和热启动详情跳转；测试不自行授予权限，并清理测试日程与通知。若手机限制测试 Activity 从后台启动，需要允许本应用的后台弹出界面，测试后恢复原设置。手机已安装正式签名包时，应用与测试 APK 都必须用相同密钥签署后覆盖安装，不能通过卸载清数据解决签名冲突。

自定义日程按北京时间计算，与校园账号无关，使用独立 Keystore 密钥保存在 `noBackupFilesDir/schedule`。全天日程内部使用不包含结束日的区间，表单显示包含结束日；每月和每年重复遇到不存在的日期时跳过。重复系列保存原始规则和单次例外，整个系列改变开始日期或重复规则时需要确认清除例外。提醒只安排下一次，通知权限关闭时不发送，缺少精确闹钟权限时使用可能延迟的普通闹钟。强行停止应用后需重新打开应用恢复调度；系统/厂商后台限制仍可能影响提醒。

### 课程通知回归

通知设置分别检测自启动、Android 电池优化豁免和小米省电策略，返回设置页时刷新。“电池优化”弹窗提供两个系统入口。小米自启动只读查询 AppOps 10008；省电策略只读查询 PowerKeeper `userTable` 的本包、本用户 `bgControl`，不调用可能修改设置的 `getPowerSaveAppConfigure`。权限不足、缺少记录或未知值显示“无法检测”；支持的系统框架桥接可提供特权只读查询，结果仍为系统真实设置。

“Xposed 后台提醒增强”默认关闭，独立于超级岛。启用需要增加 LSPosed 的 `system` 系统框架作用域并重启设备；现代 API 的 `android` 是普通系统包，不能替代 `system`。API 101 的服务连接由两项功能共用，开关经远程偏好存储同步；系统端仅适配 HyperOS `AlarmManagerServiceStubImpl` 的投递、对齐和 SSRU 限制，检查 PendingIntent 创建包名、UID、目标包和课程/日程 action 后才豁免。所有必需签名匹配且真实通道确认后才报告就绪。关闭后恢复原始判断，不修改全局省电设置，不提供强行停止后的自动拉起，也不持有常驻保活服务。

`BackgroundStatusTest` 覆盖权限映射与提醒身份匹配；`BackgroundDeviceTest` 默认执行只读状态和伪造通道拒绝测试。添加 `-e backgroundEnabled true` 才验证真实 Hook、远程开关、课程/日程闹钟实际命中与无关 action 不受影响；测试使用无效事件令牌，撤销所有测试闹钟并恢复开关。锁屏与系统回收后的真实通知仍需课程、日程回归测试验证。

系统框架模块调试必须使用 `adb install --no-incremental -r APK` 完整安装：真机增量安装的 APK 在早期开机阶段曾被 LSPosed 读取时报 `I/O error`，导致系统框架注入被跳过，而解锁后应用及系统界面模块仍能正常工作。应用 APK 更新后重启设备；仅更新测试 APK 无需重启。

应用会核对系统框架中已加载模块的 APK 路径；覆盖安装后即使旧模块仍响应，也必须显示需要重启。升级前后可分别用 `BackgroundDeviceTest.updatedApkRequiresSystemServerRestart`（参数 `-e backgroundUpdated true`）和 `loadedHostReportsRealPermissionsAndSwitchControlsReminderAlarmExemptions`（参数 `-e backgroundEnabled true`）验证更新提示、配置回执和真实闹钟行为。后者通过状态流等待配置生效，不轮询刷新，并恢复原有开关。

`BackgroundAlarmLifecycleDeviceTest` 用 `-e backgroundLifecycle true -e enhanced false`（或 `true`）运行 `prepareColdLockedReminders`，准备 50 秒后的普通课程/日程提醒并锁屏；结束 instrumentation 后用 `adb shell am kill moe.nepnep.hduhelper` 回收后台进程，等到目标时间后 15 秒，再运行 `verifyPreviouslyDeliveredColdRemindersAndRestore`。它要求通知早于验证启动且在目标时间 10 秒内发布，避免冷启动补发造成假通过。中断时以 `-e backgroundRecovery true` 运行 `restoreInterruptedColdReminderProbe`，按唯一标识清理测试数据并恢复设置。不要用强行停止替代进程回收。

`CampusCodeRecoveryTest` 覆盖网络/认证恢复顺序、有限重试、合并和取消。`CampusCodeNetworkDeviceTest` 仅在 `-e campusNetwork true` 时运行：复用已有登录状态，停留一码通页面关闭再恢复网络，检查二维码自行恢复；Wi-Fi 和移动数据恢复为测试前状态。真实二维码仅在受保护窗口及内存中使用，不截图、不输出认证信息。

“我的 → 通知设置”默认仅开启上课提醒（提前 10 分钟），下课提醒默认提前 1 分钟但关闭，默认使用普通通知。两种提前量均通过输入框填写 0–30 的整数分钟，0 表示准点提醒。连续节次按一整段处理，使用课程所属校区的作息时间；提醒只读取当前账号、当前学期的缓存，离线无需登录，浏览其他学期和课表显示过滤不改变提醒。

普通模式在配置时间提醒一次。安装包内置现代 Xposed API 101 模块：在 LSPosed 启用杭电助手，勾选 `com.android.systemui`、`miui.systemui.plugin` 并重启作用域，再返回“通知设置”。仅 HyperOS 3 及以上、模块激活且作用域授权时显示“开启课程表超级岛”，默认关闭；授权后 Hook 尚未加载时显示禁用提示。开关打开且能力检查通过时，用小米原生模板 9 替代普通提醒；否则回退普通通知，不重复响铃。已移除 Android 标准 Live Updates 及其权限，旧开关不会自动迁移为开启超级岛。

超级岛仅覆盖已开启的上课/下课提醒窗口：课前或下课前倒计时，到目标时刻转为正计时，60 秒后移除。展开态显示课程名、起止时间、教室和操作按钮；胶囊显示教室及目标时间。窗口内的有界 `specialUse` 前台服务协助边界切换，退出后释放唤醒锁。取消记录及提醒去重仍使用不参与备份的散列事件日志。普通通知的精确闹钟、后台运行与通知权限要求保持不变。

“上课静音”只改变铃声/通知模式，不改变媒体、闹钟或主动开启勿扰。SystemUI 内的模块保存原声音模式及不含课程内容的到期记录，实际下课时恢复；超级岛消失或杭电助手进程退出不移除恢复任务，不另发静音控制通知。用户通过系统改变声音模式会取消模块接管；原本静音不接管。重叠课程按各自结束时间合并恢复任务。调用方校验限制了跨进程注册和声音操作，权限 Hook 仅放行杭电助手包名。参考项目未提供根目录许可证，未直接搬运其实现，模板按小米公开协议独立构建。

“测试通知”不依赖登录或课表：超级岛关闭或不可用时发送普通通知；可用并开启时模拟 60 秒后上课、课程持续 60 秒。重复测试替换旧测试通知，不写入课表。`NotificationPreviewDeviceTest` 使用 `-e notificationPreview true` 验证完整显示周期；`IslandDeviceTest` 的 `-e islandEnabled true` 验证真实模块通道，`-e islandMute true` 临时改变铃声模式并验证自动恢复，原本静音时跳过声音修改。

`CourseReminderRulesTest`、`CourseReminderJournalTest` 和 `ScheduleViewModelTest` 覆盖计时边界、教学周、校区、去重、取消、设置及账号变化、冷启动闹钟时序和课程详情跳转。`NotificationSettingsUiTest` 验证深浅色设置界面和独立偏好存储，`CourseNotificationTest` 验证真实 Android 通知模板的倒计时/正计时、自动清理时限、小米模板数据以及不再请求标准实时通知，不实际发送通知或修改课表。`TimetableNavigationTest` 还验证通知设置入口和返回导航，该用例在通知权限未授权时跳过以避免无人值守操作系统权限弹窗。

```sh
adb shell am instrument -w \
  -e class moe.nepnep.hduhelper.NotificationSettingsUiTest,moe.nepnep.hduhelper.CourseNotificationTest,moe.nepnep.hduhelper.TimetableNavigationTest \
  moe.nepnep.hduhelper.test/androidx.test.runner.AndroidJUnitRunner
```

`CourseReminderDeviceTest` 通过 `-e courseReminders true` 单独启用，要求模块已就绪、通知和精确闹钟权限已开启、当前账号已有当前学期缓存，且测试窗口内没有真实课程提醒。它在缓存中加入带唯一标识的短时测试课程，等待真实闹钟、倒计时/正计时切换及 60 秒清理，验证关闭本次提醒以及与测试通知并存时互不影响，并用已有课程检查冷/热启动详情跳转；结束时只移除测试课程、校区和临时周次，恢复原通知设置。运行期间不要刷新课表或切换账号，避免替换测试缓存。测试不自行授予权限。测试变更前会保存恢复记录；若进程被中断导致清理未完成，可解锁手机后以 `-e courseReminderRecovery true` 运行同类的 `restoreAnInterruptedDeviceTest` 方法恢复，恢复过程只删除记录中的测试对象。可在计时阶段熄屏验证锁屏后台行为，再分别检查超级岛开启与关闭时的显示；模板和规则测试不能替代这些真机验证。

真实学校服务测试默认跳过，必须手动选择并先安装 Debug 和测试 APK：

```sh
python3 tools/live-auth-smoke.py --serial DEVICE_SERIAL
python3 tools/live-auth-smoke.py --serial DEVICE_SERIAL --campus-code
python3 tools/live-auth-smoke.py --serial DEVICE_SERIAL --timetable
```

脚本从交互终端读取密码，经临时 ADB 转发传入设备，使用独立测试存储并清理测试会话。不要把真实账号密码放入文件、参数、环境变量或日志。错误密码和故障场景使用模拟服务，避免锁定真实账号。一码通测试会等待一个完整刷新周期；课表测试覆盖缓存、详情与退出清理。官方额外验证由学校触发，不能视为已完整自动验证。

官方 WebView 的公开布局检查不输入账号密码：

```sh
adb shell am instrument -w -e class moe.nepnep.hduhelper.OfficialWebViewTest \
  -e officialWebViewTest true \
  moe.nepnep.hduhelper.test/androidx.test.runner.AndroidJUnitRunner
```

### 优化包离线设备回归

在独立模拟器上运行以下构建，生成优化后的应用与测试 APK：

```sh
./gradlew --no-configuration-cache -I tools/release-test.init.gradle \
  :app:assembleRelease :app:assembleReleaseAndroidTest
```

按下文签名应用 APK，再用同一个密钥通过 `apksigner sign --ks ... --ks-pass file:...` 签名 `app/build/outputs/apk/androidTest/release/` 下的测试 APK。安装两者后，使用 instrumentation 参数选择 `moe.nepnep.hduhelper.RuntimeCompatibilityTest`。这两个离线测试覆盖实际 Android Keystore 和压缩后的存储/密码算法路径。测试专用规则只忽略 Error Prone 注解中 Android 不提供的 JDK 编译器枚举，不改变生产保留规则。

## 版本与 CI

`app/build.gradle.kts` 的 `defaultConfig` 是正式版本的唯一来源。每次正式发布更新 `versionName` 并递增 `versionCode`，然后提交。

CI 在分支推送和 PR 时构建 Debug，PR 检出源提交。`tools/build-version.py ci` 从实际 HEAD 生成 `v版本.7位SHA`；Gradle 属性只覆盖 Debug 版本：

```sh
./gradlew -PciVersionName="$(python3 tools/build-version.py ci)" :app:assembleDebug
```

提供此属性时 Debug 产物为未签名 APK，交由独立任务签名；本机未提供时使用基础版本与本机 Debug 签名。CI 执行 JVM 测试、Lint、Debug 和测试 APK 构建，上传测试包与报告。CI 构建任务不访问签名 Secrets；独立签名任务使用与正式版相同的密钥。Fork PR 只构建检查、不生成签名包。CI 不测试真实账号。

## Release 优化与签名

Release 使用 AGP 9.3 的 `optimization { enable = true }`，启用 R8 代码优化、混淆和资源缩减。应用规则放在 `app/src/main/keepRules/rules.keep`；优先使用库自带规则，根据诊断仅保留确有需要的入口。修改规则后验证序列化、Keystore、一码通算法和 WebView，不通过整包保留或全局忽略告警掩盖问题。

正式签名为 PKCS12 密钥库，RSA 3072 位、别名 `hduhelper`。维护者本机将 `release.p12` 和 `release.password` 保存在仓库外的 `~/.local/share/hduhelper/signing/`，目录权限 `700`，文件权限 `600`。密钥和密码需要安全备份，后续更新继续使用同一签名；不得提交到 Git。

```sh
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
python3 tools/sign-release.py \
  --apk app/build/outputs/apk/release/app-release-unsigned.apk \
  --output dist/HDUHelper-v1.0.0.apk \
  --signing-dir "$HOME/.local/share/hduhelper/signing" \
  --build-tools "$ANDROID_HOME/build-tools/36.0.0" --tag v1.0.0
```

脚本检查包名、版本和构建类型；正式版不可调试，CI 使用 `--ci-version` 核对版本与实际 HEAD 且必须可调试。随后执行 zipalign、apksigner 签名及验证；密码仅通过文件读取，已存在的输出不会覆盖。签名后不要重新压缩或修改 APK。R8 mapping 位于 `app/build/outputs/mapping/release/`，发布流程保存为 90 天的诊断附件；需要长期排查时及时下载归档。

## 发布流程

仓库 Secrets：

| 名称 | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `release.p12` 的单行 Base64 |
| `ANDROID_KEYSTORE_PASSWORD` | `release.password` 中的随机十六进制密码，密钥密码与之相同 |

通过标准输入向 `gh secret set` 提供值，避免把密码放进命令参数或环境变量。Actions 从 runner 临时脚本写入权限受限文件，任务结束始终清理。

1. 更新正式版本，运行本机检查，提交并推送，等待 CI 成功。
2. 可在 `docs/releases/v版本.md` 撰写发布说明；缺少时 GitHub 自动生成。
3. 创建并推送附注标签，例如 `git tag -a v1.0.0 -m 'Release v1.0.0'` 与 `git push origin v1.0.0`。
4. Release 工作流仅接受 `v数字.数字.数字` 标签推送，并核对项目版本。分支、手动操作和预发布标签不会触发。
5. 测试、优化构建成功后，独立任务签名并上传 APK 与 `SHA256SUMS`，附件齐全才将草稿转为正式版。已存在的 Release 不会被覆盖；失败留下草稿时先检查原因与附件，再由维护者处理。
6. 下载正式附件，用 `sha256sum -c SHA256SUMS` 和 `apksigner verify --verbose --print-certs` 复核。
