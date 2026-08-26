# VoiceIO 架构 Review：Task 1–3 实施复盘 + Task 4（TTS）方案定稿

> 输入依据：`VoiceIO_Architecture_Review_Task4_20260826.md`  
> 项目：Akasha VoiceIO / `projects/voice_io/`  
> 环境：WSL2 + Python 3.11 + FastAPI + RTX 4070 Laptop 8GB；Android Java 8 / minSdk 26  
> 评审日期：2026-08-26

---

# 0. 最终裁定

## **结论：修改后批准（APPROVE WITH CHANGES）**

Task 1–3 的实现偏差总体合理，且大部分属于“装包后探针验证 / 真机取证后修正”，没有发现需要推翻现有 ASR 架构的问题。

Task 4 的总体方向正确：

- VoxCPM1.5 作为本地 GPU TTS；
- edge-tts 作为可选云 fallback；
- Backend 统一输出 `pcm_s16le / 24000 Hz / mono`；
- TTS 懒加载；
- ASR、TTS、Brain 保持同一 FastAPI 后端；
- TTS 失败不能拖垮 ASR；
- 先本地 WAV 实听，再接 `/ws/tts`；
- 记录 TTFA / RTF / VRAM；
- 模型提前落地到项目模型目录而不是运行时临时下载。

但有 **7 个必须修改点**：

1. **Torch 不能单独 pin。** 必须把 `torch + torchaudio + torchcodec + voxcpm` 当成兼容组锁定。
2. **流式重采样不能逐 chunk 独立 `scipy.signal.resample_poly()`。** 改为 stateful streaming resampler，推荐 `soxr.ResampleStream`。
3. **edge-tts 的 MP3 流要做 stateful decode。** PyAV 合适，不建议寻找“纯 Python MP3 decoder”。
4. **local→cloud fallback 只能在“尚未向 Android 发出首个 PCM”时无缝切换。** 已发出音频后不能从句首重播 cloud。
5. **VoxCPM GPU 常驻采用 idle grace，而不是每个 call 结束立即 unload，也不是永久驻留。**
6. **单 uvicorn worker 保留，但 VoxCPM 的同步 generator 必须放到专用推理线程；不能在 event loop 上直接迭代。**
7. **`tts.enabled=false` 不应在 status 中标 RED。** 应标 `DISABLED`，整体服务仍可 HEALTHY；这更符合“禁用 TTS 时 ASR 仍正常”的规格。

此外建议增加：

- 模型 revision/commit 与下载 manifest；
- runtime 读取 `model.tts_model.sample_rate`，禁止把 44100 写死；
- `torch.cuda.max_memory_allocated()` 峰值统计；
- TTS 单并发 GPU gate；
- Edge cloud fallback 显式配置开关，避免本地 TTS 故障时无提示把文本交给另一个云服务。

---

# 1. 对 Task 1–3 实施偏差的 Review

## 1.1 D-1～D-7

现有裁定可继续执行。

尤其赞成：

- **D-3 Brain 放 WSL 后端**：API key 不进入 APK，SSE/重试/限流集中在后端。
- **D-4 Task 执行只回 App `ACTION_RUN_GOAL`**：VoiceIO 后端不应变成第二套 adb/shell agent。
- **D-6 delegate_task 用 JSON 输出范式而不是绑定 tools-calling**：在云 endpoint / 模型兼容性未完全可控时更稳。
- **D-7 voice call 写入真实 Akasha session**：后续 transcript、任务回流、恢复上下文都更自然。

## 1.2 Paraformer hotwords 如实拒绝：批准

这不是实现缺陷。

sherpa-onnx 官方文档明确：contextual biasing / hotwords 只支持 transducer，且要求 `modified_beam_search`。

Paraformer 不支持 hotwords，所以：

```text
协议保留 hotwords 字段
+
当前模型收到非空 hotwords → 明确 ASR_ERROR
```

比静默忽略正确得多。

当前不建议为了让一个“协议可选字段”变成可用而立即更换已实测首 partial 55ms 的 ASR 模型。

## 1.3 `OnlineRecognizer.from_paraformer(...)` 替代旧 Config：批准

