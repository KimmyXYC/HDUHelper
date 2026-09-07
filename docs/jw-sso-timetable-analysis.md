# 杭电新正方教务系统：SSO 登录与课表解析分析

调研日期：2026-09-07（Asia/Shanghai）。范围：用户授权账号的登录、个人课表、菜单及相关校历、作息查询。本文区分实测结果、公开源码和实现建议；没有实现 App 课表页面。

## 1. 结论

可以采用 **已有杭电 SSO → 教务服务授权 → 教务 Cookie 会话 → JSON 查询 → 原生解析** 的方式实现课表，不必解析网页表格，也不必重新实现正方本地账号的 RSA 登录。

- 官网当前主查询为 `POST /jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151`。旧实现常见的 `xskbcx_cxXsKb.html` 本轮也能返回课表，但应优先跟随当前官网。
- `gnmkdm` 是功能模块代码。`N2151` 来自“信息查询 → 个人课表查询”的学校菜单配置，不是动态 token，也不是由学号、时间或密码算出的参数。
- CAS 使用的服务标识是 **`http://newjw.hdu.edu.cn/sso/driot4login`**。实测保留这个标识、将教务回调的实际传输升级为 HTTPS 能成功；直接把 CAS `service` 改成 HTTPS，虽然取得了 ticket，但教务回调返回 HTTP 500。
- 课表 JSON、周次日期和各节作息都有可用接口。当前学期为 `xnm=2026, xqm=3`，学期第一周从 2026-09-14 开始；这些值应动态读取。
- 普通课、实践/其他课、教学环节、实验课并非都位于同一个数组。只读取 `kbList` 会漏掉页面上的其他安排。

## 2. 实测方法与边界

使用浏览器完成一次正确凭据登录，然后读取官网实际加载的脚本、菜单和查询请求。另用仅继承 SSO Cookie 的独立浏览器请求上下文，验证不再次提交密码即可建立新的教务会话；该上下文内的后续请求由 HTTP 客户端直接完成，没有运行教务页面 JavaScript。

对照查询只涉及该账号自己的数据：当前及两个既往学期、正确/缺失/无效功能编号、官网菜单中的另一个个人课表入口、无 Cookie 查询。没有修改选课、成绩或个人资料，也没有通过猜测其他账号或功能编号访问数据。

文档不保存真实账号、密码、姓名、课程名称、教师、教室、Cookie 值、ticket、`uid`、`verify` 或完整个人课表。下文课表数量是调研样本统计；JSON 示例中的课程和标识为虚构值。

| 验证项目 | 本轮结果 |
| --- | --- |
| 用户提供入口 → SSO → 教务 | 成功进入本科教学管理服务平台 |
| 仅 SSO Cookie → 新建教务会话 → 原生 HTTP 查询 | 成功，返回 15 条 `kbList` |
| 2026–2027 学年第 1 学期 | `kbList=15`，`sjkList=2` |
| 2025–2026 学年第 2 学期 | `kbList=20`，`sjkList=1`；包含单双周与分段周次 |
| 2025–2026 学年第 1 学期 | `kbList=20`，`sjkList=2`；包含单周、单独某周及 `1-12` 节 |
| 当前学期作息 | 12 节，上午 5 节、下午 4 节、晚上 3 节 |
| 当前学期周次日期 | 返回 23 个周区间，第 1 周为 2026-09-14～09-20 |
| 按周查询第 1 周 | 返回 12 条 `kbList`，另有一周 7 天的日期映射 |
| 无 Cookie，不带 AJAX 头 | HTTP 302，指向正方登录页 |
| 无 Cookie，带 AJAX 头 | HTTP **901** |

数量指原始排课记录数，不等于课程门数；同一门课可以有多个时间、地点或周次安排。

## 3. SSO 登录与会话建立

### 3.1 官网实际跳转链

