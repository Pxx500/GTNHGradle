$ErrorActionPreference = "Stop"

$forkPath = (Resolve-Path -LiteralPath $PSScriptRoot).Path.Replace("\", "/")
$gradleHome = if ($env:GRADLE_USER_HOME) {
    $env:GRADLE_USER_HOME
} else {
    Join-Path ([Environment]::GetFolderPath("UserProfile")) ".gradle"
}
$initDirectory = Join-Path $gradleHome "init.d"
$initScript = Join-Path $initDirectory "pxx500-gtnhgradle.gradle"

$content = @"
def localGtnhGradle = new File('$forkPath')
def pluginIds = [
    'com.gtnewhorizons.gtnhconvention',
    'com.gtnewhorizons.gtnhsettingsconvention'
]

gradle.beforeSettings { settings ->
    def rootDirectory = settings.settingsDir.canonicalFile
    if (rootDirectory == localGtnhGradle.canonicalFile) {
        return
    }

    def buildFiles = [
        new File(rootDirectory, 'build.gradle'),
        new File(rootDirectory, 'build.gradle.kts'),
        new File(rootDirectory, 'settings.gradle'),
        new File(rootDirectory, 'settings.gradle.kts')
    ]
    def usesGtnhGradle = buildFiles.findAll { it.isFile() }.any { buildFile ->
        def text = buildFile.getText('UTF-8')
        pluginIds.any { pluginId ->
            text.contains("id('`$pluginId')") ||
                text.contains("id(\"`$pluginId\")") ||
                text.contains("id '`$pluginId'")
        }
    }

    if (usesGtnhGradle) {
        settings.pluginManagement {
            includeBuild(localGtnhGradle.absolutePath)
        }
    }
}
"@

New-Item -ItemType Directory -Force -Path $initDirectory | Out-Null
[IO.File]::WriteAllText($initScript, $content, [Text.UTF8Encoding]::new($false))

Write-Host "Installed local GTNHGradle override: $initScript"
Write-Host "Remove that file to disable it."