遵守“装包后探针，不猜 API”的实施原则是正确的。

## 1.4 `/ws/asr` 暂无 token

Task 4 不要求现在改。

但 Task 6 / LAN Mode B 必须重新审视 `/ws/asr`、`/ws/tts`、`/ws/call` 是否需要统一 auth policy。

---

# 2. 问题 1：Torch 版本

## 结论

### **不推荐默认采用 `torch==2.5.1+cu121`。**

它本身满足 VoxCPM 最低要求，也支持 RTX 4070 Laptop，但 2026 年的关键问题已经变成：

> VoxCPM 当前依赖 `torchcodec`，而 TorchCodec 与 Torch 有严格兼容矩阵。

OpenBMB 当前 PyPI `voxcpm` 最新稳定版为 **2.0.3（2026-05-11）**，项目依赖声明包含：

```text
torch>=2.5.0
torchaudio>=2.5.0
torchcodec
```

TorchCodec 官方兼容矩阵核心部分：

| TorchCodec | Torch |
|---|---|
| 0.1 | 2.5 |
| 0.2 | 2.6 |
| 0.3 / 0.4 / 0.5 | 2.7 |
| 0.6 / 0.7 | 2.8 |
| 0.8 / 0.9 | 2.9 |
| 0.10 | 2.10 |
| 0.11 | 2.11 |

因此最危险的是：

```text
固定 torch 2.5.1
+
让 torchcodec 漂移
```

## 推荐兼容组

### 第一选择

```text
Python      3.11.11
voxcpm      2.0.3
torch       2.7.1
torchaudio  2.7.1
torchcodec  0.5
CUDA wheel  cu126
```

PyTorch 官方提供：

```bash
pip install torch==2.7.1 torchaudio==2.7.1   --index-url https://download.pytorch.org/whl/cu126
```

RTX 4070 Laptop（Ada / sm_89）没有架构问题。当前 driver 592.82 对 CUDA 12.x runtime 足够新。

### 为什么不是 2.5.1

2.5.1 不是“不兼容”，而是：

- 已明显偏老；
- 必须同时固定 `torchcodec==0.1.x`；
- 当前 VoxCPM / torchaudio 生态测试重心已经前移；
- 没有实际收益。

### 为什么不直接追最新 Torch

Task 4 当前目标是稳定、可复现、先把 VoxCPM1.5 真机跑通，不是追最新 PyTorch。

如果 2.7.1 探针失败，可以再试：

```text
torch 2.8.0
torchaudio 2.8.0
torchcodec 0.7
cu126
```

但应基于实测升级。

## 安装后必须 probe

```python
import torch
import torchaudio
import torchcodec
import voxcpm

print(torch.__version__)
print(torch.version.cuda)
print(torch.cuda.is_available())
print(torch.cuda.get_device_name(0))
print(torch.cuda.get_device_capability(0))
```

随后执行一次本地 `VoxCPM.from_pretrained(local_model_path)` + 单句 WAV。

通过后再把最终 resolver 结果写回 `requirements.lock`。

## TorchCodec / FFmpeg

TorchCodec 仍依赖可加载的 FFmpeg shared libraries。OpenBMB issue 中已有 `Could not load libtorchcodec` 的真实案例。

因此 provisioning doctor 至少加入：

```text
import torchcodec
```

不要等以后加入 voice-cloning prompt audio 才发现 portable 环境缺依赖。

---

# 3. 问题 2：44.1kHz → 24kHz 重采样

## 原方案判断

`scipy.signal.resample_poly`：

- **整句非流式：可接受。**
- **对 `generate_streaming()` 每个 chunk 各自独立调用：不批准。**

原因是每个 chunk 都重新初始化 FIR/filter state，容易产生：

- chunk 边界瞬态；
- click / discontinuity；
- padding 误差；
- 累积样本数抖动。

44100→24000 的约分比是：

```text
up   = 80
down = 147
```

算法本身没有问题，问题在流式状态。

## 推荐

使用：

```python
soxr.ResampleStream(
    in_rate=source_rate,
    out_rate=24000,
    num_channels=1,
    dtype="float32",
    quality="HQ",
)
```

