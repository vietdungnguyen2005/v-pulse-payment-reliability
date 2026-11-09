param([string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$backend = Join-Path $repo 'backend'
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $backend 'target/portfolio-evidence/recovery' }
$destination = [IO.Path]::GetFullPath($OutputDirectory)
$generated = Join-Path $destination 'build/tests/dev/vpulse'
New-Item -ItemType Directory -Path $generated -Force | Out-Null
$sha = (& git -C $repo rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve source revision' }
if (& git -C $repo diff HEAD --name-only -- backend/src backend/pom.xml) { throw 'Backend source differs from HEAD' }
$fixture = Join-Path $backend 'src/test/java/dev/vpulse/PaymentReliabilityIntegrationTest.java'
Copy-Item -LiteralPath $fixture -Destination (Join-Path $generated 'PaymentReliabilityIntegrationTest.java')
$harness = Join-Path $PSScriptRoot 'PortfolioRecoveryEvidenceTest.java'
Copy-Item -LiteralPath $harness -Destination (Join-Path $generated 'PortfolioRecoveryEvidenceTest.java')
$fixtureHash = (Get-FileHash -LiteralPath $fixture -Algorithm SHA256).Hash
$harnessHash = (Get-FileHash -LiteralPath $harness -Algorithm SHA256).Hash
[xml]$pom = Get-Content (Join-Path $backend 'pom.xml') -Raw
$ns = $pom.DocumentElement.NamespaceURI
$buildNode = $pom.project.build
function Add-Element($parent, $name, $value) {
  $node = $pom.CreateElement($name, $ns); $node.InnerText = $value
  $null = $parent.AppendChild($node); return $node
}
$null = Add-Element $buildNode 'sourceDirectory' (Join-Path $backend 'src/main/java')
$null = Add-Element $buildNode 'testSourceDirectory' (Join-Path $destination 'build/tests')
foreach ($pair in @(@('resources','resource',(Join-Path $backend 'src/main/resources')), @('testResources','testResource',(Join-Path $backend 'src/test/resources')))) {
  $outer = $pom.CreateElement($pair[0], $ns); $inner = $pom.CreateElement($pair[1], $ns)
  $null = Add-Element $inner 'directory' $pair[2]; $null = $outer.AppendChild($inner); $null = $buildNode.AppendChild($outer)
}
$pomFile = Join-Path $destination 'build/pom.xml'; $pom.Save($pomFile)
$attempt = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
$env:PORTFOLIO_EVIDENCE_OUTPUT = Join-Path $destination ('recovery-' + $attempt + '.json')
$env:PORTFOLIO_SOURCE_SHA = $sha
$log = Join-Path $destination ('recovery-' + $attempt + '.log')
$started = [DateTime]::UtcNow
$wrapper = if ($IsWindows) { Join-Path $backend 'mvnw.cmd' } else { Join-Path $backend 'mvnw' }
Push-Location $backend
try {
  & $wrapper -q -f $pomFile '-Dtest=PortfolioRecoveryEvidenceTest#captureFailureRecoveryMatrix' test *> $log
  $code = $LASTEXITCODE
} finally { Pop-Location }
[ordered]@{
  sourceSha=$sha; startedAtUtc=$started.ToString('o'); finishedAtUtc=[DateTime]::UtcNow.ToString('o')
  exitCode=$code; fixtureSha256=$fixtureHash; harnessSha256=$harnessHash
  resultFile=[IO.Path]::GetFileName($env:PORTFOLIO_EVIDENCE_OUTPUT)
  logFile=[IO.Path]::GetFileName($log)
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $destination ('attempt-' + $attempt + '.json')) -Encoding utf8
if ($code -ne 0) { throw ('Evidence run failed; local log: ' + $log) }
$xmlPath = Join-Path $destination 'build/target/surefire-reports/TEST-dev.vpulse.PortfolioRecoveryEvidenceTest.xml'
if ((Get-Item -LiteralPath $xmlPath).LastWriteTimeUtc -lt $started) { throw 'Stale Surefire report' }
[xml]$xml = Get-Content -LiteralPath $xmlPath -Raw
$s = $xml.testsuite
if ([int]$s.tests -ne 1 -or [int]$s.failures -ne 0 -or [int]$s.errors -ne 0 -or [int]$s.skipped -ne 0) { throw 'Expected one successful, non-skipped evidence method' }
if ((Get-FileHash -LiteralPath $fixture -Algorithm SHA256).Hash -ne $fixtureHash -or
    (& git -C $repo rev-parse HEAD).Trim() -ne $sha -or
    (& git -C $repo diff HEAD --name-only -- backend/src backend/pom.xml)) { throw 'Backend snapshot changed during measurement' }
$result = Get-Content -LiteralPath $env:PORTFOLIO_EVIDENCE_OUTPUT -Raw | ConvertFrom-Json
if ($result.outcome -ne 'passed') { throw 'Evidence assertions did not pass' }
Write-Output ('Evidence saved under ignored target directory: ' + $env:PORTFOLIO_EVIDENCE_OUTPUT)
