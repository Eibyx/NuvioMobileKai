# Sincronización con el oficial y con luqman (10-nov-2026)

Una sola fusión en `kai`, sin rebase: **luqman** (`enhanced`) ya había fusionado el **oficial** (`cmp-rewrite`) hasta su 0.6.11-beta, así que con luqman entra todo: unos 129 commits (de luqman, del oficial y de sus colaboradores). Hubo 15 archivos en conflicto.

## Qué entró

| Cambio | De | Notas |
|---|---|---|
| Anime4K en ExoPlayer: escalado 2x y afinado de líneas (#337) | luqman | En Reproducción → Vídeo, sección Mejora de anime. Solo con ExoPlayer; con mpv siguen los Shaders de Kai. |
| Velocidad de descarga en la pantalla de carga | luqman | ExoPlayer cuenta los bytes de la red; mpv lee `cache-speed`. |
| Detalle de los errores de mpv en la pantalla de error | Oficial | Las tres últimas líneas de error de mpv, sin URLs, junto al error del archivo. |
| Logo del título en el reproductor, fondos de cristal de los controles y botones de saltar opcionales | luqman | En Reproducción → Interfaz. |
| AutoSync sin idioma preferido, subtítulos forzados y AutoSync activados por defecto | Oficial | Textos en español de luqman. |
| Transición suave de la cabecera de la ficha (fondo de color dominante) | luqman | Dentro de la condición de Kai: con el fondo cinemático no hay banda. |
| nuvio-libass en ExoPlayer: modos de renderizado (superposición, efecto de vídeo), resolución, reconstrucción del reproductor al elegir un ASS y AutoSync de subtítulos ASS | luqman | Añadido después de la fusión. Activado por defecto, en Reproducción → Subtítulos → Renderizado de subtítulos. Se combina con Anime4K de ExoPlayer. |
| Encuadre horizontal inteligente del destacado, iOS (memoria, PiP), TV en directo, búsqueda, traducciones | Oficial / luqman | Sin cambios de Kai. |

## Qué se quedó de Kai

| Kai | Notas |
|---|---|
| Detección de anime de Auto (géneros en varios idiomas, japonés, AniList) | El oficial la sacó a `isAnimeContent`; ahora usa las reglas de Kai. |
| libass de libmpv para ASS en ExoPlayer | Se queda como interruptor aparte y experimental (desactivado por defecto). Si está activado, manda sobre nuvio-libass, como en luqman. |
| mpvlib de mpvRx, Shaders, búfer de mpv, registros de mpv | El reproductor mpv sigue siendo el de Kai. |
| Botones de la ficha y fondo de Streams del tema claro, velo cinemático de Kai, versión 3.2.x | En oscuro, los botones usan el color translúcido del oficial sobre el fondo. |

## Qué se descartó y por qué

| Descartado | Por qué |
|---|---|
| Anime4K de luqman en mpv (`glsl-shaders` propios) | Chocaría con los Shaders de Kai, que ya ponen Anime4K en mpv. |
| Velo cinemático del oficial (0.48) | Kai mantiene el suyo por legibilidad. |

# Sincronización con el oficial y con luqman (5-nov-2026)

Una sola fusión en la rama `kai`, sin rebase: **luqman** (`luqmanfadlli/NuvioMobile-Enhanced`, rama `enhanced`) ya había fusionado el **oficial** (`NuvioMedia/NuvioMobile`, rama `cmp-rewrite`) hasta su 0.6.8-beta, así que con luqman entra todo: unos 42 commits propios de luqman y los 20 del oficial. Hubo 25 archivos en conflicto.

## Qué entró

| Cambio | De | Notas |
|---|---|---|
| Retardo del audio en el reproductor (Android), recordado por salida (altavoz, cable, cada Bluetooth) | Oficial | ExoPlayer con su `AudioDelayMediaSource`; mpv con `audio-delay`. Ajuste "Recordar el retardo del audio por dispositivo". |
| Amplificación del audio y downmix con nivel del canal central (Android) | Oficial | En mpv el volumen puede pasar del 100 % (`volume-max=400`), añadido a las opciones de mpv de Kai. |
| Reanudar un torrent cortado en el fotograma donde se paró | Oficial | |
| Si Auto cambia de motor tras un fallo, sigue con la misma fuente; los fallos de carga de mpv se ven en el reproductor | Oficial | El aviso de error de mpv usa el `END_FILE` de mpvRx. |
| Fondo cinemático ya desenfocado en la ficha y en Streams (sin `blur` en tiempo real) | Oficial | Con el velo y el glass de Kai. La imagen de cabecera se funde con el fondo (`featherArtwork`) también en el tema claro, y sustituye al fundido propio que tenía Kai. |
| AutoSync: compara ASS/SSA por sus diálogos, cae a SRT/VTT si ningún ASS encaja y solo avisa cuando el subtítulo no corresponde al vídeo | Oficial | Ver "Descartado". |
| Fondo de la app desde la galería o una URL y **Glass UI (experimental)** con estilos oscuro, equilibrado y vivo | luqman | En Apariencia; desactivado por defecto. Fondo dinámico y acento del catálogo pasan a Diseño → Inicio. |
| TV en directo: pestañas Guía, Deportes y A la carta, vista de cuadrícula o lista, recientes y favoritos | luqman | |
| Desenfoque del contorno de los subtítulos (también nativo en iOS) | luqman | |
| Subtítulos de texto en PiP y en el mini reproductor de iOS; iOS ya no salta unos segundos al abrir el Centro de control | luqman | |
| Arreglos de WeTrakr, Simkl y ajustes de plugins; "Visionado privado" pasa a Seguimiento | luqman | |
| Gradle 9.8.2 y AGP 9.4.0 | luqman | |
| Traducciones | Ambos | En español manda Kai; los textos nuevos están en `strings_sync_068.xml` y el aviso de AutoSync en `autosync_toast_strings.xml`. |

## Qué se quedó de Kai

| Parte | Cómo quedó |
|---|---|
| mpvlib de mpvRx (MPVLib + MpvSession) | El oficial pasa a un `MpvSurfaceView` propio para soltar las superficies en el hilo de mpv. Kai sigue con su vista y su sesión; las funciones nuevas (retardo, amplificación, errores) van sobre ella. |
| Un solo renderizador de ASS en ExoPlayer (libass de libmpv) | Igual que antes. El retardo del audio se aplica en la única ruta de Kai. |
| Tema claro en ficha y Streams | La cabecera del episodio sigue oscura en el claro salvo con fondo cinemático, donde se funde con él y lleva un velo del color de la página. |
| Capítulos visibles por defecto, panel lateral con su tinte, ajustes de Apariencia (modo claro/oscuro, fuente) | Integrados con las filas nuevas de búsqueda de ajustes. |
| Workflows de release y de iOS | Sin cambios: no se toma la opción de saltar fases de Kotlin/Native que luqman añadió a sus workflows. La propiedad de Gradle que la activa entra, pero no hace nada si no se pasa. |
| Versión 2.0.0, `store.json` | Sin cambios. |

## Qué se descartó y por qué

| Descartado | Por qué |
|---|---|
| AutoSync de subtítulos ASS/SSA dibujados por nuvio-libass (`AutoSyncLibassSubtitles`) y la subida de nuvio-libass a 0.0.13 | Depende del nuvio-libass del oficial, que Kai no usa (un solo renderizador de ASS). Con el ASS de Kai, AutoSync sigue dejando el subtítulo tal cual, ahora sin aviso de error. |
| `MpvSurfaceView` del oficial y su test | Va con la librería mpv del oficial (`is.xyz.mpv.MPV`), no con la de mpvRx. |
| Cambios de luqman en `mobile-release.yml` e `ios-test-build.yml` | Release y build de iOS son de Kai. |

---

# Sincronización con el oficial y con luqman (4-nov-2026)

Fusiones en la rama `kai`, sin rebase. Esta vez primero **luqman** y después el **oficial**: luqman ya había fusionado el oficial hasta su 0.6.6 y resuelto sus choques, así que en ese orden salen la mitad de conflictos (22 frente a 47).

1. **luqman**: `luqmanfadlli/NuvioMobile-Enhanced`, rama `enhanced`, hasta su 0.6.6-beta (unos 25 commits propios más los 115 del oficial que traía).
2. **Oficial**: `NuvioMedia/NuvioMobile`, rama `cmp-rewrite`, los 44 commits restantes hasta la 0.6.7.

## Qué entró

| Cambio | De | Notas |
|---|---|---|
| Apariencia de subtítulos: tipografías (también importadas), fondo, sombra, vista previa | Oficial | Para subtítulos de texto en ExoPlayer y mpv. Las fuentes importadas antes en Kai se pasan solas a las nuevas. |
| Ajustes por grupos (Tú, Personalización, Multimedia, Servicios, Sistema), página de Diseño y Reproducción repartida en diez páginas | Oficial | Inicio, Streams, Ficha y la barra de navegación pasan a Diseño. |
| Selección automática de motor (anime a mpv, HDR/Dolby Vision a ExoPlayer) y cambio de motor si falla | Oficial | Ver "Kai": Auto sigue siendo el predeterminado. |
| Segmentos para saltar por capítulos (OP, ED, resumen) | Oficial | Con dos arreglos: las escenas post-créditos nunca se saltan y los créditos largos de una película cuentan como final. Las pruebas del oficial ya lo pedían. |
| El vídeo se redimensiona junto al panel de la fiesta | Oficial | |
| "Marcar como visto" en el menú de Seguir viendo | Oficial | |
| Subtítulos de addons sin las cabeceras del stream | Oficial | Se cargan aparte, así su servidor no recibe el `Authorization` del stream. |
| Títulos "Episodio N" traducidos, insignias de códec mejores, episodios no estrenados marcados | Oficial | |
| Now Playing de iOS solo al cambiar la reproducción | Oficial | |
| Modo noche de audio en mpv (y botón en todos los motores) | luqman | |
| Botones del reproductor clásico personalizables (arriba y abajo) | luqman | El botón Shaders de Kai es uno más, después de Audio. |
| Cuándo aparece "Saltar intro" (antes o después) | luqman | |
| PublicMetaDB como servicio de seguimiento | luqman | |
| Insignias en streams de plugins, panel de capítulos con blur | luqman | |
| Traducciones | Ambos | En español manda Kai; los textos nuevos están traducidos (`strings_sync_066.xml`, `strings_publicmetadb.xml`). |

## Qué se quedó de Kai

| Parte | Cómo quedó |
|---|---|
| Un solo renderizador de ASS en ExoPlayer: el libass de libmpv | luqman vuelve a llevar también el nuvio-libass del oficial con sus modos (superposición, efecto de vídeo) y resolución. Kai sigue con uno solo y un único interruptor, ahora en Reproducción → Subtítulos. |
| Directorio de fuentes para ASS (mpvRx) | En su propia carpeta para `sub-fonts-dir`; aparece en Reproducción → Subtítulos. |
| Shaders | Ajustes en Reproducción → Vídeo; botón en la barra nueva y en la clásica. |
| Estado del dispositivo en el reproductor | Interruptor en Reproducción → Interfaz. |
| Fuente de la app y modo claro/oscuro | En Apariencia. |
| Motor predeterminado Auto | El oficial lo pasó a ExoPlayer. En Kai, Auto manda el anime a mpv (con los shaders) y lo demás a ExoPlayer. Quien ya eligió motor lo conserva. |
| mpvlib de mpvRx, glass, tamaños por clase, modo mini del reproductor | Integrados con los cambios nuevos (panel lateral con glass, controles por clase de tamaño también con el panel de la fiesta, sin modales en el modo mini). |
| Versión 1.0.5, `store.json`, README, MPVKit de luqman en iOS | Sin cambios. |

## Qué se descartó y por qué

| Descartado | Por qué |
|---|---|
| nuvio-libass del oficial (AAR, modos de renderizado, resolución, reconstrucción del reproductor al elegir un ASS) | Un solo renderizador de ASS, como en la sincronización anterior. |
| Caché VOD de ExoPlayer del oficial | luqman tiene la suya (caché en disco) y Kai usa esa. |
| Test del oficial del modo de renderizado ASS | Probaba lo descartado; lo sustituye uno que comprueba el interruptor único. |

---

# Sincronización con el oficial y con luqman (27-oct-2026)

Fusiones en la rama `kai`, en este orden y sin rebase:

1. **Oficial**: `NuvioMedia/NuvioMobile`, rama `cmp-rewrite`, hasta la 0.6.5-beta (91 commits desde la fusión del 21-oct).
2. **luqman**: `luqmanfadlli/NuvioMobile-Enhanced`, rama `enhanced`, hasta su 0.6.5-beta (ya había fusionado el oficial; unos 48 commits propios nuevos).

yesnt10 (`NuvioMobile-Enhanced`, rama `enhanced`) no tiene nada nuevo desde el 2-sep.

**Cómo se resolvieron los conflictos.** En los archivos en los que luqman ya había resuelto el mismo conflicto con el oficial se hizo una fusión a tres: Kai, la base común con luqman y la versión actual de luqman. Así se sigue la resolución de luqman y se conservan los cambios de Kai. En los choques con Kai manda Kai.

## Qué entró del oficial

| Cambio | Notas |
|---|---|
| Compose Multiplatform 1.13.0-beta01, Material 3 1.13.0-beta01, Navigation3 1.2.0, compileSdk 37.1 | Compila sin cambios en el código de Kai. |
| Capítulos del reproductor: marcas en la barra, nombre del capítulo y panel de capítulos | Única implementación de capítulos (ver "Kai"). |
| Valoraciones y clasificación por edad en el destacado de Inicio; logo real de IMDb | Con los colores legibles de Kai en el claro. |
| Diseño para plegables (Duo): vídeo y controles separados en el pliegue, mosaico de controles, brillo y volumen, insignias | Sin efecto en teléfonos normales. |
| Rendimiento: reglas de insignias y filtros de debrid compilados una vez | |
| Debrid: idioma de los streams leído mejor (alias, palabras del título) | |
| Trakt: "Seguir viendo" se mantiene si falla una petición de progreso | |
| Servidores: búsquedas por título que siguen funcionando entre episodios | |
| Motor P2P 0.1.6 | |
| Reproductor en pausa sin el nombre de la fuente | |
| Arreglos de iOS (volumen real, reajuste del vídeo en pausa, Duo) | |
| Traducciones (búlgaro, croata, griego, húngaro, italiano, portugués, vietnamita…) | En español manda la traducción de Kai; se añaden solo las cadenas que Kai no tenía. |

## Qué entró de luqman

| Cambio | Notas |
|---|---|
| Botones del reproductor personalizables (mostrar, ocultar tras la flecha, ordenar) | El botón **Shaders** de Kai es un botón más de la barra, visible por defecto. |
| "Show Chapters" para todos los motores | Activado por defecto en Kai, porque mpv ya mostraba los capítulos. |
| Miniaturas de la barra de progreso nativas en Rust (#265) | Compatibles con nuestro mpvlib 1.1.3 (FFmpeg n9.0.2, avcodec 63, avutil 61). Cargan las librerías sin fijar versión y solo leen el comienzo de `AVFrame`, que no ha cambiado; nuestras librerías exportan todas las funciones que usan. Se compilan solo para las ABI del APK (`-Pnuvio.android.abis`). |
| Decodificador Hi10p / H.264 de 10 bits para ExoPlayer (#247) | Compilado con las cabeceras de FFmpeg **n9.0.2**, las de mpvlib 1.1.3, en lugar de las de 8.0 que traía. Al cargar comprueba las versiones mayores de avcodec, avutil y swscale: si no coinciden, se desactiva sin crashear. |
| DNS sobre HTTPS con selector (NuvioDns) | Único DNS (ver "Descartado"). Se añaden los proveedores que solo tenía Kai y se migra la elección guardada en Kai. |
| Duraciones de retroceso y avance configurables | |
| Hora de fin y tiempo restante sobre la barra; tocar la duración muestra el tiempo restante | |
| Exportar e importar ajustes (copia de seguridad) | |
| Títulos, logos, pósteres y fondos en el idioma original (TMDB) | |
| Selector de tema plegable en Ajustes | Junto al modo claro/oscuro de Kai. |
| Calendario de estrenos que empieza el lunes | |
| Perfil activo como tarjeta con avatar; pestaña seleccionada más visible | |
| Arreglos de relleno de la barra y de las píldoras de tiempo, fundido de los controles, controles a todo el ancho | |
| Discord Rich Presence en iOS | |

## Qué se quedó de Kai

| Parte | Cómo quedó |
|---|---|
| mpvlib 1.1.3 de mpvRx (MPVLib + MpvSession) | Sin cambios; no se vuelve a mpv-android-lib. |
| Shaders y su botón en el reproductor | El botón está en la barra personalizable y en el diseño clásico. |
| Sincronización con AniList (dos sentidos, importación de la lista) | Sin cambios. |
| Glass (kyant backdrop) y tema claro | Sin cambios. |
| Preferencia de ID de anime | Sin cambios. |
| Nombre e ID de la app (`com.eibyx.nuviokai`) | Sin cambios. |
| Versiones 1.0.x (1.0.4 hasta publicar la siguiente) | Se descarta el 0.6.5 / 148 del oficial. |
| Workflows de release y la firma | `mobile-release.yml` igual que en la 1.0.4, más el paso de Rust. `android-release.yml` sigue fuera; `ios-test-build.yml` sigue en macos-26 con Xcode 26.6. |
| Valores por defecto del servidor | Sin cambios. |
| `store.json` de Kai | Sin cambios. |
| Textos en español de Kai | Por delante de las traducciones nuevas del oficial. "Corrientes" pasa a "Streams" y "STREAMS". |
| Ratings centrados de la ficha, navegador de episodios fijo, capa de estado del dispositivo | Integrados con los cambios del oficial y de luqman. |

## Qué se descartó y por qué

| Descartado | Por qué |
|---|---|
| nuvio-libass del oficial (activado por defecto) y peerless2012 (ass-media, ass-kt) | ExoPlayer necesita un solo renderizador de ASS. Se queda el de luqman con el libass que trae libmpv, con un único interruptor activado por defecto. mpv sigue con su propio libass. Se van también los ajustes de "tipo" y "resolución de renderizado". |
| DNS sobre HTTPS de yesnt10 (okhttp-dnsoverhttps, chips en Avanzado) | Había dos. Se queda el de luqman porque tiene selector, caché y vuelve al DNS del sistema si el DoH falla. Se le añaden DNS.WATCH, DNS.SB y Canadian Shield, y la elección guardada se migra. |
| haze 1.7.3 | Kai y luqman no usan haze: el glass va con kyant backdrop. La subida a 1.7.3 no aplica y no se añade una dependencia sin uso. |
| Memoria nativa y caché VOD de ExoPlayer del oficial | luqman ya las había revertido porque tiene su propia versión; sus textos se quedan sin usar. |
| Cambios del oficial y de luqman en los workflows de release | Release y firma son de Kai. Del trabajo de release que luqman cambió sin conflicto (ya no esperaba a iOS) no queda nada. De sus workflows solo se toma la instalación de Rust, y solo para arm64-v8a. |
| Runner `xcode-27` del oficial y de luqman | Es un runner propio de ellos. Kai sigue en macos-26. |
| `store.json` del oficial y de luqman | Son sus releases, no las de Kai. |