并持续：

```python
resample_chunk(chunk, last=False)
```

最后：

```python
resample_chunk(last_chunk, last=True)
```

### 不要写死 44100

必须：

```python
source_rate = model.tts_model.sample_rate
```

因为模型版本、denoise/enhance 路径都可能改变 native sample rate。

协议只固定 Backend 输出 24k。

---

# 4. 问题 3：edge-tts MP3 解码

## 结论

### **PyAV 合适，批准。**

当前 PyAV wheel：

- Python 3.11 可用；
- Linux manylinux 有 binary wheel；
- wheel 自带 FFmpeg；
- 很适合 packet/frame streaming decode。

项目已经因为 VoxCPM 引入数 GB torch/CUDA，没必要为了省几十 MB 去实现脆弱的 MP3 decoder。

## 关键边界

edge-tts 默认输出：

```text
audio-24khz-48kbitrate-mono-mp3
```

网络 chunk 不等于独立 MP3 文件。

不能：

```text
每个 edge chunk → 单独 av.open(BytesIO(chunk))
```

应保持 stateful decoder/parser：

```text
MP3 bytes chunks
→ parser/decoder state
→ AudioFrame
→ mono 24k
→ PCM S16LE
```

Edge 默认已经 24k mono，因此正常情况下不需要第二次重采样。

## 系统 ffmpeg subprocess

技术上可行：

```text
ffmpeg -i pipe:0 -f s16le -ar 24000 -ac 1 pipe:1
```

但会增加 portable 环境的系统依赖、subprocess 管理和 Windows/WSL 差异。当前项目更适合 PyAV。

---

# 5. 问题 4：GPU 驻留

## 结论

### **通话期间常驻，通话结束后 idle grace，再按需卸载。**

不要每个 `/ws/call` close 都立即：

```text
del model
torch.cuda.empty_cache()
```

否则下一次通话会重复冷加载。

也不建议进程启动后永久占 GPU。

## 推荐状态

```text
UNLOADED
→ LOADING
→ READY
→ IDLE_GRACE
→ UNLOADING
→ UNLOADED
```

默认：

```text
tts.gpu_idle_unload_seconds = 120
```

规则：

- active call / active synth：保持模型；
- call 结束：启动 120 秒 timer；
- timer 内新请求：取消卸载；
- 超时且无 active synthesis：卸载。

## 提前卸载条件

可配置：

```text
free VRAM < 1.5 GiB
```

或：

```text
nvidia-smi total used > 7 GiB
```

以及 CUDA OOM recovery。

## 正确卸载

```python
model = None
gc.collect()
torch.cuda.empty_cache()
```

前提：

```text
active synthesis == 0
```

建议统一由 `TtsModelManager` + lock + active_count 管理。

## VRAM 测量补充

除了：

```text
torch.cuda.memory_allocated()
nvidia-smi
```

还要记录：

```python
torch.cuda.reset_peak_memory_stats()
torch.cuda.max_memory_allocated()
torch.cuda.max_memory_reserved()
```

至少记录：

```text
GPU baseline
after torch import
after model load idle
synthesis peak
post synthesis resident
after unload
```

---

# 6. 问题 5：FastAPI 单进程模型

## 结论

### **uvicorn 单 worker 正确。**

不要开多个 worker，否则每个 worker 都可能独立加载 VoxCPM，8GB GPU 会直接变成模型复制问题。

## 但 GPU generator 不能跑 event loop

错误示例：

```python
async def handler():
    for chunk in model.generate_streaming(text):
        await websocket.send_bytes(...)
```

`next(generator)` 内是同步 GPU 推理，会阻塞：

- `/health`；
- Brain SSE；
- WebSocket ping/pong；
- ASR handler。

## 推荐

```text
ThreadPoolExecutor(max_workers=1)
+
TTS GPU semaphore = 1
+
asyncio Queue
```

架构：

```text
TTS worker thread
    |
    | model.generate_streaming()
    | resample
    | float32 -> s16le
    v
async queue / bounded bridge
    |
    v
WebSocket coroutine
```

