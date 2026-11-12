$ErrorActionPreference = 'Continue'
$dirs = kubectl -n vod exec deploy/minio -- ls -1 /data/video-hls/hls | Where-Object { $_ -match '^BV' }
$out = Join-Path $env:TEMP 'vod-codec-scan.txt'
Remove-Item $out -ErrorAction SilentlyContinue
foreach ($d in $dirs) {
    $url = "http://vod-minio-service:8081/internal/download?bucket=video-hls&objectName=hls/$d/seg-00000.ts"
    $codec = kubectl -n vod exec deploy/vod-tools -- ffprobe -f mpegts -select_streams v:0 -show_entries stream=codec_name -of default=nw=1:nk=1 $url -headers 'X-Service-Token: vod-internal-token' 2>$null
    if (-not $codec) { $codec = 'none' }
    "$d $codec" | Add-Content -Path $out -Encoding utf8
}
$lines = Get-Content $out
$lines | Group-Object { ($_ -split ' ')[1] } | Select-Object Name, Count | Format-Table -AutoSize
