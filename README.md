# TRML / TERX

> **官方仓库**：[https://github.com/yeaaeslysl/TRML-TERX](https://github.com/yeaaeslysl/TRML-TERX)

TRML 是一款基于 Shizuku 的 Android 本地 MCP（Model Context Protocol）服务器应用，让 AI Agent 能够安全地远程操控手机终端、读写文件、查看图片并执行系统级命令。它采用纯 Java 原生实现，零 Python 依赖，通过 ADB 级权限提供比 Termux 更轻量的工具链调用能力，支持 SSE/HTTP 双通道传输与多客户端并发连接。

项目内置完整的终端模拟器，配备 ExtraKeys 快捷键栏、六套预设主题（Nord / Monokai / Dracula / Solarized Dark / Light / Default）及自定义 HEX 配色系统，控制面板 UI 支持实时颜色预览与持久化。所有 MCP 工具调用均同步回显至终端，操作日志完整可追溯，安全黑名单机制拦截危险命令选项。

TERX 是 TRML 的持久化增强分支，计划集成 proot + Linux rootfs，提供完整的包管理器（apt/pkg）、独立 `$PREFIX` 目录与用户级 rc 文件加载，使安装的软件、环境变量和别名在重启后依然生效，实现接近原生 Linux 的移动开发体验。

本项目面向 AI 辅助移动运维、自动化测试与嵌入式开发场景，代码开源、架构模块化，欢迎贡献新 Tool、主题或运行时后端。

---

## 开源版本与官方分发版本

本项目同时存在两个形态：

| 形态 | 应用名 | 获取方式 | 说明 |
| :--- | :--- | :--- | :--- |
| **开源版本** | TRML / TERX | 自行从本仓库克隆源码编译 | 完全开源，可自由使用、修改、分发 |
| **官方分发版本** | T'RML / T'ERX | 作者发布的官方签名安装包 | 与开源版本功能完全一致，仅额外包含首次启动告知流程与应用加固 |

**两个版本的技术实现完全相同，不存在任何未开源的隐藏功能或算法。**

发布官方分发版本的目的是防止恶意篡改、二次打包与付费倒卖，而非隐藏技术。

**本软件完全免费。** 作者不以任何形式通过本软件获利，从未授权任何第三方以收费方式分发。若你为获得本软件支付了费用，说明你遭遇了欺诈，请立即申请退款并向平台举报。

---

## 致谢与参考

### 参考项目

-   **[Shizuku (RikkaApps)](https://github.com/RikkaApps/Shizuku)**
    -   始祖库，ADB 级权限桥接框架，本项目的核心执行基础
    -   终端 Shell 进程与全部 MCP Tool 均依赖 Shizuku 提供的权限通道运行

-   **[DSHM (RochelimitDawn)](https://github.com/RochelimitDawn/DSHM)**
    -   主题架构设计思路（`ThemeStore.kt` + `SiliconLeapTheme.kt`）
    -   终端配色方案命名规范与色彩变量组织方式
    -   proot + Linux rootfs 持久化环境设计方案（TERX 分支技术路线来源）

-   **[Moke (Briqt)](https://github.com/briqt/moke)**
    -   6 套终端配色数值移植（Nord / Monokai / Dracula / Solarized Dark / Light / Default）
    -   ExtraKeys 快捷键栏布局逻辑与按键映射表
    -   外观设置页分组结构与 HEX 颜色输入交互模式
    -   moke dark 配色体系作为控制面板默认深色主题基础

### AI 平台与工具链

-   **Qwen (通义千问)** — 全程代码生成、架构设计、问题排查、文档撰写
-   **MobIDE** — Android 项目编辑、构建、MCP Server 宿主环境（QQ 交流群：1121597624）
-   **Shizuku** — ADB 级权限桥接，终端和 MCP Tool 的执行基础

---

## 友链

-   [Linux.do 论坛](https://linux.do/)
-   [万象 API 中转站](https://mcp.babm.cn/user)
-   [万象 API 注册（邀请码 SVWFCKRJ）](https://mcp.babm.cn/register?invite=SVWFCKRJ)

> 邀请码自愿使用，不强制。

---

## 免责声明

本项目仅供技术交流与安全研究使用。使用者应自行确保已获得设备所有者授权，并遵守所在地区法律法规。开发者不对因使用本软件导致的任何数据丢失、设备损坏、隐私泄露或法律纠纷承担责任。

本软件通过 Shizuku 获取高权限执行能力，误用可能导致系统不稳定或安全风险。请在充分了解相关技术原理的前提下使用，生产环境部署前务必进行充分测试。

## 许可协议

本项目以 Apache License 2.0 开源发布。您可以自由使用、修改和分发本软件，但须保留原始版权声明、许可证副本及 NOTICE 文件（如有）。衍生作品需注明修改内容，且不得以原作者名义进行背书或推广。本软件按"现状"提供，不提供任何明示或暗示的保证。

### 修改与再分发的署名要求

本项目以 Apache License 2.0 开源。依据该协议第 4 条，任何人对本项目的使用、修改与再分发，均须：

1. 保留本项目完整的版权声明与署名（作者：yeaaeslysl）；
2. 附上 Apache License 2.0 许可证副本；
3. 对修改过的文件显著标注"已修改"；
4. 保留原始仓库地址与 NOTICE 归属信息（如有）。

同时，依据该协议第 6 条，本许可不授予 **TRML**、**TERX**、**T'RML**、**T'ERX** 等名称的商标使用权。您不得以本项目作者的名义进行推广、背书，或声称您的衍生版本为官方版本。

我们欢迎任何人基于本项目进行二次开发与创新，但请务必保留作者的署名与出处。

使用本软件即表示您已阅读、理解并同意上述全部条款。