#Requires -RunAsAdministrator
<#
.SYNOPSIS
  Shubhankar Cloud Gaming - Windows GPU Cloud Node Provisioning Script
.DESCRIPTION
  Configures a GPU-enabled Windows Server/Desktop machine for low-latency WebRTC cloud gaming:
  - Verifies NVIDIA GPU & NVENC hardware encoder via nvidia-smi
  - Installs ViGEmBus (Virtual Xbox 360 / DualShock 4 driver for remote Android controller input)
  - Configures Windows High Performance power plan & disables sleep
  - Opens firewall ports for WebRTC UDP ICE/RTP and Host Agent control plane
#>

Write-Host "=== Shubhankar Cloud Gaming GPU Node Setup ===" -ForegroundColor Cyan

# 1. Verify NVIDIA NVENC GPU
$nvidiaSmi = "C:\Windows\System32\nvidia-smi.exe"
if (-Not (Test-Path $nvidiaSmi)) {
    Write-Warning "nvidia-smi not found. Install NVIDIA GRID / GeForce Game Ready Driver before starting games."
} else {
    & $nvidiaSmi --query-gpu=name,driver_version,memory.total --format=csv
}

# 2. Enforce High Performance Power Plan & Disable Display/System Sleep
powercfg /SETACTIVE 8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c
powercfg /change standby-timeout-ac 0
powercfg /change monitor-timeout-ac 0

# 3. Install ViGEmBus virtual gamepad driver via winget (for remote Xbox360/DS4 injection)
if (Get-Command winget -ErrorAction SilentlyContinue) {
    winget install --id Nefarius.ViGEmBus --silent --accept-package-agreements --accept-source-agreements
}

# 4. Configure Windows Defender Firewall rules for WebRTC media & Host Agent
New-NetFirewallRule -DisplayName "Shubhankar WebRTC UDP Media" -Direction Inbound -Protocol UDP -LocalPort 49152-65535 -Action Allow -ErrorAction SilentlyContinue
New-NetFirewallRule -DisplayName "Shubhankar Host Agent" -Direction Inbound -Protocol TCP -LocalPort 48080 -Action Allow -ErrorAction SilentlyContinue

Write-Host "GPU node baseline configured. Set BACKEND_URL and HOST_AGENT_SECRET, then launch host-agent.ts." -ForegroundColor Green
