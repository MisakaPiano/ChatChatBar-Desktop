param([string]$Target)
$ErrorActionPreference = 'Stop'
$tokens = $null
$errors = $null
$null = [Management.Automation.Language.Parser]::ParseFile($Target, [ref]$tokens, [ref]$errors)
@{version=$PSVersionTable.PSVersion.ToString();errors=@($errors | ForEach-Object { @{id=$_.ErrorId;line=$_.Extent.StartLineNumber;message=$_.Message} })} | ConvertTo-Json -Depth 5 -Compress
if ($errors.Count -ne 0) { exit 1 }
exit 0
