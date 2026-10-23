# Shaders en mpv (Android)

Notas de la función de shaders y la lista de pruebas para el POCO X7 Pro
(Dimensity 8400-Ultra, Mali-G720, 12 GB, pantalla 2712 × 1220, HyperOS 2).

## Cuándo se ejecuta cada escalador en esta pantalla

El factor de escala es `min(2712 / ancho, 1220 / alto)`. Los escaladores originales solo
se ejecutan por encima de 1,2 (Anime4K) o 1,3 (ArtCNN, FSRCNNX); RAVU por encima de 1,414.
Con "Aplicar siempre" el primer escalador corre desde 1,0.

| Video | Factor | Sin "Aplicar siempre" | Con "Aplicar siempre" |
|---|---|---|---|
| 480p (854 × 480) | 2,54 | Anime4K: los dos x2 (el segundo tras AutoDownscalePre_x4) | igual |
| 576p (1024 × 576) | 2,12 | Anime4K: un x2 (1152 líneas; el segundo no llega a 1,2) | igual |
| 720p | 1,69 | Anime4K: un x2 (AutoDownscalePre_x2 ajusta) · ArtCNN, FSRCNNX, RAVU sí | igual |
| 1080p 16:9 | 1,13 | ningún escalador | el primer x2 (2160 líneas) y luego reducción a 1220 |
| 1080p 2,39:1 (1920 × 800) | 1,41 | Anime4K, ArtCNN y FSRCNNX sí (RAVU no) | igual + RAVU |
| 4K | 0,56 | ninguno | ninguno (nunca se relaja con video mayor que la pantalla) |

