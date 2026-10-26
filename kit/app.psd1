# Settings for the hand-off kit (kit\kit.ps1). Plain data, no code.
@{
    AppName     = 'Listen'
    Package     = 'com.thelightphone.listen'
    Device      = 'Phone'
    # Playlists, positions, played marks, podcast list and settings (written by the app).
    # Music and audiobooks in /sdcard/Listen are copies of D:\Music and D:\Reading\Audio Books
    # (send them again with Listen-Phone-Sync.cmd), so they are not backed up.
    SharedDirs  = @('/sdcard/Listen/.state')
    # Listen is installed as a release build (not readable with run-as); its private
    # folder only holds a cache it rebuilds.
    PrivateData = $false
    SkipPrivate = @()
    # All files access, as Listen-Phone-Sync.cmd option 9 does it
    AppOps      = @('MANAGE_EXTERNAL_STORAGE')
    BuildTask   = ':tool:assembleRelease'
    BuildOutput = 'tool\build\outputs\apk\release\tool-release.apk'
    KeyName     = 'SDK dev key (sdk\keys\lightsdk-dev.jks)'
}
