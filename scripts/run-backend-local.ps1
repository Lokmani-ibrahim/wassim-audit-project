$root = Split-Path $PSScriptRoot -Parent
Get-Content "$root\.env" | ForEach-Object {
  if ($_ -match '^\s*([^#][^=]*)=(.*)$') { Set-Item -Path "env:$($matches[1].Trim())" -Value $matches[2].Trim() }
}
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
Set-Location $root
.\mvnw.cmd spring-boot:run