注意：只把“创建 generator”放 executor 不够，因为真正推理发生在每次 `next()`。

应让 **整个 generator iteration 都留在同一个 TTS worker thread**。

## ASR

当前 ASR 已有首 partial 55ms 实测，不建议 Task 4 顺手重构。

Task 6 压测时如果发现 sherpa decode 会连续占 CPU 数十毫秒以上，再单独移到 CPU executor。

---

# 7. 问题 6：fallback 粒度

## 结论

### **“句级 fallback”修改后批准。**

真正边界是：

> 当前 request 是否已经向客户端发送任何可播放 PCM。

## Case A：首 PCM 之前 local 失败

例如：

- lazy load 失败；
- CUDA OOM；
- 第一 chunk 前异常；
- resampler 初始化失败。

可以：

```text
local fail
→ DEGRADED
→ cloud provider
→ first PCM ready
→ audio_start
→ cloud PCM
→ audio_end
```

这是无缝 fallback。

## Case B：已经发过 PCM 后失败

不能从 cloud 重新播放整句，否则用户会听到重复前缀。

推荐：

```text
当前句 truncated / TTS_ERROR
+
provider 标 DEGRADED
+
下一句直接走 cloud
```

Task 5 Brain 按句切分后，损失范围只是一句。

## 协议实现关键点

**`audio_start` 应尽量推迟到第一块 canonical PCM 已经准备好。**

这样 lazy-load / provider-selection / 第一块推理失败都能发生在客户端听到任何声音之前，fallback 边界最干净。

---

# 8. 问题 7：edge-tts LGPLv3

## 结论

### **Python 库 LGPLv3 本身不构成否决。**

原始 `rany2/edge-tts`：

- 大部分代码 LGPLv3；
- `srt_composer.py` 为 MIT。

普通：

```python
import edge_tts
```

不会要求整个 Akasha / VoiceIO 变成 LGPL/GPL。

## 如果分发 Backend

至少做到：

1. `THIRD_PARTY.md` 记录 edge-tts、版本、许可证、upstream；
2. 随分发物提供 LGPLv3 license；
3. 不阻止用户替换 Python 环境里的 edge-tts package；
4. 若修改 edge-tts 自身代码，修改部分按 LGPL 要求提供源码。

## 更大的风险：不是 LGPL，而是云服务本身

edge-tts 使用 Microsoft Edge consumer TTS service：

- 无正式 Azure API key；
- 不是你签约的 Azure Speech SLA；
- endpoint/token 可能变化；
- 开源客户端 LGPL 不等于 Microsoft 对服务商业用途的授权。

所以：

### 内部开发 / best-effort fallback

可以。

### 对外产品

不要把它作为“长期保证可用的正式云 TTS”。

如未来商业化，应切正式 Azure Speech / 其它有明确服务协议的 provider。

## 隐私边界

建议配置：

```json
"cloud_fallback_enabled": false
```

而不是 local 失败后无提示把文本发给 Edge。

即使 Brain 本身在云端，增加第二个文本接收方仍应显式可控。

---

# 9. 问题 8：Paraformer vs Zipformer hotwords

## 结论

### **Task 4–9 维持 Paraformer，不换 Zipformer。**

当前已有：

```text
官方 0.wav final 正确
12 个 partial
首 partial 55ms
Task 2 regression 11/11 PASS
```

不值得仅为了一个可选 hotwords 字段重新打开 ASR 风险面。

## Zipformer 优势

真实存在：

- transducer；
- `modified_beam_search`；
- contextual biasing / hotwords；
- 对专有词、人名可能更好。

## 代价

启用 hotwords 实际意味着：

```text
transducer
+
modified_beam_search
+
context graph
```

必须重新测：

- TTFA；
- partial cadence；
- CPU；
- endpoint latency；
- 中文普通句准确率；
- 中英混说；
- hotword false bias。

## Wake-word 与 hotwords 不是同一问题

Wake-word 目标：

```text
低功耗
低误触
持续监听
```

ASR contextual biasing 目标：

```text
识别过程中提升某些词的后验概率
```