En 1080p con "Aplicar siempre", Equilibrada o Alta: SSimDownscaler (variante "hasta la
pantalla") baja la imagen de 2160 a 1220 líneas; en Rápida lo hace el `dscale` de mpv.

## Preset recomendado por resolución en esta pantalla

| Contenido | Preset | Calidad | Notas |
|---|---|---|---|
| Anime 480p (DVD NTSC) | Anime4K A (o A+A si está muy borroso) | Equilibrada | Dos x2: es lo que más GPU usa. Si el guardián baja a Rápida, déjalo así. |
| Anime 576p (DVD PAL) | Anime4K A | Equilibrada | Un solo x2. |
| Anime 720p | Anime4K A | Equilibrada; Alta si no se calienta | Un solo x2; es donde más se nota la mejora. |
| Anime 1080p | Anime4K C (fuente nítida) o ArtCNN neutro | Equilibrada | Solo con "Aplicar siempre"; mejora pequeña. Para ahorrar batería, desactívalo. |
| Anime 4K | Automático (ningún escalador corre) | — | KrigBilateral y SSimDownscaler sí trabajan. |
| Películas/series 480p–720p | FSRCNNX + KrigBilateral + SSimDownscaler | Equilibrada | RAVU-Lite si se pierden fotogramas. |
| Películas/series 1080p | FSR o ninguno | — | FSRCNNX con "Aplicar siempre" es caro para la mejora que da. |
| Cualquier cosa con bandas | + Deband medio | — | El deband de mpv es barato. |

Los costes reales hay que medirlos en el teléfono (pestaña Estado del panel, que lee
`vo-passes`): en el contenedor de desarrollo no hay GPU.

## Optimización de precisión (comprobada en los 30 shaders incluidos)

El test `BundledShadersTest` procesa cada shader como al instalarlo y comprueba que todos los
pases con código llevan cabecera de precisión, que `#extension` sigue delante, que los
bloques `//!TEXTURE` y `//!PARAM` no se tocan y que ninguna coordenada de textura queda en
media precisión.

- Media precisión (FP16 en Mali): Anime4K, ArtCNN, FSRCNNX y RAVU (las redes neuronales).
  Las coordenadas (`_pos`, `_pt`, `_size`) quedan en alta precisión aunque estén en esos pases.
- Alta precisión: FSR y CAS (trucos de bits FP32), NVIDIA Image Scaling y Sharpen (posiciones
  en píxeles: en FP16 no queda parte decimal por encima de 1024), KrigBilateral,
  SSimDownscaler y Chroma From Luma (varianzas y regresiones).

## Pantalla dividida (comparación lado a lado)

No se implementa. Los hooks de usuario en LUMA/CHROMA trabajan en YUV antes de la conversión
de color y no reciben la matriz de color, así que una mezcla con `//!SAVE` del original no da
los mismos colores que la salida; además `//!PARAM`/`glsl-shader-opts` solo funcionan con
gpu-next y cada cambio recompila. Queda solo "mantener pulsado para comparar", que funciona
en pausa porque mpv redibuja el fotograma al cambiar `glsl-shaders`.

## Pendiente de comprobar en el teléfono

- La variante "hasta la pantalla" de SSimDownscaler usa el tamaño de `PREKERNEL` en sus
  condiciones. Si mpv no lo acepta, el shader falla, sale de la cadena y se avisa; en ese caso
  usar Rápida (sin esa variante) y avisar para cambiarlo.
- El arranque con Vulkan (gpu-next, androidvk, mediacodec-copy). Si falla, la app vuelve sola
  a OpenGL y lo recuerda.

## Lista de pruebas en el POCO X7 Pro

Antes: Ajustes > Reproducción > Motor de reproducción = libmpv; Shaders activado. En cada
prueba abre el panel Shaders > Estado y anota los ms por shader y el total. Al final exporta el
registro (Ajustes > Diagnóstico) y busca `Player/Shaders`.

1. **Arranque y motor**: reproduce cualquier video. En el registro debe aparecer
   `render vo=gpu-next gpu-api=vulkan ... hwdec-current=mediacodec-copy` (o el fallback a OpenGL).
2. **Anime 480p** (un DVD rip): Automático → Anime4K A Equilibrada. Estado: Restore, los dos
   Upscale y AutoDownscalePre_x4 activos. Anota ms totales; la imagen no debe tirones.
3. **Anime 720p**: Anime4K A Equilibrada → un Upscale activo, el segundo "inactivo: su
   condición no se cumple". Prueba Alta y anota la diferencia de ms.
4. **Anime 1080p con "Aplicar siempre"**: el primer Upscale activo (variante forzada) y
   SSimDownscaler activo. Anota ms. Mantén pulsado comparar (también en pausa).
5. **Anime 1080p sin "Aplicar siempre"**: los escaladores salen "inactivo: el video ya tiene la
   resolución de la pantalla".
6. **4K**: escaladores "inactivo: el video es más grande que la pantalla"; la reproducción debe
   ser fluida.
7. **Película 720p** con FSRCNNX + KrigBilateral + SSimDownscaler: los tres activos (Krig solo si
   el video es 4:2:0).
8. **Combinar**: marca ArtCNN y FSRCNNX → aviso de doble escalado; reordena arrastrando;
   "Guardar como preset" y comprueba que aparece en Presets y en Ajustes.
9. **Importar**: importa una carpeta con un .glsl válido y un .txt; debe importar uno y avisar
   del otro. Exporta las cadenas a JSON e impórtalas de nuevo.
10. **Prueba térmica de 20 minutos**: anime 1080p, Anime4K A Alta, "Aplicar siempre",
    brillo alto, cargador desconectado. Anota cada aviso de "calidad bajada" y la hora; al
    terminar busca en el registro `automatic:` (con el margen térmico de cada bajada). Tras
    parar, espera un minuto con el video en pausa y reanuda: la calidad debe volver.
11. **Ahorro de batería**: actívalo en mitad de un video → aviso y shaders en pausa;
    desactívalo → vuelven.
12. **ExoPlayer**: cambia de motor; el panel debe decir "Solo con mpv".
