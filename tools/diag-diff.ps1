<#
.SYNOPSIS
    One-command diff диагностических снимков (DiagnosticSnapshotManager / StepDiagnostics).

.DESCRIPTION
    Сравнивает два набора diagnostic_snapshot_*.json по stepId и печатает регрессии:
    failureReason, fingerprint.catalogVariant и изменения маркеров экрана (text= из screenDump).
    Результат идёт в консоль и в лог (UTF-8), чтобы регрессии анализировались по diff.

    Как снять свежие снимки с устройства (директория DiagnosticSnapshotManager.diagDir):
        adb pull /sdcard/Android/data/com.xiaohypercleaner/files/diag diag-after

.EXAMPLE
    ./tools/diag-diff.ps1
    ./tools/diag-diff.ps1 -Before diag-dumps -After diag-after -Log diag-diff.log
#>
param(
    [string]$Before = "diag-dumps",
    [string]$After = "diag-after",
    [string]$Log = "diag-diff.log",
    [int]$MaxMarkers = 40
)

# Относительные пути резолвим от корня репозитория (родитель tools/), а не от CWD запуска.
$repoRoot = Split-Path -Parent $PSScriptRoot

function Resolve-Dir([string]$d) {
    if ([System.IO.Path]::IsPathRooted($d)) { return $d }
    return (Join-Path $repoRoot $d)
}

function Read-Snapshots([string]$dir) {
    $map = @{}
    if (-not (Test-Path $dir)) { return $map }
    Get-ChildItem -Path $dir -Filter 'diagnostic_snapshot_*.json' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime |
        ForEach-Object {
            try {
                $raw = [System.IO.File]::ReadAllText($_.FullName, [System.Text.Encoding]::UTF8)
                $j = $raw | ConvertFrom-Json
                # Последний по времени снимок шага побеждает.
                $map[[string]$j.stepId] = $j
            } catch {
                Write-Warning "skip $($_.FullName): $($_.Exception.Message)"
            }
        }
    return $map
}

function Get-Markers($snapshot) {
    if (-not $snapshot -or -not $snapshot.screenDump) { return @() }
    @(($snapshot.screenDump -split "`n") |
        ForEach-Object { if ($_ -match 'text="([^"]{2,})"') { $matches[1] } } |
        Select-Object -Unique)
}

$Before = Resolve-Dir $Before
$After = Resolve-Dir $After
$beforeMap = Read-Snapshots $Before
$afterMap = Read-Snapshots $After

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("# diag-diff: $Before -> $After  ($(Get-Date -Format s))")

$allSteps = @($beforeMap.Keys) + @($afterMap.Keys) | Sort-Object -Unique
$regressions = 0

foreach ($step in $allSteps) {
    $b = $beforeMap[$step]
    $a = $afterMap[$step]
    if (-not $a) { $lines.Add("[GONE]       $step (was: $($b.failureReason))"); continue }
    if (-not $b) { $lines.Add("[NEW]        $step -> $($a.failureReason)"); continue }

    $bVariant = $b.fingerprint.catalogVariant
    $aVariant = $a.fingerprint.catalogVariant
    $bM = Get-Markers $b
    $aM = Get-Markers $a
    $added = @($aM | Where-Object { $bM -notcontains $_ })
    $removed = @($bM | Where-Object { $aM -notcontains $_ })

    $status = "[OK]         "
    if ($a.failureReason -and -not $b.failureReason) { $status = "[REGRESSION] "; $regressions++ }
    elseif (-not $a.failureReason -and $b.failureReason) { $status = "[FIXED]      " }
    elseif ($bVariant -ne $aVariant -or $added.Count -gt 0 -or $removed.Count -gt 0) { $status = "[CHANGED]    " }

    $lines.Add("$status$step  reason: $($b.failureReason) -> $($a.failureReason)")
    if ($bVariant -ne $aVariant) { $lines.Add("             variant: $bVariant -> $aVariant") }
    if ($added.Count -gt 0) {
        $lines.Add("             + markers: $((($added | Select-Object -First $MaxMarkers) -join ' | '))")
    }
    if ($removed.Count -gt 0) {
        $lines.Add("             - markers: $((($removed | Select-Object -First $MaxMarkers) -join ' | '))")
    }
}

$lines.Add("")
$lines.Add("steps=$($allSteps.Count) regressions=$regressions")

$report = $lines -join [Environment]::NewLine
Write-Output $report
[System.IO.File]::WriteAllText(
    (Join-Path (Get-Location) $Log), $report, [System.Text.UTF8Encoding]::new($false)
)
Write-Output "written: $Log"