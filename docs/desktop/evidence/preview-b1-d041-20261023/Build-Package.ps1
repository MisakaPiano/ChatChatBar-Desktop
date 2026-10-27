param([ValidateSet('901','902')][string]$Number)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$scope=Join-Path $repo 'app/desktopApp/build/preview-b1'
$out=Join-Path $PSScriptRoot "raw/package-$Number"
New-Item -ItemType Directory -Force $out | Out-Null
$build=Join-Path $scope "build-$Number"
if(Test-Path -LiteralPath $build){throw 'OWNED_BUILD_ALREADY_EXISTS'}
$forward=$build.Replace('\','/')
$init=Join-Path $out 'package.init.gradle'
"gradle.beforeProject { p -> if (p.path == ':desktopApp') p.layout.buildDirectory.set(new File('$forward')) }" | Set-Content $init
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$argsList=@('--offline','--no-daemon','--no-configuration-cache','--max-workers=1','--no-parallel','--console=plain',
 '-Dorg.gradle.jvmargs=-Xmx1024m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8','-Pkotlin.compiler.execution.strategy=in-process',
 "-PdesktopVersion=0.1.0-preview.$Number",'-I',$init,':desktopApp:createDistributable')
$argsList | ConvertTo-Json | Set-Content (Join-Path $out 'arguments.json')
Push-Location (Join-Path $repo 'app')
try{
 & ./gradlew.bat @argsList *> (Join-Path $out 'build.log')
 $code=$LASTEXITCODE;$code | Set-Content (Join-Path $out 'exit.txt')
 Write-Output "PACKAGE=$Number EXIT=$code";exit $code
}finally{Pop-Location}
