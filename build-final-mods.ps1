# Builds every ChessCraft release jar and collects them in final-mods\<Loader>\.
#   powershell -ExecutionPolicy Bypass -File build-final-mods.ps1
# Each build is a clean build without the development-only smoke test, so the jars are release jars.

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$out = Join-Path $root 'final-mods'

$builds = @(
	@{ Loader = 'Fabric';   Dir = '.';                     Jar = 'chesscraft-1.0.0.jar';                   Name = 'chesscraft-fabric-1.21.11-1.0.0.jar' },
	@{ Loader = 'Fabric';   Dir = 'ports\fabric\26.1';     Jar = 'chesscraft-fabric-26.1-1.0.0.jar' },
	@{ Loader = 'Fabric';   Dir = 'ports\fabric\26.2';     Jar = 'chesscraft-fabric-26.2-1.0.0.jar' },
	@{ Loader = 'Fabric';   Dir = 'ports\fabric\26.3';     Jar = 'chesscraft-fabric-26.3-1.0.0.jar' },
	@{ Loader = 'NeoForge'; Dir = 'ports\neoforge\1.21.11'; Jar = 'chesscraft-neoforge-1.21.11-1.0.0.jar' },
	@{ Loader = 'NeoForge'; Dir = 'ports\neoforge\26.1';   Jar = 'chesscraft-neoforge-26.1.2-1.0.0.jar' },
	@{ Loader = 'NeoForge'; Dir = 'ports\neoforge\26.2';   Jar = 'chesscraft-neoforge-26.2-1.0.0.jar' },
	@{ Loader = 'NeoForge'; Dir = 'ports\neoforge\26.3';   Jar = 'chesscraft-neoforge-26.3-1.0.0.jar' },
	@{ Loader = 'Forge';    Dir = 'ports\forge\1.21.11';   Jar = 'chesscraft-forge-1.21.11-1.0.0.jar' },
	@{ Loader = 'Forge';    Dir = 'ports\forge\26.1';      Jar = 'chesscraft-forge-26.1.2-1.0.0.jar' },
	@{ Loader = 'Forge';    Dir = 'ports\forge\26.2';      Jar = 'chesscraft-forge-26.2-1.0.0.jar' },
	@{ Loader = 'Forge';    Dir = 'ports\forge\26.3';      Jar = 'chesscraft-forge-26.3-1.0.0.jar' }
)

foreach ($loader in 'Fabric', 'NeoForge', 'Forge') {
	$dir = Join-Path $out $loader
	New-Item -ItemType Directory -Force $dir | Out-Null
	Get-ChildItem $dir -Filter '*.jar' | Remove-Item -Force
}

$failed = @()
foreach ($b in $builds) {
	$dir = Join-Path $root $b.Dir
	Write-Host "=== $($b.Loader) $($b.Jar)"
	Push-Location $dir
	try {
		& .\gradlew.bat clean build --console=plain -q
		$ok = $LASTEXITCODE -eq 0
	} finally {
		Pop-Location
	}
	$jar = Join-Path $dir "build\libs\$($b.Jar)"
	if (-not $ok -or -not (Test-Path $jar)) {
		$failed += $b.Jar
		continue
	}
	$name = if ($b.Name) { $b.Name } else { $b.Jar }
	Copy-Item $jar (Join-Path (Join-Path $out $b.Loader) $name) -Force
}

Write-Host ''
Get-ChildItem $out -Recurse -Filter '*.jar' | ForEach-Object { '{0,-10} {1}' -f $_.Directory.Name, $_.Name }
if ($failed.Count -gt 0) {
	Write-Host "FAILED: $($failed -join ', ')"
	exit 1
}
