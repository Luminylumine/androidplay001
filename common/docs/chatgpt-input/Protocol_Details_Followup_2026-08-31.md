# ADB + scrcpy 协议精确细节追问 Prompt

> 给 ChatGPT 的追问请求，用于补全 Phase 0~2 实现所需的精确协议字节级细节。  
> 背景: `d:\study\androidplay\phone_mirror_phone` feature/phone-mirror-phone 分支，Android→Android ADB 控制器，自研 ADB protocol client。

---

## Q1: ADB RSA Public Key Serialization 格式

Android ADB AUTH 流程中，Android 的 RSA public key 不是标准 SubjectPublicKeyInfo (X.509 SPKI)，而是 Android 自定义格式。具体格式是什么？

请给出：
- 完整的字节布局图（modulus 宽度、字节序、exponent 位置、padding 规则、base64 编码方式）
- 参考 AOSP 源码路径（adb_auth_host.cpp 里的 PublicKey::ToBase64() 相关）
- 如果使用 Java/AndroidKeyStore 生成 RSA-2048 密钥，如何把它转换成这个 ADB 格式的 public key byte array？
- AUTH/RSA_SIGNATURE 的签名算法是什么（SHA1withRSA? SHA256withRSA?），AOSP 里对应的常量名

## Q2: ADB Protocol 完整常量清单

请给出 AOSP `platform_system_core/adb/protocol.h` 中所有常量的精确数值（十六进制/十进制）：
- ADB_CMD_CNXN / AUTH / OPEN / OKAY / WRTE / CLSE / SYNC
- AUTH_TOKEN / AUTH_SIGNATURE / AUTH_RSAPUBLICKEY / AUTH_DONE
- ADB_CONNECTION_BANNER ("host::" 还是别的？version 字段格式？)
- ADB packet magic (command^0xffffffff, for CNXN magic=0x4f4e584e = "ONXN")
- checksum 计算规则（header 之后所有 payload 字节的 checksum? 还是整个 packet？）
- max payload size (65536? 还是别的？)

同时请确认：
- Legacy TCP handshake 时，host 发 CNXN 后，device 一定先回 AUTH/RSA 吗？还是可能直接 OKAY/DONE？
- 如果 device 回 AUTH/RSA_PUBLIC_KEY（它自己的 public key），我们要怎么处理？（Phase 0 我们做 host，device 是目标机，device 应该是有 ADB key 的）
- OPEN "shell:ls -la /data/local/tmp" 的 arg0=local_id, arg1=0 (remote_id 未知), service name 是否带 \0 结尾？
- SYNC protocol 的 SEND / RECV / STAT / LIST / DONE / DATA 帧具体格式（每个命令名长度、参数格式、DATA 块大小）

## Q3: Wireless Debugging (Android 11+) TLS Pairing 协议

请给出精确的 SPAKE2 握手步骤 + BoringSSL 在 Android 上的可用方式：
- 4-digit pairing code 的 SPAKE2 流程（client/pairing port 连接后，交换什么消息？SPAKE2 的 role 是 client 还是 server？如何用 code 派生密钥？）
- TLS connect port（不同于 pairing port）是 SPAKE2 之后直接普通 TLS 还是有额外 handshakes？
- BoringSSL 在 Android Gradle 项目中的引用方式：
  - 有 Maven Central artifact 吗？搜索关键词是什么？
  - 如果没有，如何从 NDK/prebuilt 引入？（project 里有 common/sdks/ 目录可以放预编译 .so）
- Scropy Android (feggaa/scrcpy-android) 是如何处理 TLS pairing 的？他们用了什么 crypto 库？

## Q4: scrcpy 4.0 Protocol 连接建立顺序

我从 scrcpy 官文档知道了 video/audio/control socket 的顺序，但有一些实现细节不确定：
- **Tunnel 方向**: Android→Android 的场景下（没有 PC），我们是通过 ADB SYNC 协议 push scrcpy-server.jar 到 /data/local/tmp/ 然后 shell 启动它，对吗？启动命令是什么精确格式？CLASSPATH= 后跟 app_process？
- **Tunnel 建立**: 在没有 PC 的情况下，我们作为 client，应该用 `adb forward tcp:port localabstract:scrcpy_<scid>` 还是 `adb reverse`？（reverse 需要 server 主动连过来，Android shell 能做吗？）
- **Dummy byte**: 官文档说 "如果 tunnel 是 forward，device 在第一个 socket 上会发送一个 dummy byte 让 client 检测连接" —— 这个 dummy byte 是 0x00 吗？只在读 meta data 之前？
- **device metadata**: 官文档说 device 在第一个 socket 上发 device name（64 字节固定长度？还是 \0 结尾字符串？），请精确说明
- **scid**: 31-bit 随机数，为什么不是 32-bit？MSB 用来区分什么？
- **多个 client**: 如果我们在 app 里同时开 mirror + file browser + shell，每个都需要独立的 scid 吗？还是可以共享？

## Q5: scrcpy SessionPacket / MediaPacket 位布局确认

从 scrcpy 文档我看到了：

SessionPacket (12 bytes):
```
byte 0:  bit 7 = 1 (session packet flag), bits 0..6 = padding
byte 3:  LSB = client resized flag
bytes 4..7: video width (u32 BE?)
bytes 8..11: video height (u32 BE?)
```

MediaPacket (12 bytes):
```
byte 0:  bit 7 = 0 (media packet flag), bit 6 = config packet, bit 5 = key frame, bits 0..4 = PTS[57..61] (5 bits)
bytes 1..7: PTS[0..56] (57 bits)  — 注意官方说 "PTS (u61)" 所以总共 61 bits = byte0 低5位 + 56 bits in bytes 1-7
bytes 8..11: packet size (u32 BE)
```

请确认：
- width/height 是 big-endian 还是 little-endian？（Android MediaCodec 通常用 little-endian）
- PTS 是 big-endian 还是 little-endian？
- SessionPacket 的 bytes 1-2 是什么？（文档里画了 padding）
- 第一个 packet 收到后，如何判断是 session packet 还是 media packet？（看 byte 0 bit 7 对吗？）
- session packet 出现的时机：每次 display rotation 都会收到吗？

## Q6: Scrcpy server options — max_size, max_fps, audio, video_codec, tunnel_forward, send_frame_meta

scrcpy server 启动时传 key=value 参数，完整可选项列表是什么？特别是：
- `max_size` — 默认 0 (auto?) 还是 1280?
- `max_fps` — 默认 60?
- `video_codec` — 默认 "h264"? 支持 "h265"? "av1"?
- `tunnel_forward=true` — Android→Android 是否必须开这个？（我们是 client 侧主动 forward tunnel）
- `send_frame_meta=false` — 如果开了这个，scrcpy 不发 12-byte MediaPacket header？
- `raw_stream=true` — 关掉所有 meta (codec id, dummy byte, frame header)
- `cleanup=false` — server 退出后不清理？Android→Android 重连时有用吗？

完整的 key=value 参数列表 + 默认值 + 作用。

---

## 输出要求

每个问题给出精确的技术答案。能直接给十六进制常量的直接给，能给字节布局图的直接画（用 ASCII art），能贴代码片段的贴 AOSP/Scropy 源码。不要模糊答案。

参考仓库已列出在文件头。
