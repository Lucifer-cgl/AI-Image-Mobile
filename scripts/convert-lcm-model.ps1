param(
  [string]$Source = 'E:\AI-Models\mobile-image\image\LCM_Dreamshaper_v7_4k.safetensors',
  [string]$Clip = 'E:\AI-Models\mobile-image\text-encoder\clip-l-sd15.safetensors',
  [string]$Output = 'E:\AI-Models\mobile-image\image\lcm-dreamshaper-v7-mobile-final-q4_0.gguf',
  [string]$SdCli = 'E:\gptcodex\scratch\sdcpp-win-build\bin\sd-cli.exe'
)

$ErrorActionPreference = 'Stop'
$expected = '84feab3a32f1d36108b762b25dad483ca9e37f719b23e0cfd0fdc4ad3fd5409b'
$expectedClip = '66262104f5099c84ad0e6ec156acae57b2292caebd4d7b8699327fa745145b76'
if (!(Test-Path -LiteralPath $Source)) { throw "Source model not found: $Source" }
if (!(Test-Path -LiteralPath $Clip)) { throw "CLIP model not found: $Clip" }
if (!(Test-Path -LiteralPath $SdCli)) { throw "sd-cli not found: $SdCli" }
$actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $Source).Hash.ToLowerInvariant()
if ($actual -ne $expected) { throw "Source SHA-256 mismatch: $actual" }
$actualClip = (Get-FileHash -Algorithm SHA256 -LiteralPath $Clip).Hash.ToLowerInvariant()
if ($actualClip -ne $expectedClip) { throw "CLIP SHA-256 mismatch: $actualClip" }

& $SdCli -M convert --diffusion-model $Source --clip_l $Clip -o $Output --type q4_0 --convert-name -v
if ($LASTEXITCODE -ne 0) { throw "Model conversion failed with exit code $LASTEXITCODE" }
$file = Get-Item -LiteralPath $Output
$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $Output).Hash.ToLowerInvariant()
[pscustomobject]@{ path = $file.FullName; bytes = $file.Length; sha256 = $hash }
