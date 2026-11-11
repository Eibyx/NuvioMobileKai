# Mis carpetas de subtítulos (portado desde NuvioArcKai)

Rama `kai-subtitle-folders`, creada desde `kai` (`7ceae418d`, versión 3.3.4). La rama por defecto de este repositorio
es `kai`, no `main`: el PR va hacia `kai` y no se fusiona hasta que se pruebe en el teléfono. El nombre
`kai/subtitle-folders` no es posible: git no admite una rama `kai/…` mientras exista la rama `kai`.

Referencia (solo lectura): Eibyx/NuvioArcKai, rama `kai/subtitle-folders` (`3dd4901`), `docs/kai/subtitulos-carpetas.md`.

## Fase 0: lo que hay en Mobile (evidencia)

### 1. Licencias

- Repositorio: GPL-3.0 (`LICENSE`, «GNU GENERAL PUBLIC LICENSE Version 3»). Arc también es GPL-3.0.
- `SubtitleFontDirectory.kt` (common y android) lleva `SPDX-License-Identifier: AGPL-3.0-or-later` porque deriva de
  mpvRx. GPL-3.0 §13 permite combinarlo con AGPL-3.0. Esta función **no modifica** esos archivos: lee la carpeta
  `filesDir/subtitle_fonts` que ellos llenan (ver punto 3).

### 2. Plataformas

KMP con Android e iOS (`composeApp/src/{commonMain,androidMain,iosMain}`). La función es solo Android: la lógica pura
va en `commonMain` (compila sin Android), el acceso a archivos, a la nube y a los motores en `androidMain`, y en iOS
la sección de ajustes no se muestra (implementaciones vacías detrás de `expect/actual`).

### 3. Reproductores y fuentes

- **mpv**: `app.gyrolet.mpvlib:mpvlib:1.1.3` (`gradle/libs.versions.toml:36`). `strings libmpv.so` →
  `mpv v0.41.0-dev-gc3ac7f395-dirty`, con `libass` en «List of enabled features».
- **Cuándo lee mpv `sub-fonts-dir`** (código de mpv v0.41.0): cada decodificador ASS, al crearse, llama a
  `assobjects_init` → `mp_ass_init` (`sub/sd_ass.c:254`), que lee `opts->fonts_dir` en ese momento y llama a
  `ass_set_fonts_dir` (`sub/ass_mp.c:133-143`). Un decodificador se crea con cada `sub-add` y al volver a elegir
  una pista. Por eso basta con reescribir el contenido de la carpeta antes de cada `sub-add`.
- **Hoy**: `sub-fonts-dir` = `filesDir/subtitle_fonts` (`PlayerEngine.android.kt:2292`, `SubtitleFontFiles.android.kt`),
  con las fuentes de la función «Directorio de fuentes» (`dir_*`, copiadas por `SubtitleFontDirectory.android.kt`).
- **ExoPlayer/Media3 1.8.0** (`lib-exoplayer-release.aar`) con **nuvio-libass** (`nuvio-libass-release.aar`,
  `com.nuvio.tv.libass`, la misma biblioteca que Arc): `AssHandler.addFont(String, byte[])` deja las fuentes en
  `AssFontStore` (pendientes) y `createTrack` las vuelca (javap). `PlayerLibassCompat.kt` crea el parser
  (`CompatAssSubtitleParserFactory.create`).
- **ExoPlayer con el libass de libmpv** (opción experimental `useLibmpvAss`): `AiostreamsAssEngine` pasa las fuentes
  con `AssSurface.directoryFonts(List<FontFile>)` (`AiostreamsAssEngine.kt:127`).

### 4. Subtítulos externos (ruta existente que se reutiliza)

- Menú: `runtime.addonSubtitles = currentLocalSubtitles + merge(...)` (`PlayerScreenContent.kt:151`). Los archivos
  que añade el usuario son `AddonSubtitle` con id `local:` y `url = file://…` (`ExternalSubtitleImport.kt:177`), y se
  eligen con `selectAddonSubtitle` → `playerController.setSubtitleUri(url)` (`PlayerScreenRuntimeExternalSubtitles.kt`).
- **mpv**: `setSubtitleUri` → `sub-add <url> select` (`PlayerEngine.android.kt:2900`).
- **ExoPlayer**: con libass y un ASS no se usa el sidecar (`PlayerSidecarSubtitles.kt:72`): el medio se recarga con
  una `SubtitleConfiguration` de id `addonSubtitleTrackId(url)` (`PlayerEngine.android.kt:1540`).
