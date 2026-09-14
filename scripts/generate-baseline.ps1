param(
    [Parameter(Mandatory = $true)]
    [string] $OutputPath
)

# Generate a review candidate. Never overwrite an accepted baseline or migration.
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$migrationRoot = Join-Path $repositoryRoot 'src/main/resources/db/migration'
$manifestPath = Join-Path $repositoryRoot 'src/main/resources/db/baseline/V1-V44.sha256'
$resolvedOutput = [IO.Path]::GetFullPath($OutputPath)
if (Test-Path -LiteralPath $resolvedOutput) { throw "Output already exists: $resolvedOutput" }
if (-not (Test-Path -LiteralPath (Split-Path -Parent $resolvedOutput) -PathType Container)) {
    throw 'Create the output parent directory before running this command.'
}

$migrations = Get-ChildItem -LiteralPath $migrationRoot -File |
    Where-Object { $_.Name -match '^V(\d+)__.+\.sql$' -and [int]$Matches[1] -le 44 } |
    Sort-Object { [int]($_.Name -replace '^V(\d+)__.*', '$1') }
$expectedVersions = @(1..18) + @(21..44)
$actualVersions = @($migrations | ForEach-Object { [int]($_.Name -replace '^V(\d+)__.*', '$1') })
if (($actualVersions -join ',') -ne ($expectedVersions -join ',')) {
    throw 'The accepted V1..V44 history must contain exactly 42 migrations; V19 and V20 remain absent.'
}
foreach ($entry in Get-Content -LiteralPath $manifestPath) {
    if ([string]::IsNullOrWhiteSpace($entry) -or $entry.StartsWith('#')) { continue }
    $parts = $entry -split '  ', 2
    $actualHash = (Get-FileHash -LiteralPath (Join-Path $migrationRoot $parts[1]) -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $parts[0]) { throw "Accepted migration bytes changed: $($parts[1])" }
}

$image = 'postgres:18.6-trixie@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280'
$containerName = 'orderhub-baseline-' + [Guid]::NewGuid().ToString('N')
$databaseName = 'orderhub_baseline'
$containerStarted = $false
$schemaTemporaryFile = [IO.Path]::GetTempFileName()
$permissionsTemporaryFile = [IO.Path]::GetTempFileName()

function Invoke-DockerChecked {
    param([string[]] $Arguments)
    $result = & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed (exit $LASTEXITCODE)." }
    return $result
}

function Normalize-Dump {
    param([string[]] $Lines)
    # Strip only transport guards and pg_dump metadata. Scope dump environment
    # settings to the migration transaction so pooled runtime sessions retain
    # their original timeouts and security settings after migration completes.
    # Object definitions, object comments and function bodies stay intact.
    return @($Lines | Where-Object {
        -not ($_ -match '^\\(un)?restrict ') -and
        -not ($_ -match '^-- (Dumped from database version|Dumped by pg_dump version|PostgreSQL database dump( complete)?$)')
    } | ForEach-Object {
        if ($_ -match '^SET (statement_timeout|lock_timeout|idle_in_transaction_session_timeout|transaction_timeout|client_encoding|standard_conforming_strings|check_function_bodies|xmloption|client_min_messages|row_security|default_tablespace|default_table_access_method) = ') {
            $_ -replace '^SET ', 'SET LOCAL '
        } elseif ($_ -eq "SELECT pg_catalog.set_config('search_path', '', false);") {
            "SELECT pg_catalog.set_config('search_path', '', true);"
        } else { $_ }
    })
}

