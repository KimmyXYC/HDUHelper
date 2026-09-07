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

可通过 instrumentation 参数选择 `ProfileUiTest`、`CampusCodeUiTest`、`TimetableUiTest` 或 `TimetableNavigationTest`：

```sh
adb shell am instrument -w -e class moe.nepnep.hduhelper.ProfileUiTest \
  moe.nepnep.hduhelper.test/androidx.test.runner.AndroidJUnitRunner
```

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
