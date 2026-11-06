# Almacenamiento en la nube (Google Drive y TeraBox)

Ajustes → Descubrimiento de contenido → **Almacenamiento en la nube**.

Los vídeos de las carpetas que elijas aparecen como fuentes al buscar streams de una película o un episodio, junto a los add-ons, y se reproducen con el reproductor de Nuvio (ExoPlayer o mpv) sin descargarlos.

## Conectar

**Google Drive**
1. En Google Cloud: crea un proyecto, activa la *Google Drive API* y crea un cliente OAuth de tipo **App de escritorio**.
2. En la pantalla de consentimiento, publica la app (**En producción**). En *Prueba*, Google caduca el acceso a los 7 días.
3. En Nuvio: pega el ID y el secreto de cliente y pulsa **Iniciar sesión con Google** (solo Android). También puedes pegar un *refresh token* emitido para ese mismo cliente.
4. Se pide solo el permiso `drive.readonly`.

**TeraBox (experimental)**
1. Pega la cookie de una sesión web de TeraBox, o solo su valor `ndus`, igual que en rclone-extra.
2. Trátala como una contraseña: da acceso completo a la cuenta. Si alguna vez la compartiste, cierra esa sesión en TeraBox y usa una nueva.
3. TeraBox no tiene API pública. Puede limitar o bloquear cuentas, y las gratuitas pueden ir lentas.

**Importar desde rclone.conf**
- Pega el contenido de un `rclone.conf` exportado de rclone o de Round Sync Extra. Se leen los remotos `terabox` (cookie) y `drive` (client_id, client_secret, refresh token).
- Nuvio no busca ni lee archivos de otras apps: solo lo que pegas.
- No admite configuraciones cifradas.
- Un remoto `drive` sin `client_id` propio usa el cliente compartido de rclone y hay que conectarlo con tu propio cliente.

Después, en la cuenta: **Añadir carpeta** → **Sincronizar ahora**.

## Cómo funciona

| Pieza | Archivo |
|---|---|
| Modelos y credenciales (`toString` censurado) | `features/cloudstorage/CloudStorageModels.kt` |
| Almacén cifrado (Keystore / Keychain), separado del de servidores | `CloudVault.kt` + `.android.kt` / `.ios.kt` |
| Cuentas por perfil, índice en la caché de archivos | `CloudStorageRepository.kt` |
| API de TeraBox (port de rclone-extra) | `TeraBoxApi.kt` |
| API oficial de Google Drive (v3, cambios incrementales) | `GoogleDriveApi.kt`, `GoogleDriveOAuth.kt` |
| Indexado y resolución de enlaces | `CloudProviders.kt` |
| Lectura de nombres (S02E12, `- 12`, 2x12, Temporada/Episodio, fansub) | `CloudReleaseParser.kt` |
| Emparejado con TMDB/AniList, absolutos y asociaciones manuales | `CloudMatcher.kt`, `CloudTitleAliases.kt` |
| Fuentes en la lista de streams | `CloudStreams.kt` (vía `ServerStreams.sources`) |
| Servidor local de reproducción (Android) | `androidMain/.../LocalCloudStreamServer.kt` |

**Reproducción en Android:** el mismo esquema que Round Sync Extra (`rclone serve http` + reproductor). Al elegir la fuente, Nuvio abre una sesión en un servidor HTTP propio en `127.0.0.1` y le pasa al reproductor `http://127.0.0.1:<puerto>/v1/<token>/<archivo>`. Cada petición del reproductor (HEAD o GET con Range) se convierte en un GET con Range al proveedor, con sus cabeceras: `Cookie` y User-Agent de TeraBox para Windows, como rclone-extra, o `Authorization: Bearer` en Drive. Saltar en el vídeo solo pide los bytes de ese punto, y el archivo MKV/MP4 llega intacto con todas sus pistas, sus ASS/SSA y sus fuentes adjuntas.

**Seguridad:**
- Las credenciales se guardan solo en el dispositivo (Keystore/Keychain), por perfil. No se sincronizan con Supabase, no van en el export de ajustes, no van en URLs de manifest y no se dan a los plugins.
- Al desconectar se revoca el token de Google y se borran las credenciales, el índice y las asociaciones. Cerrar sesión en Nuvio borra todo.
- El servidor local solo escucha en loopback. Exige un token de 256 bits en la ruta y un `Host` de loopback, solo acepta GET y HEAD, y atiende como mucho 8 conexiones.
- En las redirecciones, `Cookie` y `Authorization` solo van al mismo host o a sus subdominios, y nunca a direcciones locales o privadas.
- El dominio de TeraBox solo puede ser un dominio de TeraBox.

## Pruebas pendientes en un Android real

1. **TeraBox:** reproducir `ANIME/Kotoura san BD 1080/[Kashikoi Subs] Kotoura san 01 BD [1920x1080 AAC 448Kbps].mp4` desde la ficha de Kotoura-san, episodio 1 → Buscar streams → fuente TeraBox. Comprobar:
   - arranca;
   - saltar a ~11:11 sigue reproduciendo;
   - las pistas de audio aparecen;
   - pausar y reanudar funciona;
   - al salir no sigue el tráfico de red.
2. Lo mismo con un MKV con subtítulos ASS y fuentes adjuntas, en mpv y en ExoPlayer.
3. **Drive:** el mismo flujo con una película y un episodio.
4. Dejar el vídeo en pausa más de 30 minutos y reanudar: debe pedir un enlace nuevo sin error.
5. Pasar al siguiente episodio desde el reproductor.