try {
    Invoke-DockerChecked -Arguments @('run', '--detach', '--rm', '--name', $containerName,
        '--env', "POSTGRES_USER=$databaseName", '--env', "POSTGRES_DB=$databaseName",
        '--env', 'POSTGRES_PASSWORD=synthetic-baseline-generation-only',
        '--mount', "type=bind,source=$migrationRoot,target=/migrations,readonly", $image) | Out-Null
    $containerStarted = $true

    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    do {
        # Query the dependency, not a fixed startup delay. The following psql
        # calls still fail closed if PostgreSQL exits after readiness succeeds.
        # TCP stays unavailable during the image's temporary initialization server.
        & docker exec $containerName pg_isready --host=127.0.0.1 --username=$databaseName --dbname=$databaseName --quiet
        $ready = $LASTEXITCODE -eq 0
        if (-not $ready) { Start-Sleep -Milliseconds 200 }
    } until ($ready -or [DateTime]::UtcNow -ge $deadline)
    if (-not $ready) { throw 'Disposable PostgreSQL did not become ready within 60 seconds.' }

    foreach ($migration in $migrations) {
        Invoke-DockerChecked -Arguments @('exec', $containerName, 'psql', "--username=$databaseName",
            "--dbname=$databaseName", '--no-psqlrc', '--set=ON_ERROR_STOP=1', '--single-transaction',
            "--file=/migrations/$($migration.Name)") | Out-Null
    }
    Invoke-DockerChecked -Arguments @('exec', $containerName, 'pg_dump', "--username=$databaseName",
        "--dbname=$databaseName", '--schema-only', '--no-owner', '--no-privileges',
        '--exclude-table=public.flyway_schema_history', '--file=/tmp/schema.sql') | Out-Null
    Invoke-DockerChecked -Arguments @('exec', $containerName, 'pg_dump', "--username=$databaseName",
        "--dbname=$databaseName", '--data-only', '--no-owner', '--no-privileges',
        '--table=access_control.permissions', '--column-inserts', '--file=/tmp/permissions.sql') | Out-Null
    # Native stdout pipelines split at CR as well as LF. pg_dump emits literal
    # CR characters inside catalog whitespace-check string literals, so copying
    # files and splitting on LF only is necessary to preserve SQL semantics.
    Invoke-DockerChecked -Arguments @('cp', "${containerName}:/tmp/schema.sql", $schemaTemporaryFile) | Out-Null
    Invoke-DockerChecked -Arguments @('cp', "${containerName}:/tmp/permissions.sql", $permissionsTemporaryFile) | Out-Null
    $schema = [IO.File]::ReadAllText($schemaTemporaryFile, [Text.Encoding]::UTF8) -split "`n"
    $permissions = [IO.File]::ReadAllText($permissionsTemporaryFile, [Text.Encoding]::UTF8) -split "`n"
    $header = @(
        '-- OH-021 frozen fresh-install projection of the accepted V1..V44 chain (42 scripts).',
        '-- Source authority: c741cb0bfbfd3c8ec66e2c96ad4bc636d357390f.',
        '-- Generated by scripts/generate-baseline.ps1 with PostgreSQL 18.6 pg_dump.',
        '-- Includes only schema and the 24 canonical permission rows; no development data.',
        '-- Accepted V scripts and this baseline remain immutable after integration.',
        '-- Future changes belong in V45+; see docs/operations/migrations.md.',
        ''
    )
    $output = ($header + (Normalize-Dump $schema) + @('', '-- Canonical permission vocabulary.') +
        (Normalize-Dump $permissions)) -join "`n"
    # pg_dump prints the exact Unicode trim set as a literal containing tabs,
    # newlines and CR. Restore the original U& notation for this one known
    # literal so the reviewed SQL has no trailing whitespace or hidden controls.
    # Do not rewrite arbitrary SQL strings or change any expression operators.
    $trimCodePoints = @(0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x001C, 0x001D, 0x001E,
        0x001F, 0x0020, 0x00A0, 0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005,
        0x2006, 0x2007, 0x2008, 0x2009, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000)
    $literalTrimSet = "'" + (($trimCodePoints | ForEach-Object { [char]$_ }) -join '') + "'"
    $escapedTrimSet = "U&'" + (($trimCodePoints | ForEach-Object { '\' + $_.ToString('X4') }) -join '') + "'"
    $occurrences = ($output.Length - $output.Replace($literalTrimSet, '').Length) / $literalTrimSet.Length
    if ($occurrences -ne 9) { throw "Expected exactly nine accepted Unicode trim literals, found $occurrences." }
    $output = $output.Replace($literalTrimSet, $escapedTrimSet).TrimEnd("`r", "`n")
    [IO.File]::WriteAllText($resolvedOutput, $output + "`n", [Text.UTF8Encoding]::new($false))
    Write-Output "Generated review candidate: $resolvedOutput"
} finally {
    # Only the exact disposable container created by this invocation is removed.
    if ($containerStarted) { & docker rm --force $containerName 2>$null | Out-Null }
    Remove-Item -LiteralPath $schemaTemporaryFile, $permissionsTemporaryFile
}
