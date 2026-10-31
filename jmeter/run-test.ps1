param(
    [ValidateSet('light', 'medium', 'heavy', 'custom')]
    [string]$Mode = 'medium',
    [int]$Threads = 200,
    [int]$RampUp = 10,
    [int]$Duration = 60
)

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$jmx = Join-Path $scriptDir 'flashsale-load-test.jmx'
$csv = Join-Path $scriptDir 'users.csv'
$resultsDir = Join-Path $scriptDir 'results'
# 优先读取环境变量 JMETER_HOME，未设置时回退到默认路径，可按需修改
$jmeterHome = if ($env:JMETER_HOME) { $env:JMETER_HOME } else { 'D:\jmeter\apache-jmeter-5.6.3' }
$jmeter = Join-Path $jmeterHome 'bin\jmeter.bat'

switch ($Mode) {
    'light'  { $Threads = 50;  $RampUp = 5;  $Duration = 30 }
    'medium' { $Threads = 200; $RampUp = 10; $Duration = 60 }
    'heavy'  { $Threads = 500; $RampUp = 10; $Duration = 120 }
    'custom' { }
}

if (-not (Test-Path $jmeter)) {
    throw "未找到 JMeter：$jmeter。请设置环境变量 JMETER_HOME 指向 JMeter 安装目录。"
}
if (-not (Test-Path $jmx)) { throw "未找到压测脚本：$jmx" }
if (-not (Test-Path $csv)) { throw "未找到用户数据：$csv" }
if (-not $env:MYSQL_PASSWORD) {
    Write-Warning '环境变量 MYSQL_PASSWORD 未设置，压测前的数据准备/校验命令将无法执行。'
}

New-Item -ItemType Directory -Force -Path $resultsDir | Out-Null

$setupThreads = [Math]::Min($Threads, 500)
$tempJmx = Join-Path $resultsDir 'flashsale-temp.jmx'

$content = Get-Content -Raw $jmx
$content = $content.Replace('@@THREADS@@', "$Threads")
$content = $content.Replace('@@RAMPUP@@', "$RampUp")
$content = $content.Replace('@@DURATION@@', "$Duration")
$content = $content.Replace('@@SETUP_THREADS@@', "$setupThreads")
$content = $content.Replace('@@SETUP_RAMPUP@@', '5')
# CSV 使用绝对路径，避免临时 JMX 找不到 users.csv
$content = $content.Replace('<stringProp name="filename">users.csv</stringProp>',
                            ('<stringProp name="filename">' + $csv + '</stringProp>'))
Set-Content -Path $tempJmx -Value $content -Encoding UTF8

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$jtl = Join-Path $resultsDir "flashsale-$stamp.jtl"
$report = Join-Path $resultsDir "report-$stamp"
$log = Join-Path $resultsDir 'jmeter.log'

Write-Host "档位：$Mode  线程：$Threads  ramp-up：$RampUp s  持续：$Duration s"
Write-Host "临时 JMX：$tempJmx"
Write-Host '开始压测...'

& $jmeter -n -t $tempJmx -l $jtl -e -o $report -j $log
if ($LASTEXITCODE -ne 0) { throw "JMeter 执行失败，退出码：$LASTEXITCODE" }

Write-Host "JTL：$jtl"
Write-Host "HTML 报告：$report"
Write-Host '压测结束后请执行数据校验 SQL（见 JMETER_GUIDE.md）。'