如果 Task 10 的 wake phrase 是固定短词，更合理的是独立 KWS / VAD+KWS，再启动正常 ASR。

不应让主 ASR 为 wake-word 承担约 3 倍 CPU。

## Task 10 前再做 A/B

建议准备：

```text
20 普通中文
20 中英混说
20 Akasha 领域专有词
20 容易混淆人名/设备名
20 wake phrase 正样本
20 相似音负样本
```

比较：

```text
CER/WER
hotword recall
false bias
TTFA
endpoint latency
CPU%
RSS
```

有明确收益再切。

---

# 10. Provider API 建议收敛

你设计：

```python
async synthesize_stream(text)
  -> (meta, AsyncIterator[bytes])
```

可以工作。

但 Backend 协议已经固定：

```text
24000 Hz
mono
pcm_s16le
```

建议把 provider contract 定义成 canonical PCM：

```python
class TtsProvider:
    async def synthesize_pcm(self, text, cancel):
        """Yield pcm_s16le / 24000 Hz / mono."""
```

内部：

```text
VoxCPM:
float32 @ native SR
→ stateful resample
→ s16le 24k

Edge:
MP3 24k mono
→ PyAV
→ s16le 24k
```

`/ws/tts` 不需要理解 44.1k、MP3 等 provider 细节。

---

# 11. `/api/status` 修改

原方案：

```text
tts.enabled=false → tts=RED
```

不推荐。

主动禁用不是故障。

建议：

```text
DISABLED
UNLOADED
LOADING
READY
DEGRADED
ERROR
```

例如：

```text
ASR READY
TTS DISABLED
Brain READY
overall HEALTHY
```

`/ws/tts` 返回：

```json
{"type":"error","code":"TTS_DISABLED"}
```

即可。

---

# 12. 最终 TTS 流水线

```text
synthesize(requestId,text)
        |
        v
ProviderRouter
   | local preferred
   v
VoxCPM lazy load
   |
generate_streaming
   |
float32/native SR
   |
soxr.ResampleStream
   |
PCM S16LE / 24k / mono
   |
first PCM ready
   |
   +--- local failed before first PCM?
   |          |
   |         yes
   |          v
   |    Edge cloud fallback
   |          |
   |       MP3 stream
   |          |
   |      PyAV decoder
   |          |
   +----------+
        |
        v
audio_start
        |
binary PCM...
        |
provider fails after PCM?
   | yes
   v
current sentence truncated
next sentence uses cloud
        |
audio_end
```

---

# 13. 并发

当前半双工通话：

```text
tts_gpu_concurrency = 1
```

不要为了吞吐同时运行两份 VoxCPM synthesis。

8GB GPU 目标是稳定实时单通话，不是 TTS server benchmark。

---

# 14. cancellation / barge-in

虽然 barge-in 是 Task 10，但 Task 4 service API 现在就应该预留 cancel。

至少：

```text
cancel_event set
→ 停止输出新的 PCM
→ 尝试 close generator
→ 释放当前 resampler/decoder state
```

否则 Task 10 会被迫重构整个 provider contract。

---

# 15. float32 → PCM16

推荐：

```python
x = np.nan_to_num(x, nan=0.0, posinf=1.0, neginf=-1.0)
x = np.clip(x, -1.0, 1.0)
pcm = (x * 32767.0).astype("<i2", copy=False)
```

明确：

- little-endian；
- clip；
- NaN 防御；
- Android 不再做格式转换。

---

# 16. 模型下载与 portable

路径：

```text
models/tts/openbmb-VoxCPM1.5/
```

批准。

但 Task 4 必须补：

```text
model_id
revision / commit
download timestamp
file list
主要文件 SHA256
voxcpm package version
```

建议：

```text
models/tts/openbmb-VoxCPM1.5/MODEL_MANIFEST.json
```

生产加载统一 local path / local_files_only。

Backend 运行后不应该偷偷联网补模型。

---

# 17. 状态建议

建议分开：

```text
local_provider_state
cloud_provider_state
effective_tts_state
```

例如：

