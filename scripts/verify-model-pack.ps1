param(
    [Parameter(Mandatory = $true)][string]$File,
    [Parameter(Mandatory = $true)][string]$Sha256,
    [long]$Bytes = 0
)
$ErrorActionPreference = 'Stop'
$item = Get-Item -LiteralPath $File
if ($Bytes -gt 0 -and $item.Length -ne $Bytes) {
    throw "文件大小错误：实际 $($item.Length)，预期 $Bytes"
}
$actual = (Get-FileHash -LiteralPath $File -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne $Sha256.ToLowerInvariant()) {
    throw "SHA-256错误：实际 $actual"
}
Write-Host "校验通过：$($item.Name)"

