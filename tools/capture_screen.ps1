# Съём текущего экрана устройства для разведки каталога.
#
# Владелец открывает нужный экран вручную (adb-инъекция касаний на MIUI
# блокируется), скрипт фиксирует дамп дерева и скриншот, печатает фокус-пакет
# и компактный список текстов/описаний — этого достаточно, чтобы прописать
# маршрут, маркеры экрана и тип переключателя в semantic_steps.json.
#
# Пример: powershell -File tools/capture_screen.ps1 -Name music_settings

param(
    [Parameter(Mandatory = $true)][string]$Name,
    [string]$OutDir = 'diag-dumps/adb-probe'
)

$ErrorActionPreference = 'SilentlyContinue'
$adb = if ($env:ADB) { $env:ADB } else { "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" }
if (-not (Test-Path $adb)) { Write-Output "ADB not found: $adb"; exit 1 }
New-Item -ItemType Directory -Force $OutDir | Out-Null

$xml = Join-Path $OutDir "$Name.xml"
$png = Join-Path $OutDir "$Name.png"

& $adb shell uiautomator dump /sdcard/_capture.xml *> $null
& $adb pull /sdcard/_capture.xml $xml *> $null
& $adb exec-out screencap -p > $png 2>$null

Write-Output "--- focus ---"
(& $adb shell dumpsys window 2>$null | Select-String 'mCurrentFocus').Line

Write-Output "--- nodes (clickable / labelled) ---"
[xml]$doc = Get-Content -Raw -Encoding UTF8 $xml
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

Write-Output "saved: $xml / $png"
exit 0