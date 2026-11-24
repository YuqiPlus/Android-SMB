# Android SMB

把 Android 手机变成 Wi-Fi 局域网里的 SMB 文件服务器。连接后可以浏览、上传、下载、重命名和删除共享文件，也可以用支持 SMB 的播放器直接读取共享视频。

## 使用

1. 从 [GitHub Releases](https://github.com/YuqiPlus/Android-SMB/releases) 下载 APK，在 ARM64 手机（Android 8.0 或更新版本）上安装，再连接 Wi-Fi。
2. 打开 App。首次启动会生成随机密码，点「显示密码」可查看，也可以改为任意非空密码。默认用户名是 `smb`，TCP 端口是 `1445`。
3. 点「启动共享」，按系统提示授予局域网权限。允许通知后，可以直接在通知栏里停止共享。
4. 电脑连接同一个局域网，使用 App 显示的地址和账号登录。

**macOS**

在 Finder 按 `⌘K`，输入 App 显示的地址，例如 `smb://192.168.1.50:1445/Share`，选择注册用户。

**Windows 11 24H2 或更新版本**

在命令提示符里运行以下命令，根据手机显示的 IP、端口和用户名修改参数。输入密码时系统会提示。

```bat
net use Z: \\192.168.1.50\Share * /USER:smb /TCPPORT:1445
```

旧版 Windows 的内置 SMB 客户端固定使用默认端口 445。

## 跨网段连接

默认只允许手机所在子网。Windows 和手机在不同子网时，先停止共享，在「允许的其他网段」中添加 Windows 当前的 IPv4 地址，例如 `10.20.30.45`。要开放整个子网，可以填其 CIDR，例如 `10.20.30.0/24`。也可以粘贴用逗号、空格或换行分隔的多个地址。添加后会显示在列表里，可以单独移除。输入支持私有 IPv4 和 `100.64.0.0/10` 共享地址范围。

重新启动共享后，Windows 继续使用手机的 IP 连接。可用 `Test-NetConnection 手机IP -Port 1445 -InformationLevel Detailed` 验证 TCP 端口，并在 `SourceAddress` 查看 Windows 选用的本地地址。若跨网段 TCP 测试失败，检查两网段间的防火墙或 VLAN 规则是否允许访问手机的 SMB 端口。

## 共享文件

App 在内部存储中维护一个名为 `Share` 的专用目录。点击 App 中的「打开共享目录」，或在 Android 系统「文件」应用的侧栏中选择「Android SMB」，即可在手机上管理同一批文件。卸载 App 或清除应用数据会一起删除共享文件。

服务只监听当前 Wi-Fi 的 IPv4 地址，并接受手机子网及设置中放行的地址或网段。关闭界面或锁屏后，前台服务会继续运行。Wi-Fi 暂时断开时会暂停接受连接，Wi-Fi 恢复或地址变更后会自动重新监听。客户端需要密码，SMB 会话协商签名并支持 SMB 3 加密。手机系统的省电策略可能影响长时间锁屏后的可用性。HyperOS 可在 Android SMB 的应用信息中将省电策略改为「无限制」，并允许通知，方便查看和停止共享。强行停止应用或重启手机后，需要手动启动共享。

## 构建

需要 JDK 21、Android SDK Platform 37 和 Go 1.25 或更新版本。

```sh
./gradlew :app:assembleDebug
```

Go 不在 `PATH` 时，可以在项目的 `local.properties` 加入 `androidSmb.go=/完整路径/go`，让 IDE 直接使用该编译器。命令行也可以设置 `ANDROID_SMB_GO`，其优先级高于本机项目配置。调试 APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。SMB 服务端基于 [smbsrv](https://github.com/tobychui/smbsrv)，构建时作为 ARM64 原生可执行文件打包，无需单独安装 Samba 或 Termux。

## 许可证

项目源码采用 [MIT 许可证](LICENSE)。第三方组件的许可证见 [App 许可证目录](app/src/main/assets/licenses)及 [Gradle Wrapper 许可证](gradle/wrapper/LICENSE)。
