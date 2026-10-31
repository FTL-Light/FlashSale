# 生成模拟 JTL 与 HTML 报告，仅用于演示报告结构。
# 这些数据不是真实压测结果，不能作为性能结论。
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$resultsDir = Join-Path $scriptDir 'results'
$jmeterHome = if ($env:JMETER_HOME) { $env:JMETER_HOME } else { 'D:\jmeter\apache-jmeter-5.6.3' }
$jmeter = Join-Path $jmeterHome 'bin\jmeter.bat'

New-Item -ItemType Directory -Force -Path $resultsDir | Out-Null
$jtl = Join-Path $resultsDir 'flashsale-simulate.jtl'

$header = 'timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect'
$builder = [System.Text.StringBuilder]::new()
[void]$builder.AppendLine($header)

$start = [DateTimeOffset]::UtcNow.AddSeconds(-30).ToUnixTimeMilliseconds()
$sampleCount = 4250
for ($i = 0; $i -lt $sampleCount; $i++) {
    $ts = $start + ($i * 7)
    $elapsed = 18 + ($i % 12)
    [void]$builder.AppendLine("$ts,$elapsed,POST /api/seckill/1,200,OK,ThreadGroup 1-1,text,true,,320,180,10,20,http://localhost:8080/api/seckill/1,$elapsed,0,2")
    $ts2 = $start + ($i * 7) + 3
    $elapsed2 = 3 + ($i % 3)
    [void]$builder.AppendLine("$ts2,$elapsed2,GET /api/order/result/1,200,OK,ThreadGroup 1-1,text,true,,110,0,10,20,http://localhost:8080/api/order/result/1,$elapsed2,0,0")
}

Set-Content -Path $jtl -Value $builder.ToString() -Encoding UTF8
Write-Host "已生成模拟 JTL：$jtl（$($sampleCount * 2) 条样本，非真实压测）"

if (Test-Path $jmeter) {
    $report = Join-Path $resultsDir 'report'
    if (Test-Path $report) { Remove-Item -Recurse -Force $report }
    & $jmeter -g $jtl -o $report
    Write-Host "已生成 HTML 报告：$report"
} else {
    Write-Warning "未找到 JMeter：$jmeter，跳过 HTML 报告生成。"
}
