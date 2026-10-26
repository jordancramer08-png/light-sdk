# Settings for the hand-off kit (kit\kit.ps1). Plain data, no code.
@{
    AppName     = 'Reader'
    Package     = 'com.thelightphone.reader'
    Device      = 'Phone'
    # Folders in shared storage that hold the app's data (none: everything is app-private)
    SharedDirs  = @()
    # App-private folders NOT backed up: copies of books and comics from this PC
    # (send them again with the Send tools) and throw-away caches.
    SkipPrivate = @('files/shared/books', 'files/shared/comics', 'cache', 'code_cache')
    # Permissions switched on after install (none needed)
    AppOps      = @()
    BuildTask   = ':tool:assembleDebug'
    BuildOutput = 'tool\build\outputs\apk\debug\tool-debug.apk'
    KeyName     = 'SDK dev key (sdk\keys\lightsdk-dev.jks)'
}
