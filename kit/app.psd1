# Settings for the hand-off kit (kit\kit.ps1). Plain data, no code.
@{
    AppName     = 'Watch'
    Package     = 'com.jordan.watch'
    Device      = 'Phone'
    # Positions and watched marks (written by the app). The videos in /sdcard/Watch are
    # converted copies of D:\Movies, D:\TV and D:\Marvel Entertainment (send them again
    # with Watch-Phone-Sync.cmd), so they are not backed up.
    SharedDirs  = @('/sdcard/Watch/.state')
    SkipPrivate = @('cache', 'code_cache')
    # All files access, as Watch-Phone-Sync.cmd does it
    AppOps      = @('MANAGE_EXTERNAL_STORAGE')
    BuildTask   = ':tool:assembleDebug'
    BuildOutput = 'tool\build\outputs\apk\debug\tool-debug.apk'
    KeyName     = 'SDK dev key (sdk\keys\lightsdk-dev.jks)'
}