访问 [教务 SSO 入口](https://newjw.hdu.edu.cn/sso/driot4login) 后，初始重定向链包含旧 CAS 域名兼容入口：

```text
GET https://newjw.hdu.edu.cn/sso/driot4login
  → https://cas.hdu.edu.cn/cas/login?service=<URL 编码的服务标识>
  → https://sso.hdu.edu.cn/login?service=<相同服务标识>

服务标识 = http://newjw.hdu.edu.cn/sso/driot4login
```

无有效 SSO 会话时，最后一步显示统一身份认证页面。有会话或完成登录后：

| 步骤 | 服务端返回的目标/行为 | 客户端处理 |
| --- | --- | --- |
| 1 | `http://newjw.hdu.edu.cn/sso/driot4login?ticket=<动态值>` | 在发送请求前升级该目标为 HTTPS，保留 ticket |
| 2 | `/sso/driot4login;jsessionid=<动态值>`，设置 `/sso` 的 `JSESSIONID` | 按 Cookie 规范保存；继续处理服务器返回的路径参数 |
| 3 | `/jwglxt/ticketlogin?uid=<动态值>&timestamp=<动态值>&verify=<动态值>` | 原样消费服务器产生的跳转，不自行生成或复用这些参数 |
| 4 | 设置 `/jwglxt` 的 `JSESSIONID`，跳转 `/jwglxt/xtgl/login_slogin.html` | 接受此中间跳转，不能仅凭路径包含 `login` 就判失败 |
| 5 | `/jwglxt/xtgl/index_initMenu.html?jsdm=xs&_t=<动态值>&echarts=1` | 首页返回 HTTP 200；继续用个人课表响应确认身份和业务会话 |

步骤 2、3 的服务器 `Location` 在本轮也使用 HTTP；实测逐跳升级教务域名的传输后完整流程成功。服务端是否总会使用 `;jsessionid` 属于部署行为，不应把每一次跳转都硬编码成固定次数。

### 3.2 服务标识与实际传输协议必须分开

| 方案 | 实测结果 |
| --- | --- |
| `service=http://newjw.../sso/driot4login`，回调和后续教务 GET 通过 HTTPS 发送 | 成功，最终课表 JSON 有有效 `xsxx` 和 15 条排课 |
| `service=https://newjw.../sso/driot4login` | CAS 发出 ticket，但教务回调 HTTP 500，标题“系统维护页面”；没有建立可用教务会话 |

CAS ticket 与签发时的服务标识绑定，且只允许一次验证尝试，这是 [CAS 协议的规定](https://apereo.github.io/cas/development/protocol/CAS-Protocol-Specification.html#311-service-ticket-properties)。据此推断，学校桥接端可能按 HTTP 服务标识校验；具体后端配置不可见，因此 **HTTP/HTTPS 标识不匹配是有依据的解释，不是已经读取到的服务端实现**。

App 接入建议：将 `casServiceId` 与 `callbackTransportUrl` 分别建模。只对已经确认的教务同源授权路径做 HTTPS 升级，使用规范 URL 解析并校验主机、端口、路径；不全局允许明文流量，不把账号密码转发到教务域名。失败后重新走服务授权获取新 ticket，不能重放已消费的 ticket。

### 3.3 凭据登录可复用现有杭电 CAS 实现

当前仓库 [HduAuthApi.kt](../app/src/main/java/moe/nepnep/hduhelper/data/auth/HduAuthApi.kt) 已有以下杭电统一认证逻辑；本轮浏览器登录仍使用这一类表单流程，相关检查接口和 `croypto` 字段在当前官网脚本中仍存在：

1. 每次重新获取登录页面中的 `login-page-flowkey`（execution）和 `login-croypto`。不要保存并重用旧 execution。
2. 调用 `/api/protected/user/findCaptchaCount/<账号>` 检查额外验证要求。现有实现生成动态 `Csrf-Key`、`Csrf-Value`；不要从公开辅助脚本复制固定示例请求头。
3. 现有密码加密实现：Base64 解码 `croypto` 得到 AES 密钥，对 UTF-8 密码使用 AES/ECB、PKCS7 等价填充，然后 Base64 编码密文。Java/JCE 使用的名称是 `AES/ECB/PKCS5Padding`。
4. 向当前 CAS 登录 URL 提交表单：`username`、加密 `password`、`type=UsernamePassword`、`_eventId=submit`、`execution`、`croypto`、`geolocation`、`captcha_code`、`captcha_payload`。当前仓库在无需验证码时将 `{}` 加密为 `captcha_payload`。
5. 需要验证码、短信或其他交互时进入已有官方 WebView 验证流程；不自动解决挑战，不重复提交密码来探测行为。

上面加密与表单细节的实现依据是当前仓库认证代码及学校前端；本轮没有另写一个原生密码登录客户端，也没有触发验证码场景。教务接入所需的新工作主要是**服务授权与后续桥接**，不是复制一份凭据登录逻辑。

### 3.4 Cookie 和成功判定

本轮观察到的会话 Cookie 名称/作用域：

| 域名 | 路径 | 名称 | 说明 |
| --- | --- | --- | --- |
| `sso.hdu.edu.cn` | `/` | `SESSION`、`SOURCEID_TGC`、`rg_objectid` | 由标准 CookieJar 管理的 SSO 状态 |
| `newjw.hdu.edu.cn` | `/` | `route` | 部署使用的路由 Cookie，应随响应保存，不假定固定值 |
| `newjw.hdu.edu.cn` | `/sso` | `JSESSIONID` | SSO 桥接应用会话 |
| `newjw.hdu.edu.cn` | `/jwglxt` | `JSESSIONID` | 正方教务应用会话 |

两个同名 `JSESSIONID` 必须以 **name + domain + path** 区分，不能放进仅以名称为键的字典。部分学校 Cookie 未标记 `Secure`，不代表客户端必须使用 HTTP；本轮 HTTPS 请求能够正常携带并使用它们。

SSO ticket 与 `ticketlogin` 的 `uid/timestamp/verify` 都是短暂的授权材料，不是课表请求的长期 token。它们以及路径里的 `;jsessionid` 都应从请求日志、异常信息和分析文件中脱敏。

成功不能仅看“跳回 newjw”“有 JSESSIONID”或“HTTP 200”。应确认个人课表返回合法对象，`xsxx.XH` 与当前账号一致，学年学期匹配，并且没有业务限制。门户 `loginInfo.rst` 成功只能证明门户会话，不能替代这一步。

## 4. `N2151` 从哪里来，为什么不能随意替换

实测 `POST /jwglxt/xtgl/index_cxMenuList.html` 的菜单数据包含：

| 菜单路径 | `gnmkdm` | 菜单 URL（相对 `/jwglxt`） |
| --- | --- | --- |
| 信息查询 → 个人课表查询 | **`N2151`** | `/kbcx/xskbcx_cxXskbcxIndex.html` |
| 选课 → 个人课表查询 | `N253508` | `/kbcx/xskbcx_cxXskbcxIndex.html` |
| 学生课表查询（按周次） | `N2154` | `/kbcx/xskbcxZccx_cxXskbcxIndex.html` |

首页还直接包含 `clickMenu('N2151', '/kbcx/xskbcx_cxXskbcxIndex.html', '个人课表查询', ...)`。菜单脚本把功能编号作为 `gnmkdm`，并追加 `layout=default`；`N2151` 的来源因此可以在学校页面和菜单响应中直接核对。

课表页设置隐藏的 `gnmkdmKey=N2151`，加载对应的 `N2151_zh_CN.js` 本地化资源，还使用 `JW_N2151_XSKBCX` 等业务配置标识。学校 [通用前端脚本](https://newjw.hdu.edu.cn/jwglxt/js/jquery.zftal.contact-min.js) 的 `$.getURL` 从 `gnmkdmKey` 自动给请求补上 `gnmkdm`。所以在 `xskbcx.js` 里看起来没有这个参数的 AJAX，实际请求仍会带上它。

本轮对照结果也说明，应区分页面配置与数据接口：

| 参数 | 页面 GET | 当前账号个人课表 POST |
| --- | --- | --- |
| `N2151` | 正常，带“个人课表查询”标题及对应资源 | 返回 15 条 |
| 缺失 `gnmkdm` | HTTP 500，“系统维护页面” | 本轮仍返回 15 条 |
| `INVALID` | HTTP 200，但功能标题为空、缺少 `N2151` 对应资源 | 本轮仍返回 15 条 |
| 官网菜单中的 `N253508` | 返回课表表单；页面配置与 `N2151` 有差别 | 本轮返回 15 条 |

因此，“其他值不能正常打开页面”不能直接解释为“只有 `N2151` 才能通过底层数据权限校验”。本轮验证的是同一账号的个人课表接口，不能据此推断其他接口的权限实现。实际开发保持学校“信息查询”菜单给出的 **`N2151`** 即可，不需要猜号或计算编号，也不应依赖无效编号仍能返回数据的行为。

## 5. 课表、作息与校历接口

以下接口使用教务 Cookie。复现网页查询时采用表单编码，携带 `X-Requested-With: XMLHttpRequest` 和正确的页面 Referer；这些头不是额外的登录凭据。

### 5.1 学期选择与整学期课表

先 GET [个人课表页面](https://newjw.hdu.edu.cn/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default)，解析 `select#xnm`、`select#xqm` 的选项和值。

| 字段 | 语义 | 本轮值/映射 |
| --- | --- | --- |
| `xnm` | 学年起始年份 | `2026` 表示 2026–2027 学年 |
| `xqm` | 学期内部编码 | 第 1 学期 `3`，第 2 学期 `12`，第 3 学期 `16` |

不要用自然月份猜学期，不要写 `term * term * 3`：这个旧开源实现的公式只碰巧适用于前两个学期，第 3 学期会错误地生成 `27`。`16` 已在杭电下拉框确认，但本轮未查询第 3 学期课程。

主查询的实际请求为：

```http
POST /jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151 HTTP/1.1
Host: newjw.hdu.edu.cn
Content-Type: application/x-www-form-urlencoded
X-Requested-With: XMLHttpRequest
Referer: https://newjw.hdu.edu.cn/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default

xnm=2026&xqm=3&kzlx=ck&xsdm=&kclbdm=&kclxdm=
```

Cookie 由 CookieJar 注入，此示例故意省略。参数来自学校 [xskbcx.js 的 `paramMap` / `cxKbContent`](https://newjw.hdu.edu.cn/jwglxt/js/comp/jwglxt/pkgl/kbcx/xskbcx.js)：`kzlx=ck` 表示查看；其余三个是页面筛选值，本轮为空。无需传其他学号、分页参数、Authorization、应用 JWT 或签名。`validate` 只在官网出现额外验证码输入时加入，不能自行伪造。

主 JSON 是整学期数据，本轮没有分页；对象里的 `queryModel` 等通用字段不代表 `kbList` 需要按 15 条翻页。两个既往学期都一次返回了 20 条。

### 5.2 JSON 字段与模型

以下为**虚构的最小示例**，仅演示已确认字段结构：

```json
{
  "xsxx": { "XH": "<当前账号>", "XNM": "2026", "XQM": "3" },
  "xkkg": true,
  "jfckbkg": true,
  "xnxqsfkz": "false",
  "kbList": [
    {
      "kch_id": "course-demo",
      "kch": "DEMO001",
      "jxb_id": "class-demo",
      "kcmc": "示例课程",
      "xm": "示例教师",
      "cdmc": "示例教室",
      "xqh_id": "1",
      "xqmc": "示例校区",
      "xqj": "1",
      "jcs": "3-5",
      "jc": "3-5节",
      "zcd": "1-6周,9-17周"
    }
  ],
  "sjkList": [],
  "jxhjkcList": []
}
```

| 字段 | 用途 | 处理注意点 |
| --- | --- | --- |
| `xsxx.XH` | 响应归属账号 | 与当前认证账号比较，拒绝错账号响应 |
| `xsxx.XNM/XQM` | 响应学年学期 | 校验后再替换对应缓存 |
| `kbList[].kch_id/kch` | 课程标识/课程号 | 与排课记录 ID 区分 |
| `jxb_id/jxbmc` | 教学班标识/名称 | 同一课程可能有多个教学班 |
| `kcmc` | 课程名 | 原生按文本显示，不执行 HTML |
| `xm` | 教师显示文本 | 不要误认成 `xsxx.XM` 学生姓名；教师可能多人 |
| `cdmc`、`cd_id` | 上课地点/场地标识 | 空地点允许存在，不能丢掉整条课 |
| `xqh_id`、`xqmc` | 校区标识/名称 | 用于读取对应校区作息，不能把 `xqmc` 当星期 |
| `xqj` | 星期数 | 接受字符串或数字，1=周一，7=周日 |
| `jcs` | 节次表达式 | 优先用于解析，如 `3-5` |
| `jc` | 节次显示文本 | 如 `3-5节`，可作兼容回退与原文显示 |
| `zcd` | 上课周次表达式 | 解析成明确的周集合，保留原文 |
| `xf`、`kcxz`、`khfsmc` 等 | 学分、性质、考核方式 | 详情可选字段，不参与网格定位 |
| `jxbsftkbj` | 调课标记 | 不能只凭这个字段还原调课日期或取消原课 |
| `sjkList` | 实践或其他课程 | 字段含 `sfsjk`、`kcmc`、`jsxm`、`qsjsz`、`sjkcgs`、`qtkcgs` |
| `jxhjkcList` | 教学环节课程 | 本轮为空；保留扩展入口，尚无真实非空样本 |
| `rqazcList` | 周内日期映射 | 整学期查询本轮为空；按周查询有值 |

大量 `date/dateDigit/day/month/year`、`pageable/queryModel/userModel` 是共用模型附带字段。本轮多种业务对象里的 `dateDigitSeparator` 都是查询当日 `2026-9-7`，**不能把它当作课程上课日期或开学日期**。

### 5.3 周次与节次解析规则

采用“每条排课记录 → 星期 + 节次集合 + 周集合”的模型。至少保留以下三个阶段：格式规范化、分段解析、范围校验。

1. 规范化全角逗号、括号及空白，按逗号/顿号拆分周次片段。
2. 对每一片段独立读取单周数或闭区间，再独立应用该片段的“单/双”条件。
3. 合并为去重、升序的正整数周集合；不识别的片段产生解析警告，不能静默扩展成整个学期。
4. 节次同样支持单节、闭区间、分段。优先使用 `jcs`，缺失时才解析 `jc`，并校验校区作息是否包含这些节次。
5. 分段节次拆成连续的显示区间。例如合成兼容样本 `1-2,4` 应是两个区间，不是 `1-4`。

真实返回的周次表达式及应得结果：

| 表达式 | 周集合 |
| --- | --- |
| `1-6周,9-17周` | 1～6、9～17，不包含 7、8 |
| `2-6周(双),12-16周(双)` | 2、4、6、12、14、16 |
| `1-3周(单),7周,11-15周(单)` | 1、3、7、11、13、15 |
| `1周,7-15周(单)` | 1、7、9、11、13、15 |
| `5周,9周` | 5、9 |
| `15周` | 15 |

不能先找整个字符串中是否有“单/双”，再把这一条件施加到所有片段；条件属于各自片段。实际观察的节次包括 `3-5`、`6-9` 和 **`1-12`**，不能假定所有课程都是两节，也不能固定只支持十节。单节与不连续节次属于建议补充的兼容测试，本轮账号中未出现这两种节次样本。

同一课程不同教师、教室、星期、节次或周次应保留为不同排课。去重至少基于课程/教学班、星期、节次、周集合、教师及地点，不能仅按课程名去重。默认保留服务端各条记录；只有显示属性完全一致时，才考虑合并周集合。

### 5.4 实践课、其他课与额外实验表

`sjkList` 不一定有星期和节次。学校页面根据 `sfsjk` 区分实践与其他课程，并使用 `sjkcgs/qtkcgs` 展示。无法可靠定位到网格的安排应显示在“实践/其他安排”列表，保留原始时间说明；不要为它们编造周一第 1 节，也不要因为缺少 `jcs` 就删除。

主页面另外会读取以下表格接口：

| 接口（相对 `/jwglxt`） | 本轮结果 |
| --- | --- |
| `/xssygl/sykbcx_cxSykbcxxsIndex.html?doType=query&gnmkdm=N2151` | 正常分页对象，`items=[]`、`totalResult=0` |
| `/jssygl/sykbcx_cxKfxSykbcxIndex.html?doType=query&gnmkdm=N704551` | 带 AJAX 头时 HTTP 200，JSON **字符串** `"没有访问权限!"` |

前者的网页请求含 `xnm/xqm/kzlx`、筛选值、`queryModel.showCount=15`、`queryModel.currentPage=1`、排序字段等。这里才是分页数据，不能沿用主课表的“整包无分页”假设。开放性实验的编号来自官网脚本；本轮只复现了官网请求，没有更换权限参数尝试绕过拒绝。

因此，第一版应完整处理 `kbList` 与 `sjkList`；若要求覆盖页面全部实验安排，需要独立适配实验表，并补充有权限且非空的数据样本。额外模块无权限不应使已经成功获取的普通课表失败。

### 5.5 学校作息

两接口均采用 POST，查询参数 `gnmkdm=N2151`，表单为 `xnm=2026&xqm=3&xqh_id=1`。`xqh_id` 来自排课记录，不能假定所有人都在 `1` 校区。

- `/jwglxt/kbcx/xskbcx_cxRsd.html`：日时段结构，`rsdmc` 为上午/下午/晚上，`rsdzjs` 为对应节数。
- `/jwglxt/kbcx/xskbcx_cxRjc.html`：逐节作息，`jcmc` 为节次，`qssj/jssj` 为开始/结束时间。不要把 `xsdj`（大节分组）当作逐节序号。

当前样本校区作息如下，供核对，不作为永久硬编码配置：

| 节次 | 开始 | 结束 | 节次 | 开始 | 结束 |
| --- | --- | --- | --- | --- | --- |
| 1 | 08:05 | 08:50 | 7 | 14:20 | 15:05 |
| 2 | 08:55 | 09:40 | 8 | 15:15 | 16:00 |
| 3 | 10:00 | 10:45 | 9 | 16:05 | 16:50 |
| 4 | 10:50 | 11:35 | 10 | 18:30 | 19:15 |
| 5 | 11:40 | 12:25 | 11 | 19:20 | 20:05 |
| 6 | 13:30 | 14:15 | 12 | 20:10 | 20:55 |

作息缓存按“学年 + 学期 + 校区”区分。某校区返回空作息时仍可显示节次文字，不能把别的校区作息冒充为已确认时间。

### 5.6 学期日期与按周查询

学校 [按周课表脚本](https://newjw.hdu.edu.cn/jwglxt/js/comp/jwglxt/pkgl/cxkbazc/cxXskbcx.js) 提供了比猜测开学日期更直接的数据源：

```http
POST /jwglxt/kbcx/xskbcxZccx_cxZcByXnxq.html?gnmkdm=N2154
Content-Type: application/x-www-form-urlencoded
X-Requested-With: XMLHttpRequest

xnm=2026&xqm=3
```

响应是数组，关键字段为 `zs`（周次）、`rq`（起止日期）、`zcrq/zcrq2`（显示文本）。本轮首项的可公开校历字段为：

```json
{
  "zs": "1",
  "rq": "2026-09-14/2026-09-20",
  "zcrq": "1(2026-09-14至2026-09-20)"
}
```

一共返回 23 周，不代表课程一定上满 23 周。应以周区间定位当前教学周，以各课 `zcd` 决定该周是否有课。2026-09-07 早于第 1 周，页面隐藏字段 `dqzc_hide` 却为 `1`；这证明**默认选中的周次不能直接当作当前实际教学周**。开学前/学期后应有明确状态。

按周查询可进一步取得日期映射：

```http
POST /jwglxt/kbcx/xskbcxMobile_cxXsKb.html?gnmkdm=N2154
Content-Type: application/x-www-form-urlencoded
X-Requested-With: XMLHttpRequest

xnm=2026&xqm=3&zs=1&doType=app&kblx=1&xh=
```

这是官网脚本中的个人查询参数；本轮 `xh` 留空即可查询当前账号，不能替换为其他账号。`kblx=1` 是此请求的按周模式参数；不要混淆为整学期响应中表示网格列数的同名 `kblx`。

第 1 周实测返回 12 条排课，`rqazcList` 给出 `xqj=1..7` 与 `2026-09-14..20` 的对应关系。推荐整学期接口用于离线周课表，周次日期接口用于日历；按周接口作为校验和后续精确日期场景的补充。

节假日调休、临时停课及调课公告是否全部同步进这些接口，本轮没有对应事件样本，不能承诺“已覆盖所有调休”。应保留手动刷新和调整入口，实施前补充实际调课案例验证。

## 6. 失败识别与恢复

| 现象 | 分类及建议 |
| --- | --- |
| 无会话查询 HTTP 901 | 本轮已验证的登录失效标识；恢复教务授权后最多重试一次该只读查询 |
| 查询跳转 `/jwglxt/xtgl/login_slogin.html` 或最终 SSO 登录 HTML | 登录失效；与授权过程中正常路过 `login_slogin.html` 区分 |
| HTTP 200 但响应是 HTML、JSON 字符串或结构不符 | 先识别登录/权限/维护信息，不能直接当成课表对象 |
| `"没有访问权限!"` | 权限错误，不能靠重新提交密码恢复，也不计入密码错误次数 |
| `xsxx` 缺失 | 官网显示无注册信息；还需结合完整响应判断，不应伪造空课表 |
| `xnxqsfkz` 为 `"true"` | 官网限制该学年学期查看；按业务限制显示 |
| `xkkg=false` 或 `jfckbkg=false` | 官网分别有未开放、缴费后查询等分支；不要误认为 JSON 获取成功即课表可用 |
| 合法身份、正常业务状态，各课程列表均为空 | 才显示真正“暂无课程” |
| 超时、断网、5xx、格式变化 | 保留最近成功缓存并显示更新失败，不覆盖为空，也不增加凭据失败计数 |

字段可能为字符串、布尔值、数字或缺失值。解析时明确兼容已知编码，缺失关键字段归类协议异常；不要让 `"false"` 因为非空字符串而被当成 true。

## 7. 接入 HDUHelper 的建议

以下为后续实现方案，不是已经落地的改动。

### 7.1 复用认证仓库，增加受限的教务服务定义

当前 [AuthRepository.kt](../app/src/main/java/moe/nepnep/hduhelper/data/auth/AuthRepository.kt) 已提供共享恢复、`ServiceIdentity`、账号代次检查和失效通知，可以沿用。需要注意现有 [HduAuthApi.kt](../app/src/main/java/moe/nepnep/hduhelper/data/auth/HduAuthApi.kt) 的两个限制：

- `authorizeService()` 只接受既有一码通 service，且回调必须精确匹配同一 URL。
- `AuthEndpoints.accepts()` / `SessionCookieJar` 只管理门户与 SSO 域名，不会自动管理 newjw 的业务 Cookie。

因此不能只加一个课表 HTTP POST 就认为已完成接入。建议增加明确的教务服务描述：CAS 服务标识为已确认的 HTTP 字符串，允许的实际回调为 HTTPS newjw 固定路径；授权成功后由独立 `JwSession` 消费桥接链并保存 newjw Cookie。保留现有域名限制，不将“允许任意 service/任意重定向”作为通用方案。

已有 SSO 有效时不再次提交密码。只有 SSO 自身过期，才通过现有仓库按用户自动登录设置恢复；门户会话仍有效不等于 SSO 可以给教务签发新 ticket。

### 7.2 建议职责划分

| 模块 | 职责 |
| --- | --- |
| `JwSession` | 受限授权跳转、教务 CookieJar、登录失效识别 |
| `TimetableApi` | 学期选项、整学期 JSON、作息和周次日期读取 |
| `TimetableParser` | 周次/节次规范化、字段转换、错误定位与解析警告 |
| `TimetableRepository` | 单次共享刷新、账号/学期缓存、受保护只读 POST 的一次恢复重试 |
| ViewModel/UI | 周选择、网格与其他安排、加载/离线/未开放/错误状态 |

推荐缓存键为“账号 + 学年 + 学期”，作息额外加校区。保存最后成功时间和数据来源，刷新失败保留缓存；缓存沿用项目的加密和备份排除措施。每次异步结果提交前校验账号代次，退出/换号时清理业务会话及对应显示，旧请求不能把上一账号课表写回来。

解析后的排课模型至少包含：课程和教学班标识、名称、教师文本、地点与校区、星期、连续节次区间、明确周集合、原始周次/节次文本。实践/其他安排单独建模，不勉强塞进普通网格。

### 7.3 实现阶段应补充的验证

- MockWebServer：HTTP service 标识与 HTTPS 回调区分、`ticketlogin` 桥接、同名不同路径 Cookie、`901`、登录 HTML、权限字符串、业务关闭、跨域跳转拒绝、恢复重试上限。
- 离线解析用例：上述真实格式的合成样本，混合单双周、单周/单节、不连续节次、`1-12` 节、重复排课、地点为空、未知字段、非法范围、只有实践课、真实空课表。
- 日期用例：开学前、第 1 周边界、跨年学期、学期结束后，以学校周区间为依据，采用 Asia/Shanghai 校园时区。
- 设备验证：对照官网同账号同学期的普通课、其他安排和作息，切换学期、离线缓存、退出/换号及迟到响应。生产 UI 验证仍需在实现后进行。

本轮仅新增分析文档，没有为了文档变更运行 Android 构建或设备 UI 测试。

## 8. 公开实现对照与证据来源

学校端点和前端脚本是本校行为的一手证据；其他学校/项目代码仅用于交叉对照。公开仓库引用固定到本轮查阅的提交，避免以后主分支变更造成歧义。

| 来源 | 可参考内容 | 本项目不能直接照搬的部分 |
| --- | --- | --- |
| [openschoolcn/zfn_api：get_schedule、list_weeks](https://github.com/openschoolcn/zfn_api/blob/7e6762e1409c891f18eba755e8f500d6aa1a9dd3/zfn_api.py#L535) | `N2151`、旧 `cxXsKb` JSON 查询、`kbList/sjkList`、分段单双周展开 | 采用 `term**2*3`；节次解析假设至少两个数字；将无 `kbList` 视为无内容；作息是项目自己的配置 |
| [baoozak/timetable：新版正方解析器](https://github.com/baoozak/timetable/blob/e4b57dfa63359320ad0642aaf8c1f703e9236101/utils/parsers/zhengfang_new.js) | 在网页登录会话中 POST `cxXsgrkb`，提取课程、星期、节次、教师、地点和周次 | 固定 `N253508`；按月份猜学期；只匹配区间节次；只提取 `kbList`。当前 HDUHelper 不需要用页面标题传递课表 |
| [cr4n5/HDU-KillCourse：登录实现](https://github.com/cr4n5/HDU-KillCourse/blob/31d7020308b297583308c926389897f2132d8482/pkg/login/login.go) | 区分正方 RSA 本地登录与杭电 CAS AES 登录，再以 CAS 授权教务 | 本轮仅阅读登录部分，没有运行选课功能；其 Cookie、错误判断及凭据存储策略不替代本项目现有安全设计 |

主要校方证据入口：

- [SSO 入口](https://newjw.hdu.edu.cn/sso/driot4login)：跳转链及 HTTP `service` 的来源。
- [个人课表页](https://newjw.hdu.edu.cn/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default)：学期选项、功能配置、页面请求；需登录。
- [菜单查询端点](https://newjw.hdu.edu.cn/jwglxt/xtgl/index_cxMenuList.html)：使用 POST 和教务 Cookie；浏览器直接点击 GET 不能替代本文实测请求。
- [课表前端 xskbcx.js](https://newjw.hdu.edu.cn/jwglxt/js/comp/jwglxt/pkgl/kbcx/xskbcx.js)：`paramMap`、`cxKbContent`、`kbList/sjkList`、实验表与作息请求。
- [正方通用前端](https://newjw.hdu.edu.cn/jwglxt/js/jquery.zftal.contact-min.js)：`$.getURL` 自动补充功能编号。
- [按周课表前端](https://newjw.hdu.edu.cn/jwglxt/js/comp/jwglxt/pkgl/cxkbazc/cxXskbcx.js)：周次日期与按周查询参数。
- [本轮 SSO 主脚本](https://sso.hdu.edu.cn/public/cas-login/main-es2015.eab4816f749dd65da557.js)：凭据表单、验证码检查及 CSRF 处理；版本文件名可能随学校更新变化。

尚未验证的场景包括第 3 学期实际排课、其他校区、非空教学环节/实验表、验证码交互、服务端会话自然超时周期和真实调休事件。既有样本足以确定主课表的获取路径与解析模型，这些差异应在对应功能实施时补充验证。
