[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
Push-Location $project
try {
    foreach ($service in @('crowdgate', 'relayforge')) {
        $path = "integrations/$service"
        if (Test-Path "$path/.git") {
            $expected = (git ls-tree HEAD -- $path) -split '\s+'
            if ($LASTEXITCODE -ne 0 -or $expected.Count -lt 3) {
                throw "Cannot inspect pinned $service source"
            }
            $actual = git -C $path rev-parse HEAD
            if ($LASTEXITCODE -ne 0 -or $actual -ne $expected[2]) {
                throw "$service source differs from its pinned commit; preserving it"
            }
            $changes = git -C $path status --porcelain
            if ($LASTEXITCODE -ne 0 -or $changes) {
                throw "$service has local changes; preserving them"
            }
        }
    }
    git submodule update --init --recursive
    if ($LASTEXITCODE -ne 0) {
        throw 'Cannot initialize integration sources'
    }

    if (-not (Test-Path -LiteralPath '.env')) {
        $names = @(
            'EVENTRUSH_DATABASE_PASSWORD',
            'EVENTRUSH_ADMIN_API_KEY',
            'RELAYFORGE_DATABASE_PASSWORD',
            'RELAYFORGE_API_KEY',
            'CG_DATABASE_PASSWORD',
            'CG_ADMIN_KEY',
            'CG_ADMISSION_SIGNING_KEY'
        )
        $lines = foreach ($name in $names) {
            $secret = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
            "$name=$secret"
        }
        $bytes = [Text.Encoding]::UTF8.GetBytes(($lines -join "`n") + "`n")
        $file = [IO.File]::Open((Join-Path $project '.env'), [IO.FileMode]::CreateNew)
        try {
            $file.Write($bytes, 0, $bytes.Length)
        } finally {
            $file.Dispose()
        }
        Write-Host 'Created local .env; keep it private'
    }

    docker compose config --quiet
    if ($LASTEXITCODE -ne 0) {
        throw 'Compose configuration is incomplete; existing .env was preserved'
    }
    docker compose up --detach --build
    if ($LASTEXITCODE -ne 0) {
        throw 'Demo startup failed; inspect docker compose logs'
    }
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        try {
            $response = Invoke-WebRequest 'http://127.0.0.1:8082/api/v1/concerts' -TimeoutSec 2
            if ($response.StatusCode -eq 200) {
                $ready = $true
                break
            }
        } catch {
            Start-Sleep -Seconds 1
        }
    }
    if (-not $ready) {
        throw 'Demo did not become ready; inspect docker compose logs'
    }
    docker compose run --rm sample-data
    if ($LASTEXITCODE -ne 0) {
        throw 'Sample setup failed; existing data was preserved'
    }
    Write-Host 'EventRush is running at http://localhost:8082'
} finally {
    Pop-Location
}
