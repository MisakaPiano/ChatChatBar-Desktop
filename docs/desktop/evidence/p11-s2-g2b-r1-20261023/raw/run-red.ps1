$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=true'
Set-Location -LiteralPath 'H:\ChatChatBar-Desktop\app'
& .\gradlew.bat :desktopApp:test --tests '*DesktopCredentialSourceCensusTest.R1*' --offline --no-configuration-cache --console=plain --no-daemon --max-workers=1 *> '..\docs\desktop\evidence\p11-s2-g2b-r1-20261023\raw\red.log'
$g2bExit=$LASTEXITCODE
Set-Content '..\docs\desktop\evidence\p11-s2-g2b-r1-20261023\raw\red-exit.txt' $g2bExit
exit $g2bExit

