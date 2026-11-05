# Depression（抑郁症）

### [McMod](https://www.mcmod.cn/class/15427.html) | [Modrinth](https://modrinth.com/mod/depression) | [CurseForge](https://www.curseforge.com/minecraft/mc-mods/depression)

> 本仓库是 **Depression 1.21.1（NeoForge / Fabric）移植与修复版**，基于 [block-137/Depression](https://github.com/block-137/Depression) 移植，移植与修复由 **sulingqingQAQ** 完成。原作的创意与设计全部属于原作者，详见文末致谢。

## 📑 简介

Depression 为 Minecraft 加入了全新的**情绪（Emotion）**与**精神健康（Mental Health）**机制：受伤会情绪低落，长期的低落会演变为抑郁症，并带来失眠、幻觉、PTSD 等一系列症状。你需要像照顾身体一样照顾自己的精神——写日记、看心理医生、按时服药，慢慢好起来。

这不是一个"打怪变强"的模组，而是一套关于精神状态的生存玩法。

## ✨ 功能特性

### 情绪与精神健康

- **双数值系统**：情绪值实时波动，显示为经验条旁的表情；精神健康值长期累积变化，决定你的精神状态。
- **受伤与事件影响情绪**：受到伤害会情绪低落，并可能对相应伤害来源形成 **PTSD**（创伤后应激障碍）——之后再次受到同类伤害会承受更多情绪打击。
- **抑郁分级**：精神健康过低会发展为抑郁症，按程度分为轻度 / 中度 / 重度；另有**双相情感障碍**（抑郁相与躁狂相交替）。
- **精神特质**：开局可选择精神特质，影响情绪与精神健康的变化倾向（另有"切换特质"物品可一次性变更）。

### 症状与表现

- **失眠**：夜晚难以入睡，需要安眠药辅助。
- **幻觉**：精神状态差时可能出现幻视等异常体验。
- **紧张性木僵**：严重时的躯体化表现。
- **PTSD**：特定伤害来源的创伤记忆，有其形成、发作、缓解与消散的过程。

### 日记

- **日记本**：由书、羽毛与花合成，夜晚可以写日记。
- **情绪自省**：日记是了解自己精神健康状况的重要途径；配合附属模组 [Depression-TianTian](https://github.com/GRAINALCOHOL/depression-tiantian) 可获得更丰富的个性化日记内容。
- **死亡弹屏开关**：本移植版新增客户端配置 `keep_diary_screen_on_respawn`（默认 `false`），开启后死亡重生不会打断排队中的日记弹屏。

### 治疗与康复

- **心理医生**：村庄中会生成心理诊所，心理医生村民在此坐诊，可购得药物与相关物品。
- **药物**（均需注意用量与搭配）：
  | 物品 | 用途 |
  |---|---|
  | 轻度 / 中度抗抑郁药片、重度抗抑郁胶囊 | 对应不同程度的抑郁治疗 |
  | 抗躁狂药片 | 躁狂相治疗，与重度抗抑郁药同服方可治疗双相 |
  | 安眠药片 | 睡前半小时（约现实 25 秒）服用一片，改善失眠 |
- **精神健康量表**：随时自测当前精神状态。
- **治愈**：坚持治疗可以真正康复，回到健康状态。

### 电脑与 RhythmCraft

- 游戏内可交互的**电脑方块**：桌面、此电脑、控制面板、浏览器、电子邮件、文档……
- 内置音游 **RhythmCraft**，带段位结算评价。

### 其他

- **丝带旗帜图案**：多种颜色的精神健康宣传丝带。
- 多语言文案（含简体中文、English、日本語、한국어、Русский）。

## 🎮 玩法速览

1. 开局选择精神特质，留意经验条旁的情绪表情。
2. 合成日记本（书 + 羽毛 + 花），每晚写日记了解自己的状态。
3. 状态持续变差时，去村庄找心理诊所。
4. 按诊断程度服药，注意用量；双相需抗抑郁与抗躁狂药物同服。
5. 配合休息与治疗，精神健康会逐步恢复，直至治愈。

## 📦 版本与依赖

| 项目 | 版本 |
|---|---|
| Minecraft | **1.21.1** |
| NeoForge | 21.1.219（`[21,)`） |
| Fabric | Loader 0.18.4 + Fabric API 0.116.15 |
| Architectury API | **13.0.11**（必需前置；请勿使用 19.x 等不兼容大版本） |
| Java | 21 |
| 模组版本 | 0.2.3+1.21.1 |

双端构建产物：`neoforge/build/libs/` 与 `fabric/build/libs/`。

## 🔧 本移植版说明

在移植 1.21.1 之外，本分支还包含一批实玩验证过的修复（详见提交记录）：

- 旧存档兼容：修复存档加载时效果注册对象解析导致的崩溃。
- 日记系统：修复日记空白、死亡重生吞弹屏、俄语等语言的文案串段等问题。
- 文案与本地化：补齐伤害来源死亡提示、失眠提示、界面状态名等缺失键，修复英文 / 俄文日记段落串排。
- 音游结算屏段位图引用修正。
- 新增客户端配置 `keep_diary_screen_on_respawn`（见上文）。

### 构建

```bat
.\gradlew.bat :common:publishToMavenLocal :neoforge:remapJar :fabric:remapJar -x test --no-daemon
```

## 致谢 / Credits

- 感谢原作者 **[block-137](https://github.com/block-137)** 创作了 Depression 这款模组，原仓库：[block-137/Depression](https://github.com/block-137/Depression)。本仓库为其 1.21.1（NeoForge / Fabric）移植与修复版本，原作的创意与设计全部属于原作者。
- 原作作者团队（mods.toml 署名）：block_137、SimoTCD、TheMoonAries、Tianxin_Limao、hhxkzhy、SabiGold。
- 附属模组 [depression-tiantian](https://github.com/GRAINALCOHOL/depression-tiantian) 的作者 **GRAINALCOHOL**，本移植工作也参考了其设计。
- 本分支（`neoforge-1.21.1`）的移植与修复由 **sulingqingQAQ** 完成。
- 许可证：GPL-3.0（见 [COPYING](COPYING)）。

---

## English Summary

(This mod needs Architectury API to run)

In this mod, we add two attributes to players: **emotion** and **mental health**. You can see your emotion through the emoji displayed on your XP bar. When you get hurt, your emotion will drop and you may develop PTSD related to the damage source (PTSD will make you take more emotional damage from the same source).

**Emotion** determines how your mental health changes (either up or down) and how quickly it changes. When your mental health is low, you may develop **Depression** (which has three levels depending on your mental health) and experience symptoms such as insomnia.

You can gauge your mental health by writing a diary at night. (The diary can be crafted using a book, a quill, and a flower.) If you develop severe depression, you may need to look for a psychologist, who will spawn in the psychological clinic in the village.

This repository is the **1.21.1 (NeoForge / Fabric) port with additional fixes**, ported and maintained by sulingqingQAQ. All original design belongs to block-137 and the original author team.
