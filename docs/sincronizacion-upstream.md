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
