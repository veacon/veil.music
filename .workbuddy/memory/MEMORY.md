# veil-music 项目长期约定

## 工程位置
- 源码：`E:/ai/veil-latest/app`（Gradle wrapper 在此目录，`./gradlew :app:assembleRelease --offline`）
- APK 交付目录：`E:/ai/amusic/.apk/`（用户自测，不连 adb）
- 参考项目（am_atv）本地副本：`E:/ai/vm/vmlx`（包名 `dev.amatv`）
- 包名 `com.veacon.veilmusic`；版本定义 `app/release-version.properties`

## GitHub 下载：一律自己加加速前缀
- 用户明确要求：以后从 GitHub 下载任何东西（release 资产、raw、clone）都自行套加速前缀，不要直接连。
- 首选 `https://ghfast.top/` + 完整 URL；失败换 `https://gh-proxy.com/`、`https://ghproxy.net/`，最后才直连。
- 例：`curl -sL -o a.apk "https://ghfast.top/https://github.com/veacon/veil.music/releases/download/v2.4/Veil-Music-2.4.apk"`
- 上传（assets API）无法走镜像，只能直连 `uploads.github.com`。

## 发布流程（repo: veacon/veil.music）
- APK 必须用 `assembleRelease`（minify + shrinkResources），签名证书 SHA-256 `550d861a…`（本机 debug keystore，与已发布 2.3.6 及本地 debug 包一致 → 可覆盖安装）。
- Release 命名：tag `vX.Y`、标题 `Veil Music X.Y`；assets 必须是 `Veil-Music-X.Y.apk` + `update.json`（缺 update.json 会让所有客户端的更新检查失败）。
- `update.json` 字段：channel=user / packageName / versionName / versionCode / apkUrl（必须 `releases/download/<tag>/` 下）/ sizeBytes / sha256 / minSdk / notes（`\n` 转义，≤3000 字）。
- 客户端读 `releases/latest/download/update.json`，所以 release 不能是 draft/prerelease。
- 发布后回传校验：下载 APK 比对 sha256；`ghfast` 拉 latest 清单确认内容。
- 源码推送坑：`tools/gh_push_tree.py` 只推 git HEAD，未提交/未跟踪的新文件会漏（2.4 就漏了一批）。发布前用 `.tmp/push_full_tree.py`（按工作区磁盘快照推 Git Data API）全量同步，再用 `git/trees/main?recursive=1` 对比本地文件数。
- 2.4.1 已发布：tag `v2.4.1`，main 587 个文件全量同步（2.4 为 556 blob）。

## 文案约定
- 应用内更新弹窗宽 400dp、无滚动，notes 控制在 4~6 行、每行 ≤ 20 汉字，带 emoji。
- GitHub Release 正文：2.4.1 起按用户指定为 `## X.Y 更新说明` + 5 行 emoji 短句（无分类、无分隔线）；2.4 及更早用 `### emoji 分类` + `- **要点**：说明` + 结尾 `---`。后续以 2.4.1 格式为默认。
