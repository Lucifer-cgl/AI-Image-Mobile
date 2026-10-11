# 端侧生成架构

## 主链路

```text
中文输入
  -> CPU提示词规划模型
  -> SceneSpec JSON
  -> 规则校验与构图控制图
  -> 卸载规划模型
  -> GPU加载LCM图片模型
  -> 2~4步生成完整图片
  -> TAESD解码
  -> 保存历史记录
```

语言模型的准确性主要减少废图和重试次数；单次图片推理的时间主要由少步蒸馏、CFG=1、图片尺寸、GPU后端和TAESD决定。

## 资源调度

| 资源 | 任务 |
| --- | --- |
| CPU | 中文理解、SceneSpec、分词、校验、文件处理 |
| GPU | LCM 扩散与 TAESD 解码 |
| RAM | 只保留当前阶段的模型与张量 |
| 存储 | 权重、内存映射、下载断点、历史图片 |
| NPU/APU | 能力检测通过后作为可选后端，不能成为安装前提 |

低内存设备使用严格的阶段加载；高内存设备可以在用户选择“速度优先”时保留最近使用的模型上下文。

从最近任务列表划走应用时，`InferenceCleanupService.onTaskRemoved` 会先取消生成，再释放 native 模型上下文。仅把应用切到后台不会卸载模型，避免来回切换造成重复加载。

## SceneSpec接口

语言模型只能输出符合Schema的JSON，不能直接输出聊天文字。坐标统一为0到1。

```json
{
  "canvas": {"ratio": "9:16", "style": "2D anime illustration"},
  "camera": {"shot": "medium", "angle": "eye_level"},
  "subjects": [{
    "type": "dog",
    "action": "resting under a tree",
    "center_x": 0.62,
    "center_y": 0.68,
    "width": 0.28,
    "height": 0.24,
    "facing": "left"
  }],
  "lighting": {"direction": "top_right", "temperature": "warm"},
  "prompt": "a small dog resting in the shade under a large tree..."
}
```

第一版将SceneSpec渲染成一张低成本整图草稿，再通过img2img约束LCM；后续再评估ControlNet/T2I Adapter，避免一开始引入额外的大模型和显存压力。

