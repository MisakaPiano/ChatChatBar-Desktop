$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=true'
Set-Location -LiteralPath 'H:\ChatChatBar-Desktop\app'
& .\gradlew.bat :desktopApp:test --tests '*DesktopCredentialImportPlanTest' --tests '*RecoverableIntentContractTest' --tests '*G2AOfficialWireTest' --offline --no-configuration-cache --console=plain --no-daemon --max-workers=1 *> '..\docs\desktop\evidence\p11-s2-g2a-20261023\raw\initial.log'
$g2aExit=$LASTEXITCODE
Set-Content -LiteralPath '..\docs\desktop\evidence\p11-s2-g2a-20261023\raw\initial-exit.txt' -Value $g2aExit
exit $g2aExit
