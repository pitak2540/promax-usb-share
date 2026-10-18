# Downloads the driver packages bundled into the Windows installer.
# Every download is optional: a missing one is reported as a warning and simply left out.
$ErrorActionPreference = 'Continue'
$root = Join-Path $PSScriptRoot 'payload'
New-Item -ItemType Directory -Force $root | Out-Null
$found = @()

function Try-Download($name, $urls, $file) {
  foreach ($u in $urls) {
    try {
      Invoke-WebRequest -Uri $u -OutFile $file -UseBasicParsing -TimeoutSec 120 -UserAgent 'Mozilla/5.0'
      if ((Get-Item $file).Length -gt 50000) { Write-Host "::notice title=DRIVER::$name <- $u"; return $true }
    } catch { Write-Host "  $name failed: $u ($($_.Exception.Message))" }
  }
  Write-Host "::warning title=DRIVER::$name not downloaded"
  if (Test-Path $file) { Remove-Item $file -Force }
  return $false
}

# 1) usbip-win2 (BSD-2-Clause, Microsoft-signed drivers) from its GitHub releases
$tag = $env:USBIP_TAG
$rel = gh release view $tag -R vadimgrn/usbip-win2 --json assets | ConvertFrom-Json
$asset = $rel.assets | Where-Object { $_.name -like '*.exe' -and $_.name -match 'x64' } | Select-Object -First 1
if (-not $asset) { $asset = $rel.assets | Where-Object { $_.name -like '*.exe' } | Select-Object -First 1 }
if ($asset) {
  gh release download $tag -R vadimgrn/usbip-win2 -p $asset.name -D $root --clobber
  Rename-Item (Join-Path $root $asset.name) 'usbip-win2-setup.exe' -Force
  Write-Host "::notice title=DRIVER::usbip-win2 $tag <- $($asset.name)"
} else { Write-Host "::error title=DRIVER::usbip-win2 asset not found for $tag"; exit 1 }

# 1b) usbipd-win (MIT, Microsoft-signed) so this PC can also share its own USB devices
$rel2 = gh release view -R dorssel/usbipd-win --json assets,tagName | ConvertFrom-Json
$msi = $rel2.assets | Where-Object { $_.name -like '*x64.msi' } | Select-Object -First 1
if (-not $msi) { $msi = $rel2.assets | Where-Object { $_.name -like '*.msi' } | Select-Object -First 1 }
if ($msi) {
  gh release download $rel2.tagName -R dorssel/usbipd-win -p $msi.name -D $root --clobber
  Rename-Item (Join-Path $root $msi.name) 'usbipd-win.msi' -Force
  Write-Host "::notice title=DRIVER::usbipd-win $($rel2.tagName) <- $($msi.name)"
} else { Write-Host "::warning title=DRIVER::usbipd-win msi not found" }

# 2) Tactrix OpenPort 2.0 driver + J2534 DLL (official installer)
Try-Download 'Tactrix OpenPort 2.0' @('https://www.tactrix.com/downloads/openport2_setup_1024820.exe') (Join-Path $root 'openport2_setup.exe') | Out-Null

# 3) USB-serial chips (INF packages installed with pnputil)
$z = Join-Path $env:RUNNER_TEMP 'cp210x.zip'
if (Try-Download 'Silicon Labs CP210x' @('https://www.silabs.com/documents/public/software/CP210x_Universal_Windows_Driver.zip') $z) {
  Expand-Archive $z (Join-Path $root 'drivers\cp210x') -Force
}
$z = Join-Path $env:RUNNER_TEMP 'ftdi.zip'
if (Try-Download 'FTDI VCP' @(
    'https://ftdichip.com/wp-content/uploads/2023/09/CDM-v2.12.36.4-WHQL-Certified.zip',
    'https://ftdichip.com/wp-content/uploads/2023/11/CDM-v2.12.36.4-WHQL-Certified.zip',
    'https://ftdichip.com/wp-content/uploads/2021/08/CDM-v2.12.36.4-WHQL-Certified.zip',
    'https://ftdichip.com/wp-content/uploads/2021/07/CDM-v2.12.36.4-WHQL-Certified.zip') $z) {
  Expand-Archive $z (Join-Path $root 'drivers\ftdi') -Force
}
Try-Download 'WCH CH340/CH341' @(
    'https://www.wch-ic.com/downloads/file/65.html',
    'https://www.wch.cn/downloads/file/65.html',
    'https://www.wch.cn/download/file?id=65') (Join-Path $root 'CH341SER.EXE') | Out-Null

# 4) Scanmatik SM2 Pro: put the official installer in installer/vendor/scanmatik-setup.exe to bundle it
$sm = Join-Path $PSScriptRoot 'vendor\scanmatik-setup.exe'
if (Test-Path $sm) { Copy-Item $sm (Join-Path $root 'scanmatik-setup.exe'); Write-Host "::notice title=DRIVER::Scanmatik SM2 Pro bundled" }
else { Write-Host "::warning title=DRIVER::Scanmatik SM2 Pro installer not in installer/vendor (skipped)" }

Get-ChildItem -Recurse $root | Select-Object FullName, Length | Format-Table -AutoSize

# Work out silent-install switches for vendor installers (Inno Setup vs NSIS vs unknown)
function Silent-Args($path) {
  if (-not (Test-Path $path)) { return '' }
  $bytes = [System.IO.File]::ReadAllBytes($path)
  $text = [System.Text.Encoding]::ASCII.GetString($bytes)
  if ($text.Contains('Inno Setup')) { return '/VERYSILENT /SUPPRESSMSGBOXES /NORESTART' }
  if ($text.Contains('Nullsoft')) { return '/S' }
  if ($text.Contains('InstallShield')) { return '/s /v"/qn REBOOT=ReallySuppress"' }
  return ''
}
$ch341 = Silent-Args (Join-Path $root 'CH341SER.EXE')
if (-not $ch341) { $ch341 = '/S' }
$ch341 = Silent-Args (Join-Path $root 'CH341SER.EXE')
if (-not $ch341) { $ch341 = '/S' }
$defs = @(
  ("#define OPENPORT_ARGS '{0}'" -f (Silent-Args (Join-Path $root 'openport2_setup.exe'))),
  ("#define CH341_ARGS '{0}'" -f $ch341),
  ("#define SCANMATIK_ARGS '{0}'" -f (Silent-Args (Join-Path $root 'scanmatik-setup.exe')))
)
$defs | Set-Content -Encoding ASCII (Join-Path $root 'args.iss')
Get-Content (Join-Path $root 'args.iss') | ForEach-Object { Write-Host "::notice title=ARGS::$_" }