```json
{
  "tts": {
    "effective": "DEGRADED",
    "preferred": "local",
    "local": "ERROR",
    "cloud": "READY"
  }
}
```

诊断比单一 `tts=DEGRADED` 更清楚。

---

# 18. TTFA / RTF 口径

## TTFA

定义：

```text
Backend 收到 synthesize 的 monotonic time
→ 第一块 canonical PCM 准备完成
```

另记录：

```text
→ 第一 binary websocket send 完成
```

区分模型 TTFA 与 WS/network 开销。

## RTF

```text
RTF = synthesis_wall_time / generated_audio_duration
```

generated audio duration 用：

```text
PCM samples / 24000
```

计算。

建议再记录：

```text
inter_chunk_gap_p95
```

避免平均 RTF<1 但中间长时间卡顿。

---

# 19. VRAM 预算

你当前：

```text
2.5–3.5GB peak
```

只能保留为 pre-test hypothesis。

实际必须测：

```text
idle baseline
model load
loaded idle
short sentence
long sentence
streaming
retry badcase
```

并以：

```text
常驻 ≤8GB
优选 ≤7GB
```

作为规格。

---

# 20. `retry_badcase` 建议

官方示例默认可开启 bad-case retry，但实时通话应实测 tail latency。

建议 benchmark：

```text
A: retry_badcase=True
B: retry_badcase=False
```

记录：

- 失败率；
- TTFA p50/p95；
- RTF；
- 音质。

如果 retry 明显拉高 tail latency，通话模式用 `False`，离线试听再开启。

---

# 21. Task 4 修订实施顺序

## Gate 0：基线

已知：

```text
GPU idle = 302 MiB
ASR regression = PASS
```

同时保存：

```text
requirements.lock
git commit
nvidia-smi
driver
WSL kernel
Python
```

## Gate 1：安装兼容组

首选：

```text
voxcpm==2.0.3
torch==2.7.1
torchaudio==2.7.1
torchcodec==0.5
cu126
soxr
edge-tts==7.2.8
av
```

成功后重新冻结 lock。

## Gate 2：dependency doctor

```text
import torch
import torchaudio
import torchcodec
import voxcpm
import soxr
import av
import edge_tts

torch.cuda.is_available() == True
GPU == RTX 4070 Laptop
```

## Gate 3：model snapshot

```text
openbmb/VoxCPM1.5
→ local model dir
→ revision manifest
→ local_files_only probe
```

## Gate 4：最小 TTS

```text
generate_streaming
→ runtime native SR
→ stateful resample
→ PCM
→ logs/tts-test.wav
```

记录：

```text
load seconds
TTFA
RTF
audio duration
source sample rate
GPU loaded idle
GPU peak
```

## Gate 5：TEST-USER-B

用户实听 `logs/tts-test.wav`。

明确通过后再接 WS。

## Gate 6：Provider service

实现：

```text
VoxCpmProvider
EdgeTtsProvider
ProviderRouter
TtsModelManager
StreamingResampler
Mp3Decoder
```

## Gate 7：`/ws/tts`

```text
synthesize
→ first PCM ready
→ audio_start
→ binary
→ audio_end
```

单连接 requestId 串行。

## Gate 8：故障注入

至少：

```text
tts.enabled=false
model missing
CUDA unavailable
OOM
Edge offline
MP3 decode fail
client disconnect mid-stream
cancel mid-stream
local fail before first PCM
local fail after first PCM
```

## Gate 9：回归

重跑：

```text
Task 2 11/11
ASR file
ASR stream
ASR WS
/ws/call auth
```

---

# 22. 推荐配置

```json
{
  "tts": {
    "enabled": true,
    "mode": "local",
    "local": {
      "provider": "voxcpm",
      "model_path": "models/tts/openbmb-VoxCPM1.5",
      "gpu_idle_unload_seconds": 120,
      "max_concurrency": 1
    },
    "cloud": {
      "provider": "edge-tts",
      "voice": "zh-CN-XiaoxiaoNeural"
    },
    "cloud_fallback_enabled": false
  }
}
```

协议层固定：

```text
output sample rate = 24000
channels = 1
format = pcm_s16le
```

