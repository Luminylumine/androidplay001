# AdbManager 虚拟显示器输入与兼容性问题调研

## 背景
我正在开发基于C# WinForms的AdbManager工具，使用scrcpy 3.3.4的`--new-display`参数实现安卓多开扩展屏功能。当前遇到两个严重问题，需要技术指导。

## 当前使用的scrcpy命令
```bash
scrcpy -s SERIAL \
  --new-display=1080x1920/320 \
  --start-app=com.example.app \
  --no-vd-system-decorations \
  --display-ime-policy=local \
  --no-audio \
  --no-vd-destroy-content \
  --window-title="分屏 | com.example.app"
```

## 问题1：虚拟显示器输入注入错误
### 现象
- 分屏应用内部无法操作
- 操作会被映射到主屏上（主屏上出现一个触摸圈，疑似开发者选项"显示触摸操作"）
- 但实际点击效果确实发生在副屏

### 我的分析
scrcpy默认使用`--mouse=sdk`模式，通过Android系统API（InputManager.injectInputEvent）注入触摸事件。在虚拟显示器场景下，SDK模式的输入注入存在已知问题：
- GitHub Issue #4598：三星Android 14上，虚拟显示器（id=90）的点击事件不工作，但通过另一个display（id=91）注入可以工作，说明**输入注入的displayId和内容显示的displayId可能不一致**
- GitHub Issue #6699：Pixel 9 Android 16上，`--new-display`创建的虚拟显示器点击无效，但使用`--mouse=uhid --keyboard=uhid`参数后一切正常

### 问题
1. 是否确认根因是SDK输入注入模式在虚拟显示器上路由错误？
2. 使用`--mouse=uhid --keyboard=uhid`是否能彻底解决？有什么副作用？
3. UHID模式需要额外配置键盘布局吗？对中文输入有什么影响？
4. 是否有其他参数组合可以解决虚拟显示器输入问题？

## 问题2：不支持分屏的应用导致闪烁
### 现象
- 部分应用不支持分屏（副屏操作会直接把应用跳回主屏）
- 在主屏上关掉应用后，副屏处疯狂闪烁

### 我的分析
- 应用不支持在secondary display运行（没有`android.software.activities_on_secondary_displays`支持，或应用自身限制）
- 当在副屏操作时，系统检测到应用无法在副屏运行，把Activity移回主屏
- 主屏关掉应用后，虚拟显示器没有内容，scrcpy还在尝试捕获空内容，导致闪烁

### 问题
1. 如何检测应用是否支持在secondary display运行？（除了检查`android.software.activities_on_secondary_displays` feature）
2. 如何检测应用已经跳回主屏（虚拟显示器无内容）？
3. 闪烁问题的根因是什么？如何避免？
4. 检测到应用不支持分屏时，应该如何处理？（自动关闭会话？提示用户？）

## 期望回答格式
1. 使用markdown格式
2. 给出具体的scrcpy命令行参数
3. 给出C#代码片段（如果需要）
4. 说明各方案的优缺点和适用场景
5. 如果无法解决，请明确说明
