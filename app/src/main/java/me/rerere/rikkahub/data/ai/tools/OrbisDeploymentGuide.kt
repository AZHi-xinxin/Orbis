package me.rerere.rikkahub.data.ai.tools

/** Authored, query-only instructions. Never added to a conversation without orbis_help. */
internal val orbisDeploymentChapters = listOf(
    OrbisManualChapter("human_operations", "人类操作教学：从哪里点、填什么、怎么看成功", "聊天输入框上方 → 连接设置；右下角北斗导航 → 工具娱乐", listOf("orbis_help", "orbis_configure", "orbis_propose_connection"), listOf(
        OrbisManualSection("先查再教", "用户说不会、找不到、给我装ST时，不让用户解释技术名词。先查相应guide；每一步写‘在哪个页面→点哪个按钮→填什么→成功显示什么→怎么回聊天’。可以读取的配置先inspect，工作区先workspace_status，不重复向用户索要已有非秘密信息。不把整份教程每轮注入，也不为了查说明调用模型或联网。其它开源部署先查其官方安装文件和实际环境，再给相应步骤，不能把ST教程当所有软件通用脚本。"),
        OrbisManualSection("常用入口", "当前聊天输入框上方‘连接设置’可反复打开本助手已有连接建议；收起或点×回聊天，配置建议不删除。右下角北斗导航→工具娱乐→AI连接建议也是入口。工作区：右下角北斗导航→工具娱乐→工作区→当前助手对应名称。外观：聊天侧栏→自定义美化，仍在侧栏返回会话列表；AI主题须用户启用。模型/MCP完整设置可从北斗导航→系统设置进入对应提供方或MCP页；不要把用户引到其它助手的连接。"),
        OrbisManualSection("权限和成功标准", "未安装Rootfs、工具没注册、等待本次审批、系统权限关闭是不同问题。先告诉人类当前缺哪一项，只打开所需项。‘允许一次’只放行这次；记住授权减少以后确认，但不自动安装环境或授予系统权限。‘已保存’不是‘已连通’，‘能列工具’不是‘工具实际执行成功’。收起窗口不会自动发送消息或提交未填内容。")
    )),
    OrbisManualChapter("workspace_setup", "工作区与 Rootfs：安装前的真实检查", "右下角北斗导航 → 工具娱乐 → 工作区 → 当前助手工作区 → 启用 Shell", listOf("orbis_configure", "workspace_shell"), listOf(
        OrbisManualSection("AI先检查", "调用orbis_configure(action=workspace_status)，读取workspace_name、rootfs_installed、shell_ready与next_steps。未绑定时先workspace_prepare，它只创建并绑定本助手文件工作区，不安装Linux。文件工具能用不代表workspace_shell能用；Shell审批未记住不代表Shell没启用。安装中等待，不重复启动安装。"),
        OrbisManualSection("教人类安装", "可直接对人类说：请点右下角北斗导航→工具娱乐→工作区，点我对应的工作区名称，再找到‘启用 Shell’这张卡，点‘安装 Rootfs’，在弹窗确认安装。保持网络通畅，等‘Shell 状态’显示‘就绪’，按钮变成‘重新安装 Rootfs’，然后回原聊天告诉我‘安装好了’。这是Linux文件环境，不是手机root权限，不需要解锁手机。若按钮本来就是重新安装，先核实状态，别为了试试覆盖旧环境。"),
        OrbisManualSection("权限与故障", "人类回报后再workspace_status并执行短环境探针，查系统、架构、Python与空间，不只相信按钮。工具执行弹窗解释本次命令目的，让人类选允许一次或记住。没出现workspace_shell先查orbis_help(topic=tools)及绑定，必要时开始下一次正常回复刷新工具目录。下载失败先核对提示、网络和空间，不清工作区、不反复重装。命令超时先查是否仍运行、安装文件是否已生成，不能无脑重跑。"),
        OrbisManualSection("文件与秘密", "工作区内文件在/workspace/，是当前助手独立空间；手机无须ADB或开发者模式才能运行它。SSH私钥在这里生成，只交给目标机.pub公钥；私钥、密码和API Key不要写进聊天/命令输出。原生连接准备页可以把当前模型资料写入/workspace/.orbis-st-upstream.json；只让部署脚本读取并通过获准的SSH目标传递，禁止cat、调试打印、提交Git或上传到其它服务。")
    )),
    OrbisManualChapter("st_install", "给我装 ST：许愿式部署完整流程", "当前助手聊天 → 连接设置；安装前查工作区", listOf("orbis_configure", "orbis_propose_connection", "workspace_shell", "orbis_help"), listOf(
        OrbisManualSection("一句话接单与一次收齐", "用户说‘给我装ST’即先帮助规划部署，不要求他懂命令。第一轮先workspace_status与inspect，接着问：装在这部手机、家中电脑还是VPS？ST用当前模型还是另外一个？已有ST要保留还是明确要新建？已知信息不用再问。目标为手机读st_phone、电脑读st_computer、VPS读st_vps；接着继续分页读本章直到next_offset为空。只问该路线缺的资料，并附取得资料的点击路径和成功标志，不一开始抛Base URL/SSH等术语让人猜。"),
        OrbisManualSection("三种密钥不要混淆", "部署前需要模型服务商的API Key，它用来支付和调用上游模型；新ST会自动生成两把不同钥匙：网关密钥用于聊天模型连接，MCP密钥用于记忆工具连接。后两把不是用户提前编的，也不是把上游Key抄两遍。不要让用户发任何密钥到聊天。当前模型可由inspect取得非秘密地址/真实model ID；调用orbis_propose_connection(action=show_guide)，让人类核对页上模型与地址后点‘将此模型凭据准备到当前工作区’，回聊天说准备好。脚本读取/workspace/.orbis-st-upstream.json，不输出内容。其它模型先用原生连接建议让人类填上游Key、选择对应型号，确认后再准备。"),
        OrbisManualSection("从哪里找模型资料", "优先复用用户现有连接，保留其原模型ID以便失败时回去。另选模型时，告诉用户打开该服务商官网→API/开发者平台→模型文档，复制‘API地址/Base URL’和‘模型ID’给你；网页里的显示昵称不是必然的模型ID。密钥在该平台‘API Keys/密钥管理’创建，只填Orbis原生页，不发给AI。ST目前走OpenAI兼容Chat Completions，拿到Claude/Google专用接口不能直接当兼容地址。明确核对上游工具/图片能力，别凭型号名字猜。"),
        OrbisManualSection("AI负责安装", "官方源码https://github.com/AZHi-xinxin/StillerBrain。先读目标版本README、docs/INSTALL.md与scripts/install_stiller.py，再按真实系统架构选择锁文件：Linux CPython3.12 GIL、glibc至少2.34；Windows x64 CPython3.14 GIL。源码、私有配置/数据库、独立venv分开；先检查空闲端口和磁盘，不覆盖旧实例。安装器create_installation负责生成随机独立token、权限与配置；机器流程可写受控脚本调用已检查版本的安装函数，秘密由文件读取，不放命令行/回执。不要编造不存在的一键部署接口。依赖按对应锁文件保留哈希验证，不为绕报错禁鉴权或全局装包。"),
        OrbisManualSection("网络慢和保持运行", "安装脚本的pip可能硬编码pypi.org并带--isolated，环境变量镜像不一定生效。先读取该版本参数；如选可信镜像，只替换索引并保留相同锁文件、require-hashes、only-binary与no-deps约束，说明选用来源，不能暗中改依赖版本。安装检查可能会停止临时服务，之后要正式启动。桌面/VPS可配置专用用户级服务；无sudo不等于不能user systemd，先核对用户manager/linger。不能自启时明确告诉用户重启后需做哪一步，不能把shell后台进程当永久自启。手机则受Android后台限制。"),
        OrbisManualSection("把两把ST钥匙交给人类", "服务就绪后，把生成的STBRAIN_GATEWAY_TOKEN、STBRAIN_MCP_TOKEN通过部署脚本写入当前Orbis工作区的专用JSON（例如/workspace/st-connection-keys.json），只包含这两项，权限600，不打印，也不要把整份服务器config.env搬回来。propose分别创建model_openai和mcp_http两个建议，auth=bearer、credential_file为该路径；模型填写真实网关别名与完整/v1地址，MCP填完整/mcp地址。新模型工具默认开；supports_images根据真实上游明确填写。人类原生指引页可查看/复制/另存两把钥匙，不通过工具结果返回AI。"),
        OrbisManualSection("先保存再填写，随时重新打开", "告诉用户：我已经准备好了，请在指引页把两把钥匙另存好；收起可看我说的话，准备好后说‘已妥善保存’。然后orbis_propose_connection(action=open_setup,id=对应建议)打开填写。指引也可再次show_guide；同一条建议无需重新创建。人类网关页填网关密钥、MCP页填MCP密钥并开启连接；‘连接设置’固定入口可自己拉起。关闭不会删除建议，未保存输入不会偷偷提交。工具只收到配置元信息，不返回所填密钥。"),
        OrbisManualSection("最后验证和回退", "status看保存/启用；orbis_configure check分别测模型与MCP，再check_status取真实回执。新助手选网关时保留原直连型号，若失败先切回继续交流。随后必须实际调用ST health、执行一条明确标识的测试记忆写入再读回（遵循用户测试授权），验证工具续轮；只列目录不能说全部成功。分别报告安装、保持运行、模型回复、工具调用、记忆读回、重启后恢复各项证据。隐私配置/私钥保留在受限本地路径，交付不附密钥。清理仅删除本次明确试验资产，不能顺手删旧记忆。")
    )),
    OrbisManualChapter("st_phone", "ST装在这部手机：无需查调试端口", "右下角北斗导航 → 工具娱乐 → 工作区", listOf("orbis_configure", "workspace_shell"), listOf(
        OrbisManualSection("先说清楚", "若就在安装Orbis的这部手机部署，不需要用户打开无线调试、找ADB端口或购买VPS。先按workspace_setup准备当前助手Rootfs；AI自己读取架构、glibc、Python、磁盘和监听端口。手机休眠可能停进程，不能承诺后台永久在线。"),
        OrbisManualSection("两条路径别混用", "Orbis工作区已经是PRoot Linux，先核实是兼容Ubuntu/glibc与CPython3.12，再按Linux ARM64锁文件安装；不要在它里面套Termux命令或安装另一个proot。独立Termux路线是实验能力，按源码docs/PHONE-LOCAL.md和scripts/install_phone.py当前说明，Termux→proot-distro5.x→Ubuntu24.04 ARM64→CPython3.12；原生Termux的Bionic/Python3.14不能直接吃glibc锁文件。prepare默认预览不是已经装好；不要跳过平台校验。留至少3GiB空间，真实下载与完整手机链路要现场验。"),
        OrbisManualSection("连接及后台", "同一手机的网关/MCP可以用127.0.0.1对应实际空闲端口，不把电脑的127.0.0.1照抄成手机服务器。按st_install生成两把钥匙并走原生窗口。系统后台权限路径因品牌不同，先问系统品牌/读取设备事实，再教用户在系统设置→应用→Orbis→电池相关页允许所需后台运行；只说适用于当前系统的可见选项。关闭重开并实际请求，未验收则标未验，不清数据。")
    )),
    OrbisManualChapter("st_computer", "ST装在电脑：先取得地址与登录方式", "电脑网络设置；Orbis当前助手工作区", listOf("workspace_shell", "orbis_configure"), listOf(
        OrbisManualSection("用户需要发哪些", "先确认电脑是Windows还是Linux、手机和电脑是否同一Wi-Fi、是否已有SSH。请用户提供系统名称、IPv4地址、SSH端口、独立账号名；都不是密码。已有Tailnet时可用该电脑Tailscale设备页的100.x地址，两端保持连接。电脑要保持开机，睡眠/关机时ST不在线。"),
        OrbisManualSection("从哪里找地址", "Windows：点开始菜单，输入终端并打开，复制执行ipconfig，只把当前连接Wi-Fi或以太网下的IPv4地址发来；用户名可用whoami确认。Linux：打开设置→网络或Wi-Fi→当前连接旁齿轮→详情，复制IPv4；也可打开终端执行hostname -I，只发正在用的局域网地址。若多个地址不确定，让用户发地址列表或脱敏截图，你来判断，不让人猜。"),
        OrbisManualSection("首次开通登录", "AI在自己的Orbis工作区生成SSH密钥，私钥留本地，只把.pub公钥给电脑登记。电脑没有SSH服务时不能假装已可部署：Windows开始菜单搜索‘可选功能’，添加OpenSSH Server；Ubuntu使用官方openssh-server包。AI应根据确认的系统给一份可复制、说明作用的最小配置脚本，由人在电脑本地终端运行、密码只输入系统窗口；建立独立部署账号并登记公钥后再连接。不要要求新手把管理员密码或私钥发来，也不默认加sudo/开放全网。官方操作参考：learn.microsoft.com/windows-server/administration/openssh/openssh_install_firstuse 与 ubuntu.com/server/docs/openssh-server/。"),
        OrbisManualSection("连接到手机", "先验证SSH主机指纹与人类电脑提供的一致，再从Orbis实际登录。电脑上的127.0.0.1手机访问不到；为ST设置明确的私网HTTPS入口或受控隧道，并同步MCP外部resource URL/允许主机配置。没有可达入口不能只填个公网地址就宣称完成。不得为方便而关鉴权或把数据库端口公开。确认后回st_install继续，用户只填原生窗口里两把生成的钥匙。")
    )),
    OrbisManualChapter("st_vps", "ST装在VPS：新手取得部署资料", "云厂商官网 → 控制台 → 云服务器 → 实例详情", listOf("workspace_shell", "orbis_configure"), listOf(
        OrbisManualSection("一次给齐资料", "告诉用户：请登录购买服务器的网站，进控制台→云服务器/实例→点要使用的那台，复制系统名称、IP地址；在远程连接说明里看SSH端口和账号名。只把这些文字发给我，不发登录密码、私钥或云账号验证码。已有Tailscale时优先给设备列表中的私网地址。不同厂商标签不一样，用户说找不到时先问厂商，给对应实际步骤，不让人猜。"),
        OrbisManualSection("首次授权与范围", "AI在自己的工作区生成公钥，给人类一份针对已确认系统的账号开通脚本。人类在云控制台的浏览器远程连接中本地运行，建立独立部署账号、登记公钥；只给本次部署需要的目录/端口。若用户已有专用登录，先试现有权限，不把无sudo当故障。已有生产ST/OB时新建独立实例目录/端口，不覆盖现有脑库、密钥或服务。"),
        OrbisManualSection("服务与对外入口", "核对空间、系统、Python后按st_install安装；绑定本机回环并通过有鉴权的私网HTTPS提供网关与MCP。手机需要在相同私网在线才可连；无需承诺关闭Tailnet也能用。用户级systemd可以守护服务，缺linger时解释这项系统权限由管理员一次配置，不能静默提权。安装与重启恢复分别验证。连接失败区分SSH、DNS/TLS、端口、401密钥与上游模型错误，保留原直连模型用于继续排障。")
    ))
)