---

# 23. 对 8 个问题的最终短答

| # | 问题 | 裁定 |
|---|---|---|
| 1 | Torch | **成组 pin；推荐 2.7.1 + cu126 + torchaudio 2.7.1 + torchcodec 0.5 + voxcpm 2.0.3。2.5.1 可用但不是首选。** |
| 2 | Resample | **整句 `resample_poly` 可；流式 chunk 不可独立调用。改 `soxr.ResampleStream`。** |
| 3 | MP3 | **PyAV 合适；必须 stateful decode。** |
| 4 | GPU 驻留 | **call 内常驻；结束后约 120s idle grace；压力/超时再 unload。** |
| 5 | 进程 | **uvicorn 单 worker；TTS 专用 max_workers=1 executor + bounded queue + GPU semaphore=1。** |
| 6 | fallback | **首 PCM 前可同句 cloud fallback；首 PCM 后不可从头重播，下一句再切 cloud。** |
| 7 | LGPL | **库许可证可接受；做好 THIRD_PARTY/license/可替换。更大风险是非正式 Edge consumer service 的条款与稳定性。** |
| 8 | hotwords | **现在保留 Paraformer；Task 10 再 A/B Zipformer。wake-word 与 contextual biasing 分离。** |

---

# 24. 最终批准条件

Task 4 可以开始，但进入 `/ws/tts` 前必须满足：

```text
[PASS] compatibility group 已锁定
[PASS] torch / torchcodec / CUDA doctor
[PASS] VoxCPM local_files_only 加载
[PASS] runtime sample rate 获取
[PASS] stateful streaming resample
[PASS] tts-test.wav
[PASS] 用户实听确认
[PASS] TTFA / RTF / VRAM 记录
[PASS] ASR 回归不退化
```

# **Task 4：修改后批准**

不需要返工 Task 1–3，也不建议现在更换 ASR 模型。

---

# 25. 参考资料

## VoxCPM

- OpenBMB VoxCPM  
  https://github.com/OpenBMB/VoxCPM
- VoxCPM1.5  
  https://huggingface.co/openbmb/VoxCPM1.5
- VoxCPM PyPI  
  https://pypi.org/project/voxcpm/
- VoxCPM dependencies  
  https://github.com/OpenBMB/VoxCPM/blob/main/pyproject.toml

## PyTorch / TorchCodec

- PyTorch previous versions  
  https://pytorch.org/get-started/previous-versions/
- TorchCodec  
  https://github.com/meta-pytorch/torchcodec
- TorchCodec PyPI  
  https://pypi.org/project/torchcodec/

## Resampling

- SciPy `resample_poly`  
  https://docs.scipy.org/doc/scipy/reference/generated/scipy.signal.resample_poly.html
- Python-SoXR `ResampleStream`  
  https://python-soxr.readthedocs.io/en/stable/soxr.html

## edge-tts / PyAV

- rany2/edge-tts  
  https://github.com/rany2/edge-tts
- edge-tts LICENSE  
  https://github.com/rany2/edge-tts/blob/master/LICENSE
- edge-tts streaming implementation  
  https://github.com/rany2/edge-tts/blob/master/src/edge_tts/communicate.py
- PyAV  
  https://pypi.org/project/av/

## sherpa-onnx

- Hotwords / contextual biasing  
  https://k2-fsa.github.io/sherpa/onnx/hotwords/index.html
- Online recognizer  
  https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/python/sherpa_onnx/online_recognizer.py

---

# 26. Source / inference boundary

本文关于项目当前实现、55ms 首 partial、302MiB idle、Task 1–3 通过情况、D-1～D-7、协议 §16/§7/§8/PHASE 8 的描述来自本次提供的 Review 输入文档。

本文关于 VoxCPM 当前版本与依赖、Torch/TorchCodec 兼容矩阵、SoXR streaming API、edge-tts MP3 format、PyAV wheel、LGPL、sherpa-onnx hotwords 支持范围属于 2026-08-26 外部核验。

executor、fallback 边界、GPU idle grace、status 状态语义、cancellation 和 provider contract 为本次架构评审建议。
