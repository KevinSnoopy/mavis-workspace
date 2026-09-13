# 「听阅」上架 Checklist（国内安卓商店）

> 更新：2026-09-13。按"必须 → 建议"分层。✅ = 本次已完成并验证；👤 = 需要开发者本人办理（涉及资质，代码无法代办）。

## 一、应用工程侧（✅ 已由本次任务完成）

| 事项 | 状态 | 说明 |
| --- | --- | --- |
| release 正式签名 | ✅ | `keystore/eareyereading-release.jks`（30 年有效期）+ `signingConfigs.release`（凭据走 `keystore/signing.properties`，已 gitignore）。**立即备份 keystore 与密码，丢失 = 无法更新应用** |
| 隐私政策（全文） | ✅ | `docs/store/privacy-policy.md` → 生成 `docs/store/privacy-policy.html`（托管）+ `app/src/main/assets/privacy-policy.html`（内置） |
| 用户服务协议（全文） | ✅ | `docs/store/user-agreement.md` → 同上双路输出 |
| 首启隐私同意弹窗 | ✅ | `PrivacyConsentGate`：同意前不进主界面/不申请权限/不联网；不同意退出；断网可看全文（内置 assets） |
| 政策版本化重新同意 | ✅ | `PRIVACY_VERSION` 常量，政策实质变更 +1 后老用户重新弹窗 |
| 设置页合规入口 | ✅ | 设置 → 关于与合规：隐私政策 / 用户协议 / 开源许可 / APP 备案号占位 |
| 权限最小化自查 | ✅ | 7 项权限均有对应功能且可在隐私政策中解释（见 APP_LISTING.md 权限说明表）；无定位/通讯录/相机等敏感权限 |
| 64 位支持 | ✅ | abiFilters 含 arm64-v8a（+ armeabi-v7a），另有 universal APK 兜底 |
| R8 混淆 + 资源缩减 | ✅ | minify + shrinkResources 已开启，JNI/Gson 规则已就位 |
| targetSdk | ⚠️ | 当前 34。国内主流渠道 2025-2026 基线 ≥34 可过，但**部分渠道逐步要求 35+，Google Play 新应用要求 ≥35**。升级涉及 edge-to-edge 强制生效等行为变化，建议 UI 重构收尾后单独升（compileSdk/targetSdk 35 + 全量真机回归） |

## 二、需要开发者本人办理的资质（👤）

按办理顺序（多数可并行）：

1. **👤 软件著作权（软著）**——安卓全渠道基本强制（华为口径"非必选"但强烈建议）。
   - 登记名称须与应用名**一字不差**：登记名 =「听阅」（或「听阅英语听读软件」，以最终证书为准后统一）；
   - 渠道：中国版权保护中心（免费，约 30-60 工作日）或各省电子版权认证（快，1-3 工作日，部分渠道认可）；
   - 个人开发者可直接申请；软著主体须与商店开发者账号主体一致。
2. **👤 工信部 App 备案**（2023 年起强制，未备案不予收录）。
   - 入口：各大商店开发者后台会引导，或 https://beian.miit.gov.cn/（"APP备案"）；
   - 需要主体信息（个人身份证/企业营业执照）+ 已有 ICP 备案的域名/主体关联；
   - 备案信息：包名 `com.eareyereading`、应用名「听阅」、平台 Android、App 特征（工具-学习）；
   - 核准后：把备案号填入 `SettingsAboutSection.kt` 的 `APP_RECORD_NUMBER`，并同步替换隐私政策/用户协议中的占位。
3. **👤 ICP 备案**——App 有联网功能（词典 CDN、RSS、翻译），需要备案过的域名承载隐私政策在线页。
   - 隐私政策在线地址（建议托管到已备案域名或 GitHub Pages/jsDelivr）：
     - `https://cdn.jsdelivr.net/gh/KevinSnoopy/mavis-workspace@eareyereading/eareyereading/docs/store/privacy-policy.html`
     - `https://cdn.jsdelivr.net/gh/KevinSnoopy/mavis-workspace@eareyereading/eareyereading/docs/store/user-agreement.html`
   - 提交 docs/store/*.html 到公共仓库分支 `eareyereading` 后上述链接即生效；**提交前先在浏览器验证可访问**；
   - 各商店后台"隐私政策链接"填上述 URL（或备案域名下自托管地址）。
4. **👤 公安联网备案**——ICP/App 备案完成后 30 日内在"全国互联网安全管理服务平台"（beian.mps.gov.cn）登记。
5. **👤 商店开发者账号**——华为/小米/OPPO/vivo/应用宝各注册；个人开发者账号（软著本人持有）可发免费应用，企业账号覆盖率更好（部分品类仅对企业开放）。
6. **✅ 联系方式**——隐私政策与协议联系邮箱已设为 `850001735@qq.com`（改 md 后重跑 `scripts/gen_store_html.py` 同步生成 HTML）。

## 三、商店素材（✅ 素材已备，👤 按各渠道尺寸导出）

| 素材 | 状态 | 说明 |
| --- | --- | --- |
| 应用名称 | ✅ | 「听阅」（与 strings.xml 一致；上架名须与软著/备案一致） |
| 一句话简介 / 详细介绍 | ✅ | 见 `APP_LISTING.md`（vivo 要求一句话 5-16 字、介绍 ≥50 字，已按此写） |
| 512×512 商店图标 | ✅ | `docs/store/icon-512.png`（由应用内矢量图标渲染，与桌面图标一致） |
| 应用截图 1080×1920 | 👤 | 真机截图 3-5 张：书库/阅读页（普通+分栏）/生词复习/设置。勿用 iPhone 边框素材 |
| 权限用途说明 | ✅ | 见 `APP_LISTING.md` 第 3 节（商店后台逐条照填） |
| 分类/标签 | ✅ 建议 | 教育-学习 / 图书阅读 |

## 四、发布前工程自检（每次发版）

- [ ] `versionCode` +1（当前 1 / 1.0.0），versionName 按语义化版本；
- [ ] `./gradlew :app:assembleRelease` 产出三个 APK（arm64 / v7a / universal），安装 arm64 真机冒烟：冷启动弹窗 → 同意 → 导入书 → 朗读 → 生词 → 复习提醒；
- [ ] **断网冷启动**：同意弹窗内可完整查看隐私政策/用户协议（内置 assets）；
- [ ] **拒绝路径**：点"不同意并退出"应用正常退出，重开再弹；
- [ ] 检查 `apksigner verify --print-certs` 签名证书指纹与上一版一致；
- [ ] 政策有实质变更 → `PRIVACY_VERSION` +1 + 更新文档日期 + 重新生成 HTML + 同步在线托管。

## 五、参考命令

```bash
# 签名验证
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-arm64-v8a-release.apk

# 政策 HTML 再生成（改了 md 之后）
/Users/kevin/.workbuddy/binaries/python/envs/default/bin/python scripts/gen_store_html.py

# 图标 PNG 再生成（改了矢量图标之后）
/Users/kevin/.workbuddy/binaries/python/envs/default/bin/python scripts/gen_store_icon.py
```
