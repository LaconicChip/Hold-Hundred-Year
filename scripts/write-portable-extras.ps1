# 便携包里的两个中文说明文件由本脚本生成，pack-portable.cmd 只负责调用它。
#
# 为什么不能继续在 pack-portable.cmd 里直接 `echo > 文件名`：
#   那个 .cmd 存的是 GBK（936），而 cmd.exe 解析批处理里的中文时用的是**当前控制台代码页**。
#   在启用了"使用 Unicode UTF-8 提供全球语言支持"的 Windows 上（活动代码页 65001），
#   GBK 字节会被当成非法的 UTF-8 序列，文件名与文件内容一起变成替换符（U+FFFD / EF BF BD）。
#   实测踩过：怎么玩.txt 落盘成 "\uFFFD\uFFFDô\uFFFD\uFFFD.txt"。
#
# 这里改用 PowerShell 7 写文件，绕开 cmd 的编码层：
#   - 脚本文件本身是 UTF-8，PowerShell 按 UTF-8 读；
#   - 文件名走 Windows 的 Unicode API，不经过任何代码页；
#   - 内容按显式编码写：说明文本 UTF-8 + BOM（记事本在任何系统上都显示中文），
#     批处理用 GBK（它要在玩家自己的 cmd 里跑，中文 Windows 上提示正常）。
param(
    [Parameter(Mandatory = $true)]
    [string]$OutDir
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $OutDir)) {
    throw "输出目录不存在：$OutDir"
}

# 怎么玩.txt
$readme = @'
《你持有一百年看看》 绿色版

1. 解压到任意文件夹（路径里有中文和空格都没关系）
2. 双击 hold-hundred-year.exe
3. 会弹出一个独立游戏窗口（没有地址栏），里面点"注册"，起个名字就能玩
4. 关掉那个游戏窗口就是退出游戏；万一没退干净，双击 停止游戏.bat

存档：同目录 data\hold_year.mv.db 这一个文件。复制它=备份，删掉它=重开一局。
同一局域网里也可以只开一份：别人用 http://你的IP:8080 进来各自注册，互不影响。
第一次运行 Windows 若提示"已保护你的电脑"：点 更多信息，再点 仍然要运行（我们没做代码签名）
窗口没弹出来时：手动开浏览器访问 http://localhost:8080 一样能玩
'@

[System.IO.File]::WriteAllText(
    (Join-Path $OutDir '怎么玩.txt'),
    $readme,
    [System.Text.UTF8Encoding]::new($true))

# 停止游戏.bat：整份文件只用 ASCII。
# 为什么不放中文：cmd 解析批处理按当前控制台代码页解码，在开了 UTF-8 代码页（65001）的机器上，
# GBK 的中文会被解成乱码，甚至被当成命令去执行（实测报 "is not recognized as an internal or
# external command"）。文件名是 Unicode（怎么写都对），内容一律 ASCII，才是跨机器唯一可靠的写法。
# 中文说明放在 怎么玩.txt 里（UTF-8+BOM，记事本任何系统上都显示正常）。
# 只杀我们自己的进程：自带浏览器也叫 chrome.exe，按映像名杀会把朋友自己的 Chrome 一起干掉。
$stop = @"
@taskkill /f /im hold-hundred-year.exe >nul 2>&1
@echo Game stopped. If the game window is still open, just close it.
@echo (Chinese instructions: see the readme file in this folder)
@pause
"@

[System.IO.File]::WriteAllText(
    (Join-Path $OutDir '停止游戏.bat'),
    $stop,
    [System.Text.Encoding]::ASCII)

Write-Host "      extras written: readme (UTF-8+BOM) + stop-game batch (ASCII)"