- **`format.id`** (javap de `lib-exoplayer-release.aar`): `DefaultMediaSourceFactory.createMediaSource` hace
  `Format.Builder.setId(SubtitleConfiguration.id)` y llama a `SubtitleParser.Factory.create(format)` dentro de
  `lambda$createMediaSource$0` (en el hilo de carga). Las fuentes se añaden en `CompatAssSubtitleParserFactory.create`
  antes de `delegate.create` (que llama a `createTrack` y las vuelca), como en Arc. `onMediaItemTransition` (que
  borra las fuentes) llega en `setMediaItem`, antes de `prepare()`, y el parser se crea después, al cargar.
- Sin libass (sidecar de texto) el ASS se muestra como texto plano: las fuentes no se pueden usar y se avisa.

### 5. Nube

- `CloudProvider.browse` filtra a carpetas y vídeos (`CloudProviders.kt`), así que se añade `listAll` de solo lectura
  (TeraBox `TeraBoxApi.list`, Drive `GoogleDriveApi.children`, ya sin filtro).
- `CloudProvider.resolve(file)` da la URL firmada y sus cabeceras (`CloudPlaybackSource`).
- Credenciales cifradas: `CloudVault` (Android Keystore AES-GCM), nunca sincronizado. La configuración de esta función
  se guarda en el mismo almacén, por perfil (no contiene tokens).

### 6. Metadatos del episodio

`CloudStreams.request(type, videoId, season, episode)` (`CloudStreams.kt:72`) ya da título, alias de AniList/ARM
(`CloudTitleAliases`), temporada, episodio y número absoluto. El reproductor tiene `contentType`, `parentMetaId`,
`activeVideoId`, `activeSeasonNumber` y `activeEpisodeNumber` (`PlayerScreenRuntimeState.kt`).

### 7. Ajustes y textos

Reproducción → Subtítulos (`PlaybackSubtitleSettings.kt`), después de «Fuentes» (`SubtitleFontDirectorySection`).
Idiomas de `strings`: en (`values`) y es (`values-es`). Ninguna prueba obliga a indexar filas nuevas en la búsqueda
de ajustes; se añade la entrada igualmente (`SettingsSearch.kt`).

### 8. Compilación y publicación

- `applicationId` `com.eibyx.nuviokai` (`androidApp/build.gradle.kts:125`); CI compila `arm64-v8a`.
- `versionCode` = mayor·1 000 000 + menor·1000 + parche de `MARKETING_VERSION` (`androidApp/build.gradle.kts:73-84`);
  último release 3.3.4 (3003004).
- Workflow `mobile-release.yml`; secretos de firma `NUVIO_RELEASE_KEYSTORE_BASE64` y `NUVIO_LOCAL_PROPERTIES_BASE64`
  (nombres, nunca valores). `publish`/`draft` solo desde `kai`, y no tenía modo pre-release: se añade el modo
  `prerelease` (cualquier rama, `--prerelease --latest=false`, mismas validaciones).
- El actualizador de la app solo ofrece pre-releases en el canal beta (`ReleaseSelector.kt:23`).
- Pruebas base: 2567 pruebas JVM en verde en `c7790c38e` (la 3.3.4 solo cambia notas y versión).

Ninguna condición de parada: se puede compilar y firmar, y los dos motores pueden usar fuentes.

## Decisiones (la opción más conservadora)

| Punto | Decisión |
|---|---|
| Rama | `kai-subtitle-folders` desde `kai` (ver arriba). |
| Integración | Los subtítulos de carpeta son `AddonSubtitle` con id `kai-lib:` y `file://` en la caché, como los importados (`local:`): mismo menú, `selectAddonSubtitle`, `setSubtitleUri`, retardo y estilo. Sin ruta paralela. |
| mpv | `sub-fonts-dir` pasa a `noBackupFilesDir/mpv-sub-fonts-active/`: enlaces duros (o copias) de `filesDir/subtitle_fonts` (las fuentes del «Directorio de fuentes», como hoy) más las del subtítulo de carpeta que se va a añadir; se reescribe antes de cada `sub-add`. `SubtitleFontDirectory*.kt` no cambia. |
| Solo Wi-Fi | No hay un ajuste general de «solo Wi-Fi». «Permitir datos móviles» (`DownloadsSettingsRepository.kt:13`) es de las descargas para ver sin conexión, no de la reproducción: no se aplica. |
| Almacenamiento | Solo SAF (`OpenDocumentTree` + `takePersistableUriPermission`) y, en API 29+, acceso por volumen (`StorageVolume.createOpenDocumentTreeIntent`). Sin permisos nuevos en el manifest. |
| Límites de fuentes | 32 MB por subtítulo y 25 MB por archivo, como Arc. |
| AutoSync | No se aplica a los subtítulos de carpeta, como a los importados (`local:`): AutoSync descarga por HTTP. |
