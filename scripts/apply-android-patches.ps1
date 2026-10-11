$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$ggmlRoot = Join-Path $repoRoot 'third_party\stable-diffusion.cpp\ggml'
$patch = Join-Path $repoRoot 'patches\android-mali-vulkan.patch'
$sdRoot = Join-Path $repoRoot 'third_party\stable-diffusion.cpp'
$lcmPatch = Join-Path $repoRoot 'patches\lcm-diffusers-convert.patch'

if (-not (Test-Path (Join-Path $ggmlRoot '.git'))) {
    throw 'stable-diffusion.cpp/ggml 未初始化，请先运行 git submodule update --init --recursive'
}

$alreadyApplied = git -C $ggmlRoot apply --reverse --check $patch 2>$null
if ($LASTEXITCODE -eq 0) { Write-Host 'Android Mali Vulkan补丁已经应用。' }
else {
    git -C $ggmlRoot apply --check $patch
    if ($LASTEXITCODE -ne 0) { throw 'Android Mali补丁与当前固定依赖版本不匹配。' }
    git -C $ggmlRoot apply $patch
    if ($LASTEXITCODE -ne 0) { throw 'Android Mali补丁应用失败。' }
    Write-Host 'Android Mali Vulkan补丁应用完成。'
}

git -C $sdRoot apply --reverse --check $lcmPatch 2>$null
if ($LASTEXITCODE -eq 0) { Write-Host 'LCM Diffusers转换补丁已经应用。' }
else {
    git -C $sdRoot apply --check $lcmPatch
    if ($LASTEXITCODE -ne 0) { throw 'LCM Diffusers转换补丁与当前固定依赖版本不匹配。' }
    git -C $sdRoot apply $lcmPatch
    if ($LASTEXITCODE -ne 0) { throw 'LCM Diffusers转换补丁应用失败。' }
    Write-Host 'LCM Diffusers转换补丁应用完成。'
}

