# Переход по экрану: снять дамп, тапнуть по тексту/описанию, снять дамп после.
#
# Нужен для автономной разведки каталога: adb-инъекция касаний на этом устройстве
# работает (владелец выдал права), поэтому экраны можно проходить скриптом.
#
# Пример: powershell -File tools/tap_text.ps1 -Name sys_apps -Tap "Приложения"

param(
    [Parameter(Mandatory = $true)][string]$Name,
    [Parameter(Mandatory = $true)][string]$Tap,
    [int]$Index = 1,
    [int]$WaitSec = 3,
    [string]$OutDir = 'diag-dumps/adb-probe'
)

$ErrorActionPreference = 'SilentlyContinue'
$adb = if ($env:ADB) { $env:ADB } else { "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" }
New-Item -ItemType Directory -Force $OutDir | Out-Null

function Dump([string]$target) {
    & $adb shell uiautomator dump /sdcard/_nav.xml *> $null
    & $adb pull /sdcard/_nav.xml $target *> $null
}

function Show([string]$target) {
    [xml]$doc = Get-Content -Raw -Encoding UTF8 $target
    foreach ($n in $doc.SelectNodes('//node')) {
        $text = $n.text
        $desc = $n.'content-desc'
        $empty = (-not $text -or -not $text.Trim()) -and (-not $desc -or -not $desc.Trim())
        if ($empty -or $n.bounds -eq '[0,0][0,0]') { continue }
        $id = ($n.'resource-id' -replace '^.*/', '')
        $cls = ($n.class -replace '^.*\.', '')
        Write-Output ("{0} clk={1} chk={2} ckd={3} id={4} cls={5} | '{6}' '{7}'" -f `
            $n.bounds, $n.clickable, $n.checkable, $n.checked, $id, $cls, $text, $desc)
    }
}

$before = Join-Path $OutDir "$Name.xml"
$after = Join-Path $OutDir "$Name`_after.xml"
Dump $before

[xml]$doc = Get-Content -Raw -Encoding UTF8 $before
# MIUI вставляет в подписи мягкий перенос (U+00AD) — сравнение без него.
$matches = @($doc.SelectNodes('//node') | Where-Object {
        $t = ($_.text -replace "`u{00AD}", '')
        $dc = ($_.'content-desc' -replace "`u{00AD}", '')
        ($t -and $t.Trim() -eq $Tap) -or ($dc -and $dc -like "*$Tap*")
    })
Write-Output "--- focus before ---"
(& $adb shell dumpsys window | Select-String 'mCurrentFocus').Line
Write-Output ("matches=$($matches.Count) tap='$Tap'")
if ($matches.Count -lt $Index) { Write-Output 'tap target not found'; exit 1 }

$node = $matches[$Index - 1]
$bounds = $node.bounds
if ($bounds -match '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
    $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    Write-Output "tap at $x,$y"
    & $adb shell input tap $x $y *> $null
    Start-Sleep -Seconds $WaitSec
} else {
    Write-Output 'no bounds'; exit 1
}

Dump $after
Write-Output "--- after ---"
(& $adb shell dumpsys window | Select-String 'mCurrentFocus').Line
Show $after
exit 